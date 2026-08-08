package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.example.test.bitget.BitgetCredentials
import org.example.test.bitget.BitgetLiveTradingRestClient
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingConnectionState
import org.example.test.bitget.PositionSide
import java.util.Locale

/**
 * Self-contained "live trading" panel: real account balance, real open
 * positions with live PnL, and controls to open/close positions with market
 * orders against Bitget's real trading API.
 *
 * This is a structural twin of [PaperTradePanel] with the real-money
 * safeguards a paper panel doesn't need:
 *  - Every order (open or close) goes through a confirmation dialog that
 *    states plainly it will use real funds, before anything is sent out.
 *  - The credentials dialog carries an explicit warning and requires the
 *    user to acknowledge it (checkbox) before "Save" is enabled.
 *  - Distinct amber "LIVE" branding throughout so it can't be mistaken for
 *    the paper trading screen at a glance.
 *
 * Like [PaperTradePanel], this view holds no trading state itself - it just
 * renders whatever [org.example.test.bitget.LiveTradingRepository] gives it
 * and forwards user actions back out through [Callbacks].
 */
class LiveTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onCredentialsSubmitted: (BitgetCredentials) -> Unit,
        val onCredentialsCleared: () -> Unit,
        val onOpenPosition: (side: PositionSide, size: String, leverage: Int) -> Unit,
        val onClosePosition: (PaperPosition) -> Unit,
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val fieldBackground = Color.parseColor("#131722")
    private val liveAccentColor = Color.parseColor("#F0B90B") // amber - "this is real money", distinct from paper's teal
    private val liveWarningBorder = Color.parseColor("#4D3A1A")
    private val liveWarningFill = Color.parseColor("#241C0E")

    private var callbacks: Callbacks? = null
    private var savedCredentials: BitgetCredentials? = null
    private var lastConnectionState: PaperTradingConnectionState = PaperTradingConnectionState.NOT_CONFIGURED

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var balanceText: TextView
    private lateinit var balancePnlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateText: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var sizeInput: EditText
    private lateinit var leverageInput: EditText
    private lateinit var submitOrderButton: Button
    private var currentSide: PositionSide = PositionSide.LONG

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), liveAccentColor.withAlpha(0x55))
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))

        addView(buildHeaderRow())
        addView(buildWarningBanner())
        addView(buildBalanceRow())
        addView(spacer(10))
        addView(buildOrderEntryRow())
        addView(spacer(10))
        addView(buildDivider())
        addView(spacer(8))
        addView(buildPositionsHeader())

        emptyStateText = TextView(context).apply {
            text = "No open positions"
            textSize = 12.5f
            setTextColor(mutedColor)
            setPadding(0, dp(6), 0, dp(2))
        }
        addView(emptyStateText)

        positionsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(positionsContainer)
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * Preselects which side the order-entry submit button will open, e.g.
     * from the chart's Long/Short quick-action buttons. Updates the submit
     * button's label and color to match.
     */
    fun setSide(side: PositionSide) {
        currentSide = side
        applySideStyle()
    }

    private fun applySideStyle() {
        if (!::submitOrderButton.isInitialized) return
        val isLong = currentSide == PositionSide.LONG
        submitOrderButton.text = if (isLong) "Open Long" else "Open Short"
        submitOrderButton.background = pillBackground(if (isLong) bullColor else bearColor)
    }

    fun render(
        connectionState: PaperTradingConnectionState,
        balance: PaperAccountBalance?,
        positions: List<PaperPosition>,
        lastError: String?,
        credentials: BitgetCredentials?,
    ) {
        savedCredentials = credentials
        lastConnectionState = connectionState

        val (dotColor, label) = when (connectionState) {
            PaperTradingConnectionState.NOT_CONFIGURED -> mutedColor to "Not connected"
            PaperTradingConnectionState.LOADING -> mutedColor to "Connecting…"
            PaperTradingConnectionState.LIVE -> liveAccentColor to "Live account connected"
            PaperTradingConnectionState.ERROR -> bearColor to (lastError ?: "Error")
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        statusText.text = label
        statusText.setTextColor(if (connectionState == PaperTradingConnectionState.ERROR) bearColor else mutedColor)

        progressBar.visibility = if (connectionState == PaperTradingConnectionState.LOADING) View.VISIBLE else View.GONE

        val ordersEnabled = connectionState == PaperTradingConnectionState.LIVE
        submitOrderButton.isEnabled = ordersEnabled
        submitOrderButton.alpha = if (ordersEnabled) 1f else 0.5f

        if (balance != null) {
            balanceText.text = String.format(Locale.US, "%,.2f USDT", balance.equity)
            val pnl = balance.unrealizedPnl
            balancePnlText.text = String.format(Locale.US, "%s%,.2f uPnL", if (pnl >= 0) "+" else "", pnl)
            balancePnlText.setTextColor(if (pnl >= 0) bullColor else bearColor)
        } else {
            balanceText.text = "—"
            balancePnlText.text = ""
        }

        renderPositions(positions)
    }

    private fun renderPositions(positions: List<PaperPosition>) {
        positionsContainer.removeAllViews()
        emptyStateText.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        for (position in positions) {
            positionsContainer.addView(buildPositionRow(position))
        }
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleColumn.addView(TextView(context).apply {
            text = "Live Trading"
            textSize = 14.5f
            setTextColor(labelColor)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleColumn.addView(TextView(context).apply {
            text = "REAL FUNDS"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(liveAccentColor)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), liveAccentColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        })
        settingsButton = TextView(context).apply {
            text = "⚙ Live API Key"
            textSize = 12f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showCredentialsDialog() }
        }
        row.addView(titleColumn)
        row.addView(settingsButton)
        return row
    }

    private fun buildWarningBanner(): View =
        TextView(context).apply {
            text = "Orders placed here use real funds on your Bitget account."
            textSize = 11f
            setTextColor(liveAccentColor)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(liveWarningFill)
                setStroke(dp(1), liveWarningBorder)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
        }

    private fun buildBalanceRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val statusColumn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        }
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(mutedColor)
        }
        progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        statusColumn.addView(statusDot)
        statusColumn.addView(statusText)
        statusColumn.addView(progressBar)

        val balanceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        balanceText = TextView(context).apply {
            textSize = 15f
            setTextColor(labelColor)
            typeface = Typeface.DEFAULT_BOLD
            text = "—"
        }
        balancePnlText = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        balanceColumn.addView(balanceText)
        balanceColumn.addView(balancePnlText)

        row.addView(statusColumn)
        row.addView(balanceColumn)
        return row
    }

    private fun buildOrderEntryRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        sizeInput = fieldEditText(hint = "Size (BTC)", inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText("0.01")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginEnd = dp(6) }
        }
        leverageInput = fieldEditText(hint = "Lev.", inputType = InputType.TYPE_CLASS_NUMBER).apply {
            setText("5")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply { marginEnd = dp(8) }
        }

        submitOrderButton = Button(context).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { confirmAndSubmitOrder(currentSide) }
        }
        applySideStyle()

        row.addView(sizeInput)
        row.addView(leverageInput)
        row.addView(submitOrderButton)
        return row
    }

    private fun confirmAndSubmitOrder(side: PositionSide) {
        val size = sizeInput.text?.toString()?.trim().orEmpty()
        val leverage = leverageInput.text?.toString()?.trim()?.toIntOrNull() ?: 5
        if (size.toDoubleOrNull() == null || size.toDouble() <= 0.0) {
            sizeInput.error = "Enter a size"
            return
        }
        val clampedLeverage = leverage.coerceIn(1, 125)
        val sideLabel = if (side == PositionSide.LONG) "LONG" else "SHORT"

        AlertDialog.Builder(context)
            .setTitle("Confirm live order")
            .setMessage(
                "Open a $sideLabel position of $size BTC at ${clampedLeverage}x leverage " +
                    "on BTCUSDT.\n\nThis places a real order with real funds on your Bitget account.",
            )
            .setPositiveButton("Place order") { _, _ ->
                callbacks?.onOpenPosition?.invoke(side, size, clampedLeverage)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildPositionsHeader(): View =
        TextView(context).apply {
            text = "Open positions"
            textSize = 12f
            setTextColor(mutedColor)
        }

    private fun buildPositionRow(position: PaperPosition): View {
        val isLong = position.side == PositionSide.LONG
        val sideColor = if (isLong) bullColor else bearColor

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "${position.symbol}  "
            textSize = 13f
            setTextColor(labelColor)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleRow.addView(TextView(context).apply {
            text = if (isLong) "LONG ${position.leverage}x" else "SHORT ${position.leverage}x"
            textSize = 11f
            setTextColor(sideColor)
        })
        infoColumn.addView(titleRow)
        infoColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%.4f @ %,.2f", position.total, position.entryPrice)
            textSize = 11.5f
            setTextColor(mutedColor)
        })

        val pnlColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        pnlColumn.addView(TextView(context).apply {
            val pnl = position.unrealizedPnl
            text = String.format(Locale.US, "%s%,.2f", if (pnl >= 0) "+" else "", pnl)
            textSize = 13f
            setTextColor(if (pnl >= 0) bullColor else bearColor)
        })
        pnlColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%s%.1f%%", if (position.pnlPercentOfMargin >= 0) "+" else "", position.pnlPercentOfMargin)
            textSize = 11f
            setTextColor(mutedColor)
        })

        val closeButton = TextView(context).apply {
            text = "Close"
            textSize = 12f
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
            setOnClickListener { confirmAndClosePosition(position) }
        }

        row.addView(infoColumn)
        row.addView(pnlColumn)
        row.addView(closeButton)
        return row
    }

    private fun confirmAndClosePosition(position: PaperPosition) {
        val sideLabel = if (position.side == PositionSide.LONG) "LONG" else "SHORT"
        AlertDialog.Builder(context)
            .setTitle("Confirm close position")
            .setMessage(
                "Close the $sideLabel ${position.symbol} position " +
                    "(${String.format(Locale.US, "%.4f", position.total)} BTC) with a real market order.",
            )
            .setPositiveButton("Close position") { _, _ ->
                callbacks?.onClosePosition?.invoke(position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The live trading credentials modal. Deliberately scoped to exactly two
     * things - the API Key/Secret/Passphrase and the API Connection it will
     * be used against - since that's the only decision this screen needs to
     * make; order entry, positions, etc. all live on the main panel.
     */
    private fun showCredentialsDialog() {
        // Own scope for the (unsaved-credentials) "Test Connection" network
        // call, cancelled the moment the dialog goes away so a slow response
        // can't land after the views it would update are gone.
        val dialogScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        var isTestnet = savedCredentials?.isTestnet ?: false

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }

        container.addView(sectionHeader("API Credentials"))
        val (apiKeyField, apiKeyRow) = secureField("API Key", savedCredentials?.apiKey.orEmpty(), maskByDefault = false)
        val (secretField, secretRow) = secureField("API Secret", savedCredentials?.secretKey.orEmpty(), maskByDefault = true)
        val (passphraseField, passphraseRow) = secureField("Passphrase (optional)", savedCredentials?.passphrase.orEmpty(), maskByDefault = true)
        container.addView(apiKeyRow)
        container.addView(secretRow)
        container.addView(passphraseRow)

        container.addView(spacer(14))
        container.addView(sectionHeader("API Connection"))

        // Exchange selector - a single supported exchange today, presented
        // as a dropdown so this reads naturally if more are added later.
        val exchangeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Bitget"))
        }
        container.addView(labeledRow("Exchange", exchangeSpinner))

        // Environment segmented control - Mainnet vs Testnet. Testnet keys
        // are Bitget "Demo API Keys" and are routed with the sandbox header
        // (see BitgetLiveTradingRestClient) rather than the real one.
        lateinit var mainnetOption: TextView
        lateinit var testnetOption: TextView
        fun refreshEnvironmentStyle() {
            mainnetOption.background = segmentedOptionBackground(selected = !isTestnet)
            mainnetOption.setTextColor(if (!isTestnet) Color.WHITE else mutedColor)
            testnetOption.background = segmentedOptionBackground(selected = isTestnet)
            testnetOption.setTextColor(if (isTestnet) Color.WHITE else mutedColor)
        }
        mainnetOption = segmentedOption("Mainnet") { isTestnet = false; refreshEnvironmentStyle() }
        testnetOption = segmentedOption("Testnet") { isTestnet = true; refreshEnvironmentStyle() }
        val environmentRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(mainnetOption)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 0) })
            addView(testnetOption)
        }
        refreshEnvironmentStyle()
        container.addView(labeledRow("Environment", environmentRow))

        container.addView(spacer(14))
        container.addView(buildDivider())
        container.addView(spacer(10))

        // Connection status indicator.
        val statusDotView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
        }
        val statusLabel = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val statusDetail = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        }
        val testProgress = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        fun setStatus(connected: Boolean, detail: String? = null) {
            statusDotView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (connected) bullColor else mutedColor)
            }
            statusLabel.text = if (connected) "Connected" else "Not Connected"
            statusLabel.setTextColor(if (connected) bullColor else mutedColor)
            statusDetail.text = detail.orEmpty()
            statusDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDotView)
            addView(statusLabel)
            addView(testProgress)
        }
        setStatus(connected = lastConnectionState == PaperTradingConnectionState.LIVE)
        container.addView(statusRow)
        container.addView(statusDetail)

        container.addView(spacer(10))

        val testConnectionButton = TextView(context).apply {
            text = "Test Connection"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(testConnectionButton)

        container.addView(spacer(12))
        container.addView(TextView(context).apply {
            text = "Your API credentials are used only to connect to the exchange from this " +
                "device. They are never used for withdrawals - create the key with withdrawal " +
                "permissions disabled on the exchange."
            textSize = 11f
            setTextColor(mutedColor)
        })
        container.addView(spacer(14))

        val saveConnectButton = Button(context).apply {
            text = "Save & Connect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pillBackground(liveAccentColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(saveConnectButton)

        if (savedCredentials != null) {
            container.addView(spacer(6))
            container.addView(TextView(context).apply {
                text = "Remove saved key"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(bearColor)
                isClickable = true
                isFocusable = true
                setPadding(0, dp(8), 0, dp(4))
            }.also { removeText ->
                // Wired up below, once `dialog` exists.
                removeText.tag = "remove"
            })
        }
        container.addView(spacer(4))

        val scrollableContainer = ScrollView(context).apply { addView(container) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("API Key & Connection")
            .setView(scrollableContainer)
            .setNegativeButton("Cancel", null)
            .create()

        fun currentlyEnteredCredentials(): BitgetCredentials = BitgetCredentials(
            apiKey = apiKeyField.text?.toString()?.trim().orEmpty(),
            secretKey = secretField.text?.toString()?.trim().orEmpty(),
            passphrase = passphraseField.text?.toString()?.trim().orEmpty(),
            isTestnet = isTestnet,
        )

        testConnectionButton.setOnClickListener {
            val credentials = currentlyEnteredCredentials()
            if (!credentials.isComplete) {
                apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
                secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
                return@setOnClickListener
            }
            testProgress.visibility = View.VISIBLE
            testConnectionButton.isEnabled = false
            dialogScope.launch {
                val client = BitgetLiveTradingRestClient(credentialsProvider = { credentials })
                try {
                    client.fetchAccountBalance()
                    setStatus(connected = true, detail = "Key is valid and reachable.")
                } catch (e: Exception) {
                    setStatus(connected = false, detail = e.message ?: "Couldn't reach the exchange")
                } finally {
                    testProgress.visibility = View.GONE
                    testConnectionButton.isEnabled = true
                }
            }
        }

        saveConnectButton.setOnClickListener {
            val credentials = currentlyEnteredCredentials()
            if (!credentials.isComplete) {
                apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
                secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
                return@setOnClickListener
            }
            callbacks?.onCredentialsSubmitted?.invoke(credentials)
            dialog.dismiss()
        }

        (container.findViewWithTag<TextView>("remove"))?.setOnClickListener {
            callbacks?.onCredentialsCleared?.invoke()
            dialog.dismiss()
        }

        dialog.setOnDismissListener { dialogScope.cancel() }
        dialog.show()
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(Locale.US)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(mutedColor)
        letterSpacing = 0.04f
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun labeledRow(label: String, control: View): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            textSize = 12.5f
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(control)
    }

    private fun segmentedOption(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onClick() }
        }

    private fun segmentedOptionBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        if (selected) {
            setColor(liveAccentColor)
        } else {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), borderColor)
        }
    }

    /** An EditText plus an eye-icon visibility toggle, wrapped in one row. Secrets start masked; the API Key does not need to. */
    private fun secureField(hint: String, initialValue: String, maskByDefault: Boolean): Pair<EditText, View> {
        val field = fieldEditTextForDialog(hint).apply {
            setText(initialValue)
            if (maskByDefault) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        var visible = !maskByDefault
        val toggle = TextView(context).apply {
            text = if (visible) "Hide" else "Show"
            textSize = 11.5f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                field.setSelection(field.text?.length ?: 0)
                text = if (visible) "Hide" else "Show"
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(field.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(toggle)
        }
        return field to row
    }

    private fun fieldEditTextForDialog(hint: String): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 13.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
        }

    private fun fieldEditText(hint: String, inputType: Int): EditText =
        EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 13f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}
