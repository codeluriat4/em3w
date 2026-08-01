package org.example.test.ui

import android.graphics.Rect
import android.view.View

/**
 * Screen-space boundary math shared by the Drawing Context Toolbar and its popovers (color
 * picker, width dropdown). Given a [safeArea] rect -- typically the chart's visible content
 * region translated to screen coordinates, i.e. excluding the price axis, time axis, and their
 * active labels -- computes a location that keeps the floating UI fully inside that rect,
 * flipping above/below or left/right of its anchor as needed rather than spilling past an edge.
 */
object PopupPlacement {

    /** Result of a placement computation, in screen coordinates. */
    data class Location(val x: Int, val y: Int)

    /**
     * Places a box of size [width]x[height] near [anchor], preferring just below it (offset by
     * [gapPx]), flipping to just above it if there isn't enough room below within [safeArea].
     * Horizontally, it starts aligned to the anchor's left edge and is clamped so it never
     * crosses [safeArea]'s left/right edges.
     */
    fun below(
        anchor: View,
        width: Int,
        height: Int,
        safeArea: Rect,
        gapPx: Int = 0,
    ): Location {
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorLeft = anchorLoc[0]
        val anchorTop = anchorLoc[1]
        val anchorBottom = anchorTop + anchor.height

        val spaceBelow = safeArea.bottom - anchorBottom
        val spaceAbove = anchorTop - safeArea.top

        val y = if (height + gapPx <= spaceBelow || spaceBelow >= spaceAbove) {
            (anchorBottom + gapPx).coerceAtMost((safeArea.bottom - height).coerceAtLeast(safeArea.top))
        } else {
            (anchorTop - gapPx - height).coerceAtLeast(safeArea.top)
        }

        val minX = safeArea.left
        val maxX = (safeArea.right - width).coerceAtLeast(minX)
        val x = anchorLeft.coerceIn(minX, maxX)

        return Location(x, y)
    }
}
