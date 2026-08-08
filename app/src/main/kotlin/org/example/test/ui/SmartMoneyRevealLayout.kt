package org.example.test.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import org.example.test.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Coordinates the chart section collapsing to a fixed height fraction ([COLLAPSED_FRACTION]
 * of the layout's total height) as the Smart Money Dashboard panel underneath is revealed.
 *
 * Expects exactly two children, matched by id:
 *  - [R.id.chartSectionContainer] — the chart header/canvas/timeframe row, pinned to the top.
 *  - [R.id.smartMoneyDashboardScroll] — a [NestedScrollView] wrapping the dashboard content,
 *    pinned so its bottom always sits at the layout's bottom edge.
 *
 * Design points:
 *  - The chart's own touch handling (pan/pinch/crosshair, drawing tools) is never
 *    intercepted — only drags starting *outside* the chart canvas (i.e. on the header row
 *    or timeframe row, found via [R.id.chartCanvas]) can drive the collapse gesture.
 *  - Mid-gesture, resizing is done via a `scaleY`/`translationY` transform (cheap,
 *    render-only) rather than real layout/measure passes; a real layout pass is committed
 *    once, only when the drag or settle animation finishes. This keeps the collapse/expand
 *    animation smooth without repeatedly triggering the chart's expensive `onSizeChanged`
 *    recompute.
 *  - Also cooperates with the dashboard's [NestedScrollView] (via [NestedScrollingParent3])
 *    so that once the panel is showing, dragging up first scrolls its content normally, and
 *    scrolling that content back up to its top and continuing to drag re-expands the chart.
 */
class SmartMoneyRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), NestedScrollingParent3 {

    private companion object {
        const val EXPANDED_FRACTION = 1f
        const val COLLAPSED_FRACTION = 0.4f
        const val SETTLE_ANIM_MS = 220L
    }

    private var chartSection: View? = null
    private var dashboardScroll: NestedScrollView? = null
    private var chartCanvas: View? = null

    /** The fraction of total height the chart occupies once a layout pass has committed. */
    private var settledFraction = EXPANDED_FRACTION

    /** The fraction currently reflected on screen — equals [settledFraction] except mid-gesture. */
    private var liveFraction = EXPANDED_FRACTION

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isPossibleChromeDrag = false
    private var isDraggingChrome = false
    private var dragStartFraction = EXPANDED_FRACTION
    private var velocityTracker: VelocityTracker? = null

    private var settleAnimator: ValueAnimator? = null
    private val childRect = Rect()

    override fun onFinishInflate() {
        super.onFinishInflate()
        chartSection = findViewById(R.id.chartSectionContainer)
        dashboardScroll = findViewById(R.id.smartMoneyDashboardScroll)
        chartCanvas = findViewById(R.id.chartCanvas)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = measuredWidth
        val height = measuredHeight
        if (width <= 0 || height <= 0) return

        val chartHeight = (height * settledFraction).toInt()
        val dashboardHeight = (height * (EXPANDED_FRACTION - COLLAPSED_FRACTION)).toInt()

        chartSection?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(chartHeight, MeasureSpec.EXACTLY),
        )
        dashboardScroll?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dashboardHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        val width = right - left
        val chartHeight = (height * settledFraction).toInt()
        val dashboardHeight = (height * (EXPANDED_FRACTION - COLLAPSED_FRACTION)).toInt()
        val dashboardTop = (height * settledFraction).toInt()

        chartSection?.layout(0, 0, width, chartHeight)
        dashboardScroll?.layout(0, dashboardTop, width, dashboardTop + dashboardHeight)

