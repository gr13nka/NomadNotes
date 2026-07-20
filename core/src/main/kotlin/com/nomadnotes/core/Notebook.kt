package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * A notebook: an ordered collection of pages.
 *
 * The notebook owns page *order and identity* only — it lists [PageId]s rather than embedding
 * [Page]s — because each page is persisted as its own file (see [NotesJson]). Loading a
 * notebook is therefore cheap; a page's strokes are read on demand.
 *
 * @property id stable identity.
 * @property name user-facing title.
 * @property pageIds the notebook's pages in reading order.
 * @property createdAtEpochMs creation time, milliseconds since the Unix epoch.
 * @property formatVersion on-disk schema version of this notebook, for migrations.
 */
@Serializable
data class Notebook(
    val id: NotebookId,
    val name: String,
    val pageIds: List<PageId>,
    val createdAtEpochMs: Long,
    val formatVersion: Int = 1,
) {
    companion object {
        /** A new, empty notebook stamped with the current wall-clock time. */
        fun create(name: String): Notebook = Notebook(
            id = NotebookId.random(),
            name = name,
            pageIds = emptyList(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }
}
