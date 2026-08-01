package org.example.test.chart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * Facebook-style shimmering skeleton placeholder for the candlestick chart, shown while
 * live kline data is loading. Draws 10-15 evenly spaced rounded-rect "candle" bodies with
 * centered wicks at randomized heights, then sweeps a 45-degree angled gradient highlight
 * across the whole view on a seamless, zero-delay 1.5s linear loop -- the shimmer band is a
 * single shared shader so every bar reads as part of one continuous sweep rather than each
 * bar shimmering independently.
 */
class CandlestickSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // --- Tunable constants -------------------------------------------------------------

    private val minBarCount = 10
    private val maxBarCount = 15

    private val minBarHeightFraction = 0.20f
    private val maxBarHeightFraction = 0.80f

    private val barCornerRadiusDpRange = 2f..4f
    private val wickWidthDp = 2f
    private val barGapRatio = 0.35f // fraction of each slot left as gap between bars

    private val baseColor = Color.parseColor("#E0E0E0")
    private val highlightColor = Color.parseColor("#F5F5F5")

    private val shimmerDurationMs = 1_500L
    private val shimmerAngleDegrees = 45f

    // Width of the moving highlight band, as a fraction of the view width. Wide enough to
    // read as a soft sweep rather than a hard-edged bar, matching the classic Facebook look.
    private val shimmerBandWidthFraction = 0.35f

    // --- Paint / shader state ------------------------------------------------------------

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        style = Paint.Style.FILL
    }

    private val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        style = Paint.Style.FILL
    }

    private val shimmerMatrix = Matrix()
    private var shimmerShader: Shader? = null
    private var shimmerBandSpan = 0f // half-span the band must travel past each edge, in px

    // --- Bar geometry (recomputed on size change / restart) -------------------------------

    private data class BarSpec(
        val heightFraction: Float,
        val cornerRadiusPx: Float,
    )

    private var barSpecs: List<BarSpec> = emptyList()
    private val random = Random(System.nanoTime())

    private val bodyRect = RectF()

    // --- Animator --------------------------------------------------------------------------

    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerTranslateX = 0f

    init {
        // The shimmer is a pure GPU-friendly shader sweep with no bitmap allocations per
        // frame; running it on a hardware layer keeps compositing off the UI thread's
        // drawing path and helps sustain smooth output on high refresh-rate (90/120Hz)
        // displays where the animator is driven by the display's own vsync via Choreographer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        regenerateBarSpecs()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun regenerateBarSpecs() {
        val count = random.nextInt(minBarCount, maxBarCount + 1)
        barSpecs = List(count) {
            BarSpec(
                heightFraction = random.nextFloat() * (maxBarHeightFraction - minBarHeightFraction) + minBarHeightFraction,
                cornerRadiusPx = dp(random.nextFloat() * (barCornerRadiusDpRange.endInclusive - barCornerRadiusDpRange.start) + barCornerRadiusDpRange.start),
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader(w, h)
        // If startShimmer() was called before the view had a size (e.g. during initial
        // layout), kick off the animator now that we actually have dimensions to animate.
        if (visibility == VISIBLE) startShimmerAnimator()
    }

    private fun rebuildShader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        // Build the gradient line long enough to fully cover the view once rotated 45
        // degrees, so no unshaded corner is ever visible mid-sweep.
        val bandWidthPx = w * shimmerBandWidthFraction
        val diagonal = kotlin.math.hypot(w.toFloat(), h.toFloat())
        shimmerBandSpan = bandWidthPx + diagonal / 2f

        shimmerShader = LinearGradient(
            -bandWidthPx / 2f, 0f,
            bandWidthPx / 2f, 0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        shimmerPaint.shader = shimmerShader
        wickPaint.shader = shimmerShader
    }

    private fun startShimmerAnimator() {
        if (shimmerAnimator != null) return
        val w = width.takeIf { it > 0 } ?: return

        val animator = ValueAnimator.ofFloat(-shimmerBandSpan, w + shimmerBandSpan).apply {
            duration = shimmerDurationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART // exact loop, zero delay between iterations
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                shimmerTranslateX = animation.animatedValue as Float
                updateShaderMatrix()
                invalidate()
            }
        }
        shimmerAnimator = animator
        animator.start()
    }

    private fun updateShaderMatrix() {
        val shader = shimmerShader ?: return
        shimmerMatrix.reset()
        shimmerMatrix.postRotate(shimmerAngleDegrees, width / 2f, height / 2f)
        shimmerMatrix.postTranslate(shimmerTranslateX - width / 2f, 0f)
        shader.setLocalMatrix(shimmerMatrix)
    }

    private fun stopShimmerAnimator() {
        shimmerAnimator?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
        shimmerAnimator = null
    }

    /** Starts (or restarts, with freshly randomized bar heights) the shimmer loop. */
    fun startShimmer() {
        regenerateBarSpecs()
        visibility = VISIBLE
        if (width > 0 && height > 0) rebuildShader(width, height)
        startShimmerAnimator()
        invalidate()
    }

    /** Stops the shimmer loop and hides the skeleton, typically once real data has loaded. */
    fun stopShimmer() {
        stopShimmerAnimator()
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startShimmerAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Pause the loop while off-screen so it doesn't keep ticking (and burning battery)
        // behind other content or other activities.
        stopShimmerAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val specs = barSpecs
        if (specs.isEmpty() || width <= 0 || height <= 0) return

        val slotWidth = width.toFloat() / specs.size
        val barWidth = (slotWidth * (1f - barGapRatio)).coerceAtLeast(dp(2f))
        val wickWidthPx = dp(wickWidthDp)
        val wickHalf = wickWidthPx / 2f

        specs.forEachIndexed { index, spec ->
            val centerX = index * slotWidth + slotWidth / 2f
            val barHeight = height * spec.heightFraction
            val top = (height - barHeight) / 2f
            val bottom = top + barHeight

            // Wicks: thin centered lines extending a little beyond the body top/bottom,
            // drawn first so the rounded body corners cleanly overlap their ends.
            val wickExtension = barHeight * 0.18f
            bodyRect.set(
                centerX - wickHalf,
                top - wickExtension,
                centerX + wickHalf,
                bottom + wickExtension,
            )
            canvas.drawRoundRect(bodyRect, wickHalf, wickHalf, wickPaint)

            // Candle body: rounded rect with a subtle 2-4dp corner radius.
            bodyRect.set(centerX - barWidth / 2f, top, centerX + barWidth / 2f, bottom)
            canvas.drawRoundRect(bodyRect, spec.cornerRadiusPx, spec.cornerRadiusPx, shimmerPaint)
        }
    }
}
