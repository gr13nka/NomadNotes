package com.nomadnotes.core.links

import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import com.nomadnotes.core.PageLink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One page's identity in the link graph: which notebook it belongs to and its id there. */
data class NodeRef(val notebookId: NotebookId, val pageId: PageId)

/**
 * One node connected to the centre of a [LinkNeighbourhood.neighbours] query, carrying everything
 * its chip needs to draw itself without a further lookup: its display sticker (null falls back to
 * `#N`) and how many neighbours *it* has, for the chip's "N links" caption.
 */
data class Neighbour(val ref: NodeRef, val sticker: LinkSticker?, val linkCount: Int)

/**
 * The pure graph behind the links map.
 *
 * [neighbours]'s only input is an index of each scanned page's *outgoing* links, because that is
 * all the storage layer can cheaply build without following every link on every page (see
 * `NotebookStorage.loadAllPageLinks` in :app). Treating a link as a connection in both
 * directions, and folding parallel links between the same two pages into one neighbour, both
 * happen here rather than in that index.
 */
object LinkNeighbourhood {

    /**
     * The nodes connected to [centre]: [centre] links to them, they link to [centre], or both —
     * counted once either way. A link whose target is [centre] itself is not a neighbour.
     *
     * A neighbour's display sticker is the first non-null sticker among [centre]'s own links to
     * it; if none of those carry one, the first non-null sticker among its links back to
     * [centre]; otherwise null.
     *
     * Ordered with [centre]'s own notebook first, by [pageOrder]; the other notebooks follow,
     * grouped by [NotebookId.value] and ordered by [pageOrder] within each group.
     */
    fun neighbours(
        centre: NodeRef,
        outgoing: Map<NodeRef, List<PageLink>>,
        pageOrder: (NodeRef) -> Int,
    ): List<Neighbour> {
        return distinctNeighbours(centre, outgoing)
            .sortedWith(
                compareBy(
                    { it.notebookId != centre.notebookId },
                    { it.notebookId.value },
                    { pageOrder(it) },
                ),
            )
            .map { ref ->
                Neighbour(
                    ref = ref,
                    sticker = stickerBetween(centre, ref, outgoing),
                    linkCount = distinctNeighbours(ref, outgoing).size,
                )
            }
    }

    /** The distinct nodes linked to [node] in either direction; [node] itself is never included. */
    private fun distinctNeighbours(node: NodeRef, outgoing: Map<NodeRef, List<PageLink>>): Set<NodeRef> {
        val forward = outgoing[node].orEmpty().map { it.targetRef() }
        val backward = outgoing.filterValues { links -> links.any { it.targetRef() == node } }.keys
        return (forward + backward).filterTo(mutableSetOf()) { it != node }
    }

    /** [centre]'s sticker for its links to [neighbour] if any carry one, else [neighbour]'s back. */
    private fun stickerBetween(
        centre: NodeRef,
        neighbour: NodeRef,
        outgoing: Map<NodeRef, List<PageLink>>,
    ): LinkSticker? {
        val toNeighbour = outgoing[centre].orEmpty()
            .filter { it.targetRef() == neighbour }
            .firstNotNullOfOrNull { it.sticker }
        if (toNeighbour != null) return toNeighbour
        return outgoing[neighbour].orEmpty()
            .filter { it.targetRef() == centre }
            .firstNotNullOfOrNull { it.sticker }
    }

    private fun PageLink.targetRef(): NodeRef = NodeRef(targetNotebookId, targetPageId)
}

/** A position in the unit square [0,1] x [0,1] that a layout scales to its own pixel size. */
data class Offset01(val x: Float, val y: Float)

/**
 * [count] points spaced evenly on a ring of [radius] around the centre of the unit square
 * (0.5, 0.5), starting at 12 o'clock and proceeding clockwise — the arrangement the links-map
 * mockup uses for a page's neighbour chips. Empty for a non-positive [count].
 */
fun radialLayout(count: Int, radius: Float = 0.34f): List<Offset01> {
    if (count <= 0) return emptyList()
    val step = 2.0 * PI / count
    return List(count) { i ->
        // Angle 0 is 3 o'clock in standard math convention; -PI/2 rotates the start to 12 o'clock,
        // and increasing angle turns clockwise because the y axis (like page pixels) points down.
        val angle = -PI / 2.0 + step * i
        Offset01(
            x = 0.5f + (radius * cos(angle)).toFloat(),
            y = 0.5f + (radius * sin(angle)).toFloat(),
        )
    }
}
