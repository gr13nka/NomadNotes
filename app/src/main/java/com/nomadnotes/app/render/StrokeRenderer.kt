package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.ink.NibProfile
import com.nomadnotes.core.ink.dotRadius
import com.nomadnotes.core.ink.inkCurve
import com.nomadnotes.core.ink.inkOutline

/**
 * Renders a single [Stroke] as ink on a [Canvas] — the one place that decides how a stroke's
 * tool and pressure-sampled points become pixels.
 *
 * It is the single source of truth for that mapping: both the full-page rebuild and the
 * incremental "one new stroke" path in [PageRenderer] draw through here, so the ink a stroke shows
 * the moment it is finished and the ink it keeps after later re-composites are byte-for-byte the
 * same. The touch backend's live INK preview draws through the same [drawInk], with
 * `pendingEnd = true`, so the wet ink under the pen matches the ink [draw] commits once the
 * gesture ends.
 *
 * PEN inks a filled, tapered outline ([inkOutline]); PENCIL and MARKER ink a constant-width cubic
 * curve ([inkCurve]) instead — both shaped by :core's [NibProfile], the one width law shared with
 * the touch preview and (separately) :pen-onyx's hardware nib. Only the gray→color mapping is a
 * named private here, pending tuning against real hardware.
 *
 * Stateless between calls apart from the reused [Paint]/[Path] it mutates each call, so drive one
 * instance from a single thread.
 */
class StrokeRenderer {

    // Fills the tapered PEN outline; a stroked line would double the taper's own edges.
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // Strokes the constant-width PENCIL/MARKER curve; round caps/joins make the cubic segments
    // read as one continuous line rather than a chain of visible seams.
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Inks [stroke] onto [canvas]. A stroke with no points draws nothing. */
    fun draw(canvas: Canvas, stroke: Stroke) {
        drawInk(canvas, stroke.points, stroke.tool, stroke.widthBase, stroke.grayLevel)
    }

    /**
     * Inks [points] as [tool] at [widthBase]/[grayLevel] would draw them — the shared path behind
     * both the committed stroke ([draw]) and the touch backend's live preview, so the two agree
     * pixel for pixel. [pendingEnd] means the pen is still down: there is no far point yet to taper
     * into, so [inkOutline] withholds the trailing half of PEN's taper.
     */
    internal fun drawInk(
        canvas: Canvas,
        points: List<StrokePoint>,
        tool: Tool,
        widthBase: Float,
        grayLevel: Int,
        pendingEnd: Boolean = false,
    ) {
        if (points.isEmpty()) return
        val nib = NibProfile.forTool(tool, widthBase)
        val color = grayLevelToColor(grayLevel)
        val alpha = if (tool == Tool.MARKER) MARKER_ALPHA else 255
        // Color.rgb is always opaque, so .alpha must be set after .color on every path below, not before.

        if (points.size == 1) {
            drawDot(canvas, points[0], nib, color, alpha)
            return
        }

        when (tool) {
            Tool.PEN -> {
                val ring = inkOutline(points, nib, pendingEnd)
                // All of [points] sit at the same position — a stationary pen tap — so inkOutline
                // has no path to taper a body around; ink the dot it would have inked as one point.
                if (ring.isEmpty()) {
                    drawDot(canvas, points[0], nib, color, alpha)
                    return
                }
                path.reset()
                path.fillType = Path.FillType.WINDING
                path.moveTo(ring[0], ring[1])
                for (i in 2 until ring.size step 2) path.lineTo(ring[i], ring[i + 1])
                path.close()
                fillPaint.color = color
                fillPaint.alpha = alpha
                canvas.drawPath(path, fillPaint)
            }
            Tool.PENCIL, Tool.MARKER -> {
                val segments = inkCurve(points)
                // Same degenerate tap as inkOutline above; inkCurve has no positions to fit a curve
                // through.
                if (segments.isEmpty()) {
                    drawDot(canvas, points[0], nib, color, alpha)
                    return
                }
                path.reset()
                path.moveTo(segments[0].startX, segments[0].startY)
                for (segment in segments) {
                    path.cubicTo(segment.c1x, segment.c1y, segment.c2x, segment.c2y, segment.endX, segment.endY)
                }
                strokePaint.color = color
                strokePaint.alpha = alpha
                strokePaint.strokeWidth = nib.maxWidth
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    /** Inks the round dot a one-point (or degenerate, same-position) stroke draws. */
    private fun drawDot(canvas: Canvas, point: StrokePoint, nib: NibProfile, color: Int, alpha: Int) {
        fillPaint.color = color
        fillPaint.alpha = alpha
        canvas.drawCircle(point.x, point.y, dotRadius(point, nib), fillPaint)
    }

    /**
     * The gray→color mapping, private to [drawInk]. The width law it used to hold alongside this
     * (pressure response, marker weight) now lives in :core's [NibProfile], shared by :app and
     * :pen-onyx alike; only this mapping remains duplicated, in :pen-onyx's own copy, since that
     * module cannot depend on :app.
     */
    companion object {
        /** The marker is translucent so overlaps read as highlighter ink, not solid fill. */
        private const val MARKER_ALPHA = 128

        /**
         * grayLevel is ink *darkness* per the model ([Stroke.grayLevel]: 0 = white, 255 = black),
         * so it maps to an opaque gray whose channel value is its complement.
         */
        private fun grayLevelToColor(grayLevel: Int): Int {
            val channel = 255 - grayLevel.coerceIn(0, 255)
            return Color.rgb(channel, channel, channel)
        }
    }
}
