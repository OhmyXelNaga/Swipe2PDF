package com.example.swipe2pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max

object ImageAndPdf {
    private const val PDF_MAX_SIDE = 2000
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

    /** 공통 자르기 영역을 적용하되 PDF에 필요한 해상도보다 크게 디코딩하지 않는다. */
    fun cropFile(source: File, crop: NormalizedRect, maxSide: Int = PDF_MAX_SIDE): Bitmap {
        val (w, h) = bounds(source)
        require(w > 0 && h > 0 && crop.valid()) { "이미지를 읽을 수 없습니다: ${source.name}" }
        val region = crop.pixels(w, h)
        val decoder = BitmapRegionDecoder.newInstance(source.path)
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(region.width(), region.height(), maxSide) }
            return decoder.decodeRegion(region, options) ?: error("이미지를 자를 수 없습니다.")
        } finally {
            decoder.recycle()
        }
    }

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

    fun createPdf(context: Context, files: List<File>, crop: NormalizedRect, requested: String): Pair<Uri, String> {
        require(files.isNotEmpty())
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
            val pdf = PdfDocument()
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                files.forEachIndexed { index, file ->
                    val bmp = cropFile(file, crop)
                    val scale = minOf(1f, PDF_MAX_SIDE.toFloat() / max(bmp.width, bmp.height))
                    val pw = max(1, (bmp.width * scale).toInt())
                    val ph = max(1, (bmp.height * scale).toInt())
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, index + 1).create())
                    page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), paint)
                    pdf.finishPage(page)
                    bmp.recycle()
                }
                (resolver.openOutputStream(uri, "w") ?: error("PDF 파일을 열 수 없습니다.")).use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to name
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
