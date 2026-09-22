package com.nomadnotes.app.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nomadnotes.app.render.StickerRenderer
import com.nomadnotes.app.storage.LinkIndex
import com.nomadnotes.app.ui.editor.LinksMapNeighbour
import com.nomadnotes.app.ui.editor.LinksMapPanelState
import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.links.LinkNeighbourhood
import com.nomadnotes.core.links.NodeRef
import com.nomadnotes.core.links.radialLayout

/**
 * Owns the links map's own state across opens: the whole-disk [LinkIndex] it was last loaded with,
 * which page is centred (and the stack of past centres [back] unwinds), which chip is selected, and
 * a per-load cache of rendered sticker chip bitmaps (`StickerRenderer.toBitmap` rasterises, so it is
 * not cheap enough to call on every recomposition).
 *
 * `EditorActivity` owns the actual disk read, the same split it already uses for the link target
 * picker's notebook list: [beginLoad] flips [panelState]'s `loading` synchronously, so the panel
 * shows "loading" the instant it opens, and [finishLoad] is handed the result once the read
 * completes off the main thread. Every mutator here is a plain, synchronous state transition with no
 * Compose or coroutine awareness of its own — the caller re-reads [panelState] and turns a change
 * into a recomposition by bumping its own tick field, exactly as it already does for [StickerDraft].
 */
internal class LinksMapController {

    // Lazy: StickerRenderer's own StrokeRenderer eagerly builds android.graphics.Paint, which plain
    // JVM unit tests cannot construct (no Robolectric — see PageRendererTest). Every sticker-free
    // fixture never reaches bitmapFor below, so this is never forced, and LinksMapController stays
    // constructible in that environment.
    private val stickerRenderer by lazy { StickerRenderer() }

    private var index = LinkIndex(links = emptyMap(), pageIdsByNotebook = emptyMap(), notebookNames = emptyMap())
    private val centreStack = ArrayDeque<NodeRef>()
    private var selected: NodeRef? = null
    private var loading = false

    // Keyed by node rather than by sticker: cleared wholesale on every fresh [beginLoad], so a
    // sticker edited since the map was last open is never served stale from here.
    private val chipBitmaps = mutableMapOf<NodeRef, ImageBitmap>()

    /** The page currently centred, or null before the first [beginLoad] (nothing to show yet). */
    val centre: NodeRef? get() = centreStack.lastOrNull()

    /** A fresh load has started, centred on [centre] once [finishLoad] lands — the panel shows "loading". */
    fun beginLoad(centre: NodeRef) {
        loading = true
        centreStack.clear()
        centreStack.addLast(centre)
        selected = null
        chipBitmaps.clear()
    }

    /** The disk read [beginLoad] started has landed with [loaded]. */
    fun finishLoad(loaded: LinkIndex) {
        index = loaded
        loading = false
    }

    /** A chip tap: [ref] selects it; `null` (an empty-area tap) clears the selection. */
    fun select(ref: NodeRef?) {
        selected = ref
    }

    /** `[centre]`: re-anchors the map on [ref], pushing the old centre so [back] can return to it. */
    fun centreOn(ref: NodeRef) {
        centreStack.addLast(ref)
        selected = null
    }

    /** `[‹ back]`: pops to the previous centre. A no-op with nothing left to pop to. */
    fun back() {
        if (centreStack.size > 1) centreStack.removeLast()
        selected = null
    }

    /** The panel's whole state, derived fresh from [index]/[centreStack]/[selected] on every read. */
    fun panelState(): LinksMapPanelState {
        val centre = centre
        if (loading || centre == null) {
            return LinksMapPanelState(
                centreLabel = "",
                centreBitmap = null,
                neighbours = emptyList(),
                selected = null,
                canGoBack = centreStack.size > 1,
                loading = loading,
                isEmpty = false,
            )
        }
        val neighbours = LinkNeighbourhood.neighbours(centre, index.links, index::pageOrder)
        val positions = radialLayout(neighbours.size)
        val chips = neighbours.mapIndexed { i, neighbour ->
            val crossNotebook = neighbour.ref.notebookId != centre.notebookId
            LinksMapNeighbour(
                ref = neighbour.ref,
                position = positions[i],
                label = chipLabel(neighbour.ref, crossNotebook),
                linkCount = neighbour.linkCount,
                isCrossNotebook = crossNotebook,
                bitmap = neighbour.sticker?.let { bitmapFor(neighbour.ref, it) },
            )
        }
        return LinksMapPanelState(
            centreLabel = chipLabel(centre, crossNotebook = false),
            centreBitmap = centreSticker(centre)?.let { bitmapFor(centre, it) },
            neighbours = chips,
            selected = selected,
            canGoBack = centreStack.size > 1,
            loading = false,
            isEmpty = neighbours.isEmpty(),
        )
    }

    /** `#N` for a page in the centre's own notebook; `notebook #N` for [ref] in a different one. */
    private fun chipLabel(ref: NodeRef, crossNotebook: Boolean): String {
        val order = index.pageOrder(ref)
        val number = if (order >= 0) (order + 1).toString() else "?"
        return if (crossNotebook) "${index.notebookNames[ref.notebookId] ?: ref.notebookId.value} #$number" else "#$number"
    }

    /**
     * The centre's own display sticker. [LinkNeighbourhood] only names a page's sticker relative to
     * one of its neighbours (the sticker on the link between the two); the centre is nobody's
     * neighbour in its own query, so it has no such pair to draw from. It borrows the first sticker
     * among its own outgoing links instead — the same "this page's own link, first" preference
     * [LinkNeighbourhood.neighbours] already applies when naming everyone else.
     */
    private fun centreSticker(centre: NodeRef): LinkSticker? =
        index.links[centre].orEmpty().firstNotNullOfOrNull { it.sticker }

    private fun bitmapFor(ref: NodeRef, sticker: LinkSticker): ImageBitmap = chipBitmaps.getOrPut(ref) {
        stickerRenderer.toBitmap(sticker, CHIP_BITMAP_WIDTH_PX, CHIP_BITMAP_HEIGHT_PX).asImageBitmap()
    }

    private companion object {
        // A fixed pixel size for a chip's sticker thumbnail (2:1, matching LinkSticker's own ratio)
        // rather than one derived from screen density: the chip is small and the panel e-ink-coarse
        // enough that a fixed resolution reads fine at either the anchored or a future full-frame size.
        const val CHIP_BITMAP_WIDTH_PX = 192
        const val CHIP_BITMAP_HEIGHT_PX = 96
    }
}
