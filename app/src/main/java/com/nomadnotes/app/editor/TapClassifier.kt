package com.nomadnotes.app.editor

import com.nomadnotes.core.StrokePoint

/**
 * Tells a deliberate tap apart from a drawn stroke, so a link-region tap can navigate instead of
 * inking. A gesture is a tap when every point stays within a spread threshold of the first point
 * and the whole gesture fits inside a duration threshold; both thresholds are inclusive and
 * device-tuned. [isTap] uses the tight, general-purpose pair ([MAX_SPREAD_PX]/[MAX_DURATION_MS]);
 * [isLinkTap] is a separate, more generous pair for a gesture already known to have started on a
 * link (see its own doc for why).
 */
object TapClassifier {

    /** Farthest any point may sit from the first, in page pixels, for the gesture to stay a tap. */
    const val MAX_SPREAD_PX = 6f

    /** Longest a gesture may last, in milliseconds, and still count as a tap. */
    const val MAX_DURATION_MS = 300L

    /**
     * Spread tolerance for [isLinkTap]: a stylus tap aimed at a small on-page link routinely drifts
     * more than [MAX_SPREAD_PX] allows for a tap classified against blank ink — that drift is real
     * pen motion, not a drawn stroke, because the gesture is already known to have started inside
     * the link's own region or sticker card.
     */
    const val MAX_LINK_SPREAD_PX = 24f

    /** Duration tolerance for [isLinkTap], mirroring [MAX_LINK_SPREAD_PX]'s reasoning. */
    const val MAX_LINK_DURATION_MS = 500L

    fun isTap(points: List<StrokePoint>): Boolean = isTap(points, MAX_SPREAD_PX, MAX_DURATION_MS)

    /**
     * A generous counterpart to [isTap], for a gesture the caller already knows started on a link
     * (see `EditorActivity.linkAt`). Real stylus taps aimed at a small link drift and linger well
     * past [MAX_SPREAD_PX]/[MAX_DURATION_MS], which would otherwise turn most link taps into tiny
     * ink dots instead of navigating. Kept as its own named threshold rather than loosening [isTap]
     * itself, so a tap on blank ink elsewhere on the page still needs to stay tight to avoid inking.
     */
    fun isLinkTap(points: List<StrokePoint>): Boolean = isTap(points, MAX_LINK_SPREAD_PX, MAX_LINK_DURATION_MS)

    private fun isTap(points: List<StrokePoint>, maxSpreadPx: Float, maxDurationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val first = points.first()
        val withinSpread = points.all { p ->
            val dx = p.x - first.x
            val dy = p.y - first.y
            dx * dx + dy * dy <= maxSpreadPx * maxSpreadPx
        }
        if (!withinSpread) return false
        // timestampDelta is measured from the stroke's first point (see StrokePoint), so the last
        // point's delta is the whole gesture's elapsed time.
        return points.last().timestampDelta <= maxDurationMs
    }
}
