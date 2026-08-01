package org.example.test.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import kotlin.math.abs
import org.example.test.R

/**
 * Coordinates the "chart" section (full-screen by default) with a "Smart Money Dashboard"
 * section that lives underneath it and is revealed as the chart collapses to a fixed 40%
 * of the container height.
 *
 * Design goals:
 *  - The chart's own touch handling (pan / pinch / crosshair) is never intercepted or modified.
 *    The collapsing gesture is driven by any vertical drag that starts *outside* the chart
 *    canvas (the candlestick/depth-heatmap area) - e.g. the symbol header row or the
 *    timeframe/settings row - via [onInterceptTouchEvent]/[onTouchEvent] below, or by
 *    nested-scroll cooperation with the dashboard's [NestedScrollView] once it is visible.
 *  - While the user is actively dragging or a settle animation is running, the chart section is
 *    resized using a `scaleY` transform (pivoted at the top) instead of real layout changes.
 *    A transform is a render-only operation - it costs a redraw, not a measure/layout pass - so
 *    it stays smooth at 60fps and never triggers the expensive resize work that the chart's
 *    child views (candles + depth heatmap) do in onSizeChanged.
 *  - Once a drag/animation settles at either end (full-screen or 40%), we commit a *real* layout
 *    resize exactly once and reset scaleY back to 1f. This makes the resting state render at its
 *    native resolution (crisp grid/text density) rather than a stretched/squeezed transform, and
 *    it is what gives the chart's own dynamic grid-density and crosshair math correct, consistent
 *    input in both resting states.
 *  - Because Android automatically remaps MotionEvent coordinates through a child's scale/pivot
 *    when dispatching touch, panning/crosshair math inside the chart stays correct throughout,
 *    even mid-transform.
 */
class SmartMoneyRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    /** Fraction of the container height the chart occupies once collapsed. */
    var collapsedHeightFraction: Float = 0.4f

    private val nestedScrollingParentHelper = NestedScrollingParentHelper(this)

    private lateinit var chartSection: View
    private lateinit var dashboardScroll: NestedScrollView

    /** The candlestick/depth-heatmap area. Touches here are left entirely to the chart itself. */
    private var chartCanvas: View? = null

    /** True once the layout has settled into the collapsed (40%) resting state. */
    private var isCollapsed = false

    /** 0f = fully expanded (full screen), 1f = fully collapsed (40%). Only meaningful mid-gesture. */
    private var liveProgress = 0f

    private var expandedHeightPx = 0
    private var collapsedHeightPx = 0

    private var settleAnimator: ValueAnimator? = null

    var onCollapseStateChanged: ((collapsed: Boolean) -> Unit)? = null

    // ---- Outside-canvas drag gesture state ----------------------------------------------------
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var eligibleForOutsideDrag = false
    private var isOutsideDragActive = false
    private var velocityTracker: VelocityTracker? = null
    private val hitRect = Rect()

    override fun onFinishInflate() {
        super.onFinishInflate()
        chartSection = findViewById(R.id.chartSectionContainer)
        dashboardScroll = findViewById(R.id.dashboardScrollView)
        chartCanvas = findViewById(R.id.chartCanvas)
        chartSection.pivotY = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        expandedHeightPx = height
        collapsedHeightPx = (height * collapsedHeightFraction).toInt()

        val chartHeightPx = if (isCollapsed) collapsedHeightPx else expandedHeightPx
        chartSection.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(chartHeightPx, MeasureSpec.EXACTLY),
        )

        val dashboardHeightPx = (height - collapsedHeightPx).coerceAtLeast(0)
        dashboardScroll.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dashboardHeightPx, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Dashboard always occupies the bottom region starting where the collapsed chart ends.
        dashboardScroll.layout(0, collapsedHeightPx, dashboardScroll.measuredWidth, height)

        // Chart section is pinned to the top; its real height is whatever it was measured at.
        chartSection.layout(0, 0, chartSection.measuredWidth, chartSection.measuredHeight)

        if (settleAnimator?.isRunning != true) {
            chartSection.scaleY = 1f
        }
    }

    // ---- Public drag API, driven by the outside-canvas touch handling below -----------------

    fun beginDrag() {
        settleAnimator?.cancel()
    }

    /** [deltaPx] is the raw finger delta since the drag started (positive = finger moved down). */
    fun dragBy(deltaPx: Float) {
        val dragRange = (expandedHeightPx - collapsedHeightPx).toFloat()
        if (dragRange <= 0f) return

        // Dragging up (negative delta) increases progress toward collapsed; dragging down reverses it.
        val startProgress = if (isCollapsed) 1f else 0f
        val progress = (startProgress - deltaPx / dragRange).coerceIn(0f, 1f)
        applyProgress(progress)
    }

    fun toggle() {
        settleTo(!isCollapsed)
    }

    fun endDrag(velocityPxPerSec: Float) {
        val target = when {
            abs(velocityPxPerSec) > FLING_VELOCITY_THRESHOLD -> velocityPxPerSec < 0f
            else -> liveProgress >= 0.5f
        }
        settleTo(target)
    }

    // ---- Core transform / commit machinery ---------------------------------------------------

    private fun applyProgress(progress: Float) {
        liveProgress = progress
        val currentRealHeight = chartSection.height.takeIf { it > 0 } ?: expandedHeightPx
        val targetVisualHeight = expandedHeightPx - progress * (expandedHeightPx - collapsedHeightPx)
        chartSection.scaleY = targetVisualHeight / currentRealHeight
    }

    private fun settleTo(collapsed: Boolean) {
        val start = liveProgress
        val end = if (collapsed) 1f else 0f
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    commitLayout(collapsed)
                }
                override fun onAnimationCancel(animation: Animator) = Unit
            })
            start()
        }
    }

    private fun commitLayout(collapsed: Boolean) {
        if (isCollapsed != collapsed) {
            isCollapsed = collapsed
            onCollapseStateChanged?.invoke(collapsed)
        }
        liveProgress = if (collapsed) 1f else 0f
        chartSection.scaleY = 1f
        requestLayout()
    }

    // ---- Outside-canvas drag gesture (replaces the old dedicated handle) ---------------------
    // Any vertical drag that starts outside the chart canvas - e.g. on the symbol header row or
    // the timeframe/settings row - drives the same collapse/expand behavior the handle used to.
    // A plain tap on those rows (e.g. the timeframe buttons, settings button) is left alone since
    // we only start intercepting once the touch slop is exceeded on a vertical drag.

    private fun isOutsideChartCanvas(x: Float, y: Float): Boolean {
        // Anything at or below the chart section's own (real, unscaled) height belongs to the
        // dashboard area, which already handles its own scrolling via nested-scroll cooperation.
        if (y >= chartSection.height) return false
        val canvas = chartCanvas ?: return true
        canvas.getHitRect(hitRect)
        return !hitRect.contains(x.toInt(), y.toInt())
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isOutsideDragActive = false
                eligibleForOutsideDrag = isOutsideChartCanvas(ev.x, ev.y)
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (eligibleForOutsideDrag && !isOutsideDragActive) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        isOutsideDragActive = true
                        beginDrag()
                        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                eligibleForOutsideDrag = false
            }
        }
        return isOutsideDragActive
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isOutsideDragActive) return false
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragBy(ev.y - downY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.apply {
                    computeCurrentVelocity(1000)
                    endDrag(yVelocity)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isOutsideDragActive = false
                eligibleForOutsideDrag = false
            }
        }
        return true
    }

    /** True while the chart is at rest in its collapsed (40%) state. */
    fun isCollapsedAtRest(): Boolean = isCollapsed && (settleAnimator == null || settleAnimator?.isRunning != true)

    // ---- Nested scroll cooperation with the dashboard's NestedScrollView ---------------------
    // NestedScrollingParent3 extends NestedScrollingParent2 extends NestedScrollingParent, so
    // the older (no `type`) overloads below must also be implemented; they simply delegate to
    // the touch-type versions since this view only cares about the touch-driven case.

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        nestedScrollingParentHelper.onNestedScrollAccepted(child, target, axes, type)
        settleAnimator?.cancel()
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // dy > 0 means the user is dragging upward (content scrolling down / list revealing more).
        if (dy > 0 && !isCollapsed) {
            val dragRange = (expandedHeightPx - collapsedHeightPx).toFloat()
            if (dragRange <= 0f) return
            val consumable = dy.coerceAtMost((dragRange * (1f - liveProgress)).toInt())
            if (consumable > 0) {
                applyProgress(liveProgress + consumable / dragRange)
                consumed[1] = consumable
            }
        } else if (dy < 0 && isCollapsed && dashboardScroll.scrollY == 0) {
            val dragRange = (expandedHeightPx - collapsedHeightPx).toFloat()
            if (dragRange <= 0f) return
            val consumable = dy.coerceAtLeast(-(dragRange * liveProgress).toInt())
            if (consumable < 0) {
                applyProgress(liveProgress + consumable / dragRange)
                consumed[1] = consumable
            }
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
    ) = Unit

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
    ) = Unit

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
    ) = onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

    override fun onStopNestedScroll(target: View, type: Int) {
        nestedScrollingParentHelper.onStopNestedScroll(target, type)
        // Snap to whichever resting state is closer once the user lets go mid-transition.
        if (liveProgress != 0f && liveProgress != 1f) {
            settleTo(liveProgress >= 0.5f)
        }
    }

    override fun onStopNestedScroll(target: View) = onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

    override fun getNestedScrollAxes(): Int = nestedScrollingParentHelper.nestedScrollAxes

    companion object {
        private const val SETTLE_DURATION_MS = 260L
        private const val FLING_VELOCITY_THRESHOLD = 800f
    }
}
