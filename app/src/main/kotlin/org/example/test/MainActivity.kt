package org.example.test

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.example.test.bitget.BookSide
import org.example.test.bitget.DepthPipeline
import org.example.test.bitget.FileKlineCacheStore
import org.example.test.bitget.Kline
import org.example.test.bitget.LiquidityZone
import org.example.test.bitget.PipelineState
import org.example.test.bitget.SocketState
import org.example.test.bitget.Timeframe
import org.example.test.bitget.TradingChartPipeline
import org.example.test.chart.CandlestickChartView
import org.example.test.chart.DepthHeatmapView
import org.example.test.chart.DrawingTool
import org.example.test.ui.AggressionMeterView
import org.example.test.ui.DrawingContextToolbar
import org.example.test.ui.DrawingToolsPanel
import org.example.test.ui.NeumorphicPillDrawable
import org.example.test.ui.SkeletonLoadingView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val pipeline by lazy {
        TradingChartPipeline(
            instId = "BTCUSDT",
            instType = "USDT-FUTURES",
            initialTimeframe = Timeframe.DEFAULT,
            bufferCapacity = 1000,
            
            cacheStore = FileKlineCacheStore(
                applicationContext,
                cacheKey = "BTCUSDT_USDT-FUTURES_${Timeframe.DEFAULT.wsChannel}",
            ),
        )
    }

    private val depthPipeline by lazy {
        DepthPipeline(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

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
    private lateinit var activeZoneCountText: TextView
    private lateinit var strongestZonePriceText: TextView
    private lateinit var strongestZoneDetailText: TextView
    private lateinit var aggressionMeterView: AggressionMeterView
    private lateinit var aggressionBuyText: TextView
    private lateinit var aggressionSellText: TextView
    private lateinit var aggressionSubtext: TextView
    private lateinit var liquidityWallRows: LinearLayout
    private lateinit var liquidityWallsEmptyText: TextView
    private lateinit var drawingToolsButton: ImageView
    private lateinit var drawingContextToolbar: DrawingContextToolbar
    private val timeframeButtons = mutableMapOf<Timeframe, Button>()

    private val drawingToolsPanel by lazy { DrawingToolsPanel(this) }
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE

    private class DualWallRowViews(
        val root: View,
        val buyTime: TextView,
        val buyVolume: TextView,
        val buyPrice: TextView,
        val sellPrice: TextView,
        val sellVolume: TextView,
        val sellTime: TextView,
    )

    private val wallRowViews = mutableListOf<DualWallRowViews>()
    private val wallTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val maxWallRows = 10

    private var activeZoneCount = 0
    private var strongestZone: LiquidityZone? = null
    private var latestPipelineState = PipelineState.IDLE
    private var latestSocketState = SocketState.IDLE

    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val mutedColor = Color.parseColor("#787B86")

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
        activeZoneCountText = findViewById(R.id.activeZoneCountText)
        strongestZonePriceText = findViewById(R.id.strongestZonePriceText)
        strongestZoneDetailText = findViewById(R.id.strongestZoneDetailText)
        aggressionMeterView = findViewById(R.id.aggressionMeterView)
        aggressionBuyText = findViewById(R.id.aggressionBuyText)
        aggressionSellText = findViewById(R.id.aggressionSellText)
        aggressionSubtext = findViewById(R.id.aggressionSubtext)
        liquidityWallRows = findViewById(R.id.liquidityWallRows)
        liquidityWallsEmptyText = findViewById(R.id.liquidityWallsEmptyText)
        drawingToolsButton = findViewById(R.id.drawingToolsButton)
        drawingContextToolbar = findViewById(R.id.drawingContextToolbar)

        // Drawing Context Toolbar: shown the instant any placed drawing is selected, hidden the
        // instant it's deselected/deleted. Every control writes straight through to the chart
        // and re-renders immediately -- no confirmation step.
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

        // Frameless, neumorphic trigger -- same NeumorphicPillDrawable + pillTextColor logic
        // driving the timeframe row, so the two toolbar controls read as one visual language.
        updateDrawingToolsButtonState()
        drawingToolsButton.setOnClickListener {
            drawingToolsPanel.show(drawingToolsButton, activeDrawingTool) { tool ->
                activeDrawingTool = tool
                candleChart.setActiveDrawingTool(tool)
                updateDrawingToolsButtonState()
            }
        }
        // Long-press the drawing icon to quickly clear every drawing off the chart.
        drawingToolsButton.setOnLongClickListener {
            candleChart.clearDrawings()
            true
        }
        candleChart.onDrawingPlaced = {
            activeDrawingTool = DrawingTool.NONE
            updateDrawingToolsButtonState()
        }

        buildTimeframeButtons()
        buildWallRows(liquidityWallRows, wallRowViews)
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
                        activeZoneCount = zones.size
                        strongestZone = zones.maxByOrNull { it.intensity }
                        depthHeatmap.submitLiquidityZones(zones)
                        renderSmartMoneyZones()
                        renderLiquidityMap(zones)
                        renderAggressionMeter(zones)
                    }
                }
                
                launch {
                    depthPipeline.liquidityShelves.collect { shelves ->
                        depthHeatmap.submitLiquidityShelves(shelves)
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
        if (selected) bullColor else Color.parseColor("#B2B5BE")

    // Mirrors highlightSelectedTimeframe()/pillTextColor(): the trigger is "selected" whenever a
    // draw tool is armed, using the identical neumorphic pill drawable and accent/muted color
    // swap as the timeframe row, so hover/active/press feedback feel like one component.
    private fun updateDrawingToolsButtonState() {
        val isActive = activeDrawingTool != DrawingTool.NONE
        NeumorphicPillDrawable.applyTo(
            drawingToolsButton,
            NeumorphicPillDrawable(resources.displayMetrics.density, selected = isActive, haloDp = 6f),
        )
        drawingToolsButton.setColorFilter(pillTextColor(isActive))
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

    private fun renderSmartMoneyZones() {
        activeZoneCountText.text = activeZoneCount.toString()

        val zone = strongestZone
        if (zone == null) {
            strongestZonePriceText.text = getString(R.string.no_data_placeholder)
            strongestZoneDetailText.text = ""
            return
        }
        strongestZonePriceText.text = "${formatPrice(zone.price)} · ${zone.side}"
        strongestZonePriceText.setTextColor(if (zone.side == BookSide.BID) bullColor else bearColor)
        val ageSeconds = (System.currentTimeMillis() - zone.lastUpdateMs).coerceAtLeast(0L) / 1000L
        strongestZoneDetailText.text = String.format(
            Locale.US,
            "Intensity %.2f · updated %ds ago",
            zone.intensity,
            ageSeconds,
        )
    }

    /**
     * Aggression Meter: aggressive buyers (bid-side liquidity) vs. aggressive sellers
     * (ask-side liquidity), fit horizontally into one bar whose two segments always
     * total 100% of the tracked market volume — e.g. Buy volume: 72% | Sell volume: 28%.
     */
    private fun renderAggressionMeter(zones: List<LiquidityZone>) {
        val buyVolume = zones.asSequence().filter { it.side == BookSide.BID }.sumOf { it.volume }
        val sellVolume = zones.asSequence().filter { it.side == BookSide.ASK }.sumOf { it.volume }
        val totalVolume = buyVolume + sellVolume

        if (totalVolume <= 0.0) {
            aggressionBuyText.text = getString(R.string.no_data_placeholder)
            aggressionSellText.text = ""
            aggressionSubtext.text = ""
            aggressionMeterView.setBuyFraction(0.5f)
            return
        }

        val buyPct = (buyVolume / totalVolume * 100.0)
        val sellPct = 100.0 - buyPct

        aggressionBuyText.text = String.format(Locale.US, "Buy volume: %.0f%%", buyPct)
        aggressionSellText.text = String.format(Locale.US, "Sell volume: %.0f%%", sellPct)
        aggressionSubtext.text = String.format(
            Locale.US,
            "%s vs. %s · %s tracked",
            getString(R.string.aggressive_buyers_label),
            getString(R.string.aggressive_sellers_label),
            formatVolumeCompact(totalVolume),
        )

        // Keep both segments visible (min 1%) so the meter always reads as two bars.
        val buyFraction = buyPct.toFloat().coerceIn(1f, 99f) / 100f
        aggressionMeterView.setBuyFraction(buyFraction)
    }

    private fun buildWallRows(container: LinearLayout, target: MutableList<DualWallRowViews>) {
        val inflater = LayoutInflater.from(this)
        repeat(maxWallRows) {
            val row = inflater.inflate(R.layout.item_liquidity_wall_row, container, false)
            val buyTime = row.findViewById<TextView>(R.id.rowBuyTimeText)
            val buyVolume = row.findViewById<TextView>(R.id.rowBuyVolumeText)
            val buyPrice = row.findViewById<TextView>(R.id.rowBuyPriceText)
            val sellPrice = row.findViewById<TextView>(R.id.rowSellPriceText)
            val sellVolume = row.findViewById<TextView>(R.id.rowSellVolumeText)
            val sellTime = row.findViewById<TextView>(R.id.rowSellTimeText)
            row.visibility = View.GONE
            container.addView(row)
            target.add(DualWallRowViews(row, buyTime, buyVolume, buyPrice, sellPrice, sellVolume, sellTime))
        }
    }

    /**
     * Renders the liquidity map: the top 10 buy walls (highest-volume BID zones) and top 10
     * sell walls (highest-volume ASK zones), fit horizontally into a single mirrored table —
     * Time / Volume(USDT) / Price for bids, then Price / Volume(USDT) / Time for asks.
     */
    private fun renderLiquidityMap(zones: List<LiquidityZone>) {
        val topBuyWalls = zones.asSequence()
            .filter { it.side == BookSide.BID }
            .sortedByDescending { it.volume }
            .take(maxWallRows)
            .toList()
        val topSellWalls = zones.asSequence()
            .filter { it.side == BookSide.ASK }
            .sortedByDescending { it.volume }
            .take(maxWallRows)
            .toList()

        bindWallRows(topBuyWalls, topSellWalls, wallRowViews, liquidityWallsEmptyText)
    }

    private fun bindWallRows(
        buyWalls: List<LiquidityZone>,
        sellWalls: List<LiquidityZone>,
        rowViews: List<DualWallRowViews>,
        emptyText: TextView,
    ) {
        emptyText.visibility = if (buyWalls.isEmpty() && sellWalls.isEmpty()) View.VISIBLE else View.GONE
        for (i in rowViews.indices) {
            val row = rowViews[i]
            val buy = buyWalls.getOrNull(i)
            val sell = sellWalls.getOrNull(i)
            if (buy == null && sell == null) {
                row.root.visibility = View.GONE
                continue
            }
            row.root.visibility = View.VISIBLE

            if (buy != null) {
                row.buyTime.text = wallTimeFormat.format(Date(buy.lastUpdateMs))
                row.buyVolume.text = formatUsdCompact(buy.price * buy.volume)
                row.buyPrice.text = formatPrice(buy.price)
            } else {
                row.buyTime.text = ""
                row.buyVolume.text = ""
                row.buyPrice.text = getString(R.string.no_data_placeholder)
            }

            if (sell != null) {
                row.sellPrice.text = formatPrice(sell.price)
                row.sellVolume.text = formatUsdCompact(sell.price * sell.volume)
                row.sellTime.text = wallTimeFormat.format(Date(sell.lastUpdateMs))
            } else {
                row.sellPrice.text = getString(R.string.no_data_placeholder)
                row.sellVolume.text = ""
                row.sellTime.text = ""
            }
        }
    }

    private fun formatUsdCompact(value: Double): String {
        val abs = abs(value)
        return when {
            abs >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", value / 1_000_000_000)
            abs >= 1_000_000 -> String.format(Locale.US, "$%.2fM", value / 1_000_000)
            abs >= 1_000 -> String.format(Locale.US, "$%.1fK", value / 1_000)
            else -> String.format(Locale.US, "$%,.0f", value)
        }
    }

    private fun formatVolumeCompact(value: Double): String {
        val abs = abs(value)
        return when {
            abs >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
            abs >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
            else -> String.format(Locale.US, "%.1f", value)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        pipeline.start()
        depthPipeline.start()
    }

    override fun onStop() {
        super.onStop()
        pipeline.stop()
        depthPipeline.stop()
    }
}
