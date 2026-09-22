package com.nomadnotes.core.recent

import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import kotlinx.serialization.Serializable

/**
 * One visit to a page: which notebook, which page, and when.
 *
 * Deliberately thin: no name and no page index are stored. A name would go stale the moment the
 * notebook is renamed, and an index would go stale the moment a page is inserted, deleted, or
 * reordered ahead of it — both are cheap to re-derive from the notebook's own current state
 * ([lastPageOf]), so storing them here would just be another place for them to drift out of sync.
 */
@Serializable
data class RecentVisit(
    val notebookId: NotebookId,
    val pageId: PageId,
    val visitedAtEpochMs: Long,
)