        // A committed layout pass always represents the settled state, so any leftover
        // mid-gesture transform is no longer meaningful.
        applyLiveTransform(settledFraction)
    }

    // ---- Manual chrome drag (drives the gesture before the dashboard has any height to
    // receive touches of its own, e.g. starting from a fully expanded chart) ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isPossibleChromeDrag = isInChromeRegion(ev.x, ev.y)
                isDraggingChrome = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPossibleChromeDrag && !isDraggingChrome) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        beginChromeDrag()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPossibleChromeDrag = false
            }
        }
        return isDraggingChrome
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isPossibleChromeDrag && !isDraggingChrome) return false

        obtainVelocityTracker().addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingChrome && abs(ev.y - downY) > touchSlop) {
                    beginChromeDrag()
                }
                if (isDraggingChrome) {
                    val deltaY = ev.y - downY
                    val height = height.takeIf { it > 0 } ?: return true
                    val proposed = dragStartFraction - deltaY / height
                    setLiveFraction(proposed.coerceIn(COLLAPSED_FRACTION, EXPANDED_FRACTION))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingChrome) {
                    val velocityY = currentFlingVelocityY()
                    settleToNearest(velocityY)
                }
                isDraggingChrome = false
                isPossibleChromeDrag = false
                releaseVelocityTracker()
            }
        }
        return true
    }

    private fun beginChromeDrag() {
        isDraggingChrome = true
        dragStartFraction = liveFraction
        settleAnimator?.cancel()
    }

    /** True if (x, y), in this view's coordinates, falls inside the chart section but outside its canvas. */
    private fun isInChromeRegion(x: Float, y: Float): Boolean {
        val section = chartSection ?: return false
        section.getHitRect(childRect)
        if (!childRect.contains(x.toInt(), y.toInt())) return false

        val canvas = chartCanvas ?: return true
        canvas.getHitRect(childRect)
        return !childRect.contains(x.toInt(), y.toInt())
    }

    private fun obtainVelocityTracker(): VelocityTracker =
        velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }

    private fun currentFlingVelocityY(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000)
        return tracker.yVelocity
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- Nested scroll cooperation with the dashboard's NestedScrollView ----
    // NestedScrollingParent3 extends v2 and v1, so all three interfaces' methods must be
    // implemented; the v1/v2 overloads (no `type`) simply delegate to the v3 ones as
    // touch-driven scrolls, which is all this layout needs to support.

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        axes and View.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        settleAnimator?.cancel()
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        settleToNearest(0f)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        val height = height.takeIf { it > 0 } ?: return
        // Dragging content up (dy > 0) while the chart hasn't fully collapsed yet: absorb
        // that scroll into collapsing the chart first, same as a collapsing toolbar, so the
        // dashboard only starts scrolling its own content once fully revealed.
        if (dy > 0 && liveFraction > COLLAPSED_FRACTION) {
            val neededPx = (liveFraction - COLLAPSED_FRACTION) * height
            val consumedPx = min(dy.toFloat(), neededPx)
            setLiveFraction(liveFraction - consumedPx / height)
            consumed[1] = consumedPx.toInt()
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
        val height = height.takeIf { it > 0 } ?: return
        // The scroll view has hit its own top (scrollY == 0) and there's still unconsumed
        // downward drag left over: treat it as "scrolled the content back up to the top,
        // keep going" and re-expand the chart.
        if (dyUnconsumed < 0 && liveFraction < EXPANDED_FRACTION) {
            val expandPx = -dyUnconsumed
            setLiveFraction(liveFraction + expandPx / height)
            consumed[1] += dyUnconsumed
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
    ) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onStopNestedScroll(target: View) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
    ) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)
    }

    override fun getNestedScrollAxes(): Int = View.SCROLL_AXIS_VERTICAL

    // The dashboard content never flings the layout itself (only its own inner scroll), so
    // these are no-ops that let the scroll view handle its own fling.
    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

    // ---- Fraction application: cheap transform mid-gesture, real layout once settled ----

    private fun setLiveFraction(fraction: Float) {
        val clamped = fraction.coerceIn(COLLAPSED_FRACTION, EXPANDED_FRACTION)
        if (clamped == liveFraction) return
        liveFraction = clamped
        applyLiveTransform(clamped)
    }

    private fun applyLiveTransform(fraction: Float) {
        val height = height.takeIf { it > 0 } ?: return
        val section = chartSection ?: return
        val dashboard = dashboardScroll ?: return

        val settledChartHeight = height * settledFraction
        if (settledChartHeight > 0f) {
            section.pivotY = 0f
            section.scaleY = (height * fraction) / settledChartHeight
        }
        dashboard.translationY = (fraction - settledFraction) * height
    }

    private fun settleToNearest(velocityY: Float) {
        settleAnimator?.cancel()

        // A decisive fast swipe wins over position; otherwise settle to whichever state is
        // visually closer.
        val flingThreshold = 900f
        val target = when {
            velocityY < -flingThreshold -> EXPANDED_FRACTION
            velocityY > flingThreshold -> COLLAPSED_FRACTION
            else -> {
                val midpoint = (EXPANDED_FRACTION + COLLAPSED_FRACTION) / 2f
                if (liveFraction >= midpoint) EXPANDED_FRACTION else COLLAPSED_FRACTION
            }
        }

        if (target == settledFraction && liveFraction == settledFraction) return

        val start = liveFraction
        settleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = SETTLE_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { setLiveFraction(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    commit(target)
                }
            })
            start()
        }
    }

    private fun commit(fraction: Float) {
        settledFraction = fraction
        liveFraction = fraction
        chartSection?.let { it.scaleY = 1f }
        dashboardScroll?.let { it.translationY = 0f }
        requestLayout()
    }

    /** True once the dashboard is fully revealed (chart at its minimum height). */
    fun isDashboardFullyRevealed(): Boolean = settledFraction <= COLLAPSED_FRACTION

    /** Programmatically collapses the chart / reveals the dashboard, animated. */
    fun revealDashboard() {
        settleAnimator?.cancel()
        settleToNearest(velocityY = Float.POSITIVE_INFINITY)
    }

    /** Programmatically re-expands the chart to fullscreen, animated. */
    fun collapseDashboard() {
        settleAnimator?.cancel()
        settleToNearest(velocityY = Float.NEGATIVE_INFINITY)
    }
}
