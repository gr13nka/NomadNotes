package com.nomadnotes.app.storage

import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.NotesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `loadNotebook adopts the directory name when the stored name has drifted`() {
        val created = storage.createNotebook("Correct")
        // Simulate a crash between renameNotebook's directory move and its JSON rewrite: the
        // directory is "Correct" but notebook.json still carries the pre-rename name.
        File(root, "Correct.nnote/notebook.json")
            .writeText(NotesJson.encodeNotebook(created.copy(name = "Stale")))

        val loaded = storage.loadNotebook("Correct")
        assertEquals("directory name wins over the stored name", "Correct", loaded.name)
        assertEquals("identity is preserved", created.id, loaded.id)

        // A later save must write back to the real directory, never resurrect a "Stale.nnote".
        storage.savePage(loaded, storage.loadPage(loaded, loaded.pageIds.first()))
        assertFalse("stale directory must not be created", File(root, "Stale.nnote").exists())
        assertTrue(File(root, "Correct.nnote/notebook.json").isFile)
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

    @Test
    fun `findNotebookById resolves by identity and survives a directory rename`() {
        val first = storage.createNotebook("First")
        val second = storage.createNotebook("Second")

        assertEquals(first.id, storage.findNotebookById(first.id)?.id)
        assertEquals("Second", storage.findNotebookById(second.id)?.name)

        // Simulate a user renaming the notebook by moving its directory on disk. The directory name
        // is authoritative on load, so the notebook's name changes to "Renamed" — but its id must
        // not, and findNotebookById keys on the id, so the target still resolves to this notebook.
        assertTrue(File(root, "First.nnote").renameTo(File(root, "Renamed.nnote")))

        val found = storage.findNotebookById(first.id)
        assertEquals("id still resolves after the directory rename", first.id, found?.id)
        assertEquals("and now points at the renamed notebook", "Renamed", found?.name)
    }

    @Test
    fun `findNotebookById returns null when no notebook has the id`() {
        storage.createNotebook("Only")
        assertNull(storage.findNotebookById(NotebookId.random()))
    }

    // --- image assets ---------------------------------------------------------------------------

    @Test
    fun `an imported image lands inside the notebook and is found by its ref`() {
        val notebook = storage.createNotebook("Journal")

        val ref = storage.importImage(notebook, "PNGBYTES".byteInputStream(), "png")

        val file = storage.imageFile(notebook, ref)
        assertEquals("PNGBYTES", file!!.readText())
        assertEquals(File(File(root, "Journal.nnote"), "images"), file.parentFile)
        assertTrue("the ref keeps the file type", ref.endsWith(".png"))
    }

    @Test
    fun `importing the same picture twice yields two independent assets`() {
        val notebook = storage.createNotebook("Journal")

        val first = storage.importImage(notebook, "one".byteInputStream(), "png")
        val second = storage.importImage(notebook, "two".byteInputStream(), "png")

        assertNotEquals(first, second)
        assertEquals("one", storage.imageFile(notebook, first)!!.readText())
        assertEquals("two", storage.imageFile(notebook, second)!!.readText())
    }

    @Test
    fun `an image ref that no longer has a file resolves to null`() {
        val notebook = storage.createNotebook("Journal")
        val ref = storage.importImage(notebook, "bytes".byteInputStream(), "png")

        assertTrue(storage.imageFile(notebook, ref)!!.delete())

        assertNull(storage.imageFile(notebook, ref))
    }

    @Test
    fun `an image ref cannot reach outside the notebook`() {
        val notebook = storage.createNotebook("Journal")
        File(root, "secret.png").writeText("not yours")

        assertNull(storage.imageFile(notebook, "../../secret.png"))
        assertNull(storage.imageFile(notebook, "..\\secret.png"))
        assertNull(storage.imageFile(notebook, ".."))
        assertNull(storage.imageFile(notebook, ""))
    }

    @Test
    fun `deleting a notebook takes its images with it`() {
        val notebook = storage.createNotebook("Journal")
        storage.importImage(notebook, "bytes".byteInputStream(), "png")
        val imagesDir = storage.imagesDir(notebook)
        assertTrue(imagesDir.isDirectory)

        storage.deleteNotebook("Journal")

        assertFalse(imagesDir.exists())
    }

    @Test
    fun `an import leaves no temp file behind`() {
        val notebook = storage.createNotebook("Journal")
        storage.importImage(notebook, "bytes".byteInputStream(), "png")

        val leftovers = storage.imagesDir(notebook).listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("stray temp files: $leftovers", leftovers.isEmpty())
    }
}
