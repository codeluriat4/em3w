package org.example.test

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import org.example.test.chart.DepthHeatmapView
import org.example.test.chart.DrawingTool
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
    private lateinit var chartSectionContainer: LinearLayout
    private lateinit var headerRow: LinearLayout
    private lateinit var chartCanvas: FrameLayout
    private lateinit var timeframeControlsBar: LinearLayout
    private lateinit var timeframeOverlayAnchor: LinearLayout
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

    // Scroll-to-collapse chart state. naturalChartHeightPx tracks the chart's normal
    // (weight-based) height so we always have an up-to-date, responsive target to
    // animate back to on expand, even after rotation or a multi-window resize.
    private var isChartCollapsed = false
    private var naturalChartHeightPx = 0
    private var chartHeightAnimator: ValueAnimator? = null

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
        chartSectionContainer = findViewById(R.id.chartSectionContainer)
        headerRow = findViewById(R.id.headerRow)
        chartCanvas = findViewById(R.id.chartCanvas)
        timeframeControlsBar = findViewById(R.id.timeframeControlsBar)
        timeframeOverlayAnchor = findViewById(R.id.timeframeOverlayAnchor)
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
        setupChartCollapseGesture()

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

    /**
     * Lets the user shrink the chart by scrolling/dragging down anywhere that isn't part
     * of the chart itself (price axis, time axis, candles) or the timeframe row - i.e. the
     * header and the connectivity banner, plus any empty space in the root container. Those
     * views naturally consume touches for their own clickable children (the paragraph
     * button, banner retry/dismiss, etc.), so a swipe starting on them still works normally.
     *
     * Dragging down past the threshold collapses the chart to 70% of the available height
     * and relocates the timeframe row (with the double-chevron and drawing-tools icon) into
     * a frameless overlay just under the "Perpetual • Live" line. Dragging back up restores
     * everything.
     */
    private fun setupChartCollapseGesture() {
        val triggerDistancePx = dp(56)
        var startY = 0f
        var isTracking = false

        val gestureListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isTracking = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTracking) return@OnTouchListener false
                    val deltaY = event.rawY - startY
                    if (!isChartCollapsed && deltaY > triggerDistancePx) {
                        collapseChart()
                        startY = event.rawY
                    } else if (isChartCollapsed && deltaY < -triggerDistancePx) {
                        expandChart()
                        startY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTracking = false
                    false
                }
                else -> false
            }
        }

        headerRow.setOnTouchListener(gestureListener)
        connectivityBanner.setOnTouchListener(gestureListener)
        chartSectionContainer.setOnTouchListener(gestureListener)

        // Keep the "expanded" target height current so it stays correct across rotation
        // and multi-window resizes, without needing to hardcode a screen fraction for it.
        chartCanvas.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (!isChartCollapsed) {
                val height = bottom - top
                if (height > 0) naturalChartHeightPx = height
            }
        }

        // If the container is resized while collapsed (rotation, foldable, split-screen),
        // re-apply the 70% ratio against the new size immediately so the collapsed chart
        // stays responsive instead of holding on to a stale pixel height.
        chartSectionContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val newHeight = bottom - top
            val oldHeight = oldBottom - oldTop
            val animator = chartHeightAnimator
            if (isChartCollapsed && newHeight != oldHeight && newHeight > 0 && (animator == null || !animator.isRunning)) {
                val params = chartCanvas.layoutParams as LinearLayout.LayoutParams
                params.weight = 0f
                params.height = (newHeight * CHART_COLLAPSE_RATIO).toInt()
                chartCanvas.layoutParams = params
            }
        }
    }

    private fun collapseChart() {
        if (isChartCollapsed) return
        isChartCollapsed = true
        animateChartHeight(collapsed = true)
        moveTimeframeControlsToOverlay()
    }

    private fun expandChart() {
        if (!isChartCollapsed) return
        isChartCollapsed = false
        animateChartHeight(collapsed = false)
        moveTimeframeControlsToFooter()
    }

    /**
     * Animates chartCanvas's height between its natural (weight-filled) size and 70% of
     * the screen's available height. Uses explicit pixel heights (rather than animating the
     * LinearLayout weight directly, which doesn't interpolate smoothly) so the chart resizes
     * every frame without cropping - CandlestickChartView/DepthHeatmapView both recompute
     * their layout in onSizeChanged, so shrinking/growing the container is always safe.
     */
    private fun animateChartHeight(collapsed: Boolean) {
        chartHeightAnimator?.cancel()

        val params = chartCanvas.layoutParams as LinearLayout.LayoutParams
        val startHeight = chartCanvas.height.takeIf { it > 0 } ?: naturalChartHeightPx
        val containerHeight = chartSectionContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val targetHeight = if (collapsed) {
            (containerHeight * CHART_COLLAPSE_RATIO).toInt()
        } else {
            naturalChartHeightPx.takeIf { it > 0 } ?: startHeight
        }

        params.weight = 0f
        params.height = startHeight
        chartCanvas.layoutParams = params

        chartHeightAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = CHART_COLLAPSE_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.height = animator.animatedValue as Int
                chartCanvas.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!collapsed) {
                        // Hand height control back to the weight system so future resizes
                        // (rotation, banners appearing/disappearing) stay fully responsive.
                        params.weight = 1f
                        params.height = 0
                        chartCanvas.layoutParams = params
                    }
                }
            })
            start()
        }
    }

    /** Moves the timeframe row + double-chevron + drawing-tools icon onto the chart, frameless. */
    private fun moveTimeframeControlsToOverlay() {
        (timeframeControlsBar.parent as? ViewGroup)?.removeView(timeframeControlsBar)
        timeframeControlsBar.setPaddingRelative(0, 0, 0, 0)
        timeframeRow.background = null
        timeframeOverlayAnchor.addView(
            timeframeControlsBar,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        timeframeOverlayAnchor.visibility = View.VISIBLE
    }

    /** Restores the timeframe row + icons to their normal footer position and framing. */
    private fun moveTimeframeControlsToFooter() {
        (timeframeControlsBar.parent as? ViewGroup)?.removeView(timeframeControlsBar)
        timeframeOverlayAnchor.visibility = View.GONE
        timeframeRow.background = ContextCompat.getDrawable(this, R.drawable.bg_timeframe_container)
        timeframeControlsBar.setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
        chartSectionContainer.addView(
            timeframeControlsBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private companion object {
        const val CONNECTIVITY_TIMEOUT_MS = 15_000L
        const val CHART_COLLAPSE_RATIO = 0.7f
        const val CHART_COLLAPSE_ANIM_MS = 260L
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
