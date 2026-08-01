package org.example.test.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A lightweight shimmer pill for text placeholders in compact header UI.
 *
 * This view owns no business logic; callers just call [show] / [hide]. The shimmer
 * animation only runs while the view is visible and attached.
 */
class SkeletonLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val baseColor = Color.parseColor("#1A1A1A")
    private val shimmerColor = Color.parseColor("#2E2E2E")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = baseColor
    }

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private var shimmerTranslate = 0f

    private val shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            shimmerTranslate = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        visibility = GONE
        alpha = 0f
    }

    /** Fades the skeleton in and (re)starts the shimmer animation. No-op if already shown. */
    fun show() {
        if (visibility == VISIBLE && alpha == 1f) return
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(120L).start()
        startShimmer()
    }

    /** Fades the skeleton out and stops the shimmer animation once hidden. No-op if already hidden. */
    fun hide() {
        if (visibility == GONE) return
        animate().cancel()
        animate().alpha(0f).setDuration(120L).withEndAction {
            visibility = GONE
            stopShimmer()
        }.start()
    }

    private fun startShimmer() {
        if (!shimmerAnimator.isRunning) shimmerAnimator.start()
    }

    private fun stopShimmer() {
        if (shimmerAnimator.isRunning) shimmerAnimator.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) stopShimmer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = h / 2f

        // Shimmer sweeps left-to-right across the whole pill once per cycle.
        val sweepWidth = w * 0.6f
        val shimmerX = -sweepWidth + shimmerTranslate * (w + sweepWidth)
        shimmerPaint.shader = LinearGradient(
            shimmerX, 0f, shimmerX + sweepWidth, 0f,
            intArrayOf(baseColor, shimmerColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shimmerPaint)
    }
}
