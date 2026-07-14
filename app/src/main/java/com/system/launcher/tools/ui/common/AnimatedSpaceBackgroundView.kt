package com.system.launcher.tools.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.system.launcher.tools.R
import kotlin.math.cos
import kotlin.math.sin

class AnimatedSpaceBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var baseShader: Shader? = null
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 18_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseShader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                ContextCompat.getColor(context, R.color.space_background_deep),
                ContextCompat.getColor(context, R.color.space_background),
                Color.rgb(13, 10, 25)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = baseShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val turn = phase * (Math.PI * 2.0)
        drawGlow(
            canvas,
            width * (0.16f + sin(turn).toFloat() * 0.05f),
            height * (0.06f + cos(turn * 0.8).toFloat() * 0.025f),
            width * 0.72f,
            Color.argb(58, 68, 224, 238)
        )
        drawGlow(
            canvas,
            width * (0.92f + cos(turn * 0.7).toFloat() * 0.04f),
            height * (0.34f + sin(turn * 0.55).toFloat() * 0.04f),
            width * 0.78f,
            Color.argb(52, 142, 92, 255)
        )
        drawGlow(
            canvas,
            width * (0.20f + cos(turn * 0.42).toFloat() * 0.05f),
            height * (0.92f + sin(turn * 0.65).toFloat() * 0.03f),
            width * 0.82f,
            Color.argb(34, 95, 82, 230)
        )
        paint.shader = null
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x,
            y,
            radius,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
