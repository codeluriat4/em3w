package org.example.test.chart

import android.graphics.Color

/**
 * 7-stop color ramp (purple → cyan → green → yellow) used to encode a liquidity
 * intensity value (0f..1f) as a color. Shared by the depth heatmap overlay and the
 * Smart Money Dashboard's zone/shelf intensity indicators so "how strong is this
 * level" always reads the same way across the UI.
 */
object LiquidityGradient {

    private val stops = intArrayOf(
        Color.parseColor("#4C1D95"), // deep purple — negligible intensity
        Color.parseColor("#7C3AED"),
        Color.parseColor("#3B82F6"),
        Color.parseColor("#22D3C5"), // cyan — the app's own accent, sits mid-ramp
        Color.parseColor("#22C55E"),
        Color.parseColor("#A3E635"),
        Color.parseColor("#FACC15"), // yellow — peak intensity
    )

    /** Maps [intensity] in 0f..1f to a color along the ramp, clamping out-of-range input. */
    fun colorFor(intensity: Float): Int {
        val clamped = intensity.coerceIn(0f, 1f)
        val scaled = clamped * (stops.size - 1)
        val lowIndex = scaled.toInt().coerceIn(0, stops.size - 2)
        val fraction = scaled - lowIndex
        return lerpRgb(stops[lowIndex], stops[lowIndex + 1], fraction)
    }

    private fun lerpRgb(from: Int, to: Int, fraction: Float): Int {
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * fraction).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * fraction).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * fraction).toInt()
        return Color.rgb(r, g, b)
    }
}
