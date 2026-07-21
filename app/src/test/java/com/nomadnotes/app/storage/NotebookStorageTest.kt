package com.nomadnotes.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises [NotebookStorage] as a pure JVM unit (no Android): the create/list/load contract, the
 * atomicity of saves, corruption handling, name validation, and rename/delete.
 */
class NotebookStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var storage: NotebookStorage

    @Before
    fun setUp() {
        root = temp.newFolder("root")
        storage = NotebookStorage(root)
    }

    @Test
    fun `construction prepares the templates directory`() {
        assertTrue(File(root, "templates").isDirectory)
    }

    @Test
    fun `create then list then load round-trips a notebook and its first page`() {
        val created = storage.createNotebook("Journal")
        assertEquals("a new notebook has exactly one page", 1, created.pageIds.size)

        assertEquals(listOf("Journal"), storage.listNotebooks().map { it.name })

        val loaded = storage.loadNotebook("Journal")
        assertEquals(created, loaded)

        val page = storage.loadPage(loaded, loaded.pageIds.first())
        assertEquals(loaded.pageIds.first(), page.id)
    }

    @Test
    fun `savePage is atomic - no temp file remains and the target parses back`() {
        val nb = storage.createNotebook("Notes")
        val page = storage.loadPage(nb, nb.pageIds.first())
        val edited = page.copy(templateRef = "grid-5mm")

        storage.savePage(nb, edited)

        val pageFile = File(File(root, "Notes.nnote/pages"), "${page.id.value}.json")
        assertTrue("target page file exists", pageFile.isFile)
        assertFalse(
            "temp file must be gone after an atomic save",
            File(pageFile.parentFile, "${pageFile.name}.tmp").exists(),
        )
        assertEquals("target parses back to the saved page", edited, storage.loadPage(nb, page.id))
    }

    @Test
    fun `loadNotebook on corrupt json throws StorageException naming the file`() {
        storage.createNotebook("Broken")
        val notebookFile = File(root, "Broken.nnote/notebook.json")
        notebookFile.writeText("{ this is not valid json ")

        val ex = assertThrows(StorageException::class.java) { storage.loadNotebook("Broken") }
        assertTrue("message names the offending file", ex.message!!.contains(notebookFile.path))
    }

    @Test
    fun `loadPage on corrupt json throws StorageException`() {
        val nb = storage.createNotebook("P")
        val pageId = nb.pageIds.first()
        File(root, "P.nnote/pages/${pageId.value}.json").writeText("garbage, not json")

        assertThrows(StorageException::class.java) { storage.loadPage(nb, pageId) }
    }

    @Test
    fun `loadNotebook on a missing notebook throws StorageException`() {
        assertThrows(StorageException::class.java) { storage.loadNotebook("Ghost") }
    }

    @Test
    fun `createNotebook rejects a duplicate name with StorageException`() {
        storage.createNotebook("Dup")
        assertThrows(StorageException::class.java) { storage.createNotebook("Dup") }
    }

    @Test
    fun `invalid names are rejected before any file is touched`() {
        val illegal = listOf(
            "", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b",
            ".hidden", "trailing.", " leading", "trailing ", ".", "..",
        )
        for (name in illegal) {
            assertThrows(
                "expected rejection for \"$name\"",
                IllegalArgumentException::class.java,
            ) { storage.createNotebook(name) }
        }
    }

    @Test
    fun `ordinary names are accepted`() {
        val legal = listOf("Journal", "My Notes 2026", "a.b.c", "notes-01")
        for (name in legal) storage.createNotebook(name)
        assertEquals(legal.sorted(), storage.listNotebooks().map { it.name })
    }

    @Test
    fun `renameNotebook moves the directory, syncs the stored name, and keeps identity`() {
        val original = storage.createNotebook("Old")

        storage.renameNotebook("Old", "New")

        assertFalse("old directory is gone", File(root, "Old.nnote").exists())
        assertTrue("new directory exists", File(root, "New.nnote").isDirectory)
        assertEquals(listOf("New"), storage.listNotebooks().map { it.name })

        val renamed = storage.loadNotebook("New")
        assertEquals("stored name follows the directory", "New", renamed.name)
        assertEquals("identity and pages are preserved", original.id, renamed.id)
        assertEquals(original.pageIds, renamed.pageIds)
    }

    @Test
    fun `renameNotebook onto an existing name throws StorageException`() {
        storage.createNotebook("A")
        storage.createNotebook("B")
        assertThrows(StorageException::class.java) { storage.renameNotebook("A", "B") }
    }

    @Test
    fun `renameNotebook of a missing notebook throws StorageException`() {
        assertThrows(StorageException::class.java) { storage.renameNotebook("Nope", "Whatever") }
    }

    @Test
    fun `deleteNotebook removes the directory and its pages`() {
        storage.createNotebook("Temp")
        storage.deleteNotebook("Temp")

        assertFalse(File(root, "Temp.nnote").exists())
        assertTrue(storage.listNotebooks().isEmpty())
    }

    @Test
    fun `deleteNotebook of a missing notebook throws StorageException`() {
        assertThrows(StorageException::class.java) { storage.deleteNotebook("Nope") }
    }

    @Test
    fun `listNotebooks skips nnote directories without a notebook json`() {
        storage.createNotebook("Real")
        File(root, "Stray.nnote").mkdirs() // a .nnote directory with no notebook.json

        assertEquals(listOf("Real"), storage.listNotebooks().map { it.name })
    }
}
