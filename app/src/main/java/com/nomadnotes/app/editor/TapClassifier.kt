package com.nomadnotes.app.editor

import com.nomadnotes.core.StrokePoint

/**
 * Tells a deliberate tap apart from a drawn stroke, so a link-region tap can navigate instead of
 * inking. A gesture is a tap when every point stays within [MAX_SPREAD_PX] of the first point and
 * the whole gesture fits inside [MAX_DURATION_MS]; both thresholds are inclusive and device-tuned.
 */
object TapClassifier {

    /** Farthest any point may sit from the first, in page pixels, for the gesture to stay a tap. */
    const val MAX_SPREAD_PX = 6f

    /** Longest a gesture may last, in milliseconds, and still count as a tap. */
    const val MAX_DURATION_MS = 300L

    fun isTap(points: List<StrokePoint>): Boolean {
        if (points.isEmpty()) return false
        val first = points.first()
        val withinSpread = points.all { p ->
            val dx = p.x - first.x
            val dy = p.y - first.y
            dx * dx + dy * dy <= MAX_SPREAD_PX * MAX_SPREAD_PX
        }
        if (!withinSpread) return false
        // timestampDelta is measured from the stroke's first point (see StrokePoint), so the last
        // point's delta is the whole gesture's elapsed time.
        return points.last().timestampDelta <= MAX_DURATION_MS
    }
}
