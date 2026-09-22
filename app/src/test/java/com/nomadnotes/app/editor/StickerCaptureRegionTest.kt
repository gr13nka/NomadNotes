package com.nomadnotes.app.editor

import com.nomadnotes.core.LinkSticker
import org.junit.Assert.assertEquals
import org.junit.Test

/** Behavioural tests for [surfaceToStickerSpace]: a plain scale-to-fit mapping, pure of any Android type. */
class StickerCaptureRegionTest {

    @Test
    fun `the box's top-left corner maps to the sticker space's own origin`() {
        val (x, y) = surfaceToStickerSpace(x = 100f, y = 200f, boxLeft = 100f, boxTop = 200f, boxWidth = 320f, boxHeight = 160f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `the box's bottom-right corner maps to the sticker space's own far corner`() {
        val (x, y) = surfaceToStickerSpace(
            x = 100f + 320f,
            y = 200f + 160f,
            boxLeft = 100f,
            boxTop = 200f,
            boxWidth = 320f,
            boxHeight = 160f,
        )
        assertEquals(LinkSticker.WIDTH, x)
        assertEquals(LinkSticker.HEIGHT, y)
    }

    @Test
    fun `the box's centre maps to the sticker space's own centre`() {
        val (x, y) = surfaceToStickerSpace(
            x = 100f + 160f,
            y = 200f + 80f,
            boxLeft = 100f,
            boxTop = 200f,
            boxWidth = 320f,
            boxHeight = 160f,
        )
        assertEquals(LinkSticker.WIDTH / 2f, x, 0.001f)
        assertEquals(LinkSticker.HEIGHT / 2f, y, 0.001f)
    }

    @Test
    fun `a degenerate zero-size box maps to the origin instead of dividing by zero`() {
        val (x, y) = surfaceToStickerSpace(x = 50f, y = 50f, boxLeft = 0f, boxTop = 0f, boxWidth = 0f, boxHeight = 0f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }
}
