package com.nomadnotes.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nomadnotes.R
import com.nomadnotes.app.input.AndroidPenBackend
import com.nomadnotes.app.input.PenBackend
import com.nomadnotes.app.render.PageRenderer
import com.nomadnotes.app.storage.NotebookStorage
import com.nomadnotes.app.storage.notebooksRoot
import com.nomadnotes.core.Layer
import com.nomadnotes.core.Notebook
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.edit.PageEditSession
import com.nomadnotes.core.geometry.Vec2
import com.nomadnotes.core.geometry.eraserHit
import com.nomadnotes.pen.onyx.OnyxRawDrawingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A minimal end-to-end harness for the page-editing pipeline, exercising it on a device before the
 * real editor UI exists.
 *
 * It opens (or first-time creates) a "Default" notebook through [NotebookStorage] and wires its
 * first [Page] through the pieces this project introduces: a [PageEditSession] (the undoable model),
 * a [PageRenderer] (page to the bitmap shown on screen), and a [PenBackend] (hardware input to
 * strokes). Every edit is persisted: a debounced autosave while drawing, and an immediate flush on
 * pause. The views are built in code — a full-bleed [SurfaceView] under a row of tool buttons — and
 * are intentionally throwaway; the real editor UI (and a notebook picker) is a later step.
 *
 * Input always runs through [AndroidPenBackend], even on Boox hardware. There it means laggy
 * touch-based ink rather than the raw-drawing pen: wiring the Onyx backend into the editor is a
 * separate step, and the device spike remains the proof that raw drawing works.
 */
