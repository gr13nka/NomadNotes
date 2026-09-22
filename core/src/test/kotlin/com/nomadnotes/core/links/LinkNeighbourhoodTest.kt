package com.nomadnotes.core.links

import com.nomadnotes.core.LinkId
import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import com.nomadnotes.core.PageLink
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for [LinkNeighbourhood.neighbours] and [radialLayout]. */
class LinkNeighbourhoodTest {

    private val nbA = NotebookId("nb-a")
    private val nbB = NotebookId("nb-b")

    private fun node(notebook: NotebookId, page: String) = NodeRef(notebook, PageId(page))

    private fun link(id: String, target: NodeRef, sticker: LinkSticker? = null) = PageLink(
        id = LinkId(id),
        region = PageRect(0f, 0f, 10f, 10f),
        targetNotebookId = target.notebookId,
        targetPageId = target.pageId,
        sticker = sticker,
    )

    private fun sticker(strokeId: String) = LinkSticker(
        strokes = listOf(
            Stroke(
                id = StrokeId(strokeId),
                tool = Tool.PEN,
                widthBase = 2f,
                grayLevel = 0,
                points = listOf(StrokePoint(x = 0f, y = 0f, pressure = 1f, timestampDelta = 0L)),
            ),
        ),
    )

    // Page ids in these tests are "page-N"; order by that N, as a real caller would by page index.
    private val pageOrder: (NodeRef) -> Int = { it.pageId.value.removePrefix("page-").toInt() }

    // --- direction and de-duplication -------------------------------------------------------

    @Test
    fun `a page centre links to is a neighbour`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val outgoing = mapOf(centre to listOf(link("l1", other)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(listOf(other), result.map { it.ref })
    }

    @Test
    fun `a page that links to centre is a neighbour, with no outgoing link required back`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val outgoing = mapOf(other to listOf(link("l1", centre)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(listOf(other), result.map { it.ref })
    }

    @Test
    fun `a page connected in both directions counts as one neighbour`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val outgoing = mapOf(
            centre to listOf(link("l1", other)),
            other to listOf(link("l2", centre)),
        )

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(listOf(other), result.map { it.ref })
    }

    @Test
    fun `parallel links to the same page fold into one neighbour`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val outgoing = mapOf(centre to listOf(link("l1", other), link("l2", other)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(1, result.size)
    }

    @Test
    fun `a link from centre to itself is not a neighbour`() {
        val centre = node(nbA, "page-1")
        val outgoing = mapOf(centre to listOf(link("l1", centre)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertTrue(result.isEmpty())
    }

    // --- sticker choice ----------------------------------------------------------------------

    @Test
    fun `neighbour sticker prefers centre's own link over the one coming back`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val forward = sticker("forward")
        val outgoing = mapOf(
            centre to listOf(link("l1", other, sticker = forward)),
            other to listOf(link("l2", centre, sticker = sticker("backward"))),
        )

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(forward, result.single().sticker)
    }

    @Test
    fun `neighbour sticker falls back to the link coming back when centre's own has none`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val backward = sticker("backward")
        val outgoing = mapOf(
            centre to listOf(link("l1", other)),
            other to listOf(link("l2", centre, sticker = backward)),
        )

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(backward, result.single().sticker)
    }

    @Test
    fun `neighbour sticker is null when neither direction carries one`() {
        val centre = node(nbA, "page-1")
        val other = node(nbA, "page-2")
        val outgoing = mapOf(centre to listOf(link("l1", other)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(null, result.single().sticker)
    }

    // --- linkCount ---------------------------------------------------------------------------

    @Test
    fun `linkCount is the neighbour's own distinct neighbour count, not centre's`() {
        val centre = node(nbA, "page-1")
        val busy = node(nbA, "page-2")
        val busysFriend = node(nbA, "page-3")
        val outgoing = mapOf(
            centre to listOf(link("l1", busy)),
            busy to listOf(link("l2", centre), link("l3", busysFriend)),
        )

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(2, result.single { it.ref == busy }.linkCount)
    }

    // --- ordering ------------------------------------------------------------------------------

    @Test
    fun `neighbours in centre's own notebook come first, in page order`() {
        val centre = node(nbA, "page-1")
        val same2 = node(nbA, "page-2")
        val same3 = node(nbA, "page-3")
        val other = node(nbB, "page-1")
        val outgoing = mapOf(centre to listOf(link("l1", same3), link("l2", other), link("l3", same2)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(listOf(same2, same3, other), result.map { it.ref })
    }

    @Test
    fun `other-notebook neighbours are grouped by notebook id, then page order`() {
        val centre = node(nbA, "page-1")
        val nbC = NotebookId("nb-c")
        val b2 = node(nbB, "page-2")
        val b1 = node(nbB, "page-1")
        val c1 = node(nbC, "page-1")
        val outgoing = mapOf(centre to listOf(link("l1", b2), link("l2", c1), link("l3", b1)))

        val result = LinkNeighbourhood.neighbours(centre, outgoing, pageOrder)

        assertEquals(listOf(b1, b2, c1), result.map { it.ref })
    }

    // --- radialLayout --------------------------------------------------------------------------

    @Test
    fun `radialLayout of zero is empty`() {
        assertTrue(radialLayout(0).isEmpty())
    }

    @Test
    fun `radialLayout of one places it at 12 o'clock`() {
        val positions = radialLayout(count = 1, radius = 0.3f)

        assertEquals(1, positions.size)
        assertOffset(0.5f, 0.2f, positions[0])
    }

    @Test
    fun `radialLayout of four is evenly spaced clockwise from 12 o'clock`() {
        val positions = radialLayout(count = 4, radius = 0.3f)

        assertEquals(4, positions.size)
        assertOffset(0.5f, 0.2f, positions[0]) // 12 o'clock
        assertOffset(0.8f, 0.5f, positions[1]) // 3 o'clock
        assertOffset(0.5f, 0.8f, positions[2]) // 6 o'clock
        assertOffset(0.2f, 0.5f, positions[3]) // 9 o'clock
    }

    private fun assertOffset(expectedX: Float, expectedY: Float, actual: Offset01, delta: Float = 1e-4f) {
        assertEquals(expectedX, actual.x, delta)
        assertEquals(expectedY, actual.y, delta)
    }
}
