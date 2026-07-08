package com.system.launcher.tools.ui.disguise

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

class TripleTapHotZoneLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onTripleTap: (() -> Unit)? = null

    private val hotZoneWidthPx = 50f * resources.displayMetrics.density
    private val hotZoneHeightPx = 66f * resources.displayMetrics.density
    private val hotZoneTopOffsetPx = 50f * resources.displayMetrics.density
    private val tapWindowMs = 1200L
    private var tapCount = 0
    private var firstTapAt = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (isInHotZone(event.x, event.y)) {
                recordHotZoneTap()
            } else {
                resetTapState()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInHotZone(x: Float, y: Float): Boolean {
        return width > 0 && x <= hotZoneWidthPx && y >= hotZoneTopOffsetPx && y <= hotZoneTopOffsetPx + hotZoneHeightPx
    }

    private fun recordHotZoneTap() {
        val now = SystemClock.uptimeMillis()
        if (tapCount == 0 || now - firstTapAt > tapWindowMs) {
            tapCount = 1
            firstTapAt = now
            return
        }

        tapCount += 1
        if (tapCount >= 3) {
            resetTapState()
            onTripleTap?.invoke()
        }
    }

    private fun resetTapState() {
        tapCount = 0
        firstTapAt = 0L
    }
}
