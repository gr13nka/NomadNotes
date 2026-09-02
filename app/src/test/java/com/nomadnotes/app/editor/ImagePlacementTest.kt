package com.nomadnotes.app.editor

import com.nomadnotes.core.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the move and resize geometry behind dragging a placed image. */
class ImagePlacementTest {

    private val rect = PageRect(left = 100f, top = 100f, right = 300f, bottom = 200f)

    private fun assertRect(expected: PageRect, actual: PageRect) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    // --- gripAt -------------------------------------------------------------------------------

    @Test
    fun `a pen-down on a corner grabs that corner`() {
        assertEquals(ImageGrip.TOP_LEFT, ImagePlacement.gripAt(rect, 100f, 100f, within = 26f))
        assertEquals(ImageGrip.BOTTOM_RIGHT, ImagePlacement.gripAt(rect, 300f, 200f, within = 26f))
    }

    @Test
    fun `a pen-down near a corner still grabs it`() {
        assertEquals(ImageGrip.TOP_RIGHT, ImagePlacement.gripAt(rect, 290f, 110f, within = 26f))
    }

    @Test
    fun `a pen-down in the middle grabs no corner, so the drag is a move`() {
        assertNull(ImagePlacement.gripAt(rect, 200f, 150f, within = 26f))
    }

    @Test
    fun `between two corners the nearer one wins`() {
        // On a short edge both corners can be within reach; the closer must be chosen.
        val narrow = PageRect(left = 0f, top = 0f, right = 30f, bottom = 30f)
        assertEquals(ImageGrip.TOP_LEFT, ImagePlacement.gripAt(narrow, 12f, 12f, within = 40f))
        assertEquals(ImageGrip.BOTTOM_RIGHT, ImagePlacement.gripAt(narrow, 20f, 20f, within = 40f))
    }

    // --- moved --------------------------------------------------------------------------------

    @Test
    fun `moving shifts the whole rectangle and keeps its size`() {
        val moved = ImagePlacement.moved(rect, dx = 25f, dy = -40f)
        assertRect(PageRect(125f, 60f, 325f, 160f), moved)
        assertEquals(rect.width, moved.width, TOLERANCE)
        assertEquals(rect.height, moved.height, TOLERANCE)
    }

    // --- resized ------------------------------------------------------------------------------

    @Test
    fun `resizing from a corner pins the opposite corner`() {
        val resized = ImagePlacement.resized(rect, ImageGrip.BOTTOM_RIGHT, dx = 100f, dy = 50f, minSize = 48f)
        assertEquals("top-left is pinned", 100f, resized.left, TOLERANCE)
        assertEquals("top-left is pinned", 100f, resized.top, TOLERANCE)
        assertTrue("the image grew", resized.width > rect.width)
    }

    @Test
    fun `resizing from the top-left pins the bottom-right`() {
        val resized = ImagePlacement.resized(rect, ImageGrip.TOP_LEFT, dx = -100f, dy = -50f, minSize = 48f)
        assertEquals(300f, resized.right, TOLERANCE)
        assertEquals(200f, resized.bottom, TOLERANCE)
        assertTrue("the image grew", resized.width > rect.width)
    }

    @Test
    fun `resizing keeps the aspect ratio, so a photo is never stretched`() {
        val ratio = rect.width / rect.height
        for (grip in ImageGrip.entries) {
            val resized = ImagePlacement.resized(rect, grip, dx = 140f, dy = -30f, minSize = 48f)
            assertEquals("$grip distorted the image", ratio, resized.width / resized.height, TOLERANCE)
        }
    }

    @Test
    fun `an image cannot be shrunk below the minimum`() {
        val resized = ImagePlacement.resized(rect, ImageGrip.BOTTOM_RIGHT, dx = -1000f, dy = -1000f, minSize = 48f)
        assertTrue("width collapsed to ${resized.width}", resized.width >= 48f)
        assertTrue("height collapsed to ${resized.height}", resized.height >= 48f)
    }

    @Test
    fun `a resized rectangle stays well-formed whichever way it is dragged`() {
        // Dragging a corner far past its opposite must not produce an inverted rectangle.
        for (grip in ImageGrip.entries) {
            val resized = ImagePlacement.resized(rect, grip, dx = -800f, dy = 600f, minSize = 48f)
            assertTrue("$grip inverted horizontally", resized.left <= resized.right)
            assertTrue("$grip inverted vertically", resized.top <= resized.bottom)
        }
    }

    @Test
    fun `a degenerate rectangle is left alone rather than dividing by zero`() {
        val empty = PageRect(50f, 50f, 50f, 50f)
        assertRect(empty, ImagePlacement.resized(empty, ImageGrip.TOP_LEFT, dx = 10f, dy = 10f, minSize = 48f))
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
