package com.nomadnotes.core.edit

import com.nomadnotes.core.LayerId
import com.nomadnotes.core.LinkId
import com.nomadnotes.core.LinkRegion
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.Page
import com.nomadnotes.core.PageId
import com.nomadnotes.core.PageLink
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    private fun link(
        id: String,
        targetNotebook: String = "nb-target",
        targetPage: String = "page-target",
    ) = PageLink(
        id = LinkId(id),
        region = LinkRegion(0f, 0f, 10f, 10f),
        targetNotebookId = NotebookId(targetNotebook),
        targetPageId = PageId(targetPage),
    )

    private fun pageLinkIds(): List<LinkId> = session.page.links.map { it.id }

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

    // --- pasteStrokes ----------------------------------------------------------------------

    @Test
    fun `pasteStrokes appends the strokes and undo restores their absence, redo re-adds them`() {
        session.addStroke(mainLayer, stroke("existing"))

        assertTrue(session.pasteStrokes(mainLayer, listOf(stroke("p1"), stroke("p2"))))
        assertEquals(listOf(StrokeId("existing"), StrokeId("p1"), StrokeId("p2")), mainStrokeIds())

        // One undo removes exactly the pasted strokes, leaving what was there before.
        assertTrue(session.undo())
        assertEquals(listOf(StrokeId("existing")), mainStrokeIds())

        assertTrue(session.redo())
        assertEquals(listOf(StrokeId("existing"), StrokeId("p1"), StrokeId("p2")), mainStrokeIds())
    }

    @Test
    fun `pasteStrokes into a missing layer throws, consistent with addStroke`() {
        val missing = LayerId("nope")
        assertThrows(IllegalArgumentException::class.java) { session.addStroke(missing, stroke("a")) }
        assertThrows(IllegalArgumentException::class.java) {
            session.pasteStrokes(missing, listOf(stroke("b")))
        }
    }

    @Test
    fun `pasteStrokes of an empty list into a missing layer returns false without throwing`() {
        // The empty-list check runs before the layer is validated, so an empty paste is a total
        // no-op even when the target layer is gone (a stale selection's layer, say).
        assertFalse(session.pasteStrokes(LayerId("nope"), emptyList()))
    }

    @Test
    fun `pasteStrokes of an empty list is a no-op that leaves history untouched`() {
        session.addStroke(mainLayer, stroke("a"))
        session.undo()
        assertTrue(session.canRedo)

        assertFalse(session.pasteStrokes(mainLayer, emptyList()))

        assertFalse(session.canUndo)
        assertTrue("a no-op must not clear the redo stack", session.canRedo)
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
    fun `undo of a non-adjacent multi-erase restores every stroke in its original position`() {
        session.addStroke(mainLayer, stroke("s1"))
        session.addStroke(mainLayer, stroke("s2"))
        session.addStroke(mainLayer, stroke("s3"))
        session.addStroke(mainLayer, stroke("s4"))

        // Erase the strokes at indices 0 and 2, leaving a gap that undo must refill exactly.
        session.eraseStrokes(mainLayer, listOf(StrokeId("s1"), StrokeId("s3")))
        assertEquals(listOf(StrokeId("s2"), StrokeId("s4")), mainStrokeIds())

        assertTrue(session.undo())
        assertEquals(
            listOf(StrokeId("s1"), StrokeId("s2"), StrokeId("s3"), StrokeId("s4")),
            mainStrokeIds(),
        )
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

    @Test
    fun `translateStrokes moves only the named strokes and leaves the rest in place`() {
        session.addStroke(mainLayer, stroke("moved", x = 0f, y = 0f))
        session.addStroke(mainLayer, stroke("still", x = 100f, y = 100f))

        session.translateStrokes(mainLayer, listOf(StrokeId("moved")), dx = 5f, dy = 7f)

        val strokes = session.page.layers.first { it.id == mainLayer }.strokes
        val moved = strokes.first { it.id == StrokeId("moved") }.points.first()
        val still = strokes.first { it.id == StrokeId("still") }.points.first()
        assertEquals(5f, moved.x, 0f)
        assertEquals(7f, moved.y, 0f)
        assertEquals(100f, still.x, 0f)
        assertEquals(100f, still.y, 0f)
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

    // --- template --------------------------------------------------------------------------

    @Test
    fun `setTemplateRef changes the ref and undo then redo returns to each state`() {
        // Page.create() starts with no template.
        assertEquals(null, session.page.templateRef)

        session.setTemplateRef("builtin:grid")
        assertEquals("builtin:grid", session.page.templateRef)

        assertTrue(session.undo())
        assertEquals(null, session.page.templateRef)

        assertTrue(session.redo())
        assertEquals("builtin:grid", session.page.templateRef)
    }

    @Test
    fun `setTemplateRef to the current value is a no-op`() {
        // The page already has a null template ref.
        session.setTemplateRef(null)
        assertFalse(session.canUndo)
    }

    // --- links -----------------------------------------------------------------------------

    @Test
    fun `addLink appears on page and undoes cleanly`() {
        session.addLink(link("l1"))
        assertEquals(listOf(LinkId("l1")), pageLinkIds())

        assertTrue(session.undo())
        assertEquals(emptyList<LinkId>(), pageLinkIds())

        assertTrue(session.redo())
        assertEquals(listOf(LinkId("l1")), pageLinkIds())
    }

    @Test
    fun `removeLink removes and undo restores the identical link`() {
        val original = link("l1")
        session.addLink(original)

        assertTrue(session.removeLink(LinkId("l1")))
        assertTrue(session.page.links.isEmpty())

        // The whole link — id, region, and target — comes back exactly as it was.
        assertTrue(session.undo())
        assertEquals(listOf(original), session.page.links)
    }

    @Test
    fun `removeLink of absent id returns false and leaves history untouched`() {
        session.addLink(link("l1"))
        session.undo()
        assertTrue(session.canRedo)

        assertFalse(session.removeLink(LinkId("ghost")))

        assertFalse(session.canUndo)
        assertTrue("a no-op must not clear the redo stack", session.canRedo)
    }

    @Test
    fun `setLinkTarget changes target and undo restores previous target`() {
        session.addLink(link("l1", targetNotebook = "nb-a", targetPage = "page-a"))

        assertTrue(session.setLinkTarget(LinkId("l1"), NotebookId("nb-b"), PageId("page-b")))
        val moved = session.page.links.first { it.id == LinkId("l1") }
        assertEquals(NotebookId("nb-b"), moved.targetNotebookId)
        assertEquals(PageId("page-b"), moved.targetPageId)

        assertTrue(session.undo())
        val restored = session.page.links.first { it.id == LinkId("l1") }
        assertEquals(NotebookId("nb-a"), restored.targetNotebookId)
        assertEquals(PageId("page-a"), restored.targetPageId)
    }

    @Test
    fun `setLinkTarget with identical target is a no-op`() {
        session.addLink(link("l1", targetNotebook = "nb-a", targetPage = "page-a"))
        val before = session.page

        assertFalse(session.setLinkTarget(LinkId("l1"), NotebookId("nb-a"), PageId("page-a")))

        assertEquals("a no-op must not change the page", before, session.page)
        // No history entry was pushed: undoing the add is the only thing on the stack.
        assertTrue(session.undo())
        assertFalse(session.canUndo)
    }

    @Test
    fun `link commands interleave with stroke commands in one history`() {
        session.addStroke(mainLayer, stroke("s1"))
        session.addLink(link("l1"))
        assertEquals(listOf(StrokeId("s1")), mainStrokeIds())
        assertEquals(listOf(LinkId("l1")), pageLinkIds())

        // The link was the most recent change, so the first undo peels it off, leaving the stroke.
        assertTrue(session.undo())
        assertEquals(listOf(StrokeId("s1")), mainStrokeIds())
        assertTrue(session.page.links.isEmpty())

        // The second undo peels off the stroke.
        assertTrue(session.undo())
        assertTrue(mainStrokeIds().isEmpty())
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
