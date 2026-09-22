package com.nomadnotes.core.links

import com.nomadnotes.core.PageRect

/**
 * Where a link's sticker card sits on the page, derived purely from the link's region — so the
 * renderer and the hit-test agree on one rectangle without either owning it.
 *
 * The card is right-aligned to the region and sits just above it, matching the link box's
 * top-right anchor in the mockup; when that would run off the top of the page it flips to sit
 * just below instead. Page pixel dimensions are a device fact, not part of the core page model
 * (see `Page`'s coordinate-space note), so the caller supplies them rather than this object
 * assuming a fixed panel size.
 */
object StickerPlacement {
    /** Fixed card size, in page pixels. */
    const val CARD_WIDTH = 240f
    const val CARD_HEIGHT = 120f

    /** Gap kept between the card and the link region it labels, in page pixels. */
    const val GAP = 8f

    /**
     * The card's rectangle for a link whose tappable area is [region], on a page sized
     * [pageWidth] x [pageHeight]. Slides — rather than shrinks — back onto the page if the
     * preferred placement would spill past an edge, so the card keeps its fixed size.
     */
    fun stickerRect(region: PageRect, pageWidth: Float, pageHeight: Float): PageRect {
        val left = region.right - CARD_WIDTH
        val bottom = region.top - GAP
        val top = bottom - CARD_HEIGHT
        val placed = if (top >= 0f) {
            PageRect(left, top, left + CARD_WIDTH, bottom)
        } else {
            val flippedTop = region.bottom + GAP
            PageRect(left, flippedTop, left + CARD_WIDTH, flippedTop + CARD_HEIGHT)
        }
        return placed.slideOnto(pageWidth, pageHeight)
    }

    /** Slides [this] so it lies within [0, pageWidth] x [0, pageHeight], keeping its size. */
    private fun PageRect.slideOnto(pageWidth: Float, pageHeight: Float): PageRect {
        val newLeft = left.coerceIn(0f, (pageWidth - width).coerceAtLeast(0f))
        val newTop = top.coerceIn(0f, (pageHeight - height).coerceAtLeast(0f))
        return PageRect(newLeft, newTop, newLeft + width, newTop + height)
    }
}
