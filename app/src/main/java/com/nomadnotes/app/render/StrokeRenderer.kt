package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.Tool

/**
 * Renders a single [Stroke] as ink on a [Canvas] — the one place that decides how a stroke's
 * tool and pressure-sampled points become pixels.
 *
 * It is the single source of truth for that mapping: both the full-page rebuild and the
 * incremental "one new stroke" path in [PageRenderer] draw through here, so the ink a stroke shows
 * the moment it is finished and the ink it keeps after later re-composites are byte-for-byte the
 * same. The tool constants (pressure response, marker weight and opacity) are named privates
 * pending tuning against real hardware.
 *
 * Stateless between calls apart from a reused [Paint]/[Path] it mutates each call, so drive one
 * instance from a single thread.
 */
class StrokeRenderer {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Inks [stroke] onto [canvas]. A stroke with no points draws nothing. */
    fun draw(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return

        // Color.rgb resets alpha to opaque each call; the marker branch then makes itself translucent.
        paint.color = grayLevelToColor(stroke.grayLevel)
        when (stroke.tool) {
            Tool.PEN -> drawPressureModulated(canvas, stroke)
            Tool.PENCIL -> drawConstantWidth(canvas, stroke, stroke.widthBase)
            Tool.MARKER -> {
                paint.alpha = MARKER_ALPHA
                drawConstantWidth(canvas, stroke, stroke.widthBase * MARKER_WIDTH_MULTIPLIER)
            }
        }
    }

    /**
     * Draws the stroke a segment at a time, each segment's width set from the average pressure of
     * its two endpoints, so the nib swells and tapers along the path. Round caps make the abutting
     * segments read as one continuous, opaque line.
     */
    private fun drawPressureModulated(canvas: Canvas, stroke: Stroke) {
        val points = stroke.points
        if (points.size == 1) {
            paint.strokeWidth = nibWidth(stroke.widthBase, normalizedPressure(points[0].pressure))
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val averagePressure = (normalizedPressure(a.pressure) + normalizedPressure(b.pressure)) / 2f
            paint.strokeWidth = nibWidth(stroke.widthBase, averagePressure)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    /** Draws the whole stroke as one polyline at a fixed [width]; pressure does not vary the nib. */
    private fun drawConstantWidth(canvas: Canvas, stroke: Stroke, width: Float) {
        paint.strokeWidth = width
        val points = stroke.points
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        canvas.drawPath(path, paint)
    }

    private fun nibWidth(widthBase: Float, pressure: Float): Float =
        widthBase * (MIN_WIDTH_FACTOR + PRESSURE_WIDTH_FACTOR * pressure)

    /**
     * By the PenBackend contract a stroke's points arrive with pressure already normalized to
     * 0..1; this clamp is defensive, so a backend that breaks that contract cannot inflate the nib
     * width beyond [widthBase].
     */
    private fun normalizedPressure(pressure: Float): Float = pressure.coerceIn(0f, 1f)

    /**
     * grayLevel is ink *darkness* per the model ([Stroke.grayLevel]: 0 = white, 255 = black), so it
     * maps to a gray whose channel value is its complement.
     */
    private fun grayLevelToColor(grayLevel: Int): Int {
        val channel = 255 - grayLevel.coerceIn(0, 255)
        return Color.rgb(channel, channel, channel)
    }

    private companion object {
        /** Nib width at zero pressure, as a fraction of the stroke's base width. */
        const val MIN_WIDTH_FACTOR = 0.35f

        /** Extra nib width added at full pressure, as a fraction of the stroke's base width. */
        const val PRESSURE_WIDTH_FACTOR = 0.65f

        /** The marker lays down a broad nib: this multiple of the stroke's base width. */
        const val MARKER_WIDTH_MULTIPLIER = 2.5f

        /** The marker is translucent so overlaps read as highlighter ink, not solid fill. */
        const val MARKER_ALPHA = 128
    }
}
