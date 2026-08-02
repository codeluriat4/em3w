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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.example.test.bitget.DepthPipeline
import org.example.test.bitget.FileKlineCacheStore
import org.example.test.bitget.Kline
import org.example.test.bitget.PipelineState
import org.example.test.bitget.SocketState
import org.example.test.bitget.Timeframe
import org.example.test.bitget.TradingChartPipeline
import org.example.test.chart.CandlestickChartView
import org.example.test.chart.DepthHeatmapView
import org.example.test.chart.DrawingTool
import org.example.test.ui.DrawingContextToolbar
import org.example.test.ui.DrawingToolsPanel
import org.example.test.ui.NeumorphicPillDrawable
import org.example.test.ui.SkeletonLoadingView
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
    private lateinit var drawingToolsButton: ImageView
    private lateinit var drawingContextToolbar: DrawingContextToolbar
    private val timeframeButtons = mutableMapOf<Timeframe, Button>()

    private val drawingToolsPanel by lazy { DrawingToolsPanel(this) }
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE

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
        drawingToolsButton = findViewById(R.id.drawingToolsButton)
        drawingContextToolbar = findViewById(R.id.drawingContextToolbar)

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
