package com.carlosrpg.wifiopen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Terminal window with a ">" prompt: three blocks type in one after another, then the cursor blinks, in a loop. */
class AdbAnimationView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x81, 0xC7, 0x84)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val frame = RectF()
    private val chevron = Path()
    private var phase = 0f

    // 0..3: three blocks type in; 3..5: cursor blinks twice; then restart.
    private val animator = ValueAnimator.ofFloat(0f, 5f).apply {
        duration = 2000
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
        val s = minOf(width, height).toFloat()
        val left = (width - s) / 2f
        val top = (height - s) / 2f
        val stroke = s * 0.045f

        // Window frame.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.alpha = 255
        frame.set(left + s * 0.06f, top + s * 0.16f, left + s * 0.94f, top + s * 0.84f)
        canvas.drawRoundRect(frame, s * 0.08f, s * 0.08f, paint)

        // ">" prompt.
        val midY = top + s * 0.5f
        val cx = left + s * 0.2f
        chevron.reset()
        chevron.moveTo(cx, midY - s * 0.1f)
        chevron.lineTo(cx + s * 0.1f, midY)
        chevron.lineTo(cx, midY + s * 0.1f)
        canvas.drawPath(chevron, paint)

        // Typed blocks, then a blinking cursor after them.
        paint.style = Paint.Style.FILL
        val blockW = s * 0.1f
        val gap = s * 0.04f
        val startX = left + s * 0.38f
        for (i in 0..2) {
            val lit = (phase - i).coerceIn(0f, 1f)
            paint.alpha = (40 + 215 * lit).toInt()
            val x = startX + i * (blockW + gap)
            canvas.drawRect(x, midY - s * 0.02f, x + blockW, midY + s * 0.02f, paint)
        }
        val cursorOn = phase < 3f || ((phase - 3f) * 2f).toInt() % 2 == 0
        paint.alpha = if (cursorOn) 255 else 0
        val curX = startX + 3 * (blockW + gap)
        canvas.drawRect(curX, midY - s * 0.09f, curX + s * 0.05f, midY + s * 0.09f, paint)
    }
}
