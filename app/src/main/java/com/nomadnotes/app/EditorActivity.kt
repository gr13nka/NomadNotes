package com.nomadnotes.app

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import com.nomadnotes.app.input.AndroidPenBackend
import com.nomadnotes.app.input.OnyxPenBackend
import com.nomadnotes.app.input.PenBackend
import com.nomadnotes.app.render.PageRenderer
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

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backendAttached = false
    private var surfaceReady = false

    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    // Toolbar/panel state, hoisted here so the AndroidView canvas can read none of it. The pen
    // listener also reads the drawing ones (tool/width/shade/active layer), but a plain value read
    // creates no recomposition dependency.
    private var uiTool by mutableStateOf(Tool.PEN)
    private var uiEraser by mutableStateOf(false)
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
        // Stop raw drawing before we background so the pen does not draw while we are not foreground.
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
        refreshUndoRedo()
        refreshLayers()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            renderer.resize(width, height)
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

    /** Rebuilds the whole composite and shows it. Use for anything but a lone new stroke. */
    private fun renderPage() {
        composePage()
        presentComposite()
    }

    /** Rebuilds the composite from the current page and template, without touching the surface. */
    private fun composePage() {
        val session = session ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val template = templateResolver.resolve(session.page.templateRef, surfaceWidth, surfaceHeight)
        renderer.renderFull(session.page, template)
    }

    /** Shows the current composite on the surface (the backend brackets the blit if its hardware needs it). */
    private fun presentComposite() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        backend.present(renderer.composite())
    }

    // --- pen input -------------------------------------------------------------------------

    private val penListener = object : PenBackend.Listener {
        override fun onStrokeFinished(points: List<StrokePoint>) {
            val session = session ?: return
            if (points.isEmpty()) return
            val page = session.page
            // Fall back to the main layer if the active-layer selection has gone stale — e.g. a future
            // structural op removed its layer without routing through afterModelEdit — so a finished
            // stroke lands on a real layer instead of throwing.
            val layerId = uiActiveLayer?.takeIf { id -> page.layers.any { it.id == id } } ?: page.mainLayerId
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
    }

    /**
     * Erases every active-layer stroke the eraser gesture passed over as one undoable step, then
     * repaints — even when nothing was erased — to wipe the eraser preview the backend left behind.
     */
    private fun eraseAlong(points: List<StrokePoint>) {
        val session = session ?: return
        val layerId = uiActiveLayer ?: session.page.mainLayerId
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
        // Repaint regardless, to wipe the eraser preview the backend left on the surface.
        renderPage()
    }

    // --- toolbar actions -------------------------------------------------------------------

    private fun selectTool(tool: Tool) {
        uiTool = tool
        uiEraser = false
        backend.eraseMode = false
        pushStrokeAppearance()
    }

    private fun selectEraser() {
        uiEraser = true
        backend.eraseMode = true
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
        Column(Modifier.fillMaxSize()) {
            EditorToolbar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CanvasView()
                EditorOverlays()
            }
        }
    }

    /** The drawing surface. Reads no Compose state and has no update block, so it never recomposes. */
    @Composable
    private fun CanvasView() {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
    }

    @Composable
    private fun EditorToolbar() {
        Column(
            Modifier
                .fillMaxWidth()
                .background(EinkWhite)
                .onGloballyPositioned { updateToolbarExclude(it.boundsInWindow()) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EinkToggle(stringResource(R.string.action_pen), selected = !uiEraser && uiTool == Tool.PEN) { selectTool(Tool.PEN) }
                EinkToggle(stringResource(R.string.action_pencil), selected = !uiEraser && uiTool == Tool.PENCIL) { selectTool(Tool.PENCIL) }
                EinkToggle(stringResource(R.string.action_marker), selected = !uiEraser && uiTool == Tool.MARKER) { selectTool(Tool.MARKER) }
                EinkToggle(stringResource(R.string.action_eraser), selected = uiEraser) { selectEraser() }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.width_small), selected = uiWidth == StrokeWidth.S) { setWidth(StrokeWidth.S) }
                EinkToggle(stringResource(R.string.width_medium), selected = uiWidth == StrokeWidth.M) { setWidth(StrokeWidth.M) }
                EinkToggle(stringResource(R.string.width_large), selected = uiWidth == StrokeWidth.L) { setWidth(StrokeWidth.L) }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.shade_black), selected = uiShade == InkShade.BLACK) { setShade(InkShade.BLACK) }
                EinkToggle(stringResource(R.string.shade_dark), selected = uiShade == InkShade.DARK) { setShade(InkShade.DARK) }
                EinkToggle(stringResource(R.string.shade_light), selected = uiShade == InkShade.LIGHT) { setShade(InkShade.LIGHT) }
                ToolbarDivider()

                EinkButton(stringResource(R.string.action_undo), enabled = uiCanUndo) { undo() }
                EinkButton(stringResource(R.string.action_redo), enabled = uiCanRedo) { redo() }
                ToolbarDivider()

                EinkToggle(stringResource(R.string.action_layers), selected = uiLayersOpen) { toggleLayersPanel() }
                EinkToggle(stringResource(R.string.action_template), selected = uiTemplateOpen) { toggleTemplatePanel() }
                ToolbarDivider()

                EinkButton(stringResource(R.string.nav_prev), enabled = uiPageIndex > 0) { goToPage(uiPageIndex - 1) }
                Text(
                    stringResource(R.string.page_position, uiPageIndex + 1, uiPageCount),
                    color = EinkBlack,
                    fontSize = 15.sp,
                )
                EinkButton(stringResource(R.string.nav_next), enabled = uiPageIndex < uiPageCount - 1) { goToPage(uiPageIndex + 1) }
                EinkButton(stringResource(R.string.action_insert_page)) { insertPageAfterCurrent() }
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
                    deleteCurrentPage()
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

        /** Eraser disc radius, in page pixels, hit-tested at each gesture point. */
        private const val ERASER_RADIUS = 20f

        /** Image extensions offered as user templates from the `templates/` directory. */
        private val TEMPLATE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
}
