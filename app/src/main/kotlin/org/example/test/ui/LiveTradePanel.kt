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
 *  - The Live API Key & Connection section (see [buildCredentialsSection])
 *    is the panel's upfront content - connecting an account is the first
 *    thing this screen asks for, ahead of the trading UI below it, which
 *    stays disabled until [PaperTradingConnectionState.LIVE].
 *  - Every order (open or close) goes through a confirmation dialog that
 *    states plainly it will use real funds, before anything is sent out.
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
    private var fieldsPrefilled = false
    private var isTestnetSelected = false
    private var credentialScope: CoroutineScope? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var balanceText: TextView
    private lateinit var balancePnlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateText: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var sizeInput: EditText
    private lateinit var leverageInput: EditText
    private lateinit var submitOrderButton: Button
    private var currentSide: PositionSide = PositionSide.LONG

    // ---- Live API Key section (the panel's upfront content) ----
    private lateinit var apiKeyField: EditText
    private lateinit var secretField: EditText
    private lateinit var passphraseField: EditText
    private lateinit var mainnetOption: TextView
    private lateinit var testnetOption: TextView
    private lateinit var credStatusDot: View
    private lateinit var credStatusLabel: TextView
    private lateinit var credStatusDetail: TextView
    private lateinit var testProgress: ProgressBar
    private lateinit var testConnectionButton: TextView
    private lateinit var saveConnectButton: Button
    private lateinit var removeKeyText: TextView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), liveAccentColor.withAlpha(0x55))
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))

        // The Live API Key & Connection section is the panel's upfront
        // content - connecting an account is the first thing this screen
        // asks for, ahead of the (disabled-until-connected) trading UI.
        addView(buildCredentialsSection())
        addView(spacer(14))
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        credentialScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onDetachedFromWindow() {
        credentialScope?.cancel()
        credentialScope = null
        super.onDetachedFromWindow()
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

        if (!fieldsPrefilled && credentials != null) {
            apiKeyField.setText(credentials.apiKey)
            secretField.setText(credentials.secretKey)
            passphraseField.setText(credentials.passphrase)
            isTestnetSelected = credentials.isTestnet
            refreshEnvironmentStyle()
            fieldsPrefilled = true
        }
        removeKeyText.visibility = if (credentials != null) View.VISIBLE else View.GONE
        applyCredentialStatus(
            connected = connectionState == PaperTradingConnectionState.LIVE,
            detail = if (connectionState == PaperTradingConnectionState.ERROR) lastError else null,
        )

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
        row.addView(titleColumn)
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
     * The Live API Key & Connection section. This is the panel's upfront
     * content (see init{}) - connecting an account is the first thing this
     * screen asks for, scoped to exactly two things: the API
     * Key/Secret/Passphrase, and the API Connection (exchange + Mainnet vs
     * Testnet) they'll be used against.
     */
    private fun buildCredentialsSection(): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), liveAccentColor.withAlpha(0x40))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "Live API Key"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(context).apply {
            text = "REAL FUNDS"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(liveAccentColor)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), liveAccentColor)
            }
        })
        container.addView(titleRow)
        container.addView(spacer(4))
        container.addView(TextView(context).apply {
            text = "Connect your Bitget account to enable live trading."
            textSize = 11.5f
            setTextColor(mutedColor)
        })

        container.addView(spacer(12))
        container.addView(sectionHeader("API Credentials"))
        val (apiKeyF, apiKeyRow) = secureField("API Key", "", maskByDefault = false)
        val (secretF, secretRow) = secureField("API Secret", "", maskByDefault = true)
        val (passphraseF, passphraseRow) = secureField("Passphrase (optional)", "", maskByDefault = true)
        apiKeyField = apiKeyF
        secretField = secretF
        passphraseField = passphraseF
        container.addView(apiKeyRow)
        container.addView(secretRow)
        container.addView(passphraseRow)

        container.addView(spacer(12))
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
        mainnetOption = segmentedOption("Mainnet") { isTestnetSelected = false; refreshEnvironmentStyle() }
        testnetOption = segmentedOption("Testnet") { isTestnetSelected = true; refreshEnvironmentStyle() }
        val environmentRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(mainnetOption)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 0) })
            addView(testnetOption)
        }
        refreshEnvironmentStyle()
        container.addView(labeledRow("Environment", environmentRow))

        container.addView(spacer(12))
        container.addView(buildDivider())
        container.addView(spacer(10))

        // Connection status indicator.
        credStatusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
        }
        credStatusLabel = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        credStatusDetail = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        }
        testProgress = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(credStatusDot)
            addView(credStatusLabel)
            addView(testProgress)
        }
        container.addView(statusRow)
        container.addView(credStatusDetail)
        applyCredentialStatus(connected = false)

        container.addView(spacer(10))
        testConnectionButton = TextView(context).apply {
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
            setOnClickListener { testConnection() }
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

        saveConnectButton = Button(context).apply {
            text = "Save & Connect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pillBackground(liveAccentColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { saveAndConnect() }
        }
        container.addView(saveConnectButton)

        removeKeyText = TextView(context).apply {
            text = "Remove saved key"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(bearColor)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
            setOnClickListener { callbacks?.onCredentialsCleared?.invoke() }
        }
        container.addView(spacer(6))
        container.addView(removeKeyText)

        return container
    }

    private fun refreshEnvironmentStyle() {
        mainnetOption.background = segmentedOptionBackground(selected = !isTestnetSelected)
        mainnetOption.setTextColor(if (!isTestnetSelected) Color.WHITE else mutedColor)
        testnetOption.background = segmentedOptionBackground(selected = isTestnetSelected)
        testnetOption.setTextColor(if (isTestnetSelected) Color.WHITE else mutedColor)
    }

    private fun applyCredentialStatus(connected: Boolean, detail: String? = null) {
        credStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (connected) bullColor else mutedColor)
        }
        credStatusLabel.text = if (connected) "Connected" else "Not Connected"
        credStatusLabel.setTextColor(if (connected) bullColor else mutedColor)
        credStatusDetail.text = detail.orEmpty()
        credStatusDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun currentlyEnteredCredentials(): BitgetCredentials = BitgetCredentials(
        apiKey = apiKeyField.text?.toString()?.trim().orEmpty(),
        secretKey = secretField.text?.toString()?.trim().orEmpty(),
        passphrase = passphraseField.text?.toString()?.trim().orEmpty(),
        isTestnet = isTestnetSelected,
    )

    private fun testConnection() {
        val credentials = currentlyEnteredCredentials()
        if (!credentials.isComplete) {
            apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
            secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
            return
        }
        val scope = credentialScope ?: return
        testProgress.visibility = View.VISIBLE
        testConnectionButton.isEnabled = false
        scope.launch {
            val client = BitgetLiveTradingRestClient(credentialsProvider = { credentials })
            try {
                client.fetchAccountBalance()
                applyCredentialStatus(connected = true, detail = "Key is valid and reachable.")
            } catch (e: Exception) {
                applyCredentialStatus(connected = false, detail = e.message ?: "Couldn't reach the exchange")
            } finally {
                testProgress.visibility = View.GONE
                testConnectionButton.isEnabled = true
            }
        }
    }

    private fun saveAndConnect() {
        val credentials = currentlyEnteredCredentials()
        if (!credentials.isComplete) {
            apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
            secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
            return
        }
        callbacks?.onCredentialsSubmitted?.invoke(credentials)
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
