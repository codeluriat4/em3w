package org.example.test

import android.app.Application
import org.example.test.bitget.BitgetCredentialsStore
import org.example.test.bitget.BitgetLiveCredentialsStore
import org.example.test.bitget.DepthPipeline
import org.example.test.bitget.FileKlineCacheStore
import org.example.test.bitget.LiveTradingRepository
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.Timeframe
import org.example.test.bitget.TradingChartPipeline

/**
 * Holds the market-data pipelines at application scope instead of activity scope.
 *
 * Keeping the pipelines here instead of inside MainActivity means they survive
 * configuration changes and brief activity recreation without dropping the live stream.
 * [ensureMarketDataStarted] is idempotent so it's safe to call from onStart() regardless
 * of whether the pipelines are already running.
 */
class SyncoraApplication : Application() {

    val pipeline: TradingChartPipeline by lazy {
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

    val depthPipeline: DepthPipeline by lazy {
        DepthPipeline(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    val credentialsStore: BitgetCredentialsStore by lazy {
        BitgetCredentialsStore(applicationContext)
    }

    val paperTradingRepository: PaperTradingRepository by lazy {
        PaperTradingRepository(credentialsStore = credentialsStore, symbol = "BTCUSDT")
    }

    val liveCredentialsStore: BitgetLiveCredentialsStore by lazy {
        BitgetLiveCredentialsStore(applicationContext)
    }

    val liveTradingRepository: LiveTradingRepository by lazy {
        LiveTradingRepository(credentialsStore = liveCredentialsStore, symbol = "BTCUSDT")
    }

    private var marketDataStarted = false

    /** Idempotent: safe to call repeatedly from MainActivity.onStart(). */
    fun ensureMarketDataStarted() {
        if (marketDataStarted) return
        marketDataStarted = true
        pipeline.start()
        depthPipeline.start()
    }

    /** Call when the chart is no longer visible to anything (MainActivity.onStop()). */
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
    }
}
