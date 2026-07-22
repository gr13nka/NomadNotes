package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId

/**
 * Turns a [Page] into the single [Bitmap] the editor blits to its drawing surface, and keeps that
 * bitmap current as the page is edited.
 *
 * Rendering is layered for cheap incremental updates. Each layer is rasterised once into its own
 * cached bitmap; the composite is those caches blended over a white background, with the page's
 * stationery template (if any) blended in between the two. Because the caches survive between frames,
 * finishing a single stroke costs one stroke draw plus a re-blend of a handful of bitmaps
 * ([appendStroke]) rather than re-rasterising every stroke on the page.
 *
 * Two update paths, and the caller must pick the right one:
 *  - [appendStroke] — a stroke was just *added* on top of a layer; only that layer's cache and the
 *    composite change.
 *  - [renderFull] — anything else that alters the page (undo/redo, erase, layer add/remove/hide):
 *    the caches no longer match the page, so every layer is rebuilt from the page's strokes.
 *
 * Not thread-safe, and it owns native bitmap memory: drive it from one (UI) thread, [resize] it to
 * the surface before first use, and [release] it when the surface goes away.
 */
class PageRenderer {

    private val strokeRenderer = StrokeRenderer()

    // One cached bitmap per layer, keyed by id; composite order is not this map's order but [layers].
    private val layerBitmaps = HashMap<LayerId, Bitmap>()

    // The last rendered page's layers, in composite order with their visibility, so [appendStroke]
    // can re-blend without being handed the whole page again.
    private var layers: List<LayerSlot> = emptyList()

    private var compositeBitmap: Bitmap? = null

    // The stationery template composited above the white fill and below the ink layers (see
    // [recomposite]); null means a blank white page. Supplied by each [renderFull] call and only
    // referenced here — its bitmap is owned by the caller (a TemplateResolver), so this class never
    // recycles it.
    private var templateBitmap: Bitmap? = null

    private var width = 0
    private var height = 0

    /**
     * Sizes the renderer to the drawing surface. A no-op if the size is unchanged; otherwise the
     * layer caches are dropped — the caller must follow with [renderFull] to repopulate them — and
     * the composite is reset to white.
     */
    fun resize(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        disposeBitmaps()
        this.width = width
        this.height = height
        compositeBitmap = if (width > 0 && height > 0) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { Canvas(it).drawColor(Color.WHITE) }
        } else {
            null
        }
    }

    /**
     * Rebuilds every layer cache from [page] and re-blends the composite over [template] (the
     * page's resolved stationery, or null for a blank white page). Call this whenever the page's
     * strokes, layers, or template change; [appendStroke] is the one exception for a lone new stroke.
     *
     * [excludeStrokeIds] omits those strokes from every layer, so the composite shows the page *as
     * if* they were not there. The editor uses this to render the static base behind a live lasso
     * move — the moving strokes are drawn separately, translated, on top of that base. A later
     * [renderFull] with the default (empty) set restores them.
     */
    fun renderFull(page: Page, template: Bitmap?, excludeStrokeIds: Set<StrokeId> = emptySet()) {
        if (compositeBitmap == null) return
        templateBitmap = template
        dropCachesForRemovedLayers(page)
        for (layer in page.layers) {
            val bitmap = layerBitmaps.getOrPut(layer.id) { newLayerBitmap() }
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (stroke in strokesToDraw(layer.strokes, excludeStrokeIds)) strokeRenderer.draw(canvas, stroke)
        }
        layers = page.layers.map { LayerSlot(it.id, it.visible) }
        recomposite()
    }

    /**
     * Draws one just-added [stroke] into [layerId]'s cache and re-blends the composite, without
     * re-rasterising any other stroke. The layer must already have been rendered by a prior
     * [renderFull], and [stroke] must be the topmost stroke of that layer in the model — matching
     * this append-only draw over the existing ink.
     */
    fun appendStroke(layerId: LayerId, stroke: Stroke) {
        if (compositeBitmap == null) return
        val bitmap = layerBitmaps[layerId]
            ?: error("appendStroke on layer $layerId that renderFull has not rendered")
        strokeRenderer.draw(Canvas(bitmap), stroke)
        recomposite()
    }

    /** The current composited page. Valid once [resize] has been given a non-empty size. */
    fun composite(): Bitmap =
        compositeBitmap ?: error("composite() requested before resize() to a non-empty size")

    /** Recycles every cached bitmap. The renderer must be [resize]d again before reuse. */
    fun release() {
        disposeBitmaps()
        width = 0
        height = 0
    }

    private fun recomposite() {
        val canvas = Canvas(compositeBitmap ?: return)
        canvas.drawColor(Color.WHITE)
        templateBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        for (slot in layers) {
            if (!slot.visible) continue
            val bitmap = layerBitmaps[slot.id] ?: continue
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    private fun dropCachesForRemovedLayers(page: Page) {
        val present = page.layers.mapTo(HashSet()) { it.id }
        val iterator = layerBitmaps.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in present) {
                entry.value.recycle()
                iterator.remove()
            }
        }
    }

    private fun newLayerBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun disposeBitmaps() {
        layerBitmaps.values.forEach { it.recycle() }
        layerBitmaps.clear()
        layers = emptyList()
        // Only drop the reference: the template bitmap is the caller's to recycle (see the field).
        templateBitmap = null
        compositeBitmap?.recycle()
        compositeBitmap = null
    }

    private data class LayerSlot(val id: LayerId, val visible: Boolean)

    internal companion object {
        /**
         * The strokes [renderFull] rasterises for a layer: all of [strokes], minus any whose id is in
         * [excluded]. Split out as a pure function so the exclusion rule — the one piece of the
         * lasso-move base render that does not touch an Android graphics surface — is unit-testable
         * without Robolectric.
         */
        internal fun strokesToDraw(strokes: List<Stroke>, excluded: Set<StrokeId>): List<Stroke> =
            if (excluded.isEmpty()) strokes else strokes.filter { it.id !in excluded }
    }
}
