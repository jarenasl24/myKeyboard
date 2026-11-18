package co.com.onecontrol.mykeyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import co.com.onecontrol.mykeyboard.R

class GestureTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trailPath = Path()
    private val points = mutableListOf<PointF>()
    private var trailAlpha = 220
    private var fadeAnimator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    private val trailPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (context.resources.displayMetrics.density * 8f)
        color = ContextCompat.getColor(context, R.color.kb_accent_soft)
    }

    private val trailGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (context.resources.displayMetrics.density * 14f)
        color = ContextCompat.getColor(context, R.color.kb_accent_soft)
        alpha = 80
    }

    fun startTrail(x: Float, y: Float) {
        fadeAnimator?.cancel()
        trailAlpha = 220
        trailPaint.alpha = trailAlpha
        trailGlowPaint.alpha = 70
        points.clear()
        trailPath.reset()
        val cx = x.coerceIn(0f, width.toFloat().coerceAtLeast(1f))
        val cy = y.coerceIn(0f, height.toFloat().coerceAtLeast(1f))
        points.add(PointF(cx, cy))
        trailPath.moveTo(cx, cy)
        invalidate()
    }

    fun addPoint(x: Float, y: Float) {
        val cx = x.coerceIn(0f, width.toFloat().coerceAtLeast(1f))
        val cy = y.coerceIn(0f, height.toFloat().coerceAtLeast(1f))
        if (points.isEmpty()) {
            startTrail(cx, cy)
            return
        }
        val prev = points.last()
        val midX = (prev.x + cx) / 2f
        val midY = (prev.y + cy) / 2f
        trailPath.quadTo(prev.x, prev.y, midX, midY)
        points.add(PointF(cx, cy))
        invalidate()
    }

    fun finishTrail() {
        if (points.isEmpty()) {
            invalidate()
            return
        }
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofInt(trailAlpha, 0).apply {
            duration = 180L
            addUpdateListener { anim ->
                trailAlpha = anim.animatedValue as Int
                trailPaint.alpha = trailAlpha
                trailGlowPaint.alpha = (trailAlpha * 0.35f).toInt()
                invalidate()
            }
            doOnEnd {
                points.clear()
                trailPath.reset()
                invalidate()
            }
            start()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size >= 2) {
            canvas.drawPath(trailPath, trailGlowPaint)
            canvas.drawPath(trailPath, trailPaint)
        }
    }
}
