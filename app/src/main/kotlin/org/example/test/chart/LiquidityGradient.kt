package org.example.test.chart

import android.graphics.Color

object LiquidityGradient {

    private val intensityStops = intArrayOf(
        Color.parseColor("#3B0764"),
        Color.parseColor("#4338CA"),
        Color.parseColor("#1E3A8A"),
        Color.parseColor("#06B6D4"),
        Color.parseColor("#10B981"),
        Color.parseColor("#84CC16"),
        Color.parseColor("#FDE047"),
    )

    fun intensityColorFor(density: Float): Int = rampColor(density, intensityStops)

    fun legendColorFor(t: Float): Int = intensityColorFor(t)

    private fun rampColor(density: Float, stops: IntArray): Int {
        val t = density.coerceIn(0f, 1f)
        val segments = stops.size - 1
        val scaled = t * segments
        val segment = scaled.toInt().coerceIn(0, segments - 1)
        val localT = scaled - segment
        return lerpColor(stops[segment], stops[segment + 1], localT)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * t).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * t).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }
}
