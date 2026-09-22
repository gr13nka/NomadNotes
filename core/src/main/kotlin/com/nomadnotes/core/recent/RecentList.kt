package com.nomadnotes.core.recent

import com.nomadnotes.core.Notebook
import com.nomadnotes.core.PageId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Moves [visit] to the front of [visits] — most-recent-first — dropping any earlier visit to the
 * same page (a page appears at most once, at its latest visit time) and capping the result at
 * [maxSize], so the list cannot grow without bound over a long-lived install.
 */
fun recordVisit(visits: List<RecentVisit>, visit: RecentVisit, maxSize: Int = 50): List<RecentVisit> =
    (listOf(visit) + visits.filterNot { it.pageId == visit.pageId }).take(maxSize)

/**
 * The page [notebook] was last open on: the most recent visit in [visits] against this notebook
 * whose page still exists in [notebook]'s current [Notebook.pageIds]. A visit to a page since
 * deleted or moved to another notebook is skipped rather than returned, so a caller can jump
 * straight to the result without checking it first; null when [notebook] has never been visited
 * (or every visit's page is now gone).
 *
 * No page index is looked at or returned — the caller derives that from where [PageId] now sits in
 * [Notebook.pageIds], which is also how a deleted-and-reinserted or reordered page is handled for
 * free.
 */
fun lastPageOf(visits: List<RecentVisit>, notebook: Notebook): PageId? =
    visits.firstOrNull { it.notebookId == notebook.id && it.pageId in notebook.pageIds }?.pageId

/**
 * The same [Json] shape [com.nomadnotes.core.NotesJson] uses for every other stored file: unknown
 * keys tolerated, defaults written explicitly.
 */
private val recentVisitsFormat = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeRecentVisits(visits: List<RecentVisit>): String = recentVisitsFormat.encodeToString(visits)

/**
 * Decodes a stored recent-visits list. Unlike [com.nomadnotes.core.NotesJson]'s decoders, malformed
 * or blank [text] decodes to an empty list rather than throwing: this file is a cache of where the
 * user recently was, not user data, so corruption should cost "reopen at the first page instead of
 * the last one" — never stop the app from opening a notebook at all.
 */
fun decodeRecentVisits(text: String): List<RecentVisit> {
    if (text.isBlank()) return emptyList()
    return try {
        recentVisitsFormat.decodeFromString(text)
    } catch (e: SerializationException) {
        emptyList()
    }
}
