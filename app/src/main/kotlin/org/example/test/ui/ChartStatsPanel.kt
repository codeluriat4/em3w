package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.example.test.bitget.Kline
import java.util.Locale
import kotlin.math.abs

/**
 * Bottom-of-chart panel revealed by dragging down outside the chart canvas
 * (see [ScrollAwareChartContainer]). Shows simple session stats - high, low,
 * and volume across the currently loaded candles - since it needs no state
 * of its own beyond the latest candle list it's given via [render].
 */
class ChartStatsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val handleColor = Color.parseColor("#2A2E39")
    private val titleColor = Color.parseColor("#8A96A3")
    private val valueColor = Color.parseColor("#EAECEF")
    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")

    private lateinit var highValue: TextView
    private lateinit var lowValue: TextView
    private lateinit var volumeValue: TextView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0A1015"))
        }
        setPadding(dp(16), dp(6), dp(16), dp(10))

        addView(buildDragHandle())
        addView(buildTitle())
        addView(buildStatsRow())
    }

    private fun buildDragHandle() = android.widget.FrameLayout(context).apply {
        addView(
            android.view.View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(handleColor)
                }
            },
            LinearLayout.LayoutParams(dp(36), dp(4)),
        )
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
            bottomMargin = dp(10)
        }
    }

    private fun buildTitle() = TextView(context).apply {
        text = "Session Stats"
        textSize = 12f
        setTextColor(titleColor)
    }

    private fun buildStatsRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        val (highCol, highVal) = buildStatColumn("High")
        val (lowCol, lowVal) = buildStatColumn("Low")
        val (volCol, volVal) = buildStatColumn("Volume")
        highValue = highVal
        lowValue = lowVal
        volumeValue = volVal
        row.addView(highCol, weightedParams())
        row.addView(lowCol, weightedParams())
        row.addView(volCol, weightedParams())
        return row
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

    private fun buildStatColumn(label: String): Pair<LinearLayout, TextView> {
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val labelView = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(titleColor)
        }
        val valueView = TextView(context).apply {
            text = "—"
            textSize = 14f
            setTextColor(valueColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        column.addView(labelView)
        column.addView(valueView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        return column to valueView
    }

    /** Refreshes the panel with the latest candle buffer. Safe to call even while collapsed. */
    fun render(candles: List<Kline>) {
        if (candles.isEmpty()) return
        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        val volume = candles.sumOf { it.baseVolume }
        val last = candles.last()
        val first = candles.first()
        val isUp = last.close >= first.open

        highValue.text = formatPrice(high)
        highValue.setTextColor(bullColor)
        lowValue.text = formatPrice(low)
        lowValue.setTextColor(bearColor)
        volumeValue.text = formatVolume(volume)
        volumeValue.setTextColor(if (isUp) bullColor else bearColor)
    }

    private fun formatPrice(price: Double): String {
        val decimals = when {
            abs(price) >= 1000 -> 1
            abs(price) >= 1 -> 2
            else -> 5
        }
        return String.format(Locale.US, "%,.${decimals}f", price)
    }

    private fun formatVolume(volume: Double): String = when {
        volume >= 1_000_000 -> String.format(Locale.US, "%.2fM", volume / 1_000_000)
        volume >= 1_000 -> String.format(Locale.US, "%.2fK", volume / 1_000)
        else -> String.format(Locale.US, "%.2f", volume)
    }
}
