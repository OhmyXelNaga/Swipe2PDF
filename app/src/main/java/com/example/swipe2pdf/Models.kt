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
    val crop: NormalizedRect = NormalizedRect(0f, .05f, 1f, .95f), val filename: String = "스캔"
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
        c.crop.left, c.crop.top, c.crop.right, c.crop.bottom, c.filename
    ).joinToString(",")

    fun decode(s: String?): JobConfig = runCatching {
        val p = s!!.split(",")
        JobConfig(
            p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toLong(), p[5].toLong(),
            NormalizedRect(p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat()),
            p.drop(10).joinToString(",")
        )
    }.getOrDefault(JobConfig())
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
