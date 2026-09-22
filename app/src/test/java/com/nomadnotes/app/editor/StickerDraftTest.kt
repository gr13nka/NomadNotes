package com.nomadnotes.app.editor

import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for [StickerDraft]: the pointer-samples-to-[Stroke]s conversion, pure of any Android type. */
class StickerDraftTest {

    private fun draft(initial: List<Stroke> = emptyList()) =
        StickerDraft(tool = Tool.PEN, widthBase = 4f, grayLevel = 255, initialStrokes = initial)

    @Test
    fun `a finished stroke carries the draft's tool, width and shade`() {
        val draft = draft()
        draft.beginStroke(10f, 20f, 0.5f, t = 100L)
        draft.addPoint(12f, 22f, 0.6f, t = 120L)
        draft.endStroke()

        assertEquals(1, draft.strokes.size)
        val stroke = draft.strokes.single()
        assertEquals(Tool.PEN, stroke.tool)
        assertEquals(4f, stroke.widthBase)
        assertEquals(255, stroke.grayLevel)
    }

    @Test
    fun `point timestamps are relative to the stroke's own start`() {
        val draft = draft()
        draft.beginStroke(0f, 0f, 1f, t = 1_000L)
        draft.addPoint(1f, 1f, 1f, t = 1_050L)
        draft.addPoint(2f, 2f, 1f, t = 1_090L)
        draft.endStroke()

        val deltas = draft.strokes.single().points.map { it.timestampDelta }
        assertEquals(listOf(0L, 50L, 90L), deltas)
    }

    @Test
    fun `strokes accumulate across gestures in order`() {
        val draft = draft()
        draft.beginStroke(0f, 0f, 1f, t = 0L)
        draft.endStroke()
        draft.beginStroke(5f, 5f, 1f, t = 0L)
        draft.endStroke()

        assertEquals(2, draft.strokes.size)
    }

    @Test
    fun `addPoint and endStroke without a begun stroke are no-ops`() {
        val draft = draft()
        draft.addPoint(1f, 1f, 1f, t = 0L)
        draft.endStroke()

        assertTrue(draft.strokes.isEmpty())
    }

    @Test
    fun `clear drops both finished and in-progress strokes`() {
        val draft = draft()
        draft.beginStroke(0f, 0f, 1f, t = 0L)
        draft.endStroke()
        draft.beginStroke(1f, 1f, 1f, t = 0L)

        draft.clear()

        assertTrue(draft.strokes.isEmpty())
        assertTrue(draft.liveStroke.isEmpty())
    }

    @Test
    fun `liveStroke reflects the in-progress gesture and empties once it ends`() {
        val draft = draft()
        assertTrue(draft.liveStroke.isEmpty())

        draft.beginStroke(0f, 0f, 1f, t = 0L)
        draft.addPoint(1f, 1f, 1f, t = 10L)
        assertEquals(2, draft.liveStroke.size)

        draft.endStroke()
        assertTrue(draft.liveStroke.isEmpty())
    }

    @Test
    fun `points are clamped into sticker space`() {
        val draft = draft()
        draft.beginStroke(-50f, LinkSticker.HEIGHT + 50f, 1f, t = 0L)
        draft.addPoint(LinkSticker.WIDTH + 50f, -50f, 1f, t = 10L)
        draft.endStroke()

        val points = draft.strokes.single().points
        assertEquals(StrokePoint(0f, LinkSticker.HEIGHT, 1f, 0L), points[0])
        assertEquals(StrokePoint(LinkSticker.WIDTH, 0f, 1f, 10L), points[1])
    }

    @Test
    fun `toSticker is null when nothing has been drawn`() {
        assertNull(draft().toSticker())
    }

    @Test
    fun `toSticker wraps the finished strokes once something is drawn`() {
        val draft = draft()
        draft.beginStroke(0f, 0f, 1f, t = 0L)
        draft.endStroke()

        assertEquals(LinkSticker(draft.strokes), draft.toSticker())
    }

    @Test
    fun `addStroke folds an already-finished gesture in as one stroke`() {
        val draft = draft()
        draft.addStroke(listOf(StrokePoint(10f, 20f, 0.5f, 0L), StrokePoint(12f, 22f, 0.6f, 30L)))

        assertEquals(1, draft.strokes.size)
        val stroke = draft.strokes.single()
        assertEquals(Tool.PEN, stroke.tool)
        assertEquals(2, stroke.points.size)
        assertTrue(draft.liveStroke.isEmpty())
    }

    @Test
    fun `addStroke clamps its points into sticker space like the streaming path`() {
        val draft = draft()
        draft.addStroke(listOf(StrokePoint(-50f, LinkSticker.HEIGHT + 50f, 1f, 0L)))

        val point = draft.strokes.single().points.single()
        assertEquals(StrokePoint(0f, LinkSticker.HEIGHT, 1f, 0L), point)
    }

    @Test
    fun `addStroke on an empty gesture is a no-op`() {
        val draft = draft()
        draft.addStroke(emptyList())

        assertTrue(draft.strokes.isEmpty())
    }

    @Test
    fun `initialStrokes seed strokes for the edit flow`() {
        val seed = Stroke(StrokeId("seed"), Tool.PEN, 4f, 255, listOf(StrokePoint(0f, 0f, 1f, 0L)))
        val draft = draft(initial = listOf(seed))

        assertEquals(listOf(seed), draft.strokes)
    }
}
