package com.nomadnotes.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.PageLink
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.links.StickerPlacement

/**
 * Draws the tap-to-jump affordance for each [PageLink] onto a [Canvas] — a thin gray rounded box
 * over the link's region with a small filled triangle tucked into its top-right corner, plus, for a
 * link that carries a [LinkSticker], the sticker's own card ([drawStickerCard]). The [PageRenderer]
 * paints these over the page's ink at composite time, so a reader can see which circled handwriting
 * became a button. The box's tone is a deliberately faint gray, not the ink's black: the mark has
 * to stay legible under the handwriting it annotates without competing with it on the grayscale
 * panel; the card is white-on-black so the handwritten sticker inside it reads like ink on paper.
 *
 * Overlay only. It draws into the composite bitmap, which the renderer rebuilds from the layer
 * caches on every recomposite, so the affordance is re-applied each frame and never bakes into a
 * layer's own ink. One reused [Paint] per role and one reused [Path]/[StickerRenderer]; drive one
 * instance from a single (UI) thread.
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

    private val cardFillPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = CARD_BORDER_WIDTH
    }
    private val leaderPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = LEADER_WIDTH
    }
    private val stickerRenderer = StickerRenderer()
    private val cardRect = RectF()

    /**
     * Draws the affordance for every link in [links]; the regions are independent, so order is
     * immaterial. [pageWidth]/[pageHeight] are the page's device-pixel size (see [Page]'s
     * coordinate-space note), needed to place a sticker's card via [StickerPlacement.stickerRect].
     */
    fun draw(canvas: Canvas, links: List<PageLink>, pageWidth: Float, pageHeight: Float) {
        for (link in links) {
            drawAffordance(canvas, link.region)
            link.sticker?.let { sticker -> drawStickerCard(canvas, link.region, sticker, pageWidth, pageHeight) }
        }
    }

    private fun drawAffordance(canvas: Canvas, region: PageRect) {
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

    /**
     * The sticker card at [StickerPlacement.stickerRect]: a white fill (so the ink inside reads
     * like paper, not like the page showing through), the sticker's strokes scaled into it, a black
     * border on top so it stays crisp over both, and a short leader line back to the region it
     * labels so the two read as one control even when the card has slid off its usual corner (a
     * page-edge clamp — see [StickerPlacement.stickerRect]).
     */
    private fun drawStickerCard(
        canvas: Canvas,
        region: PageRect,
        sticker: LinkSticker,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        val card = StickerPlacement.stickerRect(region, pageWidth, pageHeight)
        cardRect.set(card.left, card.top, card.right, card.bottom)
        canvas.drawRect(cardRect, cardFillPaint)
        stickerRenderer.draw(canvas, sticker, cardRect)
        canvas.drawRect(cardRect, cardBorderPaint)

        // The card is normally right-aligned to the region, sitting just above or just below it
        // (stickerRect's un-clamped placement), so the short hop between them runs along that
        // shared right edge; the leader tracks whichever of the card's near/far edge actually ended
        // up next to the region after any page-edge clamp.
        val cardAbove = card.bottom <= region.top
        val leaderY = if (cardAbove) card.bottom else card.top
        val regionY = if (cardAbove) region.top else region.bottom
        canvas.drawLine(card.right, leaderY, region.right, regionY, leaderPaint)
    }

    private companion object {
        const val OUTLINE_WIDTH = 2f
        const val CORNER_RADIUS = 8f
        const val GLYPH_SIZE = 12f
        const val GLYPH_INSET = 4f
        const val CARD_BORDER_WIDTH = 1.5f
        const val LEADER_WIDTH = 1f
    }
}
