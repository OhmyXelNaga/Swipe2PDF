package com.example.swipe2pdf

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SwipeAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: SwipeAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if(instance===this) instance=null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    suspend fun swipe(width:Int,height:Int,c:JobConfig):Boolean=suspendCancellableCoroutine { cont ->
        val path=Path().apply { moveTo(width*c.startX,height*c.y); lineTo(width*c.endX,height*c.y) }
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,c.gestureMs)).build()
        val ok=dispatchGesture(gesture,object:GestureResultCallback(){override fun onCompleted(g:GestureDescription?){if(cont.isActive)cont.resume(true)};override fun onCancelled(g:GestureDescription?){if(cont.isActive)cont.resume(false)}},null)
        if(!ok && cont.isActive) cont.resume(false)
    }
}
