package com.nomadnotes.app.editor

import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the lasso selection model: bounds derivation and hit-testing. */
class SelectionStateTest {

    private val layer = LayerId("layer")

    private fun strokeOf(id: String, vararg xy: Pair<Float, Float>) = Stroke(
        id = StrokeId(id),
        tool = Tool.PEN,
        widthBase = 1f,
        grayLevel = 255,
        points = xy.map { (x, y) -> StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = 0L) },
    )

    @Test
    fun `bounds enclose every point across all strokes`() {
        val selection = SelectionState.of(
            layer,
            listOf(strokeOf("a", 2f to 3f, 8f to 5f), strokeOf("b", 1f to 9f, 6f to 4f)),
        )!!
        assertEquals(listOf(StrokeId("a"), StrokeId("b")), selection.strokeIds)
        assertEquals(SelectionBounds(left = 1f, top = 3f, right = 8f, bottom = 9f), selection.bounds)
    }

    @Test
    fun `of returns null when there are no strokes or no points`() {
        assertNull(SelectionState.of(layer, emptyList()))
        assertNull(SelectionState.of(layer, listOf(strokeOf("empty"))))
    }

    @Test
    fun `contains is inclusive of the edges and rejects points outside`() {
        val bounds = SelectionBounds(left = 0f, top = 0f, right = 10f, bottom = 10f)
        assertTrue(bounds.contains(5f, 5f))
        assertTrue(bounds.contains(0f, 10f))
        assertFalse(bounds.contains(-1f, 5f))
        assertFalse(bounds.contains(5f, 11f))
    }

    @Test
    fun `translated shifts the box by exactly the delta`() {
        val bounds = SelectionBounds(left = 1f, top = 2f, right = 3f, bottom = 4f)
        assertEquals(SelectionBounds(left = 6f, top = 9f, right = 8f, bottom = 11f), bounds.translated(5f, 7f))
    }
}
