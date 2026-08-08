package org.example.test.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Vertical [LinearLayout] that recognizes a vertical drag ("scroll") gesture
 * that starts *outside* a designated excluded view (normally the chart
 * canvas). Intended to wrap the whole screen (or at least every region the
 * gesture should be recognizable from - header, bottom bar, empty space,
 * and whatever panel the gesture reveals), not just the canvas itself,
 * otherwise there's no "outside canvas" touchable area to actually drag on.
 *
 * The chart canvas already owns its own pan/pinch/crosshair gestures and
 * calls requestDisallowInterceptTouchEvent while handling them, so this
 * never fights it for touches that begin on the canvas. Taps and clicks on
 * ordinary children (buttons, etc.) are unaffected - we only step in once a
 * touch has moved past touch-slop, at which point Android auto-cancels
 * whatever child was tracking it.
 */
class ScrollAwareChartContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** The view whose bounds should never trigger this gesture (the chart canvas). */
    var excludedView: View? = null

    /** Fired once per gesture when an outside-canvas drag crosses touch-slop moving down. */
    var onScrollDownOutsideCanvas: (() -> Unit)? = null

    /** Fired once per gesture when an outside-canvas drag crosses touch-slop moving up. */
    var onScrollUpOutsideCanvas: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val excludedRect = Rect()

    private var downRawX = 0f
    private var downRawY = 0f
    private var downInsideExcluded = false
    private var gestureHandled = false

    // Screen (raw) coordinates rather than parent-relative ones: this container may sit
    // above the excluded view at any depth, and raw coordinates sidestep having to walk
    // the view tree to translate between coordinate spaces.
    private fun isInsideExcluded(rawX: Float, rawY: Float): Boolean {
        val excluded = excludedView ?: return false
        if (excluded.visibility != View.VISIBLE || excluded.width == 0 || excluded.height == 0) return false
        excluded.getGlobalVisibleRect(excludedRect)
        return excludedRect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun trackDown(ev: MotionEvent) {
        downRawX = ev.rawX
        downRawY = ev.rawY
        downInsideExcluded = isInsideExcluded(ev.rawX, ev.rawY)
        gestureHandled = false
    }

    /** Returns true if this move crossed the threshold and fired a callback. */
    private fun maybeTrigger(ev: MotionEvent): Boolean {
        if (downInsideExcluded || gestureHandled) return false
        val dy = ev.rawY - downRawY
        val dx = ev.rawX - downRawX
        // Require a mostly-vertical drag so horizontal swipes (e.g. across the
        // bottom bar) don't accidentally trigger the collapse/expand.
        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
            gestureHandled = true
            if (dy > 0) {
                onScrollDownOutsideCanvas?.invoke()
            } else {
                onScrollUpOutsideCanvas?.invoke()
            }
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> trackDown(ev)
            MotionEvent.ACTION_MOVE -> if (maybeTrigger(ev)) return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Reached directly (bypassing onInterceptTouchEvent's per-move calls) when a
        // touch starts on empty space with no child to claim it, and also reached for
        // the remainder of any gesture we've already intercepted above. Track from
        // scratch on DOWN so the empty-space case is still recognized, and swallow
        // everything once a gesture has been (or could still be) handled.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> trackDown(ev)
            MotionEvent.ACTION_MOVE -> maybeTrigger(ev)
        }
        return true
    }
}
