package com.nomadnotes.app.storage

import com.nomadnotes.core.Notebook
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.NotesFormatException
import com.nomadnotes.core.NotesJson
import com.nomadnotes.core.Page
import com.nomadnotes.core.PageId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A notebook found on disk: its [name] (its directory name without `.nnote`) and the [dir] holding it. */
data class NotebookRef(val name: String, val dir: File)

/**
 * A storage operation failed in a way the caller must handle: a file was missing, could not be
 * read or written, or held JSON this version cannot decode. The message carries the offending
 * file or directory path so callers can surface it.
 *
 * An *invalid notebook name* is a separate, caller-preventable programming error and throws
 * [IllegalArgumentException], not this — so the editor UI can tell "fix your name" apart from
 * "the disk failed".
 */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The on-disk home of every notebook: turns the note model into a folder tree and back.
 *
 * ### Format
 * ```
 * <root>/
 *   templates/                 stationery templates (empty for now; used in step 5)
 *   <name>.nnote/              one notebook == one directory
 *     notebook.json            the Notebook (page order + metadata)
 *     pages/<page-id>.json     one file per Page, so a page's strokes load on demand
 * ```
 *
 * ### Name is directory
 * A notebook's name and its directory name are the same string and are kept in step: [listNotebooks]
 * reports the name straight from the directory (cheap — it need not open notebook.json), [createNotebook]
 * derives the directory from the name, and [renameNotebook] moves the directory and rewrites the stored
 * name together. Treat the directory name as the authoritative name.
 *
 * ### Durability
 * Every write is atomic ([writeAtomically]): the bytes go to a sibling `*.tmp` file which is then
 * renamed over the target. A crash mid-write can only ever leave a stray temp file; the previous
 * good file is never truncated, because the rename either fully replaces it or does not happen.
 *
 * ### Threading
 * All methods are synchronous and do blocking I/O; callers dispatch them off the main thread. The
 * class holds no mutable state, so separate instances over the same root are safe, but a single
 * file must not be written concurrently (callers serialise saves of the same page).
 *
 * Takes a plain [rootDir] rather than an Android `Context` so it is a pure JVM unit under test; the
 * `Context`-dependent choice of where that root lives is [notebooksRoot]'s job.
 */
class NotebookStorage(private val rootDir: File) {

    init {
        // Best-effort layout preparation so later operations can assume the root and templates dir
        // exist. If creation fails (e.g. the permission was revoked after the root was resolved),
        // the first real operation surfaces a precise StorageException instead of failing here.
        rootDir.mkdirs()
        File(rootDir, TEMPLATES_DIR).mkdirs()
    }

    /** The directory holding user stationery images (`<root>/templates/`), shared across notebooks. */
    val templatesDir: File get() = File(rootDir, TEMPLATES_DIR)

    /** Every notebook on disk, sorted by name. A `*.nnote` directory without a `notebook.json` is skipped. */
    fun listNotebooks(): List<NotebookRef> {
        val dirs = rootDir.listFiles { f -> f.isDirectory && f.name.endsWith(NOTEBOOK_SUFFIX) }
            ?: return emptyList()
        return dirs
            .filter { File(it, NOTEBOOK_FILE).isFile }
            .map { NotebookRef(name = it.name.removeSuffix(NOTEBOOK_SUFFIX), dir = it) }
            .sortedBy { it.name }
    }

    /**
     * Creates a new notebook: its directory, an empty `pages/`, one blank first page, and both JSON
     * files. Returns the [Notebook] (with its first page listed). Throws [StorageException] if a
     * notebook of that name already exists, or [IllegalArgumentException] if the name is invalid.
     */
    fun createNotebook(name: String): Notebook {
        val dir = notebookDir(validateName(name))
        if (dir.exists()) throw StorageException("Notebook already exists: ${dir.path}")

        val firstPage = Page.create()
        val notebook = Notebook.create(name).copy(pageIds = listOf(firstPage.id))
        if (!File(dir, PAGES_DIR).mkdirs()) {
            throw StorageException("Could not create notebook directory: ${dir.path}")
        }
        saveNotebook(notebook)
        savePage(notebook, firstPage)
        return notebook
    }

