package com.nomadnotes.core.edit

import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Behavioural tests for [PageEditSession]: each intention, undo/redo symmetry, and the caps. */
class PageEditSessionTest {

    private lateinit var session: PageEditSession

    // The main layer id never changes across edits, so derive it from the live page rather than
    // holding it in a field (a value class cannot be `lateinit`).
    private val mainLayer: LayerId get() = session.page.mainLayerId

    @Before
    fun setUp() {
        session = PageEditSession(Page.create())
    }

    private fun stroke(id: String, x: Float = 0f, y: Float = 0f) = Stroke(
        id = StrokeId(id),
        tool = Tool.PEN,
        widthBase = 1f,
        grayLevel = 255,
        points = listOf(StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = 0L)),
    )

    private fun mainStrokeIds(): List<StrokeId> =
        session.page.layers.first { it.id == mainLayer }.strokes.map { it.id }

    // --- addStroke -------------------------------------------------------------------------

    @Test
    fun `addStroke appends the stroke and enables undo`() {
        session.addStroke(mainLayer, stroke("a"))
        assertEquals(listOf(StrokeId("a")), mainStrokeIds())
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
    }

    @Test
    fun `addStroke undo then redo returns to each state`() {
        val before = session.page
        session.addStroke(mainLayer, stroke("a"))
        val after = session.page

        assertTrue(session.undo())
        assertEquals(before, session.page)

        assertTrue(session.redo())
        assertEquals(after, session.page)
    }

    // --- eraseStrokes ----------------------------------------------------------------------

    @Test
    fun `eraseStrokes removes the named strokes and undo restores their original order`() {
        session.addStroke(mainLayer, stroke("s1"))
        session.addStroke(mainLayer, stroke("s2"))
        session.addStroke(mainLayer, stroke("s3"))

        session.eraseStrokes(mainLayer, listOf(StrokeId("s2")))
        assertEquals(listOf(StrokeId("s1"), StrokeId("s3")), mainStrokeIds())

        assertTrue(session.undo())
        assertEquals(listOf(StrokeId("s1"), StrokeId("s2"), StrokeId("s3")), mainStrokeIds())
    }

    @Test
    fun `eraseStrokes with only absent ids is a no-op that leaves history untouched`() {
        session.addStroke(mainLayer, stroke("a"))
        session.undo()
        assertTrue(session.canRedo)

        session.eraseStrokes(mainLayer, listOf(StrokeId("ghost")))

        assertFalse(session.canUndo)
        assertTrue("a no-op must not clear the redo stack", session.canRedo)
    }

    // --- translateStrokes ------------------------------------------------------------------

    @Test
    fun `translateStrokes shifts points and undo restores them exactly`() {
        session.addStroke(mainLayer, stroke("a", x = 0f, y = 0f))

        session.translateStrokes(mainLayer, listOf(StrokeId("a")), dx = 5f, dy = 7f)
        val moved = session.page.layers.first { it.id == mainLayer }.strokes.first().points.first()
        assertEquals(5f, moved.x, 0f)
        assertEquals(7f, moved.y, 0f)

        assertTrue(session.undo())
        val restored = session.page.layers.first { it.id == mainLayer }.strokes.first().points.first()
        assertEquals(0f, restored.x, 0f)
        assertEquals(0f, restored.y, 0f)
    }

    @Test
    fun `translateStrokes by zero is a no-op`() {
        session.addStroke(mainLayer, stroke("a"))
        val before = session.page

        session.translateStrokes(mainLayer, listOf(StrokeId("a")), dx = 0f, dy = 0f)

        assertEquals(before, session.page)
        assertFalse(session.redo())
    }

    // --- layers ----------------------------------------------------------------------------

    @Test
    fun `addLayer adds up to the cap and then refuses`() {
        // create() starts with one (main) layer; four more reach the cap of five.
        assertTrue(session.addLayer("L2"))
        assertTrue(session.addLayer("L3"))
        assertTrue(session.addLayer("L4"))
        assertTrue(session.addLayer("L5"))
        assertEquals(PageEditSession.MAX_LAYERS, session.page.layers.size)

        val atCap = session.page
        assertFalse(session.addLayer("L6"))
        assertEquals("a refused add must not change the page", atCap, session.page)
    }

    @Test
    fun `removeLayer refuses the main layer`() {
        val before = session.page
        assertFalse(session.removeLayer(mainLayer))
        assertEquals(before, session.page)
    }

    @Test
    fun `removeLayer refuses an absent layer`() {
        assertFalse(session.removeLayer(LayerId("nope")))
    }

    @Test
    fun `removeLayer drops an extra layer and undo restores it in place`() {
        session.addLayer("Extra")
        val extra = session.page.layers.last()
        val withExtra = session.page

        assertTrue(session.removeLayer(extra.id))
        assertEquals(listOf(mainLayer), session.page.layers.map { it.id })

        assertTrue(session.undo())
        assertEquals(withExtra, session.page)
    }

    @Test
    fun `setLayerVisible toggles visibility and undo restores the prior value`() {
        session.setLayerVisible(mainLayer, visible = false)
        assertFalse(session.page.layers.first { it.id == mainLayer }.visible)

        assertTrue(session.undo())
        assertTrue(session.page.layers.first { it.id == mainLayer }.visible)
    }

    @Test
    fun `setLayerVisible to the current value is a no-op`() {
        // The main layer is already visible.
        session.setLayerVisible(mainLayer, visible = true)
        assertFalse(session.canUndo)
    }

    // --- undo / redo semantics -------------------------------------------------------------

    @Test
    fun `undo and redo report false when there is nothing to do`() {
        assertFalse(session.undo())
        assertFalse(session.redo())
    }

    @Test
    fun `a new command after an undo clears the redo stack`() {
        session.addStroke(mainLayer, stroke("a"))
        session.undo()
        assertTrue(session.canRedo)

        session.addStroke(mainLayer, stroke("b"))

        assertFalse(session.canRedo)
        assertFalse(session.redo())
        assertEquals(listOf(StrokeId("b")), mainStrokeIds())
    }

    @Test
    fun `the undo history is capped and drops the oldest change`() {
        val overCap = PageEditSession.MAX_UNDO + 1
        repeat(overCap) { i -> session.addStroke(mainLayer, stroke("s$i")) }

        var undos = 0
        while (session.undo()) undos++

        assertEquals(PageEditSession.MAX_UNDO, undos)
        // The oldest change fell off the end, so its stroke can never be undone away.
        assertEquals(listOf(StrokeId("s0")), mainStrokeIds())
    }
}
