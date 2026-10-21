package com.example.swipe2pdf

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class JobState { Idle, Preparing, Capturing, Paused, Reviewing, Processing, Completed, Error }

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && left < right && top < bottom

    fun pixelBounds(width: Int, height: Int): PixelRect {
        require(valid() && width > 0 && height > 0)
        return PixelRect(
            (left * width).roundToInt(), (top * height).roundToInt(),
            (right * width).roundToInt(), (bottom * height).roundToInt()
        )
    }

    fun pixels(width: Int, height: Int): Rect {
        val p = pixelBounds(width, height)
        return Rect(p.left, p.top, p.right, p.bottom)
    }
}

data class JobConfig(
    val pages: Int = 10, val startX: Float = .85f, val endX: Float = .15f, val y: Float = .5f,
    val gestureMs: Long = 300, val delayMs: Long = 800,
    val crop: NormalizedRect = NormalizedRect(0f, .05f, 1f, .95f), val filename: String = "스캔",
    /** 0이면 묶음 단위로 만든 뒤 한 파일로 합친다. 0보다 크면 그 장수마다 파일을 따로 저장한다. */
    val splitPages: Int = 0
)

object CapturePlan {
    fun swipesFor(pages: Int): Int {
        require(pages in 1..999)
        return pages - 1
    }
}

/** 서비스로 설정을 넘길 때 쓰는 직렬화. 파일 이름은 마지막 필드라 쉼표가 있어도 복원된다. */
object ConfigCodec {
    fun encode(c: JobConfig) = listOf(
        c.pages, c.startX, c.endX, c.y, c.gestureMs, c.delayMs,
        c.crop.left, c.crop.top, c.crop.right, c.crop.bottom, c.splitPages, c.filename
    ).joinToString(",")

    fun decode(s: String?): JobConfig = runCatching {
        val p = s!!.split(",")
        JobConfig(
            p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toLong(), p[5].toLong(),
            NormalizedRect(p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat()),
            p.drop(11).joinToString(","), p[10].toInt()
        )
    }.getOrDefault(JobConfig())
}

/** PDF 분할 규칙. 페이지 번호는 작업 전체 기준(1부터)이다. */
object PdfPlan {
    val SPLIT_OPTIONS = listOf(0, 50, 100, 200)
    /** 한 파일로 합칠 때 중간 파일 하나에 담는 장수. */
    const val MERGE_CHUNK = 100

    /** 남은 [count]장을 [split]장씩 나눈 인덱스 범위. */
    fun chunks(count: Int, split: Int): List<IntRange> {
        if (count <= 0) return emptyList()
        if (split <= 0) return listOf(0 until count)
        return (0 until count step split).map { it until minOf(it + split, count) }
    }

    /** 파일 이름에 쓸 기본 이름. 경로·줄바꿈 문자를 없애고 확장자를 뗀다. */
    fun baseName(requested: String) =
        requested.replace(Regex("[\\t\\r\\n/\\\\]"), " ").trim().removeSuffix(".pdf").trim().ifBlank { "스캔" }

    fun pageDigits(lastPage: Int) = lastPage.toString().length

    /**
     * 각 묶음의 파일 이름. 처음부터 한 파일로 끝나면 범위를 붙이지 않는다.
     * 예: `강의 (001-100).pdf`, `강의 (101-200).pdf`
     */
    fun fileNames(base: String, startPage: Int, count: Int, split: Int, digits: Int): List<String> {
        val chunks = chunks(count, split)
        if (chunks.size == 1 && startPage == 1) return listOf("$base.pdf")
        fun pad(n: Int) = n.toString().padStart(digits, '0')
        return chunks.map { r -> "$base (${pad(startPage + r.first)}-${pad(startPage + r.last)}).pdf" }
    }
}

/** PDF 생성 진행률. [merging]이면 중간 파일을 한 파일로 합치는 단계다. */
data class PdfProgress(val done: Int, val total: Int, val merging: Boolean = false)

/**
 * 이어서 만들기를 위한 PDF 진행 기록. [nextPage]부터 아직 만들지 않았다.
 * 한 파일로 합치는 작업([split] 0)은 완성된 중간 파일 수를 [parts]에 기록한다.
 */
data class PdfRecord(val base: String, val split: Int, val nextPage: Int, val digits: Int, val parts: Int = 0) {
    val merging get() = split <= 0
    val started get() = nextPage > 1
}

