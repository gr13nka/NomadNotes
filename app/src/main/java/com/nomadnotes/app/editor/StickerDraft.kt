package com.nomadnotes.app.editor

import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool

/**
 * Turns a pen gesture's live samples into [Stroke]s in [LinkSticker]'s fixed sticker-space
 * coordinate system — mirroring how [com.nomadnotes.app.input.GestureCollector] turns a page
 * gesture into page-pixel points, but plain Kotlin with no Android types, so the conversion is
 * unit-testable and the Compose sticker panel (`ui/editor/StickerPanel.kt`) is the only place that
 * touches an actual pointer event.
 *
 * The caller maps its drawing box's own pixels into sticker space before calling in; this class
 * only accumulates already-mapped points and turns each finished gesture into a [Stroke], stamped
 * with the [tool]/[widthBase]/[grayLevel] the panel opened with — there is no separate "sticker
 * pen" the way the main canvas offers a pen/pencil/marker choice.
 *
 * @param initialStrokes seeds [strokes], so reopening the panel over a link's existing sticker
 *   (the edit flow) starts from what is already drawn rather than a blank card.
 */
class StickerDraft(
    val tool: Tool,
    val widthBase: Float,
    val grayLevel: Int,
    initialStrokes: List<Stroke> = emptyList(),
) {
    private val completed = initialStrokes.toMutableList()
    private var current: MutableList<StrokePoint>? = null
    private var strokeStartMs = 0L

    /** Finished strokes, oldest first; empty means nothing has been drawn, or it was [clear]ed. */
    val strokes: List<Stroke> get() = completed

    /**
     * The in-progress stroke's points so far, for a live preview while the pen is still down.
     * Empty between gestures: before the first [beginStroke], and again once [endStroke] folds
     * them into [strokes].
     */
    val liveStroke: List<StrokePoint> get() = current ?: emptyList()

    /** Starts a new stroke at sticker-space ([x], [y]); [t] is any clock consistent within one gesture. */
    fun beginStroke(x: Float, y: Float, pressure: Float, t: Long) {
        strokeStartMs = t
        current = mutableListOf(clamped(x, y, pressure, 0L))
    }

    /** Appends a sample to the stroke [beginStroke] started. Ignored if no stroke is in progress. */
    fun addPoint(x: Float, y: Float, pressure: Float, t: Long) {
        current?.add(clamped(x, y, pressure, t - strokeStartMs))
    }

    /** Folds the in-progress stroke into [strokes]. A no-op if [beginStroke] was never called. */
    fun endStroke() {
        val points = current ?: return
        current = null
        completed.add(Stroke(StrokeId.random(), tool, widthBase, grayLevel, points))
    }

    /**
     * Folds an already-finished gesture straight into [strokes], already-mapped into sticker-space
     * points — the counterpart to [beginStroke]/[addPoint]/[endStroke]'s streaming trio for a
     * backend that reports a whole gesture at once instead of live samples (native raw-drawing
     * capture of the drawing box; see `EditorActivity`'s sticker capture-region handling). A no-op
     * for an empty gesture (e.g. a cancelled touch). [strokeWidth] lets the caller express the pen's
     * width in sticker space when its points were scaled into it, so the stroke keeps the width the
     * wet ink showed.
     */
    fun addStroke(points: List<StrokePoint>, strokeWidth: Float = widthBase) {
        if (points.isEmpty()) return
        val clampedPoints = points.map { clamped(it.x, it.y, it.pressure, it.timestampDelta) }
        completed.add(Stroke(StrokeId.random(), tool, strokeWidth, grayLevel, clampedPoints))
    }

    /** Drops every stroke, finished or in progress — the panel's `[Clear]`. */
    fun clear() {
        completed.clear()
        current = null
    }

    /** The accumulated ink as a [LinkSticker], or null when [strokes] is empty (nothing to save). */
    fun toSticker(): LinkSticker? = if (completed.isEmpty()) null else LinkSticker(completed.toList())

    // Keeps every point inside the fixed sticker-space bounds even if the panel's box-to-sticker
    // mapping overshoots at an edge (a drag that continues past the drawing box), so a stroke can
    // never end up outside the space LinkSticker promises it is drawn in.
    private fun clamped(x: Float, y: Float, pressure: Float, delta: Long) = StrokePoint(
        x = x.coerceIn(0f, LinkSticker.WIDTH),
        y = y.coerceIn(0f, LinkSticker.HEIGHT),
        pressure = pressure,
        timestampDelta = delta,
    )
}
