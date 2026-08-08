package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PositionSide
import java.util.Locale

/**
 * Compact "quick trade" drawer that lives directly under the chart. Unlike
 * [LiveTradePanel]/[PaperTradePanel] (full account screens shown in a
 * dialog), this view only surfaces what's needed to fire off an order
 * without leaving the chart: balance, unrealized PnL, leverage, position
 * size, order type, and the Long/Short buttons.
 *
 * It holds no trading state of its own - [MainActivity] drives visibility
 * (via the scroll gesture) and feeds it balance updates through [render].
 */
class QuickTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class OrderType { MARKET, LIMIT }

    class Callbacks(
        val onOpenPosition: (
            side: PositionSide,
            sizeUsdt: String,
            leverage: Int,
            orderType: OrderType,
            limitPrice: String?,
        ) -> Unit,
    )

    private val surfaceColor = Color.parseColor("#0A1015")
    private val borderColor = Color.parseColor("#1B2530")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#8A96A3")
    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
    private val fieldBackground = Color.parseColor("#131A21")

    private var callbacks: Callbacks? = null
    private var currentLeverage = 10
    private var currentOrderType = OrderType.MARKET

    private lateinit var balanceValueText: TextView
    private lateinit var pnlValueText: TextView
    private lateinit var leverageValueText: TextView
    private lateinit var sizeInput: EditText
    private lateinit var marketToggle: TextView
    private lateinit var limitToggle: TextView
    private lateinit var limitPriceInput: EditText
    private lateinit var longButton: TextView
    private lateinit var shortButton: TextView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), borderColor)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))

        addView(buildGrabHandle())
        addView(buildStatRow())
        addView(spacer(14))
        addView(buildLeverageRow())
        addView(spacer(10))
        addView(buildSizeRow())
        addView(spacer(10))
        addView(buildOrderTypeRow())
        limitPriceInput = buildLimitPriceInput()
        addView(limitPriceInput)
        addView(spacer(14))
        addView(buildLongShortRow())
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun render(balance: PaperAccountBalance?) {
        if (balance != null) {
            balanceValueText.text = String.format(Locale.US, "%,.2f USDT", balance.equity)
            val pnl = balance.unrealizedPnl
            pnlValueText.text = String.format(Locale.US, "%s%,.2f USDT", if (pnl >= 0) "+" else "", pnl)
            pnlValueText.setTextColor(if (pnl >= 0) bullColor else bearColor)
        } else {
            balanceValueText.text = "—"
            pnlValueText.text = "—"
            pnlValueText.setTextColor(mutedColor)
        }
    }

    private fun buildGrabHandle(): View =
        View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(borderColor)
            }
            layoutParams = LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }

    private fun buildStatRow(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }

        val balanceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        balanceColumn.addView(statLabel("Balance"))
        balanceValueText = TextView(context).apply {
            text = "—"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        }
        balanceColumn.addView(balanceValueText)

        val pnlColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        pnlColumn.addView(statLabel("Unrealized PnL", end = true))
        pnlValueText = TextView(context).apply {
            text = "—"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedColor)
            gravity = Gravity.END
        }
        pnlColumn.addView(pnlValueText)

        row.addView(balanceColumn)
        row.addView(pnlColumn)
        return row
    }

    private fun statLabel(text: String, end: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(mutedColor)
            if (end) gravity = Gravity.END
        }

    private fun buildLeverageRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(context).apply {
                text = "Leverage"
                textSize = 13f
                setTextColor(labelColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )

        val stepper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
        }
        stepper.addView(stepperButton("−") { adjustLeverage(-1) })
        leverageValueText = TextView(context).apply {
            text = "${currentLeverage}x"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(40), LayoutParams.WRAP_CONTENT)
        }
        stepper.addView(leverageValueText)
        stepper.addView(stepperButton("+") { adjustLeverage(1) })

        row.addView(stepper)
        return row
    }

    private fun stepperButton(label: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(dp(34), dp(30))
            setOnClickListener { onClick() }
        }

    private fun adjustLeverage(delta: Int) {
        currentLeverage = (currentLeverage + delta).coerceIn(1, 125)
        leverageValueText.text = "${currentLeverage}x"
    }

    private fun buildSizeRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(context).apply {
                text = "Position size"
                textSize = 13f
                setTextColor(labelColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        sizeInput = EditText(context).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            gravity = Gravity.END
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LayoutParams(dp(110), LayoutParams.WRAP_CONTENT)
        }
        row.addView(sizeInput)
        row.addView(
            TextView(context).apply {
                text = "USDT"
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(dp(6), 0, 0, 0)
            },
        )
        return row
    }

    private fun buildOrderTypeRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(context).apply {
                text = "Order type"
                textSize = 13f
                setTextColor(labelColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )

        val segmented = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
        }
        marketToggle = orderTypeSegment("Market") { setOrderType(OrderType.MARKET) }
        limitToggle = orderTypeSegment("Limit") { setOrderType(OrderType.LIMIT) }
        segmented.addView(marketToggle)
        segmented.addView(limitToggle)
        row.addView(segmented)

        applyOrderTypeStyle()
        return row
    }

    private fun orderTypeSegment(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onClick() }
        }

    private fun setOrderType(orderType: OrderType) {
        currentOrderType = orderType
        applyOrderTypeStyle()
        limitPriceInput.visibility = if (orderType == OrderType.LIMIT) View.VISIBLE else View.GONE
    }

    private fun applyOrderTypeStyle() {
        val isMarket = currentOrderType == OrderType.MARKET
        marketToggle.setTextColor(if (isMarket) Color.BLACK else mutedColor)
        marketToggle.background = if (isMarket) segmentSelectedBackground() else null
        limitToggle.setTextColor(if (!isMarket) Color.BLACK else mutedColor)
        limitToggle.background = if (!isMarket) segmentSelectedBackground() else null
    }

    private fun segmentSelectedBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(bullColor)
    }

    private fun buildLimitPriceInput(): EditText =
        EditText(context).apply {
            hint = "Limit price"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }

    private fun buildLongShortRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 2f
        }
        longButton = tradeButton("Long", bullColor) { submitOrder(PositionSide.LONG) }.apply {
            layoutParams = LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
        }
        shortButton = tradeButton("Short", bearColor) { submitOrder(PositionSide.SHORT) }.apply {
            layoutParams = LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) }
        }
        row.addView(longButton)
        row.addView(shortButton)
        return row
    }

    private fun tradeButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color)
            }
            setOnClickListener { onClick() }
        }

    private fun submitOrder(side: PositionSide) {
        val size = sizeInput.text?.toString()?.trim().orEmpty()
        if (size.toDoubleOrNull() == null || size.toDouble() <= 0.0) {
            sizeInput.error = "Enter a size"
            return
        }
        val limitPrice = limitPriceInput.text?.toString()?.trim()
        callbacks?.onOpenPosition?.invoke(side, size, currentLeverage, currentOrderType, limitPrice.takeIf { !it.isNullOrEmpty() })
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}
