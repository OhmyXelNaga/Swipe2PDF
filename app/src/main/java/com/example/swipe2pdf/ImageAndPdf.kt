package com.example.swipe2pdf

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

object ImageAndPdf {
    private const val FINGERPRINT_SIDE = 32
    private val pdfDir get() = "${Environment.DIRECTORY_DOWNLOADS}/Swipe2PDF/"

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    /** 긴 변이 대략 [maxSide] 이상인 범위에서 축소 디코딩한다. 미리보기·썸네일용. */
    fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val (w, h) = bounds(file)
        if (w <= 0 || h <= 0) return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, maxSide) })
    }

    /** 공통 자르기 영역만 원본 해상도 그대로 읽는다. */
    fun cropFile(source: File, crop: NormalizedRect): Bitmap {
        val (w, h) = bounds(source)
        require(w > 0 && h > 0 && crop.valid()) { "이미지를 읽을 수 없습니다: ${source.name}" }
        val decoder = BitmapRegionDecoder.newInstance(source.path)
        try {
            return decoder.decodeRegion(crop.pixels(w, h), BitmapFactory.Options()) ?: error("이미지를 자를 수 없습니다.")
        } finally {
            decoder.recycle()
        }
    }

    /** 자른 이미지를 무손실 압축한다. 원본 비트맵은 압축 직후 해제한다. */
    fun encodePage(source: File, crop: NormalizedRect): EncodedPage {
        val bmp = cropFile(source, crop)
        try {
            val w = bmp.width
            return encodeRgb(w, bmp.height) { y, row -> bmp.getPixels(row, 0, w, 0, y, w, 1) }
        } finally {
            bmp.recycle()
        }
    }

    /** 동시에 압축할 페이지 수. 기록과 화면 처리용으로 코어 하나를 남긴다. */
    fun parallelism() = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    fun freeBytes(dir: File) = StatFs(dir.path).availableBytes

    /** 유사도 비교용 32×32 RGB 지문. 파일당 한 번만 계산해 재사용한다. */
    fun fingerprint(file: File): IntArray? {
        val small = decodeSampled(file, FINGERPRINT_SIDE * 2) ?: return null
        val scaled = Bitmap.createScaledBitmap(small, FINGERPRINT_SIDE, FINGERPRINT_SIDE, true)
        if (scaled !== small) small.recycle()
        val pixels = IntArray(FINGERPRINT_SIDE * FINGERPRINT_SIDE)
        scaled.getPixels(pixels, 0, FINGERPRINT_SIDE, 0, 0, FINGERPRINT_SIDE, FINGERPRINT_SIDE)
        scaled.recycle()
        return pixels
    }

    fun uniqueName(existing: Set<String>, requested: String): String {
        val base = requested.trim().removeSuffix(".pdf").ifBlank { "스캔" }
        var name = "$base.pdf"
        var n = 2
        while (name in existing) name = "$base (${n++}).pdf"
        return name
    }

    private fun existingNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(pdfDir), null
        )?.use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names
    }

    /** 이전 작업이 중단돼 남은 미완성 PDF(작성 중 표시)를 지운다. */
    fun cleanupPending(context: Context) {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(pdfDir))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        val ids = mutableListOf<Long>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)
            ?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        ids.forEach { runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it), null, null) } }
    }

    /** 다운로드 폴더에 "작성 중" 파일을 만들고 [write]가 끝나면 공개한다. 실패하면 지운다. */
    private inline fun saveToDownloads(context: Context, requested: String, write: (java.io.OutputStream) -> Unit): Pair<Uri, String> {
        val resolver = context.contentResolver
        val name = uniqueName(existingNames(context), requested)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, pdfDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("PDF 저장 위치를 만들 수 없습니다.")
        try {
            (resolver.openOutputStream(uri, "w") ?: error("PDF 파일을 열 수 없습니다.")).use(write)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to name
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** 여러 장을 동시에 압축하고 순서대로 [add]에 넘긴다. 메모리 사용량은 페이지 수와 상관없이 일정하다. */
    private suspend fun encodeAll(files: List<File>, crop: NormalizedRect, onPage: (Int) -> Unit, add: (EncodedPage) -> Unit) {
        orderedParallel(files, parallelism(), { encodePage(it, crop) }) { i, page ->
            add(page)
            onPage(i + 1)
        }
    }

    /** [files]를 한 PDF로 만들어 다운로드 폴더에 저장한다. */
    suspend fun writePdf(
        context: Context, files: List<File>, crop: NormalizedRect, requested: String,
        onPage: (written: Int) -> Unit
    ): Pair<Uri, String> {
        require(files.isNotEmpty())
        return saveToDownloads(context, requested) { stream ->
            val writer = StreamingPdfWriter(stream)
            encodeAll(files, crop, onPage, writer::addPage)
            writer.finish()
        }
    }

    /** [files]를 압축해 앱 내부의 중간 파일 [target]에 저장한다. 완성되기 전에는 [target]이 생기지 않는다. */
    suspend fun writePart(files: List<File>, crop: NormalizedRect, target: File, onPage: (Int) -> Unit) {
        require(files.isNotEmpty())
        PagePartFile.Writer(target).use { part ->
            encodeAll(files, crop, onPage, part::add)
            part.commit()
        }
    }

    /** 중간 파일들을 다시 압축하지 않고 이어 붙여 PDF 하나로 저장한다. */
    fun mergeParts(context: Context, parts: List<File>, requested: String, onPage: (Int) -> Unit): Pair<Uri, String> {
        require(parts.isNotEmpty())
        return saveToDownloads(context, requested) { stream ->
            val writer = StreamingPdfWriter(stream)
            parts.forEach { part -> PagePartFile.read(part) { writer.addPage(it); onPage(writer.pageCount) } }
            writer.finish()
        }
    }
}
