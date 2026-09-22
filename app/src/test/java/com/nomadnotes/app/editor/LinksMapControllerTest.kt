package com.nomadnotes.app.editor

import com.nomadnotes.app.storage.LinkIndex
import com.nomadnotes.core.LinkId
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import com.nomadnotes.core.PageLink
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.links.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [LinksMapController]'s own state machine — load/select/centre/back and the neighbour
 * list and labels it derives from a [LinkIndex] — with every fixture sticker-free, so
 * [LinksMapController.panelState] never reaches its `StickerRenderer.toBitmap` path: that path
 * rasterises through `android.graphics`, which this module's plain JVM unit tests cannot exercise
 * (no Robolectric — see `PageRendererTest`). [LinkNeighbourhood]'s own graph rules (direction,
 * de-duplication, sticker choice) are covered in :core; this only checks what the controller adds.
 */
class LinksMapControllerTest {

    private val nbA = NotebookId("nb-a")
    private val nbB = NotebookId("nb-b")

    private fun node(notebook: NotebookId, page: String) = NodeRef(notebook, PageId(page))
    private fun link(target: NodeRef) =
        PageLink(LinkId.random(), PageRect(0f, 0f, 1f, 1f), target.notebookId, target.pageId)

    private val centre = node(nbA, "page-1")
    private val sameNotebookNeighbour = node(nbA, "page-2")
    private val crossNotebookNeighbour = node(nbB, "page-1")

    private fun loadedController(): LinksMapController {
        val index = LinkIndex(
            links = mapOf(centre to listOf(link(sameNotebookNeighbour), link(crossNotebookNeighbour))),
            pageIdsByNotebook = mapOf(
                nbA to listOf(centre.pageId, sameNotebookNeighbour.pageId),
                nbB to listOf(crossNotebookNeighbour.pageId),
            ),
            notebookNames = mapOf(nbA to "Trip", nbB to "Research"),
        )
        val controller = LinksMapController()
        controller.beginLoad(centre)
        controller.finishLoad(index)
        return controller
    }

    @Test
    fun `before any load the panel is not loading and has no neighbours`() {
        val state = LinksMapController().panelState()
        assertFalse(state.loading)
        assertTrue(state.neighbours.isEmpty())
    }

    @Test
    fun `beginLoad marks the panel loading until finishLoad lands`() {
        val controller = LinksMapController()
        controller.beginLoad(centre)
        assertTrue(controller.panelState().loading)

        controller.finishLoad(LinkIndex(emptyMap(), emptyMap(), emptyMap()))
        assertFalse(controller.panelState().loading)
    }

    @Test
    fun `finishLoad orders same-notebook neighbours before cross-notebook ones`() {
        val state = loadedController().panelState()
        assertEquals(listOf(sameNotebookNeighbour, crossNotebookNeighbour), state.neighbours.map { it.ref })
    }

    @Test
    fun `a same-notebook neighbour is labelled by page number alone`() {
        val chip = loadedController().panelState().neighbours.first { it.ref == sameNotebookNeighbour }
        assertEquals("#2", chip.label)
        assertFalse(chip.isCrossNotebook)
    }

    @Test
    fun `a cross-notebook neighbour is labelled with its notebook's name`() {
        val chip = loadedController().panelState().neighbours.first { it.ref == crossNotebookNeighbour }
        assertEquals("Research #1", chip.label)
        assertTrue(chip.isCrossNotebook)
    }

    @Test
    fun `the centre is labelled by its own page number`() {
        assertEquals("#1", loadedController().panelState().centreLabel)
    }

    @Test
    fun `a node outside the loaded notebooks falls back to a bare number sign`() {
        val controller = LinksMapController()
        val strayCentre = node(NotebookId("nb-z"), "page-9")
        controller.beginLoad(strayCentre)
        controller.finishLoad(LinkIndex(emptyMap(), emptyMap(), emptyMap()))
        assertEquals("#?", controller.panelState().centreLabel)
    }

    @Test
    fun `selecting a node round-trips through panelState, and null clears it`() {
        val controller = loadedController()

        controller.select(sameNotebookNeighbour)
        assertEquals(sameNotebookNeighbour, controller.panelState().selected)

        controller.select(null)
        assertNull(controller.panelState().selected)
    }

    @Test
    fun `centreOn re-anchors the map, enables back, and drops the selection`() {
        val controller = loadedController()
        controller.select(sameNotebookNeighbour)
        assertFalse(controller.panelState().canGoBack)

        controller.centreOn(sameNotebookNeighbour)

        assertEquals(sameNotebookNeighbour, controller.centre)
        assertTrue(controller.panelState().canGoBack)
        assertNull(controller.panelState().selected)
    }

    @Test
    fun `back returns to the previous centre and is a no-op at the root`() {
        val controller = loadedController()
        controller.centreOn(sameNotebookNeighbour)

        controller.back()

        assertEquals(centre, controller.centre)
        assertFalse(controller.panelState().canGoBack)

        controller.back() // nothing left to pop
        assertEquals(centre, controller.centre)
    }

    @Test
    fun `a centre with no links reports the empty state`() {
        val controller = LinksMapController()
        controller.beginLoad(centre)
        controller.finishLoad(LinkIndex(emptyMap(), emptyMap(), emptyMap()))
        assertTrue(controller.panelState().isEmpty)
    }
}
