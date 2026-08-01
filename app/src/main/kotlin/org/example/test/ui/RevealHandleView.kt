package org.example.test.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration

/**
 * A small always-visible pull tab sitting at the bottom edge of the chart section. Dragging it
 * up collapses the chart to 40% height and reveals the Smart Money Dashboard beneath; dragging
 * it down restores the full-screen chart. Tapping it toggles between the two resting states.
 *
 * This view owns its own, independent touch handling so the chart's pan/pinch/crosshair
 * gesture code is never touched or intercepted.
 */
class RevealHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var host: SmartMoneyRevealLayout? = null
    var collapsed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#787B86")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAECEF")
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1D26")
        style = Paint.Style.FILL
    }
    private val gripRect = RectF()

    private var downRawY = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Smart Money Dashboard handle"
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val gripWidth = dp(36f)
        val gripHeight = dp(4f)
        val gripTop = dp(6f)
        gripRect.set(
            width / 2f - gripWidth / 2f,
            gripTop,
            width / 2f + gripWidth / 2f,
            gripTop + gripHeight,
        )
        canvas.drawRoundRect(gripRect, gripHeight / 2f, gripHeight / 2f, gripPaint)

        val label = if (collapsed) "Chart ▲" else "Smart Money Dashboard ▲"
        canvas.drawText(label, width / 2f, height - dp(9f), labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                lastRawY = event.rawY
                isDragging = false
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                host?.beginDrag()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val totalDelta = event.rawY - downRawY
                if (!isDragging && abs(totalDelta) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    host?.dragBy(totalDelta)
                }
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    velocityTracker?.apply {
                        addMovement(event)
                        computeCurrentVelocity(1000)
                        host?.endDrag(yVelocity)
                    }
                } else {
                    // Simple tap: toggle state.
                    host?.toggle()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun abs(v: Float) = if (v < 0f) -v else v
}