/** 앱이 종료돼도 검토 순서와 PDF 진행 상황을 복구하기 위한 작업 기록. */
data class JobRecord(val files: List<String>, val pdf: PdfRecord? = null) {
    fun encode() = buildString {
        append(HEADER).append('\n')
        pdf?.let { append("pdf2\t${it.split}\t${it.nextPage}\t${it.digits}\t${it.parts}\t${it.base}\n") }
        files.forEach { append("file\t").append(it).append('\n') }
    }

    companion object {
        private const val HEADER = "swipe2pdf-job 1"

        fun decode(text: String): JobRecord? {
            val lines = text.lines()
            if (lines.firstOrNull() != HEADER) return null
            var pdf: PdfRecord? = null
            val files = mutableListOf<String>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t', limit = 6)
                when (parts[0]) {
                    "pdf" -> pdf = runCatching {
                        val old = line.split('\t', limit = 5)
                        PdfRecord(old[4], old[1].toInt(), old[2].toInt(), old[3].toInt())
                    }.getOrNull()
                    "pdf2" -> pdf = runCatching { PdfRecord(parts[5], parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt()) }.getOrNull()
                    "file" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(files::add)
                }
            }
            return JobRecord(files, pdf)
        }
    }
}

class StateMachine {
    var state = JobState.Idle; private set

    fun canMove(next: JobState) = next in allowed(state)

    fun move(next: JobState) {
        require(canMove(next)) { "Invalid transition: $state -> $next" }
        state = next
    }

    private fun allowed(from: JobState) = when (from) {
        JobState.Idle -> setOf(JobState.Preparing, JobState.Reviewing)
        JobState.Preparing -> setOf(JobState.Capturing, JobState.Reviewing, JobState.Error, JobState.Idle)
        JobState.Capturing -> setOf(JobState.Paused, JobState.Reviewing, JobState.Error, JobState.Idle)
        JobState.Paused -> setOf(JobState.Capturing, JobState.Reviewing, JobState.Error, JobState.Idle)
        JobState.Reviewing -> setOf(JobState.Processing, JobState.Preparing, JobState.Idle)
        JobState.Processing -> setOf(JobState.Completed, JobState.Reviewing, JobState.Error)
        JobState.Completed -> setOf(JobState.Idle, JobState.Preparing)
        JobState.Error -> setOf(JobState.Idle, JobState.Reviewing, JobState.Processing, JobState.Preparing)
    }
}

/** 촬영 파일 이름 규칙: `<촬영 시각 ms>_<페이지 번호>.png`. 앱 재시작 후 순서 복구에 사용한다. */
object CaptureNaming {
    fun name(timestampMs: Long, index: Int) = "${timestampMs}_$index.png"

    private fun key(name: String): Pair<Long, Int> {
        val parts = name.removeSuffix(".png").split("_")
        return (parts.getOrNull(0)?.toLongOrNull() ?: Long.MAX_VALUE) to (parts.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE)
    }

    fun sort(names: List<String>): List<String> = names.sortedWith(compareBy({ key(it).first }, { key(it).second }, { it }))
}

/** 긴 변이 [maxSide] 아래로 내려가지 않는 가장 큰 2의 거듭제곱 축소 배율. */
fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    require(width > 0 && height > 0 && maxSide > 0)
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** 같은 크기 RGB 지문 두 개의 유사도(1.0 = 동일). */
fun fingerprintSimilarity(a: IntArray, b: IntArray): Double {
    require(a.size == b.size && a.isNotEmpty())
    var delta = 0L
    for (i in a.indices) {
        val p = a[i]; val q = b[i]
        delta += abs((p shr 16 and 0xff) - (q shr 16 and 0xff)) +
            abs((p shr 8 and 0xff) - (q shr 8 and 0xff)) +
            abs((p and 0xff) - (q and 0xff))
    }
    return 1.0 - delta.toDouble() / (a.size * 255L * 3)
}

/** 바로 앞 이미지와 유사한 페이지의 인덱스. 지문이 없는 이미지는 비교하지 않는다. */
fun similarIndices(fingerprints: List<IntArray?>, threshold: Double = .985): Set<Int> =
    fingerprints.zipWithNext().mapIndexedNotNull { i, (a, b) ->
        if (a != null && b != null && a.size == b.size && fingerprintSimilarity(a, b) > threshold) i + 1 else null
    }.toSet()

fun <T> reorder(items: List<T>, from: Int, to: Int): List<T> {
    require(from in items.indices && to in items.indices)
    return items.toMutableList().apply { add(to, removeAt(from)) }
}