    /** Loads a notebook's metadata. Throws [StorageException] if it is missing or corrupt. */
    fun loadNotebook(name: String): Notebook {
        val dirName = validateName(name)
        val notebook = readAndDecode(File(notebookDir(dirName), NOTEBOOK_FILE), "notebook") {
            NotesJson.decodeNotebook(it)
        }
        // The directory name is authoritative. If the stored name has drifted from it (e.g. a crash
        // between renameNotebook's directory move and its JSON rewrite), adopt the directory's name
        // so later saves write back to this directory instead of resurrecting the old one via
        // savePage/saveNotebook's mkdirs.
        return if (notebook.name == dirName) notebook else notebook.copy(name = dirName)
    }

    /**
     * The notebook whose stable [id] matches, or null if none does. Resolves a link target by its
     * identity rather than its name, so a jump survives the target being renamed (a rename changes
     * the directory name — the authoritative name — but never the id). Scans [listNotebooks] and
     * reads each candidate's [loadNotebook] for its id, stopping at the first match; an unreadable
     * notebook is skipped rather than thrown, so one corrupt notebook cannot break resolution of the
     * rest. A tapped link resolves its target notebook through here before jumping.
     */
    fun findNotebookById(id: NotebookId): Notebook? {
        for (ref in listNotebooks()) {
            val notebook = runCatching { loadNotebook(ref.name) }.getOrNull()
            if (notebook != null && notebook.id == id) return notebook
        }
        return null
    }

    /** Loads one page of [notebook]. Throws [StorageException] if it is missing or corrupt. */
    fun loadPage(notebook: Notebook, pageId: PageId): Page =
        readAndDecode(pageFile(notebook, pageId), "page") { NotesJson.decodePage(it) }

    /** Atomically writes [page] into [notebook]'s `pages/` directory. */
    fun savePage(notebook: Notebook, page: Page) {
        val pagesDir = File(notebookDir(validateName(notebook.name)), PAGES_DIR)
        pagesDir.mkdirs()
        writeAtomically(File(pagesDir, "${page.id.value}.json"), NotesJson.encodePage(page))
    }

    /**
     * Deletes one page's file from [notebook]. A no-op if the file is already gone. Callers remove
     * the page id from `notebook.json` (and save it) first, so a failure here at worst leaves an
     * orphan page file that nothing references — never a dangling id that would fail to load.
     */
    fun deletePage(notebook: Notebook, pageId: PageId) {
        val file = pageFile(notebook, pageId)
        if (file.exists() && !file.delete()) {
            throw StorageException("Could not delete page file: ${file.path}")
        }
    }

    /** Atomically writes [notebook]'s `notebook.json`. */
    fun saveNotebook(notebook: Notebook) {
        val dir = notebookDir(validateName(notebook.name))
        dir.mkdirs()
        writeAtomically(File(dir, NOTEBOOK_FILE), NotesJson.encodeNotebook(notebook))
    }

    /**
     * Renames a notebook, keeping its directory name and stored name in step. Throws
     * [StorageException] if [old] is absent or a notebook named [new] already exists, or
     * [IllegalArgumentException] if either name is invalid.
     */
    fun renameNotebook(old: String, new: String) {
        val oldDir = notebookDir(validateName(old))
        val newDir = notebookDir(validateName(new))
        if (old == new) return
        if (!oldDir.isDirectory) throw StorageException("Notebook not found: ${oldDir.path}")
        if (newDir.exists()) throw StorageException("Notebook already exists: ${newDir.path}")
        if (!oldDir.renameTo(newDir)) {
            throw StorageException("Could not rename ${oldDir.path} to ${newDir.path}")
        }
        // The directory move (above) is the atomic step that changes identity; now bring the stored
        // name into step with it. A crash between the two leaves notebook.json's name stale, which
        // listNotebooks ignores anyway since it reads the name from the directory.
        val moved = readAndDecode(File(newDir, NOTEBOOK_FILE), "notebook") { NotesJson.decodeNotebook(it) }
        saveNotebook(moved.copy(name = new))
    }

