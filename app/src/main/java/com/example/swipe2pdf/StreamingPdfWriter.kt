package com.example.swipe2pdf

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Flate로 무손실 압축한 RGB 페이지 이미지. [data]의 앞 [length] 바이트만 유효하다. */
class EncodedPage(val width: Int, val height: Int, val data: ByteArray, val length: Int)

private class ExposedBuffer(size: Int) : ByteArrayOutputStream(size) {
    fun bytes(): ByteArray = buf
}

/**
 * 한 행씩 읽어 RGB 8비트로 무손실 압축한다. 완성본 크기보다 처리 속도를 우선해 가장 빠른 압축 단계를 쓴다.
 * [readRow]는 y번째 행의 ARGB 픽셀을 채워야 한다.
 */
fun encodeRgb(width: Int, height: Int, readRow: (y: Int, row: IntArray) -> Unit): EncodedPage {
    require(width > 0 && height > 0)
    val row = IntArray(width)
    val rgb = ByteArray(width * 3)
    val buffer = ExposedBuffer(maxOf(1024, width * height))
    val deflater = Deflater(Deflater.BEST_SPEED)
    try {
        DeflaterOutputStream(buffer, deflater, 64 * 1024).use { out ->
            for (y in 0 until height) {
                readRow(y, row)
                var o = 0
                for (p in row) {
                    rgb[o] = (p shr 16).toByte()
                    rgb[o + 1] = (p shr 8).toByte()
                    rgb[o + 2] = p.toByte()
                    o += 3
                }
                out.write(rgb)
            }
        }
    } finally {
        deflater.end()
    }
    return EncodedPage(width, height, buffer.bytes(), buffer.size())
}

/**
 * 페이지를 받는 즉시 출력 스트림에 기록하는 PDF 작성기. 메모리에는 페이지 위치 정보만 남는다.
 * 페이지 크기는 이미지 픽셀 수와 같으며 이미지는 원본 해상도 그대로 들어간다.
 */
class StreamingPdfWriter(output: OutputStream) {
    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L; private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private val out = Counting(output.buffered(1 shl 16))
    private val offsets = ArrayList<Long>()
    private val pageIds = ArrayList<Int>()
    private var finished = false

    init {
        // 카탈로그(1)와 페이지 트리(2)는 마지막에 기록한다.
        offsets += 0L
        offsets += 0L
        ascii("%PDF-1.7\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    val pageCount get() = pageIds.size

    fun addPage(page: EncodedPage) {
        check(!finished)
        val w = page.width
        val h = page.height
        val image = newObject()
        ascii(
            "<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /FlateDecode /Length ${page.length} >>\nstream\n"
        )
        out.write(page.data, 0, page.length)
        ascii("\nendstream\nendobj\n")

        val content = "q\n$w 0 0 $h 0 0 cm\n/Im0 Do\nQ\n".toByteArray(Charsets.US_ASCII)
        val contentId = newObject()
        ascii("<< /Length ${content.size} >>\nstream\n")
        out.write(content)
        ascii("\nendstream\nendobj\n")

        val pageId = newObject()
        ascii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] " +
                "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $contentId 0 R >>\nendobj\n"
        )
        pageIds += pageId
    }

    /** 페이지 트리·카탈로그·참조표를 기록하고 버퍼를 비운다. 출력 스트림은 닫지 않는다. */
    fun finish() {
        check(!finished && pageIds.isNotEmpty())
        finished = true
        beginObject(2)
        ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        beginObject(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val xref = out.count
        val size = offsets.size + 1
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        offsets.forEach { table.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        ascii(table.toString())
        ascii("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    private fun newObject(): Int {
        offsets += out.count
        val id = offsets.size
        ascii("$id 0 obj\n")
        return id
    }

    private fun beginObject(id: Int) {
        offsets[id - 1] = out.count
        ascii("$id 0 obj\n")
    }

    private fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
}

/**
 * 한 파일로 합치기 전 묶음 단위로 저장하는 중간 파일. 압축된 페이지를 그대로 담아 두었다가
 * 합칠 때 다시 압축하지 않고 PDF에 옮겨 쓴다. 다 쓴 뒤 이름을 바꿔 완성된 파일만 남긴다.
 */
object PagePartFile {
    private const val MAGIC = 0x53325050 // "S2PP"

    class Writer(private val target: File) : AutoCloseable {
        private val tmp = File(target.path + ".tmp")
        private val out = DataOutputStream(tmp.outputStream().buffered(1 shl 16)).apply { writeInt(MAGIC) }
        private var committed = false

        fun add(page: EncodedPage) {
            out.writeInt(page.width)
            out.writeInt(page.height)
            out.writeInt(page.length)
            out.write(page.data, 0, page.length)
        }

        /** 기록을 마치고 완성된 파일로 바꾼다. */
        fun commit() {
            out.close()
            if (!tmp.renameTo(target)) error("중간 파일을 저장하지 못했습니다.")
            committed = true
        }

        override fun close() {
            if (!committed) {
                runCatching { out.close() }
                tmp.delete()
            }
        }
    }

    /** 페이지를 한 장씩 읽어 [consume]에 넘긴다. 메모리에는 한 장만 남는다. */
    inline fun read(source: File, consume: (EncodedPage) -> Unit) {
        DataInputStream(source.inputStream().buffered(1 shl 16)).use { input ->
            require(input.readInt() == 0x53325050) { "손상된 중간 파일입니다: ${source.name}" }
            while (true) {
                val width = try { input.readInt() } catch (_: EOFException) { break }
                val height = input.readInt()
                val length = input.readInt()
                val data = ByteArray(length)
                input.readFully(data)
                consume(EncodedPage(width, height, data, length))
            }
        }
    }

    fun name(index: Int) = String.format(Locale.ROOT, "part_%05d.bin", index)

    fun index(name: String) = name.removePrefix("part_").removeSuffix(".bin").toIntOrNull()
}

/**
 * [items]를 최대 [parallelism]개씩 동시에 [transform]하고 결과는 원래 순서대로 [consume]한다.
 * 메모리에는 처리 중이거나 기록을 기다리는 결과가 최대 [parallelism]개만 남는다.
 */
suspend fun <T, R> orderedParallel(
    items: List<T>,
    parallelism: Int,
    transform: suspend (T) -> R,
    consume: suspend (index: Int, result: R) -> Unit
) = coroutineScope {
    require(parallelism >= 1)
    val window = ArrayDeque<Deferred<R>>()
    var launched = 0
    fun launchNext() {
        val item = items[launched++]
        window.addLast(async(Dispatchers.Default) { transform(item) })
    }
    repeat(minOf(parallelism, items.size)) { launchNext() }
    for (i in items.indices) {
        val result = window.removeFirst().await()
        if (launched < items.size) launchNext()
        consume(i, result)
    }
}
