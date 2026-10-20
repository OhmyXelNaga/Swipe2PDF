package com.example.swipe2pdf

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream

class PdfEngineTest {
    @Test fun chunksSplitRemainingPages() {
        assertEquals(listOf(0 until 100, 100 until 200, 200 until 250), PdfPlan.chunks(250, 100))
        assertEquals(listOf(0 until 250), PdfPlan.chunks(250, 0))
        assertEquals(listOf(0 until 30), PdfPlan.chunks(30, 100))
        assertTrue(PdfPlan.chunks(0, 100).isEmpty())
    }

    @Test fun fileNamesUsePaddedGlobalPageRanges() {
        assertEquals(
            listOf("강의 (001-100).pdf", "강의 (101-200).pdf", "강의 (201-250).pdf"),
            PdfPlan.fileNames("강의", 1, 250, 100, 3)
        )
        // 1~400쪽을 이미 만든 뒤 이어서 만드는 경우
        assertEquals(listOf("강의 (401-500).pdf", "강의 (501-550).pdf"), PdfPlan.fileNames("강의", 401, 150, 100, 3))
        assertEquals(listOf("강의.pdf"), PdfPlan.fileNames("강의", 1, 30, 100, 2))
        assertEquals(listOf("강의.pdf"), PdfPlan.fileNames("강의", 1, 900, 0, 3))
        assertEquals(listOf("강의 (901-930).pdf"), PdfPlan.fileNames("강의", 901, 30, 100, 3))
    }

    @Test fun baseNameRemovesUnsafeCharacters() {
        assertEquals("강의 3주차", PdfPlan.baseName(" 강의/3주차.pdf "))
        assertEquals("a b", PdfPlan.baseName("a\tb"))
        assertEquals("스캔", PdfPlan.baseName("  "))
        assertEquals(3, PdfPlan.pageDigits(900))
    }

    @Test fun jobRecordRoundTrip() {
        val r = JobRecord(listOf("100_2.png", "100_0.png"), PdfRecord("강의, 1장 (복습)", 100, 401, 3))
        assertEquals(r, JobRecord.decode(r.encode()))
        assertEquals(JobRecord(listOf("a.png")), JobRecord.decode(JobRecord(listOf("a.png")).encode()))
        assertNull(JobRecord.decode("garbage"))
    }

    @Test fun encodeRgbIsLossless() {
        val w = 7; val h = 5
        val pixels = IntArray(w * h) { (0xFF shl 24) or (it * 2654435761L).toInt() and 0xFFFFFF or (0xFF shl 24) }
        val page = encodeRgb(w, h) { y, row -> System.arraycopy(pixels, y * w, row, 0, w) }
        val raw = InflaterInputStream(page.data.inputStream(0, page.length)).readBytes()
        assertEquals(w * h * 3, raw.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            assertEquals(p shr 16 and 0xFF, raw[i * 3].toInt() and 0xFF)
            assertEquals(p shr 8 and 0xFF, raw[i * 3 + 1].toInt() and 0xFF)
            assertEquals(p and 0xFF, raw[i * 3 + 2].toInt() and 0xFF)
        }
    }

    @Test fun pdfStructureHasValidCrossReferences() {
        val out = ByteArrayOutputStream()
        val writer = StreamingPdfWriter(out)
        repeat(3) { n -> writer.addPage(encodeRgb(4 + n, 3) { _, row -> row.fill(0xFF336699.toInt()) }) }
        writer.finish()
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.7\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("/Count 3"))
        val startXref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref\n", startXref))
        val lines = text.substring(startXref).lines()
        val size = lines[1].split(" ")[1].toInt()
        assertEquals(1 + 2 + 3 * 3, size)
        for (id in 1 until size) {
            val offset = lines[2 + id].substring(0, 10).toInt()
            assertTrue("object $id offset", text.startsWith("$id 0 obj\n", offset))
        }
        assertTrue(text.contains("/Size $size /Root 1 0 R"))
    }

    @Test(expected = IllegalStateException::class) fun emptyPdfIsRejected() {
        StreamingPdfWriter(ByteArrayOutputStream()).finish()
    }

    @Test fun orderedParallelKeepsOrderAndBoundsConcurrency() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val seen = mutableListOf<Pair<Int, Int>>()
        orderedParallel((0 until 40).toList(), 3, { n ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            delay(((n * 7) % 5).toLong() * 3)
            active.decrementAndGet()
            n * 10
        }) { i, r -> seen += i to r }
        assertEquals((0 until 40).map { it to it * 10 }, seen)
        assertTrue("peak ${peak.get()}", peak.get() in 1..3)
    }
}
