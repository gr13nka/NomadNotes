package com.nomadnotes.app.render

import com.nomadnotes.core.ImageId
import com.nomadnotes.core.PageImage
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests [PageRenderer.strokesToDraw] and [PageRenderer.imagesToDraw] — the exclusion rules behind a
 * live lasso move and a live image drag. The full [PageRenderer.renderFull] path rasterises onto an
 * Android [android.graphics.Bitmap]/[android.graphics.Canvas], which a plain JVM unit test cannot
 * exercise (no Robolectric in this module); these cover the decisions that are pure — what a layer
 * draws given an exclusion set — that renderFull uses.
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

    private fun image(id: String) = PageImage(
        id = ImageId(id),
        assetRef = "$id.png",
        rect = PageRect(0f, 0f, 100f, 100f),
    )

    @Test
    fun `an excluded image is dropped and the rest kept in order`() {
        val images = listOf(image("a"), image("b"), image("c"))
        val kept = PageRenderer.imagesToDraw(images, setOf(ImageId("b")))
        assertEquals(listOf(ImageId("a"), ImageId("c")), kept.map { it.id })
    }

    @Test
    fun `an empty exclusion set restores every image`() {
        val images = listOf(image("a"), image("b"))
        assertSame(images, PageRenderer.imagesToDraw(images, emptySet()))
    }
}
