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
 * Vertical [LinearLayout] that hosts the chart canvas plus whatever sits
 * below it, and recognizes a vertical drag ("scroll") gesture that starts
 * *outside* a designated excluded view (normally the chart canvas itself).
 *
 * The chart canvas already owns its own pan/pinch/crosshair gestures and
 * calls [ViewGroup.requestDisallowInterceptTouchEvent] while handling them,
 * so this container never fights it for touches that begin on the canvas.
 * Instead it only watches drags that begin elsewhere in this container
 * (header, bottom bar, the panel being revealed, empty space, etc.) and
 * reports the direction once the gesture clears touch-slop, so callers can
 * drive a collapse/expand animation without interfering with normal clicks.
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

    private var downX = 0f
    private var downY = 0f
    private var downInsideExcluded = false
    private var gestureHandled = false

    private fun isInsideExcluded(x: Float, y: Float): Boolean {
        val excluded = excludedView ?: return false
        excluded.getHitRect(excludedRect)
        return excludedRect.contains(x.toInt(), y.toInt())
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downInsideExcluded = isInsideExcluded(ev.x, ev.y)
                gestureHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downInsideExcluded || gestureHandled) return false

                val dy = ev.y - downY
                val dx = ev.x - downX
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
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Once we've intercepted a gesture we simply swallow the remainder of it -
        // the collapse/expand animation is threshold-driven, not finger-following.
        return true
    }
}
