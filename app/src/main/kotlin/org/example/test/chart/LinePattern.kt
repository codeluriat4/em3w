package org.example.test.chart

/**
 * The stroke pattern applied to a placed drawing, editable live from the Drawing Context
 * Toolbar while a trend line is selected.
 */
enum class LinePattern(val label: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted");

    companion object {
        val selectable: List<LinePattern> = listOf(SOLID, DASHED, DOTTED)
    }
}
