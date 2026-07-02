package com.cesicorp.ludicrouslink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import ludicrouslink.TouchAction
import ludicrouslink.TouchEvent

class TouchInjectionService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchInjectionService"
        var instance: TouchInjectionService? = null
            private set

        fun inject(event: TouchEvent) {
            instance?.dispatchTouch(event) ?: Log.w(TAG, "Service not running")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchInject Service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "TouchInject Service disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    private fun dispatchTouch(event: TouchEvent) {
        val x = event.x * resources.displayMetrics.widthPixels
        val y = event.y * resources.displayMetrics.heightPixels

        val path = Path()
        path.moveTo(x, y)

        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)

        dispatchGesture(builder.build(), null, null)
        Log.d(TAG, "Dispatched touch: action=${event.action} x=$x y=$y")
        
        // Note: Real implementation needs to handle Down/Move/Up sequence tracking
        // For now, mapping everything to a tap/stroke for simplicity
        // Ideally we map `event.action()` to correct stroke duration/continuation
        
        // If action is Move, we need `willContinue` set to true in previous stroke.
        // This requires keeping state of active pointers.
        // TODO: Implement full multi-touch gesture mapping.
    }
}
