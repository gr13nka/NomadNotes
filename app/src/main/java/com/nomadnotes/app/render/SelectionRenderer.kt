package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.app.editor.SelectionBounds
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.geometry.Vec2

/**
 * Draws the selection decorations — the dashed outline of the lasso path, the dashed bounding box,
 * and the frame with corner grips around a selected image — onto a [Canvas]. The editor composites
 * these over a copy of the page before presenting, so they are an overlay only and never touch the
 * page's own layer bitmaps.
 *
 * One dashed paint serves the polygon and both boxes, so they read as the same "this is selected"
 * mark. Stateless apart from a reused [Paint]/[Path]; drive one instance from a single (UI) thread.
 */
class SelectionRenderer {

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH
        pathEffect = DashPathEffect(floatArrayOf(DASH_ON, DASH_OFF), 0f)
    }
    private val path = Path()

    private val handlePaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /** Draws [polygon] as a closed dashed outline (the shape the lasso traced). */
    fun drawPolygon(canvas: Canvas, polygon: List<Vec2>) {
        if (polygon.size < 2) return
        path.reset()
        path.moveTo(polygon[0].x, polygon[0].y)
        for (i in 1 until polygon.size) path.lineTo(polygon[i].x, polygon[i].y)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** Draws [bounds] as a dashed rectangle around the selection. */
    fun drawBounds(canvas: Canvas, bounds: SelectionBounds) {
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
    }

    /**
     * Draws the frame around a selected image: the same dashed box, plus a solid square at each
     * corner marking where to grab to resize.
     *
     * The grips are filled squares rather than an outline or a glyph — on a grayscale panel with no
     * antialiasing to spare, a small solid block is the shape that stays unambiguous at any size, and
     * it needs no font (the same reasoning as [LinkRenderer]'s corner triangle).
     */
    fun drawImageFrame(canvas: Canvas, rect: PageRect) {
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
        for ((x, y) in corners(rect)) {
            canvas.drawRect(
                x - HANDLE_HALF, y - HANDLE_HALF, x + HANDLE_HALF, y + HANDLE_HALF, handlePaint,
            )
        }
    }

    companion object {
        private const val OUTLINE_WIDTH = 2f
        private const val DASH_ON = 12f
        private const val DASH_OFF = 8f

        /** Half the side of a corner grip, in page pixels. */
        private const val HANDLE_HALF = 9f

        /**
         * How near a corner a pen-down counts as grabbing that grip rather than the image itself.
         * Comfortably larger than the drawn grip: the pen is precise, but the grip is small and the
         * cost of missing it (moving the image instead of resizing it) is an unwanted edit.
         */
        const val HANDLE_TOUCH_PX = 26f

        /** The four corners of [rect], in a fixed order: top-left, top-right, bottom-right, bottom-left. */
        fun corners(rect: PageRect): List<Pair<Float, Float>> = listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.right to rect.bottom,
            rect.left to rect.bottom,
        )
    }
}
