package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.app.editor.SelectionBounds
import com.nomadnotes.core.geometry.Vec2

/**
 * Draws the lasso selection decorations — the dashed outline of the lasso path and the dashed
 * bounding box — onto a [Canvas]. The editor composites these over a copy of the page before
 * presenting, so they are an overlay only and never touch the page's own layer bitmaps.
 *
 * One dashed paint serves both the polygon and the box, so the two read as the same "this is
 * selected" mark. Stateless apart from a reused [Paint]/[Path]; drive one instance from a single
 * (UI) thread.
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

    private companion object {
        const val OUTLINE_WIDTH = 2f
        const val DASH_ON = 12f
        const val DASH_OFF = 8f
    }
}
