package com.nomadnotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single page of a [Notebook]: an optional background template plus the layers drawn on it.
 *
 * Page coordinates are the device pixels of the drawing surface (on the target Go 10.3 panel,
 * 1860 wide by 2418 high). Those dimensions are the *context* a stroke's `x`/`y` are measured
 * in; they are deliberately not stored here, because the panel size is a device fact, not page
 * data.
 *
 * Persistence: one page is stored as one JSON file (see [NotesJson]). A page is created via
 * [create] so that the mainLayer/mainLayerId invariant below always holds.
 *
 * @property id stable identity, listed in the owning notebook's `pageIds`.
 * @property templateRef reference to the background template (a stationery ruling/grid), or
 *   null for a blank page. Resolved to actual pixels by the rendering layer, not here.
 * @property layers the page's layers in composite order (index 0 is drawn first, at the back).
 *   Holds the main layer plus up to four extra layers.
 * @property mainLayerId the layer new strokes land on by default; always the id of a layer
 *   present in [layers], and that layer cannot be removed.
 * @property formatVersion on-disk schema version of this page, for migrations.
 * @property extra forward-compatibility escape hatch for page-level fields written by a newer
 *   app version; preserved verbatim across a decode/encode roundtrip. See [Stroke.extra].
 */
@Serializable
data class Page(
    val id: PageId,
    val templateRef: String? = null,
    val layers: List<Layer>,
    val mainLayerId: LayerId,
    val formatVersion: Int = 1,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    companion object {
        /**
         * A fresh blank page: no background template and a single visible "Main" layer that is
         * set as [mainLayerId]. Extra layers are added later via the editing session.
         */
        fun create(): Page {
            val main = Layer(id = LayerId.random(), name = "Main", strokes = emptyList())
            return Page(id = PageId.random(), layers = listOf(main), mainLayerId = main.id)
        }
    }
}
