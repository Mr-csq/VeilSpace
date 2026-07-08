package com.system.launcher.tools.ui.disguise

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class GradientStatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawRadial(canvas, 0f, -height * 0.1f, Color.rgb(220, 244, 247))
        drawRadial(canvas, width.toFloat(), height * 0.1f, Color.rgb(232, 235, 252))
    }

    private fun drawRadial(canvas: Canvas, centerX: Float, centerY: Float, color: Int) {
        val radius = width * 0.5f
        paint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
