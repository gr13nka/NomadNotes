package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.core.LinkRegion
import com.nomadnotes.core.PageLink

/**
 * Draws the tap-to-jump affordance for each [PageLink] onto a [Canvas] — a thin gray rounded box
 * over the link's region with a small filled triangle tucked into its top-right corner. The
 * [PageRenderer] paints these over the page's ink at composite time, so a reader can see which
 * circled handwriting became a button. The tone is a deliberately faint gray, not the ink's black:
 * the mark has to stay legible under the handwriting it annotates without competing with it on the
 * grayscale panel.
 *
 * Overlay only. It draws into the composite bitmap, which the renderer rebuilds from the layer
 * caches on every recomposite, so the affordance is re-applied each frame and never bakes into a
 * layer's own ink. One reused [Paint] per role and one reused [Path]; drive one instance from a
 * single (UI) thread.
 */
class LinkRenderer {

    private val boxPaint = Paint().apply {
        isAntiAlias = true
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH
    }
    private val glyphPaint = Paint().apply {
        isAntiAlias = true
        color = Color.GRAY
        style = Paint.Style.FILL
    }
    private val glyph = Path()

    /** Draws the affordance for every link in [links]; the regions are independent, so order is immaterial. */
    fun draw(canvas: Canvas, links: List<PageLink>) {
        for (link in links) drawAffordance(canvas, link.region)
    }

    private fun drawAffordance(canvas: Canvas, region: LinkRegion) {
        canvas.drawRoundRect(
            region.left, region.top, region.right, region.bottom,
            CORNER_RADIUS, CORNER_RADIUS, boxPaint,
        )
        // A right triangle hugging the inner top-right corner — the "this is a button" cue, drawn as
        // a path rather than a glyph char so it needs no font and stays crisp on e-ink.
        val cornerX = region.right - GLYPH_INSET
        val cornerY = region.top + GLYPH_INSET
        glyph.reset()
        glyph.moveTo(cornerX, cornerY)
        glyph.lineTo(cornerX - GLYPH_SIZE, cornerY)
        glyph.lineTo(cornerX, cornerY + GLYPH_SIZE)
        glyph.close()
        canvas.drawPath(glyph, glyphPaint)
    }

    private companion object {
        const val OUTLINE_WIDTH = 2f
        const val CORNER_RADIUS = 8f
        const val GLYPH_SIZE = 12f
        const val GLYPH_INSET = 4f
    }
}
