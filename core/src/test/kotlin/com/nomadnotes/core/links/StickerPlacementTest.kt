package com.nomadnotes.core.links

import com.nomadnotes.core.PageRect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the placement math in [StickerPlacement.stickerRect]: the above-anchor, the flip, and the clamp. */
class StickerPlacementTest {

    private val pageWidth = 1000f
    private val pageHeight = 2000f

    @Test
    fun `sticker sits above the region, right-aligned to it`() {
        val region = PageRect(left = 100f, top = 200f, right = 300f, bottom = 250f)

        val rect = StickerPlacement.stickerRect(region, pageWidth, pageHeight)

        assertEquals(PageRect(left = 60f, top = 72f, right = 300f, bottom = 192f), rect)
    }

    @Test
    fun `sticker flips below the region when there is no room above`() {
        val region = PageRect(left = 100f, top = 50f, right = 300f, bottom = 90f)

        val rect = StickerPlacement.stickerRect(region, pageWidth, pageHeight)

        assertEquals(PageRect(left = 60f, top = 98f, right = 300f, bottom = 218f), rect)
    }

    @Test
    fun `sticker slides back onto the page when it would spill past the left edge`() {
        // right = 100 is narrower than CARD_WIDTH, so the right-aligned card would start negative.
        val region = PageRect(left = 0f, top = 200f, right = 100f, bottom = 250f)

        val rect = StickerPlacement.stickerRect(region, pageWidth, pageHeight)

        assertEquals(PageRect(left = 0f, top = 72f, right = 240f, bottom = 192f), rect)
    }

    @Test
    fun `sticker slides back onto the page when it would spill past the right edge`() {
        val region = PageRect(left = 900f, top = 200f, right = 1050f, bottom = 250f)

        val rect = StickerPlacement.stickerRect(region, pageWidth, pageHeight)

        assertEquals(PageRect(left = 760f, top = 72f, right = 1000f, bottom = 192f), rect)
    }

    @Test
    fun `a flipped sticker still slides back onto the page when it would spill past the bottom`() {
        val shortPage = 300f
        val region = PageRect(left = 100f, top = 10f, right = 300f, bottom = 290f)

        val rect = StickerPlacement.stickerRect(region, pageWidth, shortPage)

        assertEquals(PageRect(left = 60f, top = 180f, right = 300f, bottom = 300f), rect)
    }
}