    /** Deletes a notebook and all its pages. Throws [StorageException] if it does not exist. */
    fun deleteNotebook(name: String) {
        val dir = notebookDir(validateName(name))
        if (!dir.isDirectory) throw StorageException("Notebook not found: ${dir.path}")
        if (!dir.deleteRecursively()) throw StorageException("Could not delete ${dir.path}")
    }

    private fun notebookDir(name: String): File = File(rootDir, name + NOTEBOOK_SUFFIX)

    private fun pageFile(notebook: Notebook, pageId: PageId): File =
        File(File(notebookDir(validateName(notebook.name)), PAGES_DIR), "${pageId.value}.json")

    /**
     * Reads [file] and decodes it, mapping every failure to a [StorageException] naming the file:
     * a missing file, an I/O error, or JSON that does not parse ([NotesFormatException]). [kind] is
     * the human word ("notebook"/"page") used in the message.
     */
    private fun <T> readAndDecode(file: File, kind: String, decode: (String) -> T): T {
        if (!file.isFile) throw StorageException("Missing $kind file: ${file.path}")
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw StorageException("Could not read $kind file: ${file.path}", e)
        }
        return try {
            decode(text)
        } catch (e: NotesFormatException) {
            throw StorageException("Corrupt $kind file: ${file.path}", e)
        }
    }

    /**
     * Writes [text] to [target] atomically: fully write a sibling temp file, then rename it over
     * the target. The temp lives in the target's own directory so the rename is a same-filesystem
     * move, which [StandardCopyOption.ATOMIC_MOVE] makes an all-or-nothing replace of any existing
     * target. This is why a crash mid-write cannot corrupt the previous good file.
     *
     * Assumption: a same-directory rename is atomic, which holds on the filesystems this app writes
     * to (ext4/f2fs on device storage, exFAT on removable cards). If a filesystem cannot honour it,
     * [Files.move] throws [java.nio.file.AtomicMoveNotSupportedException] (an [IOException]) and the
     * save deliberately fails as a [StorageException] — we never silently fall back to a non-atomic
     * copy that could truncate the good file.
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw StorageException("Could not write ${target.path}", e)
        } finally {
            // Gone after a successful move; this clears a leftover temp if the write or move failed.
            tmp.delete()
        }
    }

    /**
     * The single definition of a legal notebook name, since the name doubles as a directory name.
     * Rejects the empty string, the path-unsafe characters `/ \ : * ? " < > |`, and a leading or
     * trailing dot or space (which also rules out `.` and `..`). Returns the name unchanged when it
     * is legal; throws [IllegalArgumentException] otherwise.
     */
    private fun validateName(name: String): String {
        require(name.isNotEmpty()) { "Notebook name must not be empty" }
        require(name.none { it in FORBIDDEN_NAME_CHARS }) {
            "Notebook name must not contain any of $FORBIDDEN_NAME_CHARS: \"$name\""
        }
        require(name.first() != '.' && name.last() != '.' && name.first() != ' ' && name.last() != ' ') {
            "Notebook name must not start or end with a dot or space: \"$name\""
        }
        return name
    }

    private companion object {
        const val NOTEBOOK_SUFFIX = ".nnote"
        const val NOTEBOOK_FILE = "notebook.json"
        const val PAGES_DIR = "pages"
        const val TEMPLATES_DIR = "templates"
        val FORBIDDEN_NAME_CHARS = "/\\:*?\"<>|".toSet()
    }
}
