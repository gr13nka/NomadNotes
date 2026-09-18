package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Draws a short acknowledgement message as a badge near the bottom of the surface — the toolbar
 * overlays the top, so the bottom is the only strip a badge can occupy without fighting it.
 *
 * Designed for a grayscale e-ink panel rather than a normal screen: solid black text on a solid
 * white plate with a heavy black border, no antialiasing, no translucency. A soft edge or a tinted
 * fill would dissolve into gray dithering under a partial refresh; a flat block survives it.
 * Stateless apart from reused [Paint]s; drive one instance from the UI thread only, like
 * [SelectionRenderer].
 */
class BadgeRenderer {

    private val textPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        textSize = TEXT_SIZE
        textAlign = Paint.Align.CENTER
    }

    private val fillPaint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = false
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
    }

    private val textBounds = Rect()

    /** Draws [text] as a bottom-centred badge sized to fit it, over a surface [surfaceWidth] by [surfaceHeight]. */
    fun draw(canvas: Canvas, text: String, surfaceWidth: Int, surfaceHeight: Int) {
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val plateWidth = textBounds.width() + PADDING_HORIZONTAL * 2
        val plateHeight = textBounds.height() + PADDING_VERTICAL * 2
        val left = (surfaceWidth - plateWidth) / 2f
        val top = surfaceHeight - BOTTOM_MARGIN - plateHeight
        val right = left + plateWidth
        val bottom = top + plateHeight
        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)
        // Baseline centred in the plate: textBounds is measured from the baseline, so its own height
        // and descent recover the offset that lands the glyphs' visual centre in the middle.
        val baseline = top + plateHeight / 2f - textBounds.exactCenterY()
        canvas.drawText(text, surfaceWidth / 2f, baseline, textPaint)
    }

    companion object {
        /** Large enough to read at a glance and to survive a partial e-ink refresh. */
        private const val TEXT_SIZE = 48f

        /** Heavy enough to stay a solid line rather than dithering away on a partial refresh. */
        private const val BORDER_WIDTH = 4f

        private const val PADDING_HORIZONTAL = 32f
        private const val PADDING_VERTICAL = 20f

        /** Gap between the badge and the bottom edge of the surface. */
        private const val BOTTOM_MARGIN = 48f
    }
}
