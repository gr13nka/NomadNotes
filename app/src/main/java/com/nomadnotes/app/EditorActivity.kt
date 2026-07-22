package com.nomadnotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.nomadnotes.R
import com.nomadnotes.app.editor.SelectionBounds
import com.nomadnotes.app.editor.SelectionState
import com.nomadnotes.app.input.AndroidPenBackend
import com.nomadnotes.app.input.CaptureMode
import com.nomadnotes.app.input.OnyxPenBackend
import com.nomadnotes.app.input.PenBackend
import com.nomadnotes.app.render.PageRenderer
import com.nomadnotes.app.render.SelectionRenderer
import com.nomadnotes.app.render.StrokeRenderer
import com.nomadnotes.app.render.TemplateRef
import com.nomadnotes.app.render.TemplateResolver
import com.nomadnotes.app.storage.NotebookStorage
import com.nomadnotes.app.storage.notebooksRoot
import com.nomadnotes.app.ui.ConfirmDialog
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkButton
import com.nomadnotes.app.ui.EinkCheckbox
import com.nomadnotes.app.ui.EinkGray
import com.nomadnotes.app.ui.EinkRadioDot
import com.nomadnotes.app.ui.EinkTheme
import com.nomadnotes.app.ui.EinkToggle
import com.nomadnotes.app.ui.EinkWhite
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Notebook
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.edit.PageEditSession
import com.nomadnotes.core.geometry.Vec2
import com.nomadnotes.core.geometry.eraserHit
import com.nomadnotes.core.geometry.lassoSelect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Nib-width presets the toolbar offers, in page pixels before the renderer applies pressure. */
private enum class StrokeWidth(val px: Float) { S(2f), M(4f), L(8f) }

/** Ink-darkness presets ([Stroke.grayLevel]: 255 = black); the marker keeps its own translucency. */
private enum class InkShade(val level: Int) { BLACK(255), DARK(170), LIGHT(85) }

/** A layer as the layers panel shows it: identity, label, visibility, and whether it is undeletable. */
private data class LayerRow(val id: LayerId, val name: String, val visible: Boolean, val isMain: Boolean)

/** Dims the screen behind an open panel and captures taps to dismiss it; no fade, instant. */
private val ScrimColor = Color(0x33000000)

/**
 * The notebook editor: a full page-editing surface with a Compose toolbar, layers panel, template
 * picker, and page navigation.
 *
 * The editing core is unchanged from the earlier harness and stays deliberately imperative: a
 * [SurfaceView] the [PageRenderer] blits into, an [AndroidPenBackend] feeding a [PageEditSession],
 * and a debounced autosave (with a synchronous flush on pause). Only the chrome is Compose. The
 * canvas lives in an [AndroidView] that reads no Compose state, so toolbar changes never recompose
 * it; the toolbar and panels read hoisted [mutableStateOf] fields that the imperative editing
 * methods keep in step with the model.
 *
 * The notebook to open is named by [EXTRA_NOTEBOOK_NAME] (falling back to [DEFAULT_NOTEBOOK]); it is
 * created on first use. As in the harness, a storage fault degrades to an unsaved in-memory page
 * rather than bricking the editor.
 */
