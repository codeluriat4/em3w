package org.example.test

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
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
import org.example.test.bitget.Kline
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingConnectionState
import org.example.test.bitget.PaperTradingResult
import org.example.test.bitget.PipelineState
import org.example.test.bitget.PositionSide
import org.example.test.bitget.SocketState
import org.example.test.bitget.Timeframe
import org.example.test.chart.CandlestickChartView
import org.example.test.chart.ChartLayoutMetrics
import org.example.test.chart.DepthHeatmapView
import org.example.test.chart.DrawingTool
import org.example.test.ui.DrawingContextToolbar
import org.example.test.ui.DrawingToolsPanel
import org.example.test.ui.LiveTradePanel
import org.example.test.ui.NeumorphicInsetFrameDrawable
import org.example.test.ui.NeumorphicPillDrawable
import org.example.test.ui.PaperTradePanel
import org.example.test.ui.QuickTradePanel
import org.example.test.ui.RoundedIconButton
import org.example.test.ui.ScrollRevealContainer
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
    private lateinit var chartSectionContainer: ScrollRevealContainer
    private lateinit var chartAndQuickTradeContainer: LinearLayout
    private lateinit var chartCanvas: FrameLayout
    private lateinit var quickTradePanel: QuickTradePanel
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

    private val drawingToolsPanel by lazy { DrawingToolsPanel(this) }
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE
    private var isOrderButtonsVisible: Boolean = false

    private var latestPipelineState = PipelineState.IDLE
    private var latestSocketState = SocketState.IDLE
    private var connectivityBannerDismissed = false

    // Quick-trade drawer: revealed by an upward drag, hidden by a downward drag, made
    // anywhere ScrollRevealContainer reports as eligible (i.e. outside the chart's plot
    // area - so this covers the price axis, time axis, timeframe row, and toolbar icons,
    // while leaving the chart's own pan/zoom gestures untouched). The drawer follows the
    // finger live as it drags (quickTradeProgress), then settles fully open or fully
    // closed on release.
    private var isQuickTradeExpanded = false
    private var quickTradeProgress = 0f // 0 = fully collapsed, 1 = fully expanded
    private var quickTradeDragBaseProgress = 0f
    private var quickTradeSettleAnimator: ValueAnimator? = null
    private val quickTradeMaxDragPx by lazy { dp(220) }
    private val quickTradeExpandedChartWeight = 0.7f
    private val quickTradeExpandedPanelWeight = 0.3f
    private val quickTradeCollapsedChartWeight = 1f
    private val quickTradeCollapsedPanelWeight = 0f

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
        chartSectionContainer = findViewById(R.id.chartSectionContainer)
        chartAndQuickTradeContainer = findViewById(R.id.chartAndQuickTradeContainer)
        chartCanvas = findViewById(R.id.chartCanvas)
        quickTradePanel = findViewById(R.id.quickTradePanel)
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
        setupQuickTradePanel()
        setupQuickTradeScrollGesture()

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
                    }
                }

                launch {
                    depthPipeline.liquidityShelves.collect { shelves ->
                        depthHeatmap.submitLiquidityShelves(shelves)
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
                        quickTradePanel.render(renderState.balance)
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

    /**
     * Wires the quick-trade drawer's Long/Short buttons to paper trading -
     * the same account the chart's own Long/Short quick-action buttons use.
     * Order type is currently UI-only for LIMIT since neither trading
     * repository supports resting orders yet; the user is told so rather
     * than having the tap silently do nothing.
     */
    private fun setupQuickTradePanel() {
        quickTradePanel.bind(
            QuickTradePanel.Callbacks(
                onOpenPosition = { side, sizeUsdt, leverage, orderType, limitPrice ->
                    if (orderType == QuickTradePanel.OrderType.LIMIT) {
                        Toast.makeText(this, "Limit orders aren't supported yet - use Market", Toast.LENGTH_LONG).show()
                        return@Callbacks
                    }
                    lifecycleScope.launch {
                        val result = paperTradingRepository.openPosition(side, sizeUsdt, leverage)
                        if (result is PaperTradingResult.Failure) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "${side.name.lowercase().replaceFirstChar { it.uppercase() }} order placed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ),
        )
    }

    /**
     * Wires [ScrollRevealContainer]'s drag reporting to the quick-trade drawer.
     * The container reports drags made anywhere except the chart's plot area
     * (see [ScrollRevealContainer] for how that's determined), which lands us
     * the price axis, time axis, timeframe row, and toolbar icons in addition
     * to the header/banner - covering everywhere "outside the chart canvas"
     * without touching the chart's own pan/zoom handling. The drawer's own
     * grab handle additionally reports drags directly via
     * [QuickTradePanel.onHandleDrag], independent of that container-wide
     * detection, so dragging the handle itself is never at the mercy of the
     * broader screen-wide gesture heuristics.
     *
     * Direction: dragging the finger *up* (negative deltaY) reveals the
     * drawer; dragging *down* (positive deltaY) hides it. The drawer tracks
     * the finger 1:1 while dragging (an on-screen, live expand rather than a
     * snap after a hidden threshold) and settles fully open or fully closed
     * once the finger lifts, based on which side of the midpoint it landed.
     */
    private fun setupQuickTradeScrollGesture() {
        chartSectionContainer.excludedInteractiveView = candleChart
        chartSectionContainer.excludedRightInsetPx = ChartLayoutMetrics.priceAxisWidthPx(resources)
        chartSectionContainer.excludedBottomInsetPx = ChartLayoutMetrics.timeAxisHeightPx(resources)
        // Leaves the drawer's grab handle draggable for the reveal gesture while
        // letting drags that start on its body (balance, leverage, size, order
        // type, Long/Short) scroll the drawer instead of resizing it, once the
        // body actually has overflow content to scroll.
        chartSectionContainer.excludedScrollableView = quickTradePanel.scrollableContent
        chartSectionContainer.onVerticalDrag = ::handleQuickTradeDrag
        quickTradePanel.onHandleDrag = ::handleQuickTradeDrag
    }

    /** Shared handler for both the screen-wide reveal gesture and the drawer's own grab-handle drag. */
    private fun handleQuickTradeDrag(phase: ScrollRevealContainer.DragPhase, deltaY: Float) {
        when (phase) {
            ScrollRevealContainer.DragPhase.START -> {
                quickTradeSettleAnimator?.cancel()
                quickTradeDragBaseProgress = quickTradeProgress
            }
            ScrollRevealContainer.DragPhase.MOVE -> {
                val progress = (quickTradeDragBaseProgress - deltaY / quickTradeMaxDragPx).coerceIn(0f, 1f)
                applyQuickTradeProgress(progress)
            }
            ScrollRevealContainer.DragPhase.END, ScrollRevealContainer.DragPhase.CANCEL -> {
                settleQuickTrade()
            }
        }
    }

    /** Applies a 0..1 reveal progress directly to the chart/drawer weights - the live, finger-following part of the gesture. */
    private fun applyQuickTradeProgress(progress: Float) {
        quickTradeProgress = progress
        val chartParams = chartCanvas.layoutParams as LinearLayout.LayoutParams
        val panelParams = quickTradePanel.layoutParams as LinearLayout.LayoutParams
        chartParams.weight = quickTradeCollapsedChartWeight + (quickTradeExpandedChartWeight - quickTradeCollapsedChartWeight) * progress
        panelParams.weight = quickTradeCollapsedPanelWeight + (quickTradeExpandedPanelWeight - quickTradeCollapsedPanelWeight) * progress
        chartCanvas.layoutParams = chartParams
        quickTradePanel.layoutParams = panelParams
        quickTradePanel.visibility = if (progress > 0f) View.VISIBLE else View.GONE
        chartAndQuickTradeContainer.requestLayout()
    }

    /** Called on finger-up: snaps to fully expanded (0.7/0.3 weights) or fully collapsed, whichever the drag ended closer to. */
    private fun settleQuickTrade() {
        val target = if (quickTradeProgress >= 0.5f) 1f else 0f
        isQuickTradeExpanded = target == 1f
        val start = quickTradeProgress
        quickTradeSettleAnimator?.cancel()
        quickTradeSettleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyQuickTradeProgress(it.animatedValue as Float) }
            start()
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
