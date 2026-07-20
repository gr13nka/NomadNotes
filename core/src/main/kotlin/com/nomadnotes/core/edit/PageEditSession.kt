package com.nomadnotes.core.edit

import com.nomadnotes.core.Layer
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId

/**
 * A single page's editing surface with undo/redo.
 *
 * The session owns the page being edited: [page] is replaced wholesale by a new immutable copy
 * on every mutation, never modified in place, so the previous value stays valid for undo. The
 * public methods are intention-shaped (add a stroke, erase strokes, move a selection, manage
 * layers) rather than exposing the underlying edit commands.
 *
 * Undo/redo contract:
 *  - Each mutating call that actually changes the page pushes one undo entry; a call that would
 *    change nothing (moving by zero, erasing ids that are absent, setting the visibility a layer
 *    already has) is a no-op and leaves the history — including the redo stack — untouched.
 *  - Any change clears the redo stack: once you edit after undoing, the undone future is gone.
 *  - The undo history is capped at [MAX_UNDO]; pushing past the cap discards the oldest entry,
 *    so only the most recent [MAX_UNDO] changes can be undone.
 *
 * Not thread-safe: drive one session from a single (UI) thread.
 */
class PageEditSession(initialPage: Page) {

    var page: Page = initialPage
        private set

    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Appends [stroke] on top of the given layer. Throws if the layer is not on the page. */
    fun addStroke(layerId: LayerId, stroke: Stroke) {
        val layer = page.layerOrThrow(layerId)
        commit(InsertStrokes(layerId, listOf(PositionedStroke(layer.strokes.size, stroke))))
    }

    /**
     * Removes the strokes with the given [ids] from the layer, keeping the rest in place. Ids
     * that are not on the layer are ignored; if none match, this is a no-op.
     */
    fun eraseStrokes(layerId: LayerId, ids: Collection<StrokeId>) {
        val layer = page.layerOrThrow(layerId)
        val targets = ids.toSet()
        val present = layer.strokes.filter { it.id in targets }.map { it.id }
        if (present.isEmpty()) return
        commit(RemoveStrokes(layerId, present))
    }

    /**
     * Shifts the strokes with the given [ids] on the layer by ([dx], [dy]) page pixels. Ids not
     * on the layer are ignored; a zero translation, or one matching no strokes, is a no-op.
     */
    fun translateStrokes(layerId: LayerId, ids: Collection<StrokeId>, dx: Float, dy: Float) {
        val layer = page.layerOrThrow(layerId)
        if (dx == 0f && dy == 0f) return
        val targets = ids.toSet()
        val moved = layer.strokes.filter { it.id in targets }.map { it.translate(dx, dy) }
        if (moved.isEmpty()) return
        commit(ReplaceStrokes(layerId, moved))
    }

    /**
     * Adds a new empty layer named [name] on top of the stack. Returns false without changing
     * anything when the page already holds [MAX_LAYERS] layers.
     */
    fun addLayer(name: String): Boolean {
        if (page.layers.size >= MAX_LAYERS) return false
        val layer = Layer(id = LayerId.random(), name = name, strokes = emptyList())
        commit(InsertLayer(page.layers.size, layer))
        return true
    }

    /**
     * Removes the layer with [layerId] and its strokes. Returns false without changing anything
     * when [layerId] is the page's main layer (which is never removable) or is not on the page.
     */
    fun removeLayer(layerId: LayerId): Boolean {
        if (layerId == page.mainLayerId) return false
        if (page.layers.none { it.id == layerId }) return false
        commit(RemoveLayer(layerId))
        return true
    }

    /** Shows or hides the layer. No-op if it already has that visibility. Throws if absent. */
    fun setLayerVisible(layerId: LayerId, visible: Boolean) {
        val layer = page.layerOrThrow(layerId)
        if (layer.visible == visible) return
        commit(SetLayerVisible(layerId, visible))
    }

    /** Reverts the most recent change. Returns false when there is nothing to undo. */
    fun undo(): Boolean {
        val command = undoStack.removeFirstOrNull() ?: return false
        val applied = command.applyTo(page)
        page = applied.page
        redoStack.addFirst(applied.inverse)
        return true
    }

    /** Re-applies the most recently undone change. Returns false when there is none. */
    fun redo(): Boolean {
        val command = redoStack.removeFirstOrNull() ?: return false
        val applied = command.applyTo(page)
        page = applied.page
        pushUndo(applied.inverse)
        return true
    }

    private fun commit(command: EditCommand) {
        val applied = command.applyTo(page)
        page = applied.page
        pushUndo(applied.inverse)
        redoStack.clear()
    }

    private fun pushUndo(command: EditCommand) {
        undoStack.addFirst(command)
        while (undoStack.size > MAX_UNDO) undoStack.removeLast()
    }

    companion object {
        /** Maximum layers per page: the main layer plus up to four more. */
        const val MAX_LAYERS: Int = 5

        /** Depth of the undo history; older changes fall off the end. */
        const val MAX_UNDO: Int = 100
    }
}