class EditorActivity : ComponentActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: LinearLayout
    private lateinit var storage: NotebookStorage

    private val renderer = PageRenderer()
    private val backend = AndroidPenBackend { renderer.composite() }

    // Populated on a background thread once the notebook loads; both are read only on the main thread
    // after that, except for the immutable page snapshot autosave grabs (see saveCurrentPage).
    private var notebook: Notebook? = null
    private var session: PageEditSession? = null

    private var currentTool: Tool = Tool.PEN
    private var backendAttached = false
    private var surfaceReady = false

    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = notebooksRoot(this)
        Log.i(TAG, "Storage root: ${root.path} (allFilesAccess=${Environment.isExternalStorageManager()})")
        storage = NotebookStorage(root)
        requestAllFilesAccessOnce()
        setContentView(buildContentView())
        surfaceView.holder.addCallback(surfaceCallback)
        openDefaultNotebook()
    }

    override fun onPause() {
        super.onPause()
        // onPause can be the last callback before the process is killed, so persist synchronously
        // here rather than handing the save to a background job that might not finish. The page JSON
        // is small and the write is atomic, so the brief main-thread block is acceptable; runBlocking
        // (not a fire-and-forget launch) is what guarantees the save completes before we return.
        // Cancel the pending debounce first so the snapshot saved below is the last write to land.
        autosaveJob?.cancel()
        if (notebook != null && session != null) {
            runBlocking { saveCurrentPage() }
        }
    }

    override fun onDestroy() {
        backend.detach()
        renderer.release()
        super.onDestroy()
    }

    /**
     * Prompts for All-Files access at most once per process (a declined prompt is not re-shown, so
     * there is no nag loop). The app works without it: [notebooksRoot] falls back to app-private
     * storage, only the storage location differs.
     */
    private fun requestAllFilesAccessOnce() {
        if (Environment.isExternalStorageManager() || allFilesAccessRequested) return
        allFilesAccessRequested = true
        val perApp = Intent(
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(perApp)
        } catch (e: ActivityNotFoundException) {
            // A few devices lack the per-app screen; fall back to the global All-Files list.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "No All-Files access settings screen; staying on the fallback root", e2)
            }
        }
    }

    /**
     * Opens (or first-time creates) the [DEFAULT_NOTEBOOK] and loads its first page off the main
     * thread, then installs it on the main thread. If storage cannot be read the editor still opens
     * on an unsaved in-memory page, so a disk fault never bricks the harness.
     */
    private fun openDefaultNotebook() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val nb = if (storage.listNotebooks().any { it.name == DEFAULT_NOTEBOOK }) {
                        storage.loadNotebook(DEFAULT_NOTEBOOK)
                    } else {
                        storage.createNotebook(DEFAULT_NOTEBOOK)
                    }
                    nb to storage.loadPage(nb, nb.pageIds.first())
                }
            }
            loaded
                .onSuccess { (nb, page) ->
                    notebook = nb
                    session = PageEditSession(page)
                }
                .onFailure { e ->
                    Log.e(TAG, "Storage unavailable; editing an unsaved in-memory page", e)
                    session = PageEditSession(Page.create())
                }
            startEditingIfReady()
        }
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this)

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(surfaceView)

        val padding = (16 * resources.displayMetrics.density).toInt()
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.LTGRAY)
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
            addToolButton(R.string.action_pen) { selectTool(Tool.PEN) }
            addToolButton(R.string.action_marker) { selectTool(Tool.MARKER) }
            addToolButton(R.string.action_eraser) { backend.eraseMode = true }
            addToolButton(R.string.action_undo) { undo() }
            addToolButton(R.string.action_redo) { redo() }
            addToolButton(R.string.action_clear) { clearPage() }
        }
        root.addView(topBar)

        return root
    }

    private fun LinearLayout.addToolButton(labelRes: Int, onClick: () -> Unit) {
        addView(
            Button(this@EditorActivity).apply {
                setText(labelRes)
                setOnClickListener { onClick() }
            },
        )
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            renderer.resize(width, height)
            surfaceReady = true
            startEditingIfReady()
            // Keep the toolbar tappable: pen input landing on it is ignored.
            backend.setExcludeRects(listOf(relativeRect(surfaceView, topBar)))
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    /**
     * The first render and backend attach, run once both prerequisites hold: the surface has a size
     * and the page has loaded. Whichever of the two finishes second drives it; a later surface
     * resize re-renders but does not re-attach.
     */
    private fun startEditingIfReady() {
        val session = session ?: return
        if (!surfaceReady) return
        renderer.renderFull(session.page)
        presentComposite()
        if (!backendAttached) {
            backend.attach(surfaceView, penListener)
            backendAttached = true
        }
    }

    private val penListener = object : PenBackend.Listener {
        override fun onStrokeFinished(points: List<StrokePoint>) {
            val session = session ?: return
            if (points.isEmpty()) return
            val layerId = session.page.mainLayerId
            val stroke = Stroke(
                id = StrokeId.random(),
                tool = currentTool,
                widthBase = STROKE_WIDTH_BASE,
                grayLevel = STROKE_GRAY_LEVEL,
                points = points,
            )
            session.addStroke(layerId, stroke)
            renderer.appendStroke(layerId, stroke)
            presentComposite()
            scheduleAutosave()
        }

        override fun onEraseGesture(points: List<StrokePoint>) = eraseAlong(points)
    }

    private fun selectTool(tool: Tool) {
        currentTool = tool
        backend.eraseMode = false
    }

    private fun undo() {
        val session = session ?: return
        if (session.undo()) {
            rerenderPage()
            scheduleAutosave()
        }
    }

    private fun redo() {
        val session = session ?: return
        if (session.redo()) {
            rerenderPage()
            scheduleAutosave()
        }
    }

    private fun clearPage() {
        val session = session ?: return
        val layerId = session.page.mainLayerId
        val ids = activeLayer(session).strokes.map { it.id }
        if (ids.isEmpty()) return
        session.eraseStrokes(layerId, ids)
        rerenderPage()
        scheduleAutosave()
    }

    /**
     * Erases every stroke the eraser gesture passed over: each gesture point is a small eraser
     * disc, and any active-layer stroke it touches is removed as one undoable step. The page is
     * repainted even when nothing was erased, to wipe the eraser preview the backend left behind.
     */
    private fun eraseAlong(points: List<StrokePoint>) {
        val session = session ?: return
        val strokes = activeLayer(session).strokes
        val hits = LinkedHashSet<StrokeId>()
        for (point in points) {
            val center = Vec2(point.x, point.y)
            for (stroke in strokes) {
                if (stroke.id !in hits && eraserHit(stroke, center, ERASER_RADIUS)) hits.add(stroke.id)
            }
        }
        if (hits.isNotEmpty()) {
            session.eraseStrokes(session.page.mainLayerId, hits)
            scheduleAutosave()
        }
        rerenderPage()
    }

    private fun activeLayer(session: PageEditSession): Layer {
        val layerId = session.page.mainLayerId
        return session.page.layers.first { it.id == layerId }
    }

    private fun rerenderPage() {
        val session = session ?: return
        renderer.renderFull(session.page)
        presentComposite()
    }

    private fun presentComposite() {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(renderer.composite(), 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** (Re)starts the 5s idle timer; the previous pending save, if any, is cancelled. */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = lifecycleScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveCurrentPage()
        }
    }

    private suspend fun saveCurrentPage() {
        val notebook = notebook ?: return
        val session = session ?: return
        // The model is immutable and swapped wholesale on every edit, so reading session.page grabs
        // a consistent snapshot by reference — no copy or lock needed to hand it to serialization.
        // Read it here, on the caller's thread, before dispatching to IO.
        val page = session.page
        withContext(Dispatchers.IO) {
            // Acquire and release the lock entirely on the IO dispatcher, so a save never needs the
            // main thread to make progress — that is what lets onPause's runBlocking wait on an
            // in-flight save without deadlocking. One writer at a time; the newest snapshot wins.
            saveMutex.withLock { storage.savePage(notebook, page) }
        }
    }

    /** Rectangle of [child] expressed in [parent]'s local coordinates. */
    private fun relativeRect(parent: View, child: View): Rect {
        val parentLocation = IntArray(2).also { parent.getLocationOnScreen(it) }
        val childLocation = IntArray(2).also { child.getLocationOnScreen(it) }
        return Rect().also { child.getLocalVisibleRect(it) }.apply {
            offset(childLocation[0] - parentLocation[0], childLocation[1] - parentLocation[1])
        }
    }

    private companion object {
        const val TAG = "EditorActivity"

        /** The single notebook this harness edits until the notebook picker arrives in a later step. */
        const val DEFAULT_NOTEBOOK = "Default"

        /** Idle time after the last edit before the debounced autosave fires. */
        const val AUTOSAVE_DELAY_MS = 5_000L

        /** Nib width new strokes carry before the renderer applies pressure; tuned later. */
        const val STROKE_WIDTH_BASE = 4f

        /** New strokes are full black ([Stroke.grayLevel] 255); the palette is a later step. */
        const val STROKE_GRAY_LEVEL = 255

        /** Eraser disc radius, in page pixels, hit-tested at each gesture point. */
        const val ERASER_RADIUS = 20f

        /** Whether this process has already shown the All-Files access prompt; set once, never reset. */
        var allFilesAccessRequested = false
    }
}
