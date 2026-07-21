package com.nomadnotes.core.edit

import com.nomadnotes.core.Layer
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint

/**
 * A reversible primitive edit to a [Page].
 *
 * The design choice that makes undo simple: applying a command does not just produce the new
 * page, it also produces the command that would put the page back. [PageEditSession] never has
 * to compute an inverse itself or snapshot whole pages — it keeps the returned inverse, and
 * because that inverse is itself an [EditCommand], applying *it* yields the redo command, and so
 * on. The inverse is built from the page as it actually was at apply time, which is why an
 * erase can restore its strokes to their exact former positions.
 *
 * The primitives come in mutually-inverse pairs — insert/remove for strokes and for layers —
 * plus two self-inverse replacements (a stroke swap for moves, a visibility toggle), which is
 * enough to express every public editing intention.
 */
internal sealed interface EditCommand {
    fun applyTo(page: Page): Applied
}

/** The result of applying an [EditCommand]: the new page and the command that reverses it. */
internal class Applied(val page: Page, val inverse: EditCommand)

/** A stroke together with the index it occupies in its layer, used to restore paint order. */
internal class PositionedStroke(val index: Int, val stroke: Stroke)

/** Inserts strokes at fixed indices in a layer; inverse of [RemoveStrokes]. */
internal class InsertStrokes(
    private val layerId: LayerId,
    private val entries: List<PositionedStroke>,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val layer = page.layerOrThrow(layerId)
        val strokes = layer.strokes.toMutableList()
        // Ascending index order so each stored index lands at its intended final position.
        for (entry in entries.sortedBy { it.index }) {
            strokes.add(entry.index, entry.stroke)
        }
        val newPage = page.withLayer(layer.copy(strokes = strokes))
        return Applied(newPage, RemoveStrokes(layerId, entries.map { it.stroke.id }))
    }
}

/** Removes strokes by id from a layer; inverse restores them where they were ([InsertStrokes]). */
internal class RemoveStrokes(
    private val layerId: LayerId,
    private val ids: List<StrokeId>,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val layer = page.layerOrThrow(layerId)
        val targets = ids.toSet()
        val removed = layer.strokes.mapIndexedNotNull { index, stroke ->
            if (stroke.id in targets) PositionedStroke(index, stroke) else null
        }
        val kept = layer.strokes.filter { it.id !in targets }
        val newPage = page.withLayer(layer.copy(strokes = kept))
        return Applied(newPage, InsertStrokes(layerId, removed))
    }
}

/**
 * Swaps strokes for new versions with the same ids, keeping their positions. Used for moves;
 * self-inverse, capturing the pre-swap strokes so undo restores them exactly.
 */
internal class ReplaceStrokes(
    private val layerId: LayerId,
    private val replacements: List<Stroke>,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val layer = page.layerOrThrow(layerId)
        val byId = replacements.associateBy { it.id }
        val previous = mutableListOf<Stroke>()
        val swapped = layer.strokes.map { stroke ->
            val replacement = byId[stroke.id]
            if (replacement != null) {
                previous.add(stroke)
                replacement
            } else {
                stroke
            }
        }
        val newPage = page.withLayer(layer.copy(strokes = swapped))
        return Applied(newPage, ReplaceStrokes(layerId, previous))
    }
}

/** Inserts a layer at a fixed index; inverse of [RemoveLayer]. */
internal class InsertLayer(
    private val index: Int,
    private val layer: Layer,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val layers = page.layers.toMutableList().apply { add(index, layer) }
        return Applied(page.copy(layers = layers), RemoveLayer(layer.id))
    }
}

/** Removes a layer by id; inverse restores it at its former index ([InsertLayer]). */
internal class RemoveLayer(
    private val layerId: LayerId,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val index = page.layers.indexOfFirst { it.id == layerId }
        require(index >= 0) { "No layer $layerId on page ${page.id}" }
        val layer = page.layers[index]
        val layers = page.layers.toMutableList().apply { removeAt(index) }
        return Applied(page.copy(layers = layers), InsertLayer(index, layer))
    }
}

/** Sets a layer's visibility; self-inverse, capturing the prior value. */
internal class SetLayerVisible(
    private val layerId: LayerId,
    private val visible: Boolean,
) : EditCommand {
    override fun applyTo(page: Page): Applied {
        val index = page.layers.indexOfFirst { it.id == layerId }
        require(index >= 0) { "No layer $layerId on page ${page.id}" }
        val layer = page.layers[index]
        val layers = page.layers.toMutableList().also { it[index] = layer.copy(visible = visible) }
        return Applied(page.copy(layers = layers), SetLayerVisible(layerId, layer.visible))
    }
}

/** Sets a page's background template ref; self-inverse, capturing the prior ref. */
internal class SetTemplateRef(
    private val templateRef: String?,
) : EditCommand {
    override fun applyTo(page: Page): Applied =
        Applied(page.copy(templateRef = templateRef), SetTemplateRef(page.templateRef))
}

/** Returns the layer with [id], or throws — the id came from the page, so absence is a bug. */
internal fun Page.layerOrThrow(id: LayerId): Layer =
    layers.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No layer $id on page ${this.id}")

/** Returns a copy of the page with [layer] substituted for the layer sharing its id. */
internal fun Page.withLayer(layer: Layer): Page =
    copy(layers = layers.map { if (it.id == layer.id) layer else it })

/** Returns a copy of the stroke shifted by ([dx], [dy]) page pixels. */
internal fun Stroke.translate(dx: Float, dy: Float): Stroke =
    copy(points = points.map { it.shifted(dx, dy) })

private fun StrokePoint.shifted(dx: Float, dy: Float): StrokePoint =
    copy(x = x + dx, y = y + dy)
