package org.example.test

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.example.test.bitget.BookSide
import org.example.test.bitget.Kline
import org.example.test.bitget.LiquidityShelf
import org.example.test.bitget.LiquidityZone
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingConnectionState
import org.example.test.bitget.PaperTradingResult
import org.example.test.bitget.PipelineState
import org.example.test.bitget.PositionSide
import org.example.test.bitget.SocketState
import org.example.test.bitget.Timeframe
import org.example.test.chart.CandlestickChartView
import org.example.test.chart.DepthHeatmapView
import org.example.test.chart.DrawingTool
import org.example.test.chart.LiquidityGradient
import org.example.test.ui.DrawingContextToolbar
import org.example.test.ui.DrawingToolsPanel
import org.example.test.ui.LiveTradePanel
import org.example.test.ui.NeumorphicInsetFrameDrawable
import org.example.test.ui.NeumorphicPillDrawable
import org.example.test.ui.PaperTradePanel
import org.example.test.ui.RoundedIconButton
import org.example.test.ui.SkeletonLoadingView
import org.example.test.ui.TradingModeDialog
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Held at application scope so the pipeline survives activity recreation — see
    // SyncoraApplication.ensureMarketDataStarted().
    private val app by lazy { application as SyncoraApplication }
    private val pipeline by lazy { app.pipeline }
    private val depthPipeline by lazy { app.depthPipeline }
    private val credentialsStore by lazy { app.credentialsStore }
    private val paperTradingRepository by lazy { app.paperTradingRepository }
    private val liveCredentialsStore by lazy { app.liveCredentialsStore }
    private val liveTradingRepository by lazy { app.liveTradingRepository }

    private lateinit var candleChart: CandlestickChartView
    private lateinit var depthHeatmap: DepthHeatmapView
    private lateinit var timeframeRow: LinearLayout
    private lateinit var symbolText: TextView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var liveDot: View
    private lateinit var liveText: TextView
    private lateinit var symbolSkeleton: SkeletonLoadingView
    private lateinit var priceSkeleton: SkeletonLoadingView
    private lateinit var changeSkeleton: SkeletonLoadingView
    private lateinit var drawingToolsButton: ImageView
    private lateinit var timeframeExpandButton: ImageView
    private lateinit var drawingContextToolbar: DrawingContextToolbar
    private val paperTradePanel by lazy { PaperTradePanel(this) }
    private val liveTradePanel by lazy { LiveTradePanel(this) }
    private lateinit var paragraphButton: RoundedIconButton
    private lateinit var chartLongButton: Button
    private lateinit var chartShortButton: Button
    private lateinit var chartOrderButtonsRow: LinearLayout
    private lateinit var connectivityBanner: LinearLayout
    private lateinit var connectivityBannerText: TextView
    private lateinit var connectivityBannerRetry: TextView
    private lateinit var connectivityBannerDismiss: TextView
    private val timeframeButtons = mutableMapOf<Timeframe, Button>()

    private lateinit var zoneCountValue: TextView
    private lateinit var zoneCountBreakdown: TextView
    private lateinit var strongestZoneCard: LinearLayout
    private lateinit var strongestZoneSideChip: TextView
    private lateinit var strongestZonePrice: TextView
    private lateinit var strongestZoneVolume: TextView
    private lateinit var strongestZoneIntensityTrack: LinearLayout
    private lateinit var strongestZoneIntensityFill: View
    private lateinit var strongestShelfCard: LinearLayout
    private lateinit var strongestShelfSideChip: TextView
    private lateinit var strongestShelfRange: TextView
    private lateinit var strongestShelfVolume: TextView
    private lateinit var strongestShelfIntensityTrack: LinearLayout
    private lateinit var strongestShelfIntensityFill: View
    private lateinit var strongestShelfProximityLabel: TextView
    private lateinit var smartMoneyEmptyState: TextView

    private val drawingToolsPanel by lazy { DrawingToolsPanel(this) }
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE
    private var isOrderButtonsVisible: Boolean = false

    private var latestPipelineState = PipelineState.IDLE
    private var latestSocketState = SocketState.IDLE
    private var connectivityBannerDismissed = false

    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
    private val mutedColor = Color.parseColor("#8A96A3")
    private val inactivePillTextColor = Color.parseColor("#8A96A3")
    private val activePillBgColor = Color.parseColor("#102A2B")
    private val timeframeContainerColor = Color.parseColor("#0A1015")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        candleChart = findViewById(R.id.candleChart)
        depthHeatmap = findViewById(R.id.depthHeatmap)
        timeframeRow = findViewById(R.id.timeframeRow)
        symbolText = findViewById(R.id.symbolText)
        priceText = findViewById(R.id.priceText)
        changeText = findViewById(R.id.changeText)
        liveDot = findViewById(R.id.liveDot)
        liveText = findViewById(R.id.liveText)
        symbolSkeleton = findViewById(R.id.symbolSkeleton)
        priceSkeleton = findViewById(R.id.priceSkeleton)
        changeSkeleton = findViewById(R.id.changeSkeleton)
        drawingToolsButton = findViewById(R.id.drawingToolsButton)
        timeframeExpandButton = findViewById(R.id.timeframeExpandButton)
        drawingContextToolbar = findViewById(R.id.drawingContextToolbar)
        paragraphButton = findViewById(R.id.paragraphButton)
        paragraphButton.setOnClickListener {
            TradingModeDialog(this, paperTradePanel, liveTradePanel).show()
        }
        chartLongButton = findViewById(R.id.chartLongButton)
        chartShortButton = findViewById(R.id.chartShortButton)
        chartOrderButtonsRow = findViewById(R.id.chartOrderButtonsRow)
        connectivityBanner = findViewById(R.id.connectivityBanner)
        connectivityBannerText = findViewById(R.id.connectivityBannerText)
        connectivityBannerRetry = findViewById(R.id.connectivityBannerRetry)
        connectivityBannerDismiss = findViewById(R.id.connectivityBannerDismiss)
        zoneCountValue = findViewById(R.id.zoneCountValue)
        zoneCountBreakdown = findViewById(R.id.zoneCountBreakdown)
        strongestZoneCard = findViewById(R.id.strongestZoneCard)
        strongestZoneSideChip = findViewById(R.id.strongestZoneSideChip)
        strongestZonePrice = findViewById(R.id.strongestZonePrice)
        strongestZoneVolume = findViewById(R.id.strongestZoneVolume)
        strongestZoneIntensityTrack = findViewById(R.id.strongestZoneIntensityTrack)
        strongestZoneIntensityFill = findViewById(R.id.strongestZoneIntensityFill)
        strongestShelfCard = findViewById(R.id.strongestShelfCard)
        strongestShelfSideChip = findViewById(R.id.strongestShelfSideChip)
        strongestShelfRange = findViewById(R.id.strongestShelfRange)
        strongestShelfVolume = findViewById(R.id.strongestShelfVolume)
        strongestShelfIntensityTrack = findViewById(R.id.strongestShelfIntensityTrack)
        strongestShelfIntensityFill = findViewById(R.id.strongestShelfIntensityFill)
        strongestShelfProximityLabel = findViewById(R.id.strongestShelfProximityLabel)
        smartMoneyEmptyState = findViewById(R.id.smartMoneyEmptyState)
        connectivityBannerRetry.setOnClickListener {
            connectivityBannerDismissed = false
            hideConnectivityBanner()
            app.stopMarketData()
            app.ensureMarketDataStarted()
        }
        connectivityBannerDismiss.setOnClickListener {
            connectivityBannerDismissed = true
            hideConnectivityBanner()
        }
        chartLongButton.setOnClickListener {
            paperTradePanel.setSide(PositionSide.LONG)
            val dialog = TradingModeDialog(this, paperTradePanel, liveTradePanel)
            dialog.show()
            dialog.showPaperTradingScreen()
        }
        chartShortButton.setOnClickListener {
            paperTradePanel.setSide(PositionSide.SHORT)
            val dialog = TradingModeDialog(this, paperTradePanel, liveTradePanel)
            dialog.show()
            dialog.showPaperTradingScreen()
        }
        setupPaperTrading()
        setupLiveTrading()

        drawingContextToolbar.bind(
            candleChart,
            DrawingContextToolbar.Callbacks(
                onColorChange = { color -> candleChart.setSelectedLineColor(color) },
                onOpacityChange = { percent -> candleChart.setSelectedLineOpacity(percent) },
                onWidthChange = { widthDp -> candleChart.setSelectedLineWidth(widthDp) },
                onPatternChange = { pattern -> candleChart.setSelectedLinePattern(pattern) },
                onDelete = { candleChart.deleteSelectedDrawing() },
            ),
        )
        candleChart.onSelectedDrawingChanged = { style ->
            if (style != null) {
                drawingContextToolbar.showForStyle(style)
            } else {
                drawingContextToolbar.hide()
            }
        }

        updateDrawingToolsButtonState()
        drawingToolsButton.setOnClickListener {
            drawingToolsPanel.show(drawingToolsButton, activeDrawingTool) { tool ->
                activeDrawingTool = tool
                candleChart.setActiveDrawingTool(tool)
                updateDrawingToolsButtonState()
            }
        }

        drawingToolsButton.setOnLongClickListener {
            candleChart.clearDrawings()
            true
        }
        candleChart.onDrawingPlaced = {
            activeDrawingTool = DrawingTool.NONE
            updateDrawingToolsButtonState()
        }

        updateTimeframeExpandButtonState()
        timeframeExpandButton.setOnClickListener {
            isOrderButtonsVisible = !isOrderButtonsVisible
            updateTimeframeExpandButtonState()
        }

        buildTimeframeButtons()
        renderConnectionState()

        candleChart.onViewportChange = { range -> depthHeatmap.setInteractiveOverride(range) }

        candleChart.onTimeWindowChange = { visible -> depthHeatmap.syncToCandles(visible, pipeline.barDurationMillis.value) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    pipeline.pipelineState.collect { state ->
                        latestPipelineState = state
                        renderConnectionState()
                    }
                }
                launch {
                    pipeline.socketState.collect { state ->
                        latestSocketState = state
                        renderConnectionState()
                    }
                }
                launch {
                    pipeline.currentTimeframe.collect { timeframe ->
                        highlightSelectedTimeframe(timeframe)

                    }
                }

                launch { pipeline.barDurationMillis.collect { candleChart.setBarDurationMillis(it) } }
                launch {
                    pipeline.klines.collect { candles ->
                        candleChart.submitCandles(candles)
                        val visible = candleChart.visibleCandles()
                        renderHeader(candles, visible)

                        depthHeatmap.syncToCandles(visible, pipeline.barDurationMillis.value)
                    }
                }

                launch {
                    depthPipeline.renderTicks.collect { tick ->
                        val delta = tick.delta
                        if (delta != null) {
                            depthHeatmap.submitDepthDelta(delta, tick.snapshot)
                        } else {
                            depthHeatmap.submitDepth(tick.snapshot)
                        }
                    }
                }

                launch {
                    depthPipeline.liquidityZones.collect { zones ->
                        depthHeatmap.submitLiquidityZones(zones)
                        renderZoneCards(zones)
                    }
                }

                launch {
                    depthPipeline.liquidityShelves.collect { shelves ->
                        depthHeatmap.submitLiquidityShelves(shelves)
                        renderShelfCard(shelves)
                    }
                }

                launch { watchConnectivity() }
            }
        }
    }

    private fun setupPaperTrading() {
        paperTradePanel.bind(
            PaperTradePanel.Callbacks(
                onCredentialsSubmitted = { credentials ->
                    credentialsStore.save(credentials)
                    paperTradingRepository.onCredentialsChanged()
                    Toast.makeText(this, "Demo API Key saved", Toast.LENGTH_SHORT).show()
                },
                onCredentialsCleared = {
                    credentialsStore.clear()
                    paperTradingRepository.onCredentialsChanged()
                },
                onOpenPosition = { side, size, leverage ->
                    lifecycleScope.launch {
                        val result = paperTradingRepository.openPosition(side, size, leverage)
                        if (result is PaperTradingResult.Failure) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onClosePosition = { position ->
                    lifecycleScope.launch {
                        val result = paperTradingRepository.closePosition(position)
                        if (result is PaperTradingResult.Failure) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            ),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        paperTradingRepository.connectionState,
                        paperTradingRepository.balance,
                        paperTradingRepository.positions,
                        paperTradingRepository.lastError,
                    ) { state, balance, positions, error ->
                        PaperTradeRenderState(state, balance, positions, error)
                    }.collect { renderState ->
                        paperTradePanel.render(
                            connectionState = renderState.state,
                            balance = renderState.balance,
                            positions = renderState.positions,
                            lastError = renderState.error,
                            credentials = credentialsStore.load(),
                        )
                    }
                }
            }
        }
    }

    private data class PaperTradeRenderState(
        val state: PaperTradingConnectionState,
        val balance: PaperAccountBalance?,
        val positions: List<PaperPosition>,
        val error: String?,
    )

    private fun setupLiveTrading() {
        liveTradePanel.bind(
            LiveTradePanel.Callbacks(
                onCredentialsSubmitted = { credentials ->
                    liveCredentialsStore.save(credentials)
                    liveTradingRepository.onCredentialsChanged()
                    Toast.makeText(this, "Live API Key saved", Toast.LENGTH_SHORT).show()
                },
                onCredentialsCleared = {
                    liveCredentialsStore.clear()
                    liveTradingRepository.onCredentialsChanged()
                },
                onOpenPosition = { side, size, leverage ->
                    lifecycleScope.launch {
                        val result = liveTradingRepository.openPosition(side, size, leverage)
                        if (result is PaperTradingResult.Failure) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onClosePosition = { position ->
                    lifecycleScope.launch {
                        val result = liveTradingRepository.closePosition(position)
                        if (result is PaperTradingResult.Failure) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            ),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        liveTradingRepository.connectionState,
                        liveTradingRepository.balance,
                        liveTradingRepository.positions,
                        liveTradingRepository.lastError,
                    ) { state, balance, positions, error ->
                        PaperTradeRenderState(state, balance, positions, error)
                    }.collect { renderState ->
                        liveTradePanel.render(
                            connectionState = renderState.state,
                            balance = renderState.balance,
                            positions = renderState.positions,
                            lastError = renderState.error,
                            credentials = liveCredentialsStore.load(),
                        )
                    }
                }
            }
        }
    }

    private fun buildTimeframeButtons() {
        for (timeframe in Timeframe.entries) {
            val isSelected = timeframe == pipeline.currentTimeframe.value
            val button = Button(this).apply {
                text = timeframe.label
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isAllCaps = false
                gravity = Gravity.CENTER
                setTextColor(pillTextColor(isSelected))
                NeumorphicPillDrawable.applyTo(this, NeumorphicPillDrawable(resources.displayMetrics.density, selected = isSelected))
                setOnClickListener { pipeline.switchTimeframe(timeframe) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            timeframeRow.addView(button, params)
            timeframeButtons[timeframe] = button
        }
    }

    private fun highlightSelectedTimeframe(selected: Timeframe) {
        for ((timeframe, button) in timeframeButtons) {
            val isSelected = timeframe == selected
            NeumorphicPillDrawable.applyTo(button, NeumorphicPillDrawable(resources.displayMetrics.density, selected = isSelected))
            button.setTextColor(pillTextColor(isSelected))
        }
    }

    private fun pillTextColor(selected: Boolean): Int =
        if (selected) bullColor else inactivePillTextColor

    private fun updateDrawingToolsButtonState() {
        val isActive = activeDrawingTool != DrawingTool.NONE
        NeumorphicInsetFrameDrawable.applyTo(
            drawingToolsButton,
            NeumorphicInsetFrameDrawable(resources.displayMetrics.density, selected = isActive),
        )
        drawingToolsButton.setColorFilter(pillTextColor(isActive))
    }

    private fun updateTimeframeExpandButtonState() {
        NeumorphicInsetFrameDrawable.applyTo(
            timeframeExpandButton,
            NeumorphicInsetFrameDrawable(resources.displayMetrics.density, selected = isOrderButtonsVisible),
        )
        timeframeExpandButton.setColorFilter(pillTextColor(isOrderButtonsVisible))
        chartOrderButtonsRow.visibility = if (isOrderButtonsVisible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val CONNECTIVITY_TIMEOUT_MS = 15_000L
    }

    /**
     * Watches both market-data sockets and, if neither manages to connect within
     * [CONNECTIVITY_TIMEOUT_MS], surfaces a banner distinguishing "still can't reach
     * Bitget" from the ordinary brief "Connecting…" state shown by [renderConnectionState].
     * This matters in regions where ISPs block Bitget's domains (e.g. under Philippines
     * NTC directives) - in that case the socket will just keep retrying forever and the
     * user would otherwise see nothing but a spinner with no explanation.
     *
     * Uses collectLatest so each new state cancels any pending delay from the previous
     * one - the timer only fires if a state has been sustained for the full timeout.
     */
    private suspend fun watchConnectivity() {
        combine(pipeline.socketState, depthPipeline.socketState) { kline, depth -> kline to depth }
            .collectLatest { (klineState, depthState) ->
                val anyConnected = klineState == SocketState.CONNECTED || depthState == SocketState.CONNECTED
                if (anyConnected) {
                    connectivityBannerDismissed = false
                    hideConnectivityBanner()
                    return@collectLatest
                }
                delay(CONNECTIVITY_TIMEOUT_MS)
                if (!connectivityBannerDismissed) {
                    showConnectivityBanner(klineMissing = klineState != SocketState.CONNECTED)
                }
            }
    }

    private fun showConnectivityBanner(klineMissing: Boolean) {
        connectivityBannerText.text = getString(
            if (klineMissing) R.string.connectivity_banner_market_data else R.string.connectivity_banner_order_book,
        )
        connectivityBanner.visibility = View.VISIBLE
    }

    private fun hideConnectivityBanner() {
        connectivityBanner.visibility = View.GONE
    }

    private fun renderConnectionState() {
        val isLive = latestPipelineState == PipelineState.LIVE && latestSocketState == SocketState.CONNECTED
        val isLoading = !isLive

        liveDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isLive) bullColor else mutedColor)
        }
        liveText.text = if (isLive) getString(R.string.live_label) else getString(R.string.connecting_label)
        liveText.setTextColor(if (isLive) bullColor else mutedColor)

        renderHeaderSkeletons(isLoading)
        dimChart(isLoading)
    }

    private fun renderHeaderSkeletons(show: Boolean) {
        val textVisibility = if (show) View.INVISIBLE else View.VISIBLE
        symbolText.visibility = textVisibility
        priceText.visibility = textVisibility
        changeText.visibility = textVisibility

        if (show) {
            symbolSkeleton.show()
            priceSkeleton.show()
            changeSkeleton.show()
        } else {
            symbolSkeleton.hide()
            priceSkeleton.hide()
            changeSkeleton.hide()
        }
    }

    private fun dimChart(dimmed: Boolean) {
        candleChart.alpha = 1f
        candleChart.setSkeletonLoading(dimmed)
        depthHeatmap.alpha = if (dimmed) 0.42f else 1f
    }

    private fun renderHeader(liveCandles: List<Kline>, visibleWindow: List<Kline>) {
        val last = liveCandles.lastOrNull() ?: return
        val first = visibleWindow.firstOrNull() ?: last
        val changePct = if (first.open != 0.0) (last.close - first.open) / first.open * 100.0 else 0.0
        val isUp = changePct >= 0.0
        val color = if (isUp) bullColor else bearColor

        priceText.text = formatPrice(last.close)
        priceText.setTextColor(color)
        changeText.text = String.format(Locale.US, "%s%.2f%%", if (isUp) "+" else "", changePct)
        changeText.setTextColor(color)
    }

    private fun formatPrice(price: Double): String {
        val decimals = when {
            abs(price) >= 1000 -> 1
            abs(price) >= 1 -> 2
            else -> 5
        }
        return String.format(Locale.US, "%,.${decimals}f", price)
    }

    /**
     * Renders the "active liquidity zones" count card and the "strongest zone" card from
     * the latest zone list. The strongest zone is whichever single price level currently has
     * the highest intensity (i.e. is the largest outlier relative to recent typical size) —
     * ties broken by raw volume.
     */
    private fun renderZoneCards(zones: List<LiquidityZone>) {
        if (zones.isEmpty()) {
            zoneCountValue.text = "0"
            zoneCountBreakdown.text = ""
            strongestZoneCard.visibility = View.GONE
            smartMoneyEmptyState.visibility = View.VISIBLE
            return
        }
        smartMoneyEmptyState.visibility = View.GONE
        strongestZoneCard.visibility = View.VISIBLE

        val bidCount = zones.count { it.side == BookSide.BID }
        val askCount = zones.size - bidCount
        zoneCountValue.text = zones.size.toString()
        zoneCountBreakdown.text = String.format(Locale.US, "%d bid · %d ask", bidCount, askCount)

        val strongest = zones.maxWithOrNull(compareBy({ it.intensity }, { it.volume })) ?: return
        val isBid = strongest.side == BookSide.BID
        strongestZoneSideChip.text = getString(if (isBid) R.string.smart_money_side_bid else R.string.smart_money_side_ask)
        applySideChipStyle(strongestZoneSideChip, isBid)
        strongestZonePrice.text = formatPrice(strongest.price)
        val ageSeconds = ((System.currentTimeMillis() - strongest.firstSeenMs) / 1000L).coerceAtLeast(0)
        strongestZoneVolume.text = String.format(Locale.US, "%.3f BTC resting · seen %ds", strongest.volume, ageSeconds)
        setIntensityBar(strongestZoneIntensityTrack, strongestZoneIntensityFill, strongest.intensity)
    }

    /**
     * Renders the "strongest shelf" card. [LiquidityShelfMerger] already returns shelves
     * sorted by [org.example.test.bitget.LiquidityShelf.priorityScore] descending, so the
     * strongest one is simply the first entry.
     */
    private fun renderShelfCard(shelves: List<LiquidityShelf>) {
        val strongest = shelves.firstOrNull()
        if (strongest == null) {
            strongestShelfCard.visibility = View.GONE
            return
        }
        strongestShelfCard.visibility = View.VISIBLE

        val isBid = strongest.side == BookSide.BID
        strongestShelfSideChip.text = getString(if (isBid) R.string.smart_money_side_bid else R.string.smart_money_side_ask)
        applySideChipStyle(strongestShelfSideChip, isBid)
        strongestShelfRange.text = "${formatPrice(strongest.minPrice)} – ${formatPrice(strongest.maxPrice)}"
        strongestShelfVolume.text = String.format(
            Locale.US,
            "%.2f BTC across %d level%s",
            strongest.totalVolume,
            strongest.levelCount,
            if (strongest.levelCount == 1) "" else "s",
        )
        setIntensityBar(strongestShelfIntensityTrack, strongestShelfIntensityFill, strongest.peakIntensity)

        val proximityDescriptor = if (strongest.distanceFraction < 0.001) {
            "At the touch"
        } else {
            String.format(Locale.US, "%.2f%% from mid", strongest.distanceFraction * 100.0)
        }
        strongestShelfProximityLabel.text = String.format(
            Locale.US,
            "%s · priority-ranked #1 of %d shelves",
            proximityDescriptor,
            shelves.size,
        )
    }

    private fun applySideChipStyle(chip: TextView, isBid: Boolean) {
        val color = if (isBid) bullColor else bearColor
        chip.setTextColor(color)
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    /** Sizes an intensity bar's fill via weight redistribution and tints it off [LiquidityGradient]. */
    private fun setIntensityBar(track: LinearLayout, fill: View, intensity: Float) {
        // Keep a small sliver visible even at ~0 intensity so the bar doesn't look broken/empty.
        val fillFraction = intensity.coerceIn(0.03f, 1f)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = fillFraction
        val spacer = track.getChildAt(1)
        (spacer.layoutParams as LinearLayout.LayoutParams).weight = (1f - fillFraction).coerceAtLeast(0f)
        (fill.background.mutate() as GradientDrawable).setColor(LiquidityGradient.colorFor(intensity))
        track.requestLayout()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        // Idempotent: no-op if already running, starts fresh if the app was fully
        // backgrounded and stopped in between.
        app.ensureMarketDataStarted()
        paperTradingRepository.start()
        liveTradingRepository.start()
    }

    override fun onStop() {
        super.onStop()
        app.stopMarketData()
        paperTradingRepository.stop()
        liveTradingRepository.stop()
    }
}