class EditorActivity : ComponentActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var storage: NotebookStorage
    private lateinit var templateResolver: TemplateResolver

    private val renderer = PageRenderer()
    private val selectionRenderer = SelectionRenderer()

    // Draws the selected strokes onto the live move-drag preview (the same ink path a committed
    // stroke takes through PageRenderer, so the previewed strokes look identical to the result).
    private val strokeRenderer = StrokeRenderer()

    // Chosen in onCreate: the Onyx raw-drawing backend on Boox hardware, the plain touch backend
    // everywhere else. The editor talks only to the interface and never learns which it holds.
    private lateinit var backend: PenBackend

    // Last toolbar exclude rect pushed to the backend, in surface-local pixels; deduped so a stable
    // re-layout does not churn the backend's capture region.
    private var toolbarExcludeRect: Rect? = null

    // Loaded off the main thread once, then read only on the main thread. `notebook` is null when
    // storage is unavailable — the editor still runs on an in-memory page, it just cannot persist.
    private var notebook: Notebook? = null
    private var session: PageEditSession? = null

    // Lasso selection state, meaningful only while the LASSO tool is active. `selection` is the
    // durable model (ids + layer + bounds); `selectionPolygon` is the lasso outline to decorate,
    // shown right after a selection and dropped once it is moved or pasted. `clipboard` holds copied
    // strokes verbatim (fresh ids are minted at paste). `decorationBitmap` is a surface-sized scratch
    // into which the composite plus decorations are drawn before present, so layer bitmaps are never
    // touched. All read and written on the main thread.
    private var selection: SelectionState? = null
    private var selectionPolygon: List<Vec2>? = null
    private var clipboard: List<Stroke> = emptyList()
    private var decorationBitmap: Bitmap? = null

    // Live lasso-preview state, alive only from the first onLassoMove of a gesture to its finishing
    // onLassoGesture (all on the main thread). `lassoGestureStart` is the gesture's first sample —
    // non-null while a gesture is being previewed — used both to detect a move (start inside the
    // selection box) and to measure the drag delta. A MOVE drag additionally sets `draggingSelection`
    // (the selection being moved) and `dragBaseBitmap` (the composite rendered *without* those
    // strokes, so each frame is that base plus the strokes redrawn at the current offset);
    // `dragStrokes` caches those strokes so no per-frame lookup is needed. The bitmap is allocated at
    // drag start and recycled at drag end. `lassoDrawSamples` accumulates a new lasso's outline for
    // the wet-ink-less preview; `lastPreviewPresentMs` throttles presents during a fast gesture.
    private var lassoGestureStart: Vec2? = null
    private var draggingSelection: SelectionState? = null
    private var dragBaseBitmap: Bitmap? = null
    private var dragStrokes: List<Stroke> = emptyList()
    private val lassoDrawSamples = ArrayList<Vec2>()
    private var lastPreviewPresentMs = 0L

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backendAttached = false
    private var surfaceReady = false

    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    // A pending re-enable of pen capture, posted by [withChromeRefresh] after a chrome action so the
    // Compose repaint reaches the panel before raw drawing resumes; held so a rapid next action can
    // cancel it, and so onPause can drop it before we background.
    private var pendingChromeReenable: Runnable? = null

    // Toolbar/panel state, hoisted here so the AndroidView canvas can read none of it. The pen
    // listener also reads the drawing ones (tool/width/shade/active layer), but a plain value read
    // creates no recomposition dependency.
    private var uiTool by mutableStateOf(Tool.PEN)
    private var uiEraser by mutableStateOf(false)
    private var uiLasso by mutableStateOf(false)
    private var uiWidth by mutableStateOf(StrokeWidth.M)
    private var uiShade by mutableStateOf(InkShade.BLACK)
    private var uiCanUndo by mutableStateOf(false)
    private var uiCanRedo by mutableStateOf(false)
    private var uiPageIndex by mutableStateOf(0)
    private var uiPageCount by mutableStateOf(1)
    private var uiLayersOpen by mutableStateOf(false)
    private var uiTemplateOpen by mutableStateOf(false)
    private var uiLayers by mutableStateOf<List<LayerRow>>(emptyList())
    private var uiActiveLayer by mutableStateOf<LayerId?>(null)
    private var uiTemplateRef by mutableStateOf<String?>(null)
    private var uiTemplateFiles by mutableStateOf<List<String>>(emptyList())
    private var uiDeletePageDialog by mutableStateOf(false)
    private var uiHasSelection by mutableStateOf(false)
    private var uiClipboardHasContent by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = notebooksRoot(this)
        Log.i(TAG, "Storage root: ${root.path}")
        storage = NotebookStorage(root)
        templateResolver = TemplateResolver(storage.templatesDir)
        surfaceView = SurfaceView(this)
        backend = createBackend()
        surfaceView.holder.addCallback(surfaceCallback)
        setContent { EinkTheme { EditorScreen() } }
        openNotebookFromIntent()
    }

    /**
     * Picks the pen backend for this device. On Boox the hidden-API exemption must be installed
     * before the Onyx SDK is ever touched, so it runs here — [OnyxPenBackend.isSupported] only reads
     * the manufacturer and loads no Onyx SDK class.
     */
    private fun createBackend(): PenBackend {
        val composite = { renderer.composite() }
        return if (OnyxPenBackend.isSupported()) {
            OnyxPenBackend.prepareProcess()
            Log.i(TAG, "Pen backend: Onyx raw drawing")
            OnyxPenBackend(composite)
        } else {
            Log.i(TAG, "Pen backend: touch")
            AndroidPenBackend(composite)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-enable pen capture on return, unless a panel is up (then it stays suppressed).
        updateBackendEnabled()
    }

    override fun onPause() {
        // Stop raw drawing before we background so the pen does not draw while we are not foreground;
        // drop any pending chrome resume so it cannot re-enable capture after we have paused.
        pendingChromeReenable?.let { surfaceView.removeCallbacks(it) }
        pendingChromeReenable = null
        backend.setEnabled(false)
        super.onPause()
        // onPause can be the last callback before the process is killed, so persist synchronously:
        // cancel the pending debounce, then block on the final save so it lands before we return.
        autosaveJob?.cancel()
        if (notebook != null && session != null) {
            runBlocking { saveCurrentPage() }
        }
    }

    override fun onDestroy() {
        backend.detach()
        renderer.release()
        templateResolver.release()
        decorationBitmap?.recycle()
        decorationBitmap = null
        dragBaseBitmap?.recycle()
        dragBaseBitmap = null
        super.onDestroy()
    }

    // --- notebook / page loading -----------------------------------------------------------

    private fun openNotebookFromIntent() {
        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: DEFAULT_NOTEBOOK
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val nb = if (storage.listNotebooks().any { it.name == name }) {
                        storage.loadNotebook(name)
                    } else {
                        storage.createNotebook(name)
                    }
                    nb to storage.loadPage(nb, nb.pageIds.first())
                }
            }
            loaded
                .onSuccess { (nb, page) ->
                    notebook = nb
                    installPage(page, index = 0)
                }
                .onFailure { e ->
                    Log.e(TAG, "Storage unavailable; editing an unsaved in-memory page", e)
                    notebook = null
                    installPage(Page.create(), index = 0)
                }
            startEditingIfReady()
        }
    }

    /** Installs [page] as the one being edited and syncs every toolbar state to it. Does not render. */
    private fun installPage(page: Page, index: Int) {
        session = PageEditSession(page)
        uiPageIndex = index
        uiPageCount = notebook?.pageIds?.size ?: 1
        uiActiveLayer = page.mainLayerId
        uiTemplateRef = page.templateRef
        // A selection belongs to the page it was made on; page navigation drops it (no decorations
        // survive onto the next page's first paint).
        selection = null
        selectionPolygon = null
        updateSelectionUi()
        refreshUndoRedo()
        refreshLayers()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            renderer.resize(width, height)
            // The decoration scratch is surface-sized; drop it so it is rebuilt at the new size.
            decorationBitmap?.recycle()
            decorationBitmap = null
            surfaceReady = true
            startEditingIfReady()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    /**
     * First render plus backend attach, once the surface has a size and a page has loaded. The
     * composite is built before attach so the backend can show it while bringing pen capture up; on
     * the Onyx backend that order matters (the surface must be drawn before raw drawing turns on).
     */
    private fun startEditingIfReady() {
        session ?: return
        if (!surfaceReady) return
        composePage()
        if (!backendAttached) {
            backend.setStrokeAppearance(uiTool, uiWidth.px, uiShade.level)
            backend.attach(surfaceView, penListener)
            backendAttached = true
        } else {
            presentComposite()
        }
    }

    // --- rendering -------------------------------------------------------------------------

    /**
     * Rebuilds the whole composite and shows it (decorated with the selection). Use for anything but a
     * lone new stroke. [cleanRefresh] requests a ghost-free e-ink repaint (see [PenBackend.present]),
     * for a repaint that removes ink such as erasing.
     */
    private fun renderPage(cleanRefresh: Boolean = false) {
        composePage()
        present(cleanRefresh)
    }

    /** Rebuilds the composite from the current page and template, without touching the surface. */
    private fun composePage() {
        val session = session ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val template = templateResolver.resolve(session.page.templateRef, surfaceWidth, surfaceHeight)
        renderer.renderFull(session.page, template)
    }

    /** Shows the current composite on the surface (the backend brackets the blit if its hardware needs it). */
    private fun presentComposite(cleanRefresh: Boolean = false) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        backend.present(renderer.composite(), cleanRefresh)
    }

    /**
     * Shows the current composite, decorated with the lasso outline and bounding box when a selection
     * is active. Every path to the surface for a selection goes through here, so decorations are drawn
     * only into a scratch copy and the page's layer bitmaps stay untouched.
     */
    private fun present(cleanRefresh: Boolean = false) {
        val selection = selection
        if (selection == null) presentComposite(cleanRefresh)
        else presentDecorated(selection.bounds, selectionPolygon, cleanRefresh)
    }

    /** Composites the page plus [polygon] (if any) and the [bounds] box into the scratch bitmap, then presents it. */
    private fun presentDecorated(bounds: SelectionBounds, polygon: List<Vec2>?, cleanRefresh: Boolean = false) {
        val scratch = decorationScratch()
        if (scratch == null) {
            presentComposite(cleanRefresh)
            return
        }
        val canvas = Canvas(scratch)
        canvas.drawBitmap(renderer.composite(), 0f, 0f, null)
        polygon?.let { selectionRenderer.drawPolygon(canvas, it) }
        selectionRenderer.drawBounds(canvas, bounds)
        backend.present(scratch, cleanRefresh)
    }

    /** The surface-sized scratch bitmap for decorations, made on demand and rebuilt if the size changed. */
    private fun decorationScratch(): Bitmap? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
        val existing = decorationBitmap
        if (existing != null && existing.width == surfaceWidth && existing.height == surfaceHeight) return existing
        existing?.recycle()
        return Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            .also { decorationBitmap = it }
    }

    // --- pen input -------------------------------------------------------------------------

    private val penListener = object : PenBackend.Listener {
        override fun onStrokeFinished(points: List<StrokePoint>) {
            val session = session ?: return
            if (points.isEmpty()) return
            val layerId = activeLayerId(session)
            val stroke = Stroke(
                id = StrokeId.random(),
                tool = uiTool,
                widthBase = uiWidth.px,
                grayLevel = uiShade.level,
                points = points,
            )
            session.addStroke(layerId, stroke)
            // Always record the stroke into the layer bitmap. Present it only when the backend does
            // not paint wet ink itself: on Onyx the panel already shows this stroke, and a per-stroke
            // blit there (with raw drawing briefly disabled) is exactly what delays the next stroke.
            renderer.appendStroke(layerId, stroke)
            if (!backend.rendersWetInkNatively) presentComposite()
            refreshUndoRedo()
            scheduleAutosave()
        }

        override fun onEraseGesture(points: List<StrokePoint>) = eraseAlong(points)

        override fun onLassoGesture(points: List<StrokePoint>) = endLassoGesture(points)

        override fun onLassoMove(point: StrokePoint) = previewLasso(point)
    }

    /**
     * Erases every active-layer stroke the eraser gesture passed over as one undoable step, then
     * repaints — even when nothing was erased — to wipe the eraser preview the backend left behind.
     */
    private fun eraseAlong(points: List<StrokePoint>) {
        val session = session ?: return
        val layerId = activeLayerId(session)
        val layer = session.page.layers.firstOrNull { it.id == layerId }
        if (layer != null) {
            val hits = LinkedHashSet<StrokeId>()
            for (point in points) {
                val center = Vec2(point.x, point.y)
                for (stroke in layer.strokes) {
                    if (stroke.id !in hits && eraserHit(stroke, center, ERASER_RADIUS)) hits.add(stroke.id)
                }
            }
            if (hits.isNotEmpty()) {
                session.eraseStrokes(layerId, hits)
                refreshUndoRedo()
                scheduleAutosave()
            }
        }
        // Repaint regardless, to wipe the eraser preview the backend left on the surface. Ask for a
        // clean refresh: this repaint removes ink, which the fast additive e-ink mode would leave
        // ghosted (the erased strokes "blink" back until a later full refresh).
        renderPage(cleanRefresh = true)
    }

    // --- lasso selection -------------------------------------------------------------------

    /**
     * A live sample of an in-progress LASSO gesture (see [PenBackend.Listener.onLassoMove]). The
     * first sample of the gesture decides its shape, held until [endLassoGesture]:
     *  - starting inside the current selection's box begins a MOVE drag — the selected strokes are
     *    previewed lifted off [dragBaseBitmap] (the page rendered without them) and dragged under the
     *    pen, so the user can aim before committing;
     *  - starting elsewhere is a DRAW — its growing outline is previewed over the page, but only on a
     *    backend without hardware wet ink (the Onyx panel shows nothing mid-lasso by design, which the
     *    user accepted, so drawing a new lasso stays preview-less there).
     * Presents are throttled to [LASSO_PREVIEW_MIN_INTERVAL_MS]; the exact final position is committed
     * by [endLassoGesture] regardless of what the last throttled frame showed.
     */
    private fun previewLasso(point: StrokePoint) {
        val here = Vec2(point.x, point.y)
        val start = lassoGestureStart
        if (start == null) {
            beginLassoPreview(here)
            return
        }
        val dragging = draggingSelection
        if (dragging != null) {
            if (throttleAllowsPresent()) presentMoveDrag(dx = here.x - start.x, dy = here.y - start.y)
        } else if (!backend.rendersWetInkNatively) {
            lassoDrawSamples.add(here)
            if (throttleAllowsPresent()) presentLassoDraw()
        }
    }

    /** Decides a fresh LASSO gesture from its first sample: a move drag if it began inside the selection, else a draw. */
    private fun beginLassoPreview(start: Vec2) {
        lassoGestureStart = start
        lastPreviewPresentMs = SystemClock.uptimeMillis()
        val selection = selection
        if (selection != null && selection.bounds.contains(start.x, start.y)) {
            beginMoveDrag(selection)
            presentMoveDrag(dx = 0f, dy = 0f) // lift the selection off its base straight away
        } else if (!backend.rendersWetInkNatively) {
            lassoDrawSamples.clear()
            lassoDrawSamples.add(start)
        }
    }

    /**
     * Enters a move-drag preview for [selection]: caches its strokes and renders [dragBaseBitmap], the
     * static page the strokes are dragged over — the composite with those strokes removed. The shared
     * renderer is left holding the full page again afterwards, so [renderer].composite() stays the
     * committed page throughout the drag (the preview draws from [dragBaseBitmap], never from it). A
     * no-op if the surface or the selection's layer is unavailable.
     */
    private fun beginMoveDrag(selection: SelectionState) {
        val session = session ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val layer = session.page.layers.firstOrNull { it.id == selection.layerId } ?: return
        val ids = selection.strokeIds.toSet()
        dragStrokes = layer.strokes.filter { it.id in ids }
        val template = templateResolver.resolve(session.page.templateRef, surfaceWidth, surfaceHeight)
        renderer.renderFull(session.page, template, excludeStrokeIds = ids)
        dragBaseBitmap?.recycle()
        val base = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            .also { dragBaseBitmap = it }
        Canvas(base).drawBitmap(renderer.composite(), 0f, 0f, null)
        // Restore the shared composite to the full page, so a zero-delta commit or a cancel repaints
        // correctly from it without a stale exclusion.
        renderer.renderFull(session.page, template)
        draggingSelection = selection
    }

    /** Presents [dragBaseBitmap] with the dragged strokes and their box redrawn at offset ([dx], [dy]). */
    private fun presentMoveDrag(dx: Float, dy: Float) {
        val base = dragBaseBitmap ?: return
        val selection = draggingSelection ?: return
        val scratch = decorationScratch() ?: return
        val canvas = Canvas(scratch)
        canvas.drawBitmap(base, 0f, 0f, null)
        val saved = canvas.save()
        canvas.translate(dx, dy)
        for (stroke in dragStrokes) strokeRenderer.draw(canvas, stroke)
        canvas.restoreToCount(saved)
        selectionRenderer.drawBounds(canvas, selection.bounds.translated(dx, dy))
        backend.presentDuringCapture(scratch)
    }

    /** Presents the page with the in-progress lasso outline (wet-ink-less backends only). */
    private fun presentLassoDraw() {
        val scratch = decorationScratch() ?: return
        val canvas = Canvas(scratch)
        canvas.drawBitmap(renderer.composite(), 0f, 0f, null)
        selectionRenderer.drawPolygon(canvas, lassoDrawSamples)
        backend.presentDuringCapture(scratch)
    }

    /** True at most once per [LASSO_PREVIEW_MIN_INTERVAL_MS], to cap the live-preview frame rate. */
    private fun throttleAllowsPresent(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewPresentMs < LASSO_PREVIEW_MIN_INTERVAL_MS) return false
        lastPreviewPresentMs = now
        return true
    }

    /** Ends the current gesture's live preview and frees the drag base. Safe to call with none active. */
    private fun endLassoPreview() {
        lassoGestureStart = null
        draggingSelection = null
        dragStrokes = emptyList()
        lassoDrawSamples.clear()
        dragBaseBitmap?.recycle()
        dragBaseBitmap = null
    }

    /**
     * A LASSO gesture ended: tear down its live preview, then act on it. An empty path means the
     * gesture was abandoned (a touch cancel) — commit nothing and just repaint over whatever the
     * preview left on the surface; otherwise route it as a move or a new lasso ([handleLassoGesture]).
     */
    private fun endLassoGesture(points: List<StrokePoint>) {
        val hadPreview = lassoGestureStart != null
        endLassoPreview()
        if (points.isEmpty()) {
            if (hadPreview) present()
            return
        }
        handleLassoGesture(points)
    }

    /**
     * A finished LASSO-mode gesture. The backend cannot tell a lasso from a move, so the decision is
     * made here from the current selection: a gesture that *starts inside* the selection box is a move
     * (by the start→end delta), otherwise it is a new lasso polygon. The live preview (see
     * [previewLasso]) tracks the same decision; this is the authoritative commit at pen-up.
     */
    private fun handleLassoGesture(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val selection = selection
        val start = points.first()
        if (selection != null && selection.bounds.contains(start.x, start.y)) {
            val end = points.last()
            commitMove(selection, dx = end.x - start.x, dy = end.y - start.y)
        } else {
            applyLasso(points.map { Vec2(it.x, it.y) })
        }
    }

    /** Selects every active-layer stroke fully enclosed by [polygon]; an empty result clears the selection. */
    private fun applyLasso(polygon: List<Vec2>) {
        val session = session ?: return
        val layerId = activeLayerId(session)
        val layer = session.page.layers.first { it.id == layerId }
        val ids = lassoSelect(layer.strokes, polygon).toSet()
        if (ids.isEmpty()) {
            selection = null
            selectionPolygon = null
            updateSelectionUi()
            // Repaint to wipe the lasso preview the backend left on the surface (as the eraser does).
            presentComposite()
            return
        }
        selection = SelectionState.of(layerId, layer.strokes.filter { it.id in ids })
        selectionPolygon = polygon
        updateSelectionUi()
        present()
    }

    /**
     * Commits a move of [selection] by ([dx], [dy]) as one undoable step, then repaints with the box
     * at its new position (the lasso outline is dropped — it no longer matches the moved strokes). A
     * zero move just repaints, to wipe any preview the backend left.
     */
    private fun commitMove(selection: SelectionState, dx: Float, dy: Float) {
        val session = session ?: return
        if (dx == 0f && dy == 0f) {
            present()
            return
        }
        session.translateStrokes(selection.layerId, selection.strokeIds, dx, dy)
        this.selection = selection.copy(bounds = selection.bounds.translated(dx, dy))
        selectionPolygon = null
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
    }

    /** Copies the selected strokes to the in-memory clipboard. Fresh ids are minted later, at paste. */
    private fun copySelection() {
        val session = session ?: return
        val selection = selection ?: return
        val layer = session.page.layers.firstOrNull { it.id == selection.layerId } ?: return
        val ids = selection.strokeIds.toSet()
        clipboard = layer.strokes.filter { it.id in ids }
        uiClipboardHasContent = clipboard.isNotEmpty()
    }

    /**
     * Pastes the clipboard onto the active layer, offset slightly so it does not hide the originals,
     * and selects the result. Each pasted stroke gets a fresh id, so repeated pastes stay independent.
     */
    private fun pasteClipboard() {
        val session = session ?: return
        if (clipboard.isEmpty()) return
        val layerId = activeLayerId(session)
        val pasted = clipboard.map { it.offsetCopy(PASTE_OFFSET_PX, PASTE_OFFSET_PX) }
        if (!session.pasteStrokes(layerId, pasted)) return
        // PASTE is offered in any tool, but a selection is only interactable in LASSO mode (and a
        // tool switch clears it), so paste enters LASSO mode — otherwise the pasted strokes would be
        // selected yet unmovable. selectLasso first (it clears any prior selection), then adopt this one.
        if (!uiLasso) selectLasso()
        selection = SelectionState.of(layerId, pasted)
        selectionPolygon = null
        updateSelectionUi()
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
    }

    /** Deletes the selected strokes as one undoable step and clears the selection. */
    private fun deleteSelection() {
        val session = session ?: return
        val selection = selection ?: return
        session.eraseStrokes(selection.layerId, selection.strokeIds)
        this.selection = null
        selectionPolygon = null
        updateSelectionUi()
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
    }

    /** Clears the selection and wipes its decorations. Present-wise a no-op when nothing was selected. */
    private fun clearSelection() {
        val had = selection != null
        selection = null
        selectionPolygon = null
        updateSelectionUi()
        // The decoration scratch is only needed while a selection is on screen; free it now and let
        // the next selection rebuild it on demand (decorationScratch), rather than holding a
        // surface-sized bitmap through every non-selection edit.
        decorationBitmap?.recycle()
        decorationBitmap = null
        if (had) presentComposite()
    }

    private fun updateSelectionUi() {
        uiHasSelection = selection != null
    }

    /** The active layer if it is still on the page, else the main layer — so an edit never targets a gone layer. */
    private fun activeLayerId(session: PageEditSession): LayerId =
        uiActiveLayer?.takeIf { id -> session.page.layers.any { it.id == id } } ?: session.page.mainLayerId

    /** A deep copy of the stroke with a fresh id and its points shifted by ([dx], [dy]). */
    private fun Stroke.offsetCopy(dx: Float, dy: Float): Stroke =
        copy(id = StrokeId.random(), points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })

    // --- toolbar actions -------------------------------------------------------------------

    private fun selectTool(tool: Tool) {
        if (uiTool == tool && !uiEraser && !uiLasso) return
        uiTool = tool
        uiEraser = false
        uiLasso = false
        clearSelection()
        backend.captureMode = CaptureMode.INK
        pushStrokeAppearance()
    }

    private fun selectEraser() {
        if (uiEraser) return
        uiEraser = true
        uiLasso = false
        clearSelection()
        backend.captureMode = CaptureMode.ERASE
    }

    /** Enters lasso mode: a lasso encloses strokes into a selection to move, copy, or delete. */
    private fun selectLasso() {
        if (uiLasso) return
        uiLasso = true
        uiEraser = false
        clearSelection()
        backend.captureMode = CaptureMode.LASSO
    }

    private fun setWidth(width: StrokeWidth) {
        uiWidth = width
        pushStrokeAppearance()
    }

    private fun setShade(shade: InkShade) {
        uiShade = shade
        pushStrokeAppearance()
    }

    /** Tells the backend how the next stroke should look, so its wet ink matches the committed stroke. */
    private fun pushStrokeAppearance() {
        backend.setStrokeAppearance(uiTool, uiWidth.px, uiShade.level)
    }

    private fun undo() {
        val session = session ?: return
        if (session.undo()) afterModelEdit()
    }

    private fun redo() {
        val session = session ?: return
        if (session.redo()) afterModelEdit()
    }

    /**
     * Shared tail for edits that can restructure the page (undo/redo, erase, layer and template
     * changes): re-validate the active-layer selection, resync the dependent toolbar state, repaint
     * from scratch, and schedule a save.
     */
    private fun afterModelEdit() {
        val session = session ?: return
        if (uiActiveLayer == null || session.page.layers.none { it.id == uiActiveLayer }) {
            uiActiveLayer = session.page.mainLayerId
        }
        uiTemplateRef = session.page.templateRef
        // A structural edit (undo/redo, layer/template change) can invalidate the selection's ids or
        // bounds, so drop it; renderPage then repaints without decorations.
        selection = null
        selectionPolygon = null
        updateSelectionUi()
        refreshUndoRedo()
        refreshLayers()
        renderPage()
        scheduleAutosave()
    }

    // --- layers ----------------------------------------------------------------------------

    private fun toggleLayerVisible(id: LayerId, visible: Boolean) {
        session?.setLayerVisible(id, visible)
        afterModelEdit()
    }

    /** Points new strokes and the eraser at a layer. A UI-only choice, so it makes no undo entry. */
    private fun setActiveLayer(id: LayerId) {
        uiActiveLayer = id
    }

    private fun addLayer() {
        val session = session ?: return
        if (session.addLayer("Layer ${session.page.layers.size + 1}")) afterModelEdit()
    }

    private fun removeLayer(id: LayerId) {
        val session = session ?: return
        if (session.removeLayer(id)) afterModelEdit()
    }

    // --- template --------------------------------------------------------------------------

    private fun setTemplate(ref: String?) {
        session?.setTemplateRef(ref)
        afterModelEdit()
    }

    private fun loadTemplateFiles() {
        lifecycleScope.launch {
            uiTemplateFiles = withContext(Dispatchers.IO) {
                runCatching {
                    storage.templatesDir
                        .listFiles { f -> f.isFile && f.extension.lowercase() in TEMPLATE_IMAGE_EXTS }
                        ?.map { it.name }
                        ?.sorted()
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
    }

    // --- panels ----------------------------------------------------------------------------

    private fun toggleLayersPanel() {
        uiLayersOpen = !uiLayersOpen
        if (uiLayersOpen) {
            uiTemplateOpen = false
            refreshLayers()
        }
        updateBackendEnabled()
    }

    private fun toggleTemplatePanel() {
        uiTemplateOpen = !uiTemplateOpen
        if (uiTemplateOpen) {
            uiLayersOpen = false
            loadTemplateFiles()
        }
        updateBackendEnabled()
    }

    /** Pen capture is off while a panel is open, so a tap meant for the panel never draws a stroke. */
    private fun updateBackendEnabled() {
        backend.setEnabled(!uiLayersOpen && !uiTemplateOpen)
    }

    /**
     * Runs a chrome action (a toolbar or page mutation) with pen capture briefly paused, so the
     * Compose repaint the action triggers actually reaches the e-ink panel.
     *
     * On Onyx firmware, while raw drawing is enabled the panel suppresses normal window rendering, so
     * a tool highlight or page-counter change does not appear until some later full refresh — the
     * toolbar looks frozen for seconds after drawing. Disabling the backend lets the frame through; a
     * posted continuation resumes capture after [CHROME_REFRESH_MS], long enough for the frame to
     * land. Resuming goes through [updateBackendEnabled] rather than a blind enable, so an action that
     * opened a panel keeps capture suppressed. A rapid next action cancels the prior pending resume.
     */
    private fun withChromeRefresh(action: () -> Unit) {
        pendingChromeReenable?.let { surfaceView.removeCallbacks(it) }
        backend.setEnabled(false)
        action()
        val resume = Runnable {
            pendingChromeReenable = null
            updateBackendEnabled()
        }
        pendingChromeReenable = resume
        surfaceView.postDelayed(resume, CHROME_REFRESH_MS)
    }

    /**
     * Pushes the toolbar's on-screen bounds to the backend as an exclude rect, in surface-local
     * pixels, so a pen stroke starting on the toolbar is never captured as ink. Called on each
     * toolbar layout; deduped so a stable layout does not reconfigure the backend's capture region.
     */
    private fun updateToolbarExclude(boundsInWindow: androidx.compose.ui.geometry.Rect) {
        val surfaceLocation = IntArray(2).also { surfaceView.getLocationInWindow(it) }
        val rect = Rect(
            (boundsInWindow.left - surfaceLocation[0]).toInt(),
            (boundsInWindow.top - surfaceLocation[1]).toInt(),
            (boundsInWindow.right - surfaceLocation[0]).toInt(),
            (boundsInWindow.bottom - surfaceLocation[1]).toInt(),
        )
        if (rect == toolbarExcludeRect) return
        toolbarExcludeRect = rect
        Log.i(TAG, "Toolbar exclude rect: $rect")
        backend.setExcludeRects(listOf(rect))
    }

    // --- page navigation -------------------------------------------------------------------

    private fun goToPage(index: Int) {
        val notebook = notebook ?: return
        if (index < 0 || index >= notebook.pageIds.size || index == uiPageIndex) return
        autosaveJob?.cancel()
        lifecycleScope.launch {
            saveCurrentPage()
            val loaded = withContext(Dispatchers.IO) {
                runCatching { storage.loadPage(notebook, notebook.pageIds[index]) }
            }
            loaded
                .onSuccess { page ->
                    installPage(page, index)
                    renderPage()
                }
                .onFailure { e -> Log.e(TAG, "Could not load page $index", e) }
        }
    }

    private fun insertPageAfterCurrent() {
        val current = notebook ?: return
        autosaveJob?.cancel()
        lifecycleScope.launch {
            saveCurrentPage()
            val newPage = Page.create()
            val at = uiPageIndex + 1
            val updated = current.copy(
                pageIds = current.pageIds.toMutableList().apply { add(at, newPage.id) },
            )
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    saveMutex.withLock {
                        storage.savePage(updated, newPage)
                        storage.saveNotebook(updated)
                    }
                }.isSuccess
            }
            if (!saved) {
                Log.e(TAG, "Could not insert a page")
                return@launch
            }
            notebook = updated
            installPage(newPage, at)
            renderPage()
        }
    }

    private fun deleteCurrentPage() {
        val current = notebook ?: return
        if (current.pageIds.size <= 1) return
        autosaveJob?.cancel()
        lifecycleScope.launch {
            val removedId = current.pageIds[uiPageIndex]
            val remaining = current.pageIds.toMutableList().apply { removeAt(uiPageIndex) }
            val updated = current.copy(pageIds = remaining)
            val newIndex = uiPageIndex.coerceIn(0, remaining.size - 1)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Rewrite notebook.json (dropping the id) before deleting the file, so a crash
                    // between the two leaves a harmless orphan file, not a dangling id.
                    saveMutex.withLock {
                        storage.saveNotebook(updated)
                        storage.deletePage(updated, removedId)
                    }
                    storage.loadPage(updated, remaining[newIndex])
                }
            }
            result
                .onSuccess { page ->
                    notebook = updated
                    installPage(page, newIndex)
                    renderPage()
                }
                .onFailure { e -> Log.e(TAG, "Could not delete the page", e) }
        }
    }

    // --- autosave --------------------------------------------------------------------------

    /** (Re)starts the idle timer; the previous pending save, if any, is cancelled. */
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
        // The model is immutable and swapped wholesale per edit, so reading session.page here (on the
        // caller's thread, before dispatching to IO) grabs a consistent snapshot to serialize.
        val page = session.page
        withContext(Dispatchers.IO) {
            saveMutex.withLock { storage.savePage(notebook, page) }
        }
    }

    // --- state sync ------------------------------------------------------------------------

    private fun refreshUndoRedo() {
        val session = session ?: return
        uiCanUndo = session.canUndo
        uiCanRedo = session.canRedo
    }

    private fun refreshLayers() {
        val session = session ?: return
        val mainId = session.page.mainLayerId
        // Top-most layer first in the panel; page.layers is back-to-front.
        uiLayers = session.page.layers.asReversed().map {
            LayerRow(id = it.id, name = it.name, visible = it.visible, isMain = it.id == mainId)
        }
    }

    // --- Compose UI ------------------------------------------------------------------------

    @Composable
    private fun EditorScreen() {
        // Full-bleed canvas with the toolbar overlaid on top, matching the drawing spike's proven
        // geometry: the SurfaceView fills the window, so the measured toolbar exclude rect lands as a
        // positive on-surface region (in a Column the toolbar sat above the surface and its rect was
        // off-surface — a no-op that let pen taps on the toolbar reach the raw-input reader). The
        // toolbar's opaque background hides the ink beneath it. Overlays draw last, so when a panel is
        // open its scrim also dims the toolbar strip — acceptable, and it keeps the panel fully visible
        // rather than clipped under the toolbar.
        Box(Modifier.fillMaxSize()) {
            CanvasView()
            EditorToolbar()
            EditorOverlays()
        }
    }

    /** The drawing surface. Reads no Compose state and has no update block, so it never recomposes. */
    @Composable
    private fun CanvasView() {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
    }

    @OptIn(ExperimentalLayoutApi::class) // FlowRow / FlowRowScope.align are still marked experimental.
    @Composable
    private fun EditorToolbar() {
        Column(
            Modifier
                .fillMaxWidth()
                .background(EinkWhite)
                .onGloballyPositioned { updateToolbarExclude(it.boundsInWindow()) },
        ) {
            // A wrapping FlowRow rather than a horizontally scrolling Row: every control stays on
            // screen (spilling onto a second line when the width runs out) and there is no scroll
            // gesture — the toolbar scroll lagged badly after a stroke, since raw drawing suppresses
            // the fling's window repaints. The toolbar grows taller when it wraps; the exclude rect is
            // measured from these bounds, so it grows with it, and the full-bleed canvas sits behind.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Every chrome mutation is wrapped in withChromeRefresh: raw drawing suppresses normal
                // window rendering on Onyx, so without the brief capture pause the toolbar repaint (tool
                // highlight, page counter) would not reach the panel until a later full refresh. The
                // panel toggles are the exception — they drive updateBackendEnabled themselves.
                EinkToggle(stringResource(R.string.action_pen), selected = !uiEraser && uiTool == Tool.PEN) { withChromeRefresh { selectTool(Tool.PEN) } }
                EinkToggle(stringResource(R.string.action_pencil), selected = !uiEraser && uiTool == Tool.PENCIL) { withChromeRefresh { selectTool(Tool.PENCIL) } }
                EinkToggle(stringResource(R.string.action_marker), selected = !uiEraser && uiTool == Tool.MARKER) { withChromeRefresh { selectTool(Tool.MARKER) } }
                EinkToggle(stringResource(R.string.action_eraser), selected = uiEraser) { withChromeRefresh { selectEraser() } }
                EinkToggle(stringResource(R.string.action_lasso), selected = uiLasso) { withChromeRefresh { selectLasso() } }
                // The selection action bar lives in the toolbar so its buttons sit in the already-excluded
                // strip (no dynamic raw-drawing exclude rect) and appearing does not resize the canvas.
                if (uiHasSelection || uiClipboardHasContent) {
                    ToolbarDivider()
                    if (uiHasSelection) {
                        EinkButton(stringResource(R.string.action_copy)) { withChromeRefresh { copySelection() } }
                        EinkButton(stringResource(R.string.action_delete)) { withChromeRefresh { deleteSelection() } }
                        EinkButton(stringResource(R.string.action_deselect)) { withChromeRefresh { clearSelection() } }
                    }
                    if (uiClipboardHasContent) {
                        EinkButton(stringResource(R.string.action_paste)) { withChromeRefresh { pasteClipboard() } }
                    }
                }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.width_small), selected = uiWidth == StrokeWidth.S) { withChromeRefresh { setWidth(StrokeWidth.S) } }
                EinkToggle(stringResource(R.string.width_medium), selected = uiWidth == StrokeWidth.M) { withChromeRefresh { setWidth(StrokeWidth.M) } }
                EinkToggle(stringResource(R.string.width_large), selected = uiWidth == StrokeWidth.L) { withChromeRefresh { setWidth(StrokeWidth.L) } }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.shade_black), selected = uiShade == InkShade.BLACK) { withChromeRefresh { setShade(InkShade.BLACK) } }
                EinkToggle(stringResource(R.string.shade_dark), selected = uiShade == InkShade.DARK) { withChromeRefresh { setShade(InkShade.DARK) } }
                EinkToggle(stringResource(R.string.shade_light), selected = uiShade == InkShade.LIGHT) { withChromeRefresh { setShade(InkShade.LIGHT) } }
                ToolbarDivider()

                EinkButton(stringResource(R.string.action_undo), enabled = uiCanUndo) { withChromeRefresh { undo() } }
                EinkButton(stringResource(R.string.action_redo), enabled = uiCanRedo) { withChromeRefresh { redo() } }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.action_layers), selected = uiLayersOpen) { toggleLayersPanel() }
                EinkToggle(stringResource(R.string.action_template), selected = uiTemplateOpen) { toggleTemplatePanel() }
                ToolbarDivider()

                EinkButton(stringResource(R.string.nav_prev), enabled = uiPageIndex > 0) { withChromeRefresh { goToPage(uiPageIndex - 1) } }
                Text(
                    stringResource(R.string.page_position, uiPageIndex + 1, uiPageCount),
                    color = EinkBlack,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                EinkButton(stringResource(R.string.nav_next), enabled = uiPageIndex < uiPageCount - 1) { withChromeRefresh { goToPage(uiPageIndex + 1) } }
                EinkButton(stringResource(R.string.action_insert_page)) { withChromeRefresh { insertPageAfterCurrent() } }
                EinkButton(stringResource(R.string.action_delete_page), enabled = uiPageCount > 1) { uiDeletePageDialog = true }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(EinkBlack))
        }
    }

    @Composable
    private fun ToolbarDivider() {
        Box(Modifier.width(1.dp).height(28.dp).background(EinkGray))
    }

    /** The panels and dialogs stacked over the canvas. Isolated here so their state reads never touch [CanvasView]. */
    @Composable
    private fun BoxScope.EditorOverlays() {
        if (uiLayersOpen) {
            Scrim { toggleLayersPanel() }
            LayersPanel(Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
        if (uiTemplateOpen) {
            Scrim { toggleTemplatePanel() }
            TemplatePanel(Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
        if (uiDeletePageDialog) {
            ConfirmDialog(
                title = stringResource(R.string.delete_page_title),
                message = stringResource(R.string.delete_page_message),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    uiDeletePageDialog = false
                    withChromeRefresh { deleteCurrentPage() }
                },
                onDismiss = { uiDeletePageDialog = false },
            )
        }
    }

    @Composable
    private fun Scrim(onDismiss: () -> Unit) {
        Box(Modifier.fillMaxSize().background(ScrimColor).clickable(onClick = onDismiss))
    }

    @Composable
    private fun LayersPanel(modifier: Modifier) {
        Column(
            modifier = modifier
                .width(280.dp)
                .background(EinkWhite)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.layers_title), color = EinkBlack, fontSize = 18.sp)
            for (row in uiLayers) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EinkCheckbox(checked = row.visible) { toggleLayerVisible(row.id, it) }
                    EinkRadioDot(selected = uiActiveLayer == row.id) { setActiveLayer(row.id) }
                    Text(row.name, color = EinkBlack, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    EinkButton(stringResource(R.string.item_delete), enabled = !row.isMain) { removeLayer(row.id) }
                }
            }
            EinkButton(stringResource(R.string.layers_add), enabled = uiLayers.size < PageEditSession.MAX_LAYERS) { addLayer() }
        }
    }

    @Composable
    private fun TemplatePanel(modifier: Modifier) {
        val current = uiTemplateRef
        val blankLabel = stringResource(R.string.template_blank)
        val linesLabel = stringResource(R.string.template_lines)
        val gridLabel = stringResource(R.string.template_grid)
        val options = buildList {
            add(blankLabel to TemplateRef.BLANK)
            add(linesLabel to TemplateRef.LINES)
            add(gridLabel to TemplateRef.GRID)
            uiTemplateFiles.forEach { add(it to (TemplateRef.USER_PREFIX + it)) }
        }
        Column(
            modifier = modifier
                .width(320.dp)
                .background(EinkWhite)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.template_title), color = EinkBlack, fontSize = 18.sp)
            for (pair in options.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, ref) in pair) {
                        val selected = if (ref == TemplateRef.BLANK) {
                            TemplateRef.parse(current) == TemplateRef.Blank
                        } else {
                            current == ref
                        }
                        EinkToggle(label, selected = selected, modifier = Modifier.weight(1f)) { setTemplate(ref) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    companion object {
        private const val TAG = "EditorActivity"

        /** Intent extra naming the notebook to open; absent means [DEFAULT_NOTEBOOK]. */
        const val EXTRA_NOTEBOOK_NAME = "com.nomadnotes.notebook_name"

        /** Opened when no notebook name is supplied (e.g. a launcher shortcut straight to the editor). */
        private const val DEFAULT_NOTEBOOK = "Default"

        /** Idle time after the last edit before the debounced autosave fires. */
        private const val AUTOSAVE_DELAY_MS = 5_000L

        /**
         * How long pen capture stays paused around a chrome action, so the Compose repaint reaches the
         * e-ink panel before raw drawing resumes (see [withChromeRefresh]). Tune on device.
         */
        private const val CHROME_REFRESH_MS = 100L

        /** Eraser disc radius, in page pixels, hit-tested at each gesture point. */
        private const val ERASER_RADIUS = 20f

        /**
         * Minimum spacing between live lasso-preview presents (a move drag or a draw outline), so a
         * fast gesture does not post more surface blits than the panel can keep up with. Pen-up
         * commits the exact final position regardless, so dropped intermediate frames are harmless.
         * Tune on device against the panel's refresh cadence.
         */
        private const val LASSO_PREVIEW_MIN_INTERVAL_MS = 80L

        /** How far a paste is offset from the copied strokes, in page pixels, so it does not hide them. */
        private const val PASTE_OFFSET_PX = 24f

        /** Image extensions offered as user templates from the `templates/` directory. */
        private val TEMPLATE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
}
