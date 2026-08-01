package org.example.test.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Aggression Meter: a single rounded bar split between aggressive-buyer and
 * aggressive-seller volume share.
 *
 * This replaces a plain two-`View`/`layout_weight` track with a custom view so the
 * meter can be both:
 *
 *  - **Smooth**: the split point glides to its new position with a decelerating
 *    animation instead of snapping instantly, so frequent live-feed updates read
 *    as a fluid shift rather than a flicker.
 *  - **Light-edged**: the whole track is clipped to a rounded-rect silhouette
 *    (soft rounded end-caps), and the seam between the buy/sell segments is
 *    blended with a short linear gradient instead of a hard vertical line.
 */
class AggressionMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var trackColor: Int = Color.parseColor("#0B0D12")
        set(value) { field = value; invalidate() }

    var buyColor: Int = Color.parseColor("#26A69A")
        set(value) { field = value; invalidate() }

    var sellColor: Int = Color.parseColor("#EF5350")
        set(value) { field = value; invalidate() }

    /** Corner radius of the track's rounded end-caps, in dp. */
    var cornerRadiusDp: Float = 5f
        set(value) { field = value; updateClipPath(); invalidate() }

    /** Width of the soft blended seam between the two segments, in dp. */
    var seamWidthDp: Float = 14f
        set(value) { field = value; invalidate() }

    private val density = context.resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val clipRect = RectF()

    /** 0f..1f share of width given to the buy (left) segment. Animated. */
    private var displayedBuyFraction = 0.5f
    private var animator: ValueAnimator? = null

    /**
     * Animate (or jump, if [animate] is false) the buy/sell split to [target],
     * a fraction in 0f..1f of the bar given to the buy side.
     */
    fun setBuyFraction(target: Float, animate: Boolean = true) {
        val clamped = target.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate || width == 0) {
            displayedBuyFraction = clamped
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayedBuyFraction, clamped).apply {
            duration = 380L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                displayedBuyFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipPath()
    }

    private fun updateClipPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        clipRect.set(0f, 0f, w, h)
        val radiusPx = cornerRadiusDp * density
        clipPath.reset()
        clipPath.addRoundRect(clipRect, radiusPx, radiusPx, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        canvas.clipPath(clipPath)

        trackPaint.color = trackColor
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        val splitX = w * displayedBuyFraction

        segmentPaint.shader = null
        segmentPaint.color = buyColor
        canvas.drawRect(0f, 0f, splitX, h, segmentPaint)
        segmentPaint.color = sellColor
        canvas.drawRect(splitX, 0f, w, h, segmentPaint)

        // Soft light seam: blend the two colors across a short span instead of
        // leaving a hard edge where the segments meet.
        val seamHalf = (seamWidthDp * density) / 2f
        if (seamHalf > 0f) {
            val left = (splitX - seamHalf).coerceAtLeast(0f)
            val right = (splitX + seamHalf).coerceAtMost(w)
            if (right > left) {
                segmentPaint.shader = LinearGradient(
                    left, 0f, right, 0f,
                    buyColor, sellColor,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(left, 0f, right, h, segmentPaint)
                segmentPaint.shader = null
            }
        }

        canvas.restore()
    }
}
