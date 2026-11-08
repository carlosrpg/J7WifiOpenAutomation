package com.carlosrpg.wifiopen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** WiFi symbol whose dot and three arcs light up one after another, bottom to top, in a loop. */
class WifiAnimationView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4F, 0xC3, 0xF7)
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var phase = 0f

    // 0..5: four segments light up (0..4), then one beat fully lit before restarting.
    private val animator = ValueAnimator.ofFloat(0f, 5f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val step = minOf(width, height) * 0.24f
        val cx = width / 2f
        val baseY = height * 0.85f
        paint.strokeWidth = step * 0.32f

        for (i in 0..3) {
            val lit = (phase - i).coerceIn(0f, 1f)
            paint.alpha = (50 + 205 * lit).toInt()
            if (i == 0) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, baseY, step * 0.28f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                val r = step * i
                oval.set(cx - r, baseY - r, cx + r, baseY + r)
                canvas.drawArc(oval, 225f, 90f, false, paint)
            }
        }
    }
}
