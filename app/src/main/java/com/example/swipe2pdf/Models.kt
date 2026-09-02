package com.example.swipe2pdf

import android.graphics.Rect
import kotlin.math.roundToInt

enum class JobState { Idle, Preparing, Capturing, Paused, Processing, Completed, Error }

data class PixelRect(val left:Int,val top:Int,val right:Int,val bottom:Int)

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && left < right && top < bottom
    fun pixelBounds(width:Int,height:Int):PixelRect {
        require(valid() && width > 0 && height > 0)
        return PixelRect((left*width).roundToInt(),(top*height).roundToInt(),(right*width).roundToInt(),(bottom*height).roundToInt())
    }
    fun pixels(width:Int,height:Int):Rect { val p=pixelBounds(width,height);return Rect(p.left,p.top,p.right,p.bottom) }
}

data class JobConfig(
    val pages: Int = 10, val startX: Float = .85f, val endX: Float = .15f, val y: Float = .5f,
    val gestureMs: Long = 300, val delayMs: Long = 800,
    val crop: NormalizedRect = NormalizedRect(0f, .05f, 1f, .95f), val filename: String = "스캔"
)

object CapturePlan { fun swipesFor(pages: Int): Int { require(pages in 1..999); return pages - 1 } }

class StateMachine {
    var state = JobState.Idle; private set
    fun move(next: JobState) {
        val allowed = when (state) {
            JobState.Idle -> setOf(JobState.Preparing)
            JobState.Preparing -> setOf(JobState.Capturing, JobState.Error, JobState.Idle)
            JobState.Capturing -> setOf(JobState.Paused, JobState.Processing, JobState.Error, JobState.Idle)
            JobState.Paused -> setOf(JobState.Capturing, JobState.Processing, JobState.Error, JobState.Idle)
            JobState.Processing -> setOf(JobState.Completed, JobState.Error)
            JobState.Completed, JobState.Error -> setOf(JobState.Idle)
        }
        require(next in allowed) { "Invalid transition: $state -> $next" }; state = next
    }
}

fun <T> reorder(items: List<T>, from: Int, to: Int): List<T> {
    require(from in items.indices && to in items.indices)
    return items.toMutableList().apply { add(to, removeAt(from)) }
}
