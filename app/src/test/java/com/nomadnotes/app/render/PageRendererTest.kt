package com.nomadnotes.app.render

import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests [PageRenderer.strokesToDraw] — the stroke-exclusion rule behind a live lasso move. The full
 * [PageRenderer.renderFull] path rasterises onto an Android [android.graphics.Bitmap]/[android.graphics.Canvas],
 * which a plain JVM unit test cannot exercise (no Robolectric in this module); this covers the one
 * decision that is pure — which strokes a layer draws given an exclusion set — that renderFull uses.
 */
class PageRendererTest {

    private fun stroke(id: String) = Stroke(
        id = StrokeId(id),
        tool = Tool.PEN,
        widthBase = 1f,
        grayLevel = 255,
        points = listOf(StrokePoint(x = 0f, y = 0f, pressure = 1f, timestampDelta = 0L)),
    )

    @Test
    fun `excluded strokes are dropped and the rest kept in order`() {
        val strokes = listOf(stroke("a"), stroke("b"), stroke("c"))
        val kept = PageRenderer.strokesToDraw(strokes, setOf(StrokeId("b")))
        assertEquals(listOf(StrokeId("a"), StrokeId("c")), kept.map { it.id })
    }

    @Test
    fun `an empty exclusion set restores every stroke`() {
        val strokes = listOf(stroke("a"), stroke("b"))
        // The default path (no exclusions) must return the strokes untouched — the same list the
        // caller passed, so a post-move renderFull draws exactly what it did before.
        assertSame(strokes, PageRenderer.strokesToDraw(strokes, emptySet()))
    }
}
