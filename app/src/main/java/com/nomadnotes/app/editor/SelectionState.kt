package com.nomadnotes.app.editor

import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId

/**
 * An immutable lasso selection: which strokes are selected, the layer they live on, and their
 * bounding box in page pixels.
 *
 * This is plain data held as editor UI state, never mutated in place: a move or an erase produces a
 * new [SelectionState] (or none) rather than editing this one. It records only the [StrokeId]s, not
 * the strokes themselves, so it cannot go stale against an edited page — the current strokes are
 * always looked up from the live page by id.
 *
 * Phase-2 anchor: a lasso'd region is what a Supernote-style link is drawn around (a tappable button
 * from a region of one page to another page/notebook). Keeping the selection as "these stroke ids on
 * this layer, bounded by this box" — free of any transient gesture or rendering state — is what lets
 * a later phase turn a selection into a persisted link target without rework.
 */
data class SelectionState(
    val layerId: LayerId,
    val strokeIds: List<StrokeId>,
    val bounds: SelectionBounds,
) {
    companion object {
        /**
         * The selection of [strokes] on [layerId], or null when [strokes] is empty or none of them
         * has any point (so there is no box to bound and nothing to select).
         */
        fun of(layerId: LayerId, strokes: List<Stroke>): SelectionState? {
            val bounds = SelectionBounds.of(strokes) ?: return null
            return SelectionState(layerId, strokes.map { it.id }, bounds)
        }
    }
}

/**
 * An axis-aligned bounding box in page pixels, with [left] <= [right] and [top] <= [bottom].
 *
 * Used both to hit-test a pen-down ([contains] — inside means "grab to move", outside means "start a
 * new lasso") and to draw the dashed selection box. [translated] shifts it to follow a committed
 * move without re-deriving it from points, since translating every point by (dx, dy) shifts the box
 * by exactly (dx, dy).
 */
data class SelectionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    fun translated(dx: Float, dy: Float): SelectionBounds =
        SelectionBounds(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        /** The tightest box enclosing every point of [strokes], or null if there are no points. */
        fun of(strokes: List<Stroke>): SelectionBounds? {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            var any = false
            for (stroke in strokes) {
                for (point in stroke.points) {
                    any = true
                    if (point.x < left) left = point.x
                    if (point.x > right) right = point.x
                    if (point.y < top) top = point.y
                    if (point.y > bottom) bottom = point.y
                }
            }
            return if (any) SelectionBounds(left, top, right, bottom) else null
        }
    }
}
