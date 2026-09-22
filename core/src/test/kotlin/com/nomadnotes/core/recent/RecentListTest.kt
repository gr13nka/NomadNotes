package com.nomadnotes.core.recent

import com.nomadnotes.core.Notebook
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [recordVisit]/[lastPageOf] (the pure recent-visits transforms) and their
 * JSON encode/decode pair.
 */
class RecentListTest {

    private val notebookA = NotebookId("nb-a")
    private val notebookB = NotebookId("nb-b")

    private fun visit(notebookId: NotebookId, pageId: String, atMs: Long) =
        RecentVisit(notebookId, PageId(pageId), atMs)

    private fun notebookOf(id: NotebookId, vararg pageIds: String) =
        Notebook(id = id, name = "N", pageIds = pageIds.map { PageId(it) }, createdAtEpochMs = 0L)

    // --- recordVisit -------------------------------------------------------------------------

    @Test
    fun `a fresh visit is added to the front`() {
        val visits = recordVisit(emptyList(), visit(notebookA, "p1", 100L))
        assertEquals(listOf(visit(notebookA, "p1", 100L)), visits)
    }

    @Test
    fun `revisiting a page moves it to the front instead of duplicating it`() {
        val existing = listOf(visit(notebookA, "p1", 100L), visit(notebookA, "p2", 90L))
        val updated = recordVisit(existing, visit(notebookA, "p2", 200L))
        assertEquals(
            listOf(visit(notebookA, "p2", 200L), visit(notebookA, "p1", 100L)),
            updated,
        )
    }

    @Test
    fun `the list is capped at maxSize, dropping the oldest`() {
        val existing = (0 until 5).map { visit(notebookA, "p$it", it.toLong()) }
        val updated = recordVisit(existing, visit(notebookA, "new", 99L), maxSize = 3)
        assertEquals(3, updated.size)
        assertEquals(PageId("new"), updated[0].pageId)
        // p0..p2 are the three most recently added of the five existing entries (list is already
        // most-recent-first, so the front of `existing` survives the cap).
        assertEquals(listOf(PageId("new"), PageId("p0"), PageId("p1")), updated.map { it.pageId })
    }

    // --- lastPageOf ----------------------------------------------------------------------------

    @Test
    fun `lastPageOf returns the most recent visit whose page still exists`() {
        val notebook = notebookOf(notebookA, "p1", "p2")
        val visits = listOf(visit(notebookA, "p2", 200L), visit(notebookA, "p1", 100L))
        assertEquals(PageId("p2"), lastPageOf(visits, notebook))
    }

    @Test
    fun `lastPageOf falls back past a visit whose page was deleted`() {
        val notebook = notebookOf(notebookA, "p1") // p2 no longer exists
        val visits = listOf(visit(notebookA, "p2", 200L), visit(notebookA, "p1", 100L))
        assertEquals(PageId("p1"), lastPageOf(visits, notebook))
    }

    @Test
    fun `lastPageOf ignores visits to another notebook`() {
        val notebook = notebookOf(notebookA, "p1")
        val visits = listOf(visit(notebookB, "p1", 999L))
        assertNull(lastPageOf(visits, notebook))
    }

    @Test
    fun `lastPageOf returns null when nothing matches`() {
        val notebook = notebookOf(notebookA, "p1")
        assertNull(lastPageOf(emptyList(), notebook))
    }

    // --- JSON --------------------------------------------------------------------------------

    @Test
    fun `a visit list survives an encode-decode roundtrip`() {
        val visits = listOf(visit(notebookA, "p1", 100L), visit(notebookB, "p2", 200L))
        assertEquals(visits, decodeRecentVisits(encodeRecentVisits(visits)))
    }

    @Test
    fun `malformed JSON decodes to an empty list rather than throwing`() {
        assertTrue(decodeRecentVisits("{ not valid").isEmpty())
    }

    @Test
    fun `empty text decodes to an empty list`() {
        assertTrue(decodeRecentVisits("").isEmpty())
    }
}
