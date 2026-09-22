package com.nomadnotes.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.nomadnotes.R
import com.nomadnotes.app.editor.ImageGrip
import com.nomadnotes.app.editor.ImagePlacement
import com.nomadnotes.app.editor.LinksMapController
import com.nomadnotes.app.editor.SelectionBounds
import com.nomadnotes.app.editor.SelectionState
import com.nomadnotes.app.editor.StickerDraft
import com.nomadnotes.app.editor.StickerFlowMode
import com.nomadnotes.app.editor.StickerFlowState
import com.nomadnotes.app.editor.TapClassifier
import com.nomadnotes.app.editor.surfaceToStickerSpace
import com.nomadnotes.app.input.AndroidPenBackend
import com.nomadnotes.app.input.CaptureMode
import com.nomadnotes.app.input.OnyxPenBackend
import com.nomadnotes.app.input.PenBackend
import com.nomadnotes.app.render.ImageResolver
import com.nomadnotes.app.render.PageRenderer
import com.nomadnotes.app.render.PageThumbnailCache
import com.nomadnotes.app.render.SelectionRenderer
import com.nomadnotes.app.render.StrokeRenderer
import com.nomadnotes.app.render.TemplateResolver
import com.nomadnotes.app.storage.EditorPrefs
import com.nomadnotes.app.storage.NotebookStorage
import com.nomadnotes.app.storage.notebooksRoot
import com.nomadnotes.app.ui.BorderWidth
import com.nomadnotes.app.ui.ConfirmDialog
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkMuted
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTheme
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.EinkWhite
import com.nomadnotes.app.ui.editor.BarMode
import com.nomadnotes.app.ui.editor.EditorBar
import com.nomadnotes.app.ui.editor.EditorBarActions
import com.nomadnotes.app.ui.editor.EditorBarState
import com.nomadnotes.app.ui.editor.EditorPanel
import com.nomadnotes.app.ui.editor.EditorTool
import com.nomadnotes.app.ui.editor.LinksMapPanel
import com.nomadnotes.app.ui.editor.LinksMapPanelActions
import com.nomadnotes.app.ui.editor.LinksMapPanelState
import com.nomadnotes.app.ui.editor.MorePanel
import com.nomadnotes.app.ui.editor.MorePanelActions
import com.nomadnotes.app.ui.editor.MorePanelState
import com.nomadnotes.app.ui.editor.PagePanel
import com.nomadnotes.app.ui.editor.PagePanelActions
import com.nomadnotes.app.ui.editor.PagePanelState
import com.nomadnotes.app.ui.editor.StickerPanel
import com.nomadnotes.app.ui.editor.StickerPanelActions
import com.nomadnotes.app.ui.editor.StickerPanelState
import com.nomadnotes.app.ui.editor.ToolPanel
import com.nomadnotes.app.ui.editor.ToolPanelActions
import com.nomadnotes.app.ui.editor.ToolPanelState
import com.nomadnotes.app.ui.editor.asCoreTool
import com.nomadnotes.core.ImageId
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.LinkId
import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.Notebook
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.Page
import com.nomadnotes.core.PageId
import com.nomadnotes.core.PageImage
import com.nomadnotes.core.PageLink
import com.nomadnotes.core.links.NodeRef
import com.nomadnotes.core.links.StickerPlacement
import com.nomadnotes.core.recent.lastPageOf
import com.nomadnotes.core.PageRect
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.edit.PageEditSession
import com.nomadnotes.core.geometry.Vec2
import com.nomadnotes.core.geometry.eraserHit
import com.nomadnotes.core.geometry.lassoCoversRegion
import com.nomadnotes.core.geometry.lassoSelect
import com.nomadnotes.core.ink.SmoothingLevel
import com.nomadnotes.core.ink.smoothStroke
import com.nomadnotes.core.pageWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Nib-width presets the toolbar offers, in page pixels before the renderer applies pressure.
 * Internal, not private: [com.nomadnotes.app.ui.editor.ToolPanelState] names the current preset
 * directly rather than re-deriving a value/label pair the Activity already owns.
 */
internal enum class StrokeWidth(val px: Float) { S(2f), M(4f), L(8f) }

/**
 * Ink-darkness presets ([Stroke.grayLevel]: 255 = black); the marker keeps its own translucency.
 * Internal for the same reason as [StrokeWidth]: [com.nomadnotes.app.ui.editor.ToolPanelState]
 * names the current preset directly.
 */
internal enum class InkShade(val level: Int) { BLACK(255), DARK(170), LIGHT(85) }

/**
 * A layer as the layers panel shows it: identity, label, visibility, and whether it is undeletable.
 * Internal, not private: [com.nomadnotes.app.ui.editor.MorePanelState] carries these rows straight
 * through to the panel rather than re-shaping them into a parallel type.
 */
internal data class LayerRow(val id: LayerId, val name: String, val visible: Boolean, val isMain: Boolean)

/**
 * What the target picker does once a page is chosen.
 *
 * [Create] attaches a fresh link over a just-lassoed region; [EditTarget] retargets the link the
 * user circled with the lasso. The picker is built once around both modes and drives them through
 * the same two-step notebook/page flow.
 */
private sealed interface LinkPickerMode {
    data class Create(val bounds: SelectionBounds) : LinkPickerMode
    data class EditTarget(val linkId: LinkId) : LinkPickerMode
}

/**
 * The open target picker's state: what it will do with the chosen page ([mode]), the notebooks it
 * offers ([notebooks] — null until the off-main-thread load finishes), and the notebook drilled
 * into for the page step ([chosenNotebook] — null while the notebook list is showing).
 */
private data class LinkPickerState(
    val mode: LinkPickerMode,
    val notebooks: List<Notebook>? = null,
    val chosenNotebook: Notebook? = null,
)

/**
 * A position a link jump departed from, so the step-back can return to it. Held by notebook *name*
 * (not id) and page id: the jump stack is in-memory only and dies with the process, so within one
 * session the directory name is a stable enough handle and lets the return load the notebook back
 * by name. [pageIndex] is that page's 0-based index within its notebook at the moment of the jump,
 * kept alongside [pageId] purely to render the jump-back line's label ("research #3") without a
 * lookup; navigation itself still resolves the target by [pageId].
 */
private data class JumpOrigin(val notebookName: String, val pageId: PageId, val pageIndex: Int)

/** Dims the screen behind an open panel and captures taps to dismiss it; no fade, instant. */
private val ScrimColor = Color(0x33000000)

/**
 * The link picker's thumbnail cell size — wider than PagePanel's own paging-strip cell (32 dp)
 * since here the grid is the picker's primary content, not a compact strip. Aspect mirrors
 * `PagePanel.PageThumbCell`'s own 400:520 page shape (that file's `PageAspectRatio`); duplicated
 * rather than shared because that constant is private to `PagePanel.kt`.
 */
private val LinkPickerThumbWidth = 88.dp
private const val LinkPickerThumbAspect = 400f / 520f

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
// Internal, not public: it implements the internal *Actions interfaces (ToolPanelActions.onSelectTool
// takes the internal EditorTool), and a public class cannot expose an internal type in a public
// member's signature. Referenced only within :app (NotebookListActivity's startActivity, by name from
// AndroidManifest.xml), so internal loses nothing.
internal class EditorActivity :
    ComponentActivity(),
    EditorBarActions,
    ToolPanelActions,
    PagePanelActions,
    MorePanelActions,
    StickerPanelActions,
    LinksMapPanelActions {

    private lateinit var surfaceView: SurfaceView
    private lateinit var storage: NotebookStorage
    private lateinit var templateResolver: TemplateResolver
    private lateinit var prefs: EditorPrefs

    // Renders and caches the link picker's page-grid thumbnails off the main thread; outlives any
    // one picker visit so reopening it or scrolling back to an already-rendered page costs nothing.
    private lateinit var thumbnailCache: PageThumbnailCache

    // Decodes placed images on demand. Reads the notebook through a lambda rather than holding one,
    // so the same resolver keeps working as the editor follows a link into another notebook.
    private val imageResolver = ImageResolver { ref ->
        notebook?.let { storage.imageFile(it, ref) }
    }

    private val renderer = PageRenderer(imageResolver)
    private val selectionRenderer = SelectionRenderer()

    // Owns the links map's own state (the loaded index, the centre stack, the selection, and its
    // chip bitmap cache) across opens; this Activity only loads the index off the main thread and
    // forwards actions — see LinksMapController's own doc.
    private val linksMapController = LinksMapController()

    // Draws the selected strokes onto the live move-drag preview (the same ink path a committed
    // stroke takes through PageRenderer, so the previewed strokes look identical to the result).
    private val strokeRenderer = StrokeRenderer()

    // Filtered scaling for the dragged image's preview, matching how PageRenderer draws a committed
    // one, so the picture does not change appearance the moment the pen lifts.
    private val imagePreviewPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Chosen in onCreate: the Onyx raw-drawing backend on Boox hardware, the plain touch backend
    // everywhere else. The editor talks only to the interface and never learns which it holds.
    private lateinit var backend: PenBackend

    // Raw-drawing exclude rects pushed to the backend, in surface-local pixels, keyed by chrome
    // element ("bar", "panel") so the bar and whichever panel is open can each update or remove their
    // own rect independently; the union is what actually goes to PenBackend.setExcludeRects, which
    // already accepts a list. Replaces the old single toolbarExcludeRect now that more than one
    // chrome element can be on screen at once.
    private val chromeRects = mutableMapOf<String, Rect>()

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

    // A pending repaint that swaps the hardware's raw wet ink for our own rendered ink, posted after
    // a stroke and cancelled by the next pen-down (see [scheduleInkSettle]).
    private var pendingInkSettle: Runnable? = null

    // The pending clean refresh that clears the ghosting a burst of undo/redo gestures leaves on the
    // panel (see [scheduleHistorySettle]); the page change itself is the gesture's only feedback now,
    // so this settle has nothing left to clear but the ghost.
    private var pendingHistorySettle: Runnable? = null

    // Whether the lasso latch a two-finger hold armed is currently on. Compose state, unlike most
    // backend-facing flags here, because the bar's tool bracket reads it through [currentEditorTool]
    // to show `[✎ lasso]` while armed — the gesture's only feedback now that the surface badge is
    // gone. Set by [armLassoForNextStroke], cleared by [clearLassoLatch]; see [armLassoForNextStroke]'s
    // doc for why it covers exactly one pen stroke rather than staying on for as long as the fingers
    // are held.
    private var uiLassoLatched by mutableStateOf(false)

    // The pending [clearLassoLatch] that fires if the lasso is armed but never drawn into, so the user
    // is never stranded in selection mode. Restarted by each new arm, cancelled once the latched
    // stroke commits. Mirrors [pendingSmoothingSettle]'s shape.
    private var pendingLassoLatchExpiry: Runnable? = null

    // Toolbar/panel state, hoisted here so the AndroidView canvas can read none of it. The pen
    // listener also reads the drawing ones (tool/width/shade/active layer), but a plain value read
    // creates no recomposition dependency.
    private var uiTool by mutableStateOf(Tool.PEN)
    private var uiEraser by mutableStateOf(false)
    private var uiLasso by mutableStateOf(false)
    private var uiWidth by mutableStateOf(StrokeWidth.M)
    private var uiShade by mutableStateOf(InkShade.BLACK)
    private var uiSmoothing by mutableStateOf(SmoothingLevel.AUTO)
    private var uiCanUndo by mutableStateOf(false)
    private var uiCanRedo by mutableStateOf(false)
    private var uiPageIndex by mutableStateOf(0)
    private var uiPageCount by mutableStateOf(1)
    // Which chrome panel (if any) is open, and the window-pixel bounds of the glyph that opened it,
    // so the panel can anchor under it (PanelAnchor.kt). Replaces the old uiLayersOpen/uiTemplateOpen:
    // Layers and Template are sub-pages of the More panel now (MorePanel.kt's own local state), not
    // panels of their own.
    private var uiOpenPanel by mutableStateOf(EditorPanel.NONE)
    private var uiOpenPanelAnchor by mutableStateOf(ComposeRect.Zero)
    private var uiLayers by mutableStateOf<List<LayerRow>>(emptyList())
    private var uiActiveLayer by mutableStateOf<LayerId?>(null)
    private var uiTemplateRef by mutableStateOf<String?>(null)
    private var uiTemplateFiles by mutableStateOf<List<String>>(emptyList())
    private var uiHasSelection by mutableStateOf(false)
    // The selected stroke count, for the bar's "N strokes caught" (0 while uiHasSelection is false).
    private var uiSelectionStrokeCount by mutableStateOf(0)
    private var uiSelectionOnMainLayer by mutableStateOf(false)
    private var uiClipboardHasContent by mutableStateOf(false)
    private var uiLinkPicker by mutableStateOf<LinkPickerState?>(null)

    // The open sticker panel's flow (which link it is for, and the draft it is filling), or null
    // when none is open. Suppresses pen capture exactly like uiLinkPicker (updateBackendEnabled) —
    // it usually opens right after the picker closes, continuing the same suppressed-capture span.
    private var uiStickerFlow by mutableStateOf<StickerFlowState?>(null)

    // Bumped on every drawn point, finished stroke, clear, or native-capture-region change, so the
    // sticker panel recomposes as the user draws or as capture hands off between the native path and
    // the Compose fallback. StickerDraft and stickerCaptureRegionActive are plain fields, not Compose
    // state — this is what tells Compose the live drawing it reads off `uiStickerFlow.draft`, and
    // `stickerCaptureRegionActive` itself, are stale (see stickerPanelState).
    private var uiStickerDraftTick by mutableStateOf(0)

    // The sticker drawing box's own surface-local bounds while native raw-drawing capture is
    // restricted to it (see updateStickerCaptureBox), or null before that first lands / once the
    // flow closes. Plain fields, not Compose state: both are read only by imperative pen-input code
    // (penListener, updateBackendEnabled), never by a composable.
    private var stickerCaptureBox: Rect? = null

    // Whether [PenBackend.setCaptureRegion] accepted the sticker box — native raw drawing is inking
    // it directly, lag-free like the main canvas, instead of StickerPanel's own Compose pointerInput
    // fallback. Gates updateBackendEnabled (native capture wants the backend ON but restricted to the
    // box; the fallback wants it fully OFF so Compose is the only capture path) and which branch of
    // penListener.onStrokeFinished a finished gesture routes through.
    private var stickerCaptureRegionActive = false

    // Bumped on every LinksMapController mutation (load, select, centre, back), the same role
    // uiStickerDraftTick plays for StickerDraft: the controller is a plain class with no Compose
    // state of its own, so this is what tells Compose linksMapPanelState()'s result is stale.
    private var uiLinksMapTick by mutableStateOf(0)

    // Whether the bar is hidden down to a single "[≡]" ("Just the page"), persisted like [uiSmoothing].
    private var uiChromeHidden by mutableStateOf(false)

    // The link the last lasso circled (its region centre enclosed), or null. Drives the "Edit link"/
    // "Delete link" actions in the selection bar; independent of any stroke selection the same circle
    // made, so both sets of actions can show at once. Cleared by [clearSelection] (and tool switches).
    private var uiCircledLink by mutableStateOf<LinkId?>(null)

    // The image the last lasso circled, or null. Drives the image frame drawn over the page, the
    // "Delete image" action, and whether a drag grabs an image. Cleared exactly where the circled
    // link is, so the two selections never outlive the gesture that made them.
    private var uiCircledImage by mutableStateOf<ImageId?>(null)

    // The image drag in flight, from the first sample to the finishing gesture; null when the current
    // gesture is not moving or resizing an image. `imageDragRect` is where the drag has got to, which
    // the preview draws and the commit stores.
    private var imageDrag: ImageDrag? = null
    private var imageDragRect: PageRect? = null

    // The broken link awaiting the "delete or cancel" dialog, or null when none is up. Set when a
    // tapped link resolves to a missing notebook or a missing page; the dialog offers to remove it.
    private var uiBrokenLinkDialog by mutableStateOf<PageLink?>(null)

    // Where past link jumps departed from, most recent last, so the toolbar "←" steps back through
    // them one at a time (Supernote-style). In-memory only — never persisted, never cleared by manual
    // page navigation — and capped at MAX_JUMP_STACK, dropping the oldest. `uiJumpDepth` mirrors its
    // size for the back button's visibility, updated on every push and pop.
    private val jumpStack = ArrayDeque<JumpOrigin>()
    private var uiJumpDepth by mutableStateOf(0)

    // The system photo picker. It grants read access to just the picked item for the length of the
    // callback, which needs no storage permission of our own — so the import must copy the bytes out
    // then and there (see [insertImage]).
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insertImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = notebooksRoot(this)
        Log.i(TAG, "Storage root: ${root.path}")
        storage = NotebookStorage(root)
        templateResolver = TemplateResolver(storage.templatesDir)
        prefs = EditorPrefs(this)
        thumbnailCache = PageThumbnailCache(storage)
        uiSmoothing = prefs.smoothing
        uiChromeHidden = prefs.chromeHidden
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
        // Likewise the settle repaints: backgrounding already repaints, and none of them must blit later.
        cancelInkSettle()
        cancelHistorySettle()
        cancelLassoLatchExpiry()
        // Drop the transient target picker so we do not resume onto a stale overlay.
        uiLinkPicker = null
        backend.setEnabled(false)
        // A lasso gesture still in flight when we background is dropped without a finishing callback
        // (disabling capture resets the collector), so clear its live-preview state now — otherwise the
        // next gesture would resume from it as a stale drag of the old selection from the old start.
        endLassoPreview()
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
        imageResolver.release()
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
                    // Reopen wherever this notebook was last left; lastPageOf already falls back past
                    // a since-deleted page, so the only remaining fallback here is "never visited".
                    val pageId = lastPageOf(storage.loadRecentVisits(), nb) ?: nb.pageIds.first()
                    val index = nb.pageIds.indexOf(pageId)
                    Triple(nb, storage.loadPage(nb, pageId), index)
                }
            }
            loaded
                .onSuccess { (nb, page, index) ->
                    notebook = nb
                    installPage(page, index)
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
        // The single funnel for "this page is now being edited" (initial open, page turn, insert,
        // delete, link jump all route through here), so recording the visit here is enough for
        // openNotebookFromIntent to later reopen this notebook where it was left.
        recordVisit(page.id)
    }

    /**
     * Records that [pageId] is now open, off the main thread like every other save. A no-op with no
     * notebook to record against (storage unavailable, an in-memory-only page) — there is nothing
     * durable to reopen later. Fire-and-forget: a failed write here only costs "reopens at the first
     * page instead of the last one" next time, not worth surfacing to the user.
     */
    private fun recordVisit(pageId: PageId) {
        val notebook = notebook ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { storage.recordVisit(notebook.id, pageId) }
                    .onFailure { e -> Log.w(TAG, "Could not record recent visit", e) }
            }
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            renderer.resize(width, height)
            // The decoration and drag-base scratches are surface-sized; drop them so they are rebuilt
            // at the new size (a resize mid-drag would otherwise blit through a wrong-sized base).
            decorationBitmap?.recycle()
            decorationBitmap = null
            dragBaseBitmap?.recycle()
            dragBaseBitmap = null
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
            backend.setInkSmoothing(uiSmoothing)
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
        val imageRect = circledImageRect()
        if (selection == null && imageRect == null) presentComposite(cleanRefresh)
        else presentDecorated(selection?.bounds, selectionPolygon, imageRect, cleanRefresh)
    }

    /**
     * Composites the page plus whichever decorations are live — the lasso [polygon], the selection
     * [bounds] box, the frame around a circled image at [imageRect] — into the scratch bitmap, then
     * presents it. Any of them may be null; they are independent outcomes of one lasso gesture.
     */
    private fun presentDecorated(
        bounds: SelectionBounds?,
        polygon: List<Vec2>?,
        imageRect: PageRect?,
        cleanRefresh: Boolean = false,
    ) {
        val scratch = decorationScratch()
        if (scratch == null) {
            presentComposite(cleanRefresh)
            return
        }
        val canvas = Canvas(scratch)
        canvas.drawBitmap(renderer.composite(), 0f, 0f, null)
        polygon?.let { selectionRenderer.drawPolygon(canvas, it) }
        bounds?.let { selectionRenderer.drawBounds(canvas, it) }
        imageRect?.let { selectionRenderer.drawImageFrame(canvas, it) }
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
        override fun onGestureStarted() {
            // The pen is down again: abandon the settle repaint rather than blit through the stroke.
            cancelInkSettle()
        }

        override fun onStrokeFinished(points: List<StrokePoint>) {
            if (points.isEmpty()) return
            // A gesture the backend captured natively inside the sticker box arrives on this same
            // callback (see routeStickerCaptureStroke) — it is never page ink, so intercept it before
            // touching session/link/tool state at all.
            if (stickerCaptureRegionActive) {
                routeStickerCaptureStroke(points)
                return
            }
            val session = session ?: return
            // A tap that starts on a link navigates instead of inking, using TapClassifier.isLinkTap's
            // more generous tolerance rather than the ordinary isTap — a real stylus tap on a small
            // link routinely drifts and lingers past isTap's tight thresholds. Only in INK mode
            // (ERASE/LASSO gestures arrive on other callbacks); anything not starting on a link falls
            // through to the ordinary ink path below unchanged.
            if (backend.captureMode == CaptureMode.INK) {
                val first = points.first()
                val link = linkAt(session, first.x, first.y)
                if (link != null && TapClassifier.isLinkTap(points)) {
                    navigateToLink(link)
                    return
                }
            }
            val layerId = activeLayerId(session)
            val stroke = Stroke(
                id = StrokeId.random(),
                tool = uiTool,
                widthBase = uiWidth.px,
                grayLevel = uiShade.level,
                // Smoothing runs after the tap test above, so link taps are still classified from
                // what the pen actually did rather than from a refitted curve.
                points = smoothStroke(points, uiSmoothing),
            )
            session.addStroke(layerId, stroke)
            // Always record the stroke into the layer bitmap. Present it only when the backend does
            // not paint wet ink itself: on Onyx the panel already shows this stroke, and a per-stroke
            // blit there (with raw drawing briefly disabled) is exactly what delays the next stroke.
            renderer.appendStroke(layerId, stroke)
            if (!backend.rendersWetInkNatively) presentComposite() else scheduleInkSettle()
            refreshUndoRedo()
            scheduleAutosave()
        }

        // Native sticker capture has no eraser of its own ([clear] is its only reset), and no lasso —
        // both would otherwise touch the page from underneath a modal that should be isolated from
        // it. A side-button erase or a two-/three-finger gesture landing inside the restricted box is
        // dropped rather than acted on; see the shared uiStickerFlow guard below on the intent
        // gestures (undo/redo/lasso-arm/swipe/finger-tap), which reach the surface-wide touch
        // listener that enabling the backend for native capture necessarily re-arms.
        override fun onEraseGesture(points: List<StrokePoint>) {
            if (uiStickerFlow != null) return
            eraseAlong(points)
        }

        override fun onLassoGesture(points: List<StrokePoint>) = endLassoGesture(points)

        override fun onLassoMove(point: StrokePoint) = previewLasso(point)

        override fun onUndoGesture() {
            if (uiStickerFlow != null) return
            undoByGesture()
        }

        override fun onRedoGesture() {
            if (uiStickerFlow != null) return
            redoByGesture()
        }

        override fun onLassoArmed() {
            if (uiStickerFlow != null) return
            armLassoForNextStroke()
        }

        // Reuse the Page panel's own prev/next exactly, so a swipe gets the same boundary no-op (no
        // page past the last, none before the first) and the same one-refresh chrome pause.
        override fun onSwipeNextPage() {
            if (uiStickerFlow != null) return
            onNextPage()
        }

        override fun onSwipePrevPage() {
            if (uiStickerFlow != null) return
            onPrevPage()
        }

        override fun onFingerTap(x: Float, y: Float) {
            if (uiStickerFlow != null) return
            onFingerTapAt(x, y)
        }
    }

    /**
     * A gesture the backend captured natively inside the sticker box ([updateStickerCaptureBox])
     * arrives on [penListener]'s ordinary drawing callback, since that backend has only the one.
     * Maps it from surface pixels into the draft's fixed sticker-space coordinates and folds it in,
     * then briefly pauses capture — the same [withChromeRefresh] dance any other chrome change gets
     * — so the panel's own Compose repaint (now drawing this stroke from the draft, like every other
     * committed one) reaches the screen and replaces the hardware's ephemeral wet ink.
     */
    private fun routeStickerCaptureStroke(points: List<StrokePoint>) {
        val flow = uiStickerFlow ?: return
        val box = stickerCaptureBox ?: return
        val mapped = points.map { p ->
            val (x, y) = surfaceToStickerSpace(
                p.x, p.y, box.left.toFloat(), box.top.toFloat(), box.width().toFloat(), box.height().toFloat(),
            )
            p.copy(x = x, y = y)
        }
        // The points were scaled into sticker space, so the width must be too, or the saved stroke
        // comes out thinner than the wet ink the user just saw.
        val widthInStickerSpace = flow.draft.widthBase * (LinkSticker.WIDTH / box.width().toFloat())
        flow.draft.addStroke(mapped, widthInStickerSpace)
        // Deliberately no repaint: the panel's wet ink already shows this stroke exactly, and
        // repainting per stroke pauses raw drawing (a visible blink) to redraw the same ink. As with
        // page ink, the draft only needs to reach the screen when the panel itself changes.
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
     *  - starting elsewhere is a DRAW — its growing outline (a dashed polyline) is previewed over the
     *    page on both backends. On Onyx a lasso is captured as ordinary touch with raw drawing off, so
     *    the outline blits the same way the move-drag preview does.
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
        val imageDrag = imageDrag
        if (dragging != null) {
            if (throttleAllowsPresent()) presentMoveDrag(dx = here.x - start.x, dy = here.y - start.y)
        } else if (imageDrag != null) {
            imageDragRect = imageDrag.rectAfter(dx = here.x - start.x, dy = here.y - start.y)
            if (throttleAllowsPresent()) presentImageDrag()
        } else {
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
        } else if (beginImageDrag(start)) {
            presentImageDrag() // lift the image off its base straight away
        } else {
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

    /** Presents the page with the in-progress lasso outline (a dashed polyline over the composite). */
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
        imageDrag = null
        imageDragRect = null
        lassoDrawSamples.clear()
        dragBaseBitmap?.recycle()
        dragBaseBitmap = null
    }

    /**
     * A LASSO gesture ended: tear down its live preview, then act on it. An empty path means the
     * gesture was abandoned (a touch cancel) — commit nothing and just repaint over whatever the
     * preview left on the surface; otherwise route it as a move or a new lasso ([handleLassoGesture]).
     *
     * Either way, this is also the one place a lasso latch a two-finger hold armed gets released
     * ([clearLassoLatch]) — after the gesture is fully handled, not before, so an abandoned gesture
     * still restores whatever capture mode the toolbar asks for. A no-op when the toolbar's own
     * [uiLasso] is what put the backend in LASSO mode, since nothing was latched.
     */
    private fun endLassoGesture(points: List<StrokePoint>) {
        val hadPreview = lassoGestureStart != null
        endLassoPreview()
        if (points.isEmpty()) {
            if (hadPreview) present()
        } else {
            handleLassoGesture(points)
        }
        clearLassoLatch()
    }

    /**
     * A finished LASSO-mode gesture. The backend cannot tell a lasso from a drag, so the decision is
     * made here from what is currently picked out: a gesture starting inside the selection box moves
     * the selected strokes, one starting on a circled image moves or resizes it, and anything else
     * draws a new lasso — each by the start→end delta. The live preview (see [previewLasso]) tracks
     * the same decision from the same pen-down; this is the authoritative commit at pen-up.
     */
    private fun handleLassoGesture(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val selection = selection
        val start = points.first()
        val end = points.last()
        val startPoint = Vec2(start.x, start.y)
        val imageDrag = imageDragFor(startPoint)
        if (selection != null && selection.bounds.contains(start.x, start.y)) {
            commitMove(selection, dx = end.x - start.x, dy = end.y - start.y)
        } else if (imageDrag != null) {
            commitImageDrag(imageDrag, dx = end.x - start.x, dy = end.y - start.y)
        } else {
            applyLasso(points.map { Vec2(it.x, it.y) })
        }
    }

    /**
     * Acts on a new lasso [polygon]: selects every active-layer stroke it fully encloses, and — as
     * independent outcomes — marks the link and the image it circles (if any) for their own actions. A
     * circle can do all of these, some, or none. A circle that catches no strokes still clears the
     * stroke selection but keeps a circled link or image, so a link-only or image-only circle is
     * possible: circling an image is how you pick it up to move, resize, or delete it.
     */
    private fun applyLasso(polygon: List<Vec2>) {
        val session = session ?: return
        val layerId = activeLayerId(session)
        val layer = session.page.layers.first { it.id == layerId }
        val ids = lassoSelect(layer.strokes, polygon).toSet()
        uiCircledLink = circledLinkId(session, polygon)
        uiCircledImage = layer.images.firstOrNull { lassoCoversRegion(it.rect, polygon) }?.id
        if (ids.isEmpty()) {
            selection = null
            selectionPolygon = null
            updateSelectionUi()
            // Repaint to wipe the lasso preview the backend left on the surface (as the eraser does).
            // Through [present], so a circle that caught no strokes but did catch an image still draws
            // that image's frame.
            present()
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

    /** Clears the selection and wipes its decorations. Present-wise a no-op when nothing was showing. */
    private fun clearSelection() {
        // An image frame is an overlay decoration like the selection box, so dropping either needs a
        // repaint to wipe it. A circled link needs none — its affordance is painted into the page
        // composite itself and stays there.
        val had = selection != null || uiCircledImage != null
        selection = null
        selectionPolygon = null
        // A circled link or image is part of the same lasso outcome as the selection, so both clear
        // with it — which is how a tool switch (routing through here) drops their actions.
        uiCircledLink = null
        uiCircledImage = null
        // The picker is anchored to a selection, so clearing the selection dismisses it — this is how
        // a tool switch (which routes through here) also closes it. The picker's own scrim blocks the
        // toolbar while it is open, so this never runs with the picker actually up, and the backend
        // resume it would need is handled by the confirm/cancel paths instead.
        uiLinkPicker = null
        updateSelectionUi()
        // The decoration scratch is only needed while a selection is on screen; free it now and let
        // the next selection rebuild it on demand (decorationScratch), rather than holding a
        // surface-sized bitmap through every non-selection edit.
        decorationBitmap?.recycle()
        decorationBitmap = null
        // Through present(), not presentComposite(): a live badge (the armed lasso latch, or a
        // still-settling undo acknowledgement) must survive a selection clear that happens alongside it.
        if (had) present()
    }

    /**
     * A single finger tapped down and lifted at ([x], [y]) without dragging or turning into a swipe
     * (see [PenBackend.Listener.onFingerTap]). While a selection is showing, a tap outside it clears
     * it — the same outcome as the bar's `[×]` ([onDeselect]) — so poking at the page to look at
     * something else dismisses the selection instead of leaving it stranded until the next lasso or
     * tool switch. A tap inside does nothing new. With nothing selected, a tap on a link (its region
     * or sticker card) navigates instead — the finger counterpart of a stylus link tap
     * ([penListener]'s `onStrokeFinished`), since a plain touch never reaches that pen-only path.
     */
    private fun onFingerTapAt(x: Float, y: Float) {
        if (selection != null || uiCircledImage != null || uiCircledLink != null) {
            if (!insideSelectionAffordance(x, y)) withChromeRefresh { clearSelection() }
            return
        }
        val session = session ?: return
        val link = linkAt(session, x, y) ?: return
        navigateToLink(link)
    }

    /**
     * Whether ([x], [y]) falls inside whatever a lasso selection currently shows: the stroke
     * selection box, a circled image's frame, or a circled link's region. Surface pixels and page
     * pixels are the same space here — this app never scales or pans the page relative to the
     * surface — so a touch coordinate can be tested against these bounds directly, with no
     * conversion. Used by [onFingerTapAt] to tell "tapped the selection" from "tapped elsewhere".
     */
    private fun insideSelectionAffordance(x: Float, y: Float): Boolean {
        selection?.bounds?.let { if (it.contains(x, y)) return true }
        circledImageRect()?.let { if (it.contains(x, y)) return true }
        val linkRegion = uiCircledLink?.let { id -> session?.page?.links?.firstOrNull { it.id == id }?.region }
        return linkRegion?.contains(x, y) == true
    }

    private fun updateSelectionUi() {
        val selection = selection
        uiHasSelection = selection != null
        uiSelectionStrokeCount = selection?.strokeIds?.size ?: 0
        // A link binds to the main layer's handwriting (spec), so the "Link" action is offered only
        // for a main-layer selection.
        uiSelectionOnMainLayer = selection != null && selection.layerId == session?.page?.mainLayerId
    }

    /** The active layer if it is still on the page, else the main layer — so an edit never targets a gone layer. */
    private fun activeLayerId(session: PageEditSession): LayerId =
        uiActiveLayer?.takeIf { id -> session.page.layers.any { it.id == id } } ?: session.page.mainLayerId

    /** A deep copy of the stroke with a fresh id and its points shifted by ([dx], [dy]). */
    private fun Stroke.offsetCopy(dx: Float, dy: Float): Stroke =
        copy(id = StrokeId.random(), points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })

    // --- images ------------------------------------------------------------------------------

    /**
     * Places the picture at [uri] on the active layer, sized to fit the page.
     *
     * The file is copied into the notebook rather than referenced where it sits: the picker grants
     * read access only for this call, and a note that silently loses its pictures when the user
     * tidies their gallery would be worse than one that costs a little disk.
     */
    private fun insertImage(uri: Uri) {
        val session = session ?: return
        val notebook = notebook ?: return
        val layerId = activeLayerId(session)
        lifecycleScope.launch {
            val placed = withContext(Dispatchers.IO) {
                runCatching {
                    val size = readImageSize(uri) ?: return@runCatching null
                    val assetRef = contentResolver.openInputStream(uri)?.use { stream ->
                        storage.importImage(notebook, stream, extensionOf(uri))
                    } ?: return@runCatching null
                    PageImage(
                        id = ImageId.random(),
                        assetRef = assetRef,
                        rect = defaultPlacement(size.first, size.second),
                    )
                }.onFailure { Log.w(TAG, "Could not import image $uri", it) }.getOrNull()
            } ?: return@launch
            session.addImage(layerId, placed)
            // A photo is continuous tone, which the fast additive e-ink mode renders as a smear of
            // ghosting; ask for the clean waveform the way erasing does.
            renderPage(cleanRefresh = true)
            refreshUndoRedo()
            scheduleAutosave()
        }
    }

    /** The picked image's pixel dimensions, read from its header alone, or null if it is not an image. */
    private fun readImageSize(uri: Uri): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    /** The file type to store the picked image as, from its MIME type; PNG when that is unknown. */
    private fun extensionOf(uri: Uri): String {
        val mime = contentResolver.getType(uri) ?: return DEFAULT_IMAGE_EXT
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: DEFAULT_IMAGE_EXT
    }

    /**
     * Where a newly inserted image goes: centred on the page, its aspect ratio kept, scaled to at
     * most [INSERTED_IMAGE_PAGE_FRACTION] of the surface. Big enough to see and work with, small
     * enough to leave the page's writing visible around it, and the user resizes from there.
     */
    private fun defaultPlacement(sourceWidth: Int, sourceHeight: Int): PageRect {
        val maxWidth = surfaceWidth * INSERTED_IMAGE_PAGE_FRACTION
        val maxHeight = surfaceHeight * INSERTED_IMAGE_PAGE_FRACTION
        val scale = minOf(maxWidth / sourceWidth, maxHeight / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (surfaceWidth - width) / 2f
        val top = (surfaceHeight - height) / 2f
        return PageRect(left = left, top = top, right = left + width, bottom = top + height)
    }

    /** The rectangle of the currently circled image, or null when none is circled or it has gone. */
    private fun circledImageRect(): PageRect? {
        val id = uiCircledImage ?: return null
        val session = session ?: return null
        return session.page.layers
            .firstOrNull { it.id == activeLayerId(session) }
            ?.images?.firstOrNull { it.id == id }
            ?.rect
    }

    /** The circled image together with the layer holding it, or null when there is no usable one. */
    private fun circledImage(): Pair<LayerId, PageImage>? {
        val id = uiCircledImage ?: return null
        val session = session ?: return null
        val layerId = activeLayerId(session)
        val image = session.page.layers
            .firstOrNull { it.id == layerId }
            ?.images?.firstOrNull { it.id == id }
            ?: return null
        return layerId to image
    }

    /**
     * What a drag starting at [start] does to the circled image: resize it from a corner grip, move it
     * from anywhere else inside it, or nothing at all when the pen came down elsewhere — in which case
     * the gesture goes on to be an ordinary lasso.
     *
     * Shared by the live preview and the commit so both reach the same verdict from the same pen-down.
     */
    private fun imageDragFor(start: Vec2): ImageDrag? {
        val (layerId, image) = circledImage() ?: return null
        val grip = ImagePlacement.gripAt(image.rect, start.x, start.y, SelectionRenderer.HANDLE_TOUCH_PX)
        if (grip == null && !image.rect.contains(start.x, start.y)) return null
        return ImageDrag(layerId, image, grip)
    }

    /**
     * Enters an image drag if [start] grabbed the circled image, and returns whether it did. Renders
     * [dragBaseBitmap] — the page without that image — so each preview frame is that base plus the
     * picture redrawn at its current rectangle, exactly as a stroke move works.
     */
    private fun beginImageDrag(start: Vec2): Boolean {
        val session = session ?: return false
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return false
        val drag = imageDragFor(start) ?: return false
        val template = templateResolver.resolve(session.page.templateRef, surfaceWidth, surfaceHeight)
        renderer.renderFull(session.page, template, excludeImageIds = setOf(drag.image.id))
        dragBaseBitmap?.recycle()
        val base = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
            .also { dragBaseBitmap = it }
        Canvas(base).drawBitmap(renderer.composite(), 0f, 0f, null)
        // Restore the shared composite to the full page, so a cancel or a zero-delta commit repaints
        // from it without a stale exclusion (as [beginMoveDrag] does).
        renderer.renderFull(session.page, template)
        imageDrag = drag
        imageDragRect = drag.image.rect
        return true
    }

    /** Presents [dragBaseBitmap] with the dragged image and its frame redrawn at the current rectangle. */
    private fun presentImageDrag() {
        val base = dragBaseBitmap ?: return
        val drag = imageDrag ?: return
        val rect = imageDragRect ?: return
        val scratch = decorationScratch() ?: return
        val canvas = Canvas(scratch)
        canvas.drawBitmap(base, 0f, 0f, null)
        imageResolver.resolve(drag.image.copy(rect = rect))?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, RectF(rect.left, rect.top, rect.right, rect.bottom), imagePreviewPaint)
        }
        selectionRenderer.drawImageFrame(canvas, rect)
        backend.presentDuringCapture(scratch)
    }

    /**
     * Commits a finished image drag as one undoable step. A drag that left the image exactly where it
     * was just repaints, to wipe the preview the backend left on the surface.
     */
    private fun commitImageDrag(drag: ImageDrag, dx: Float, dy: Float) {
        val session = session ?: return
        val rect = drag.rectAfter(dx, dy)
        if (!session.setImageRect(drag.layerId, drag.image.id, rect)) {
            present()
            return
        }
        refreshUndoRedo()
        // Moving a picture uncovers what was beneath it, which the fast additive e-ink mode leaves
        // ghosted — the same reason erasing asks for the clean waveform.
        renderPage(cleanRefresh = true)
        scheduleAutosave()
    }

    /** Removes the circled image as one undoable step. */
    private fun deleteCircledImage() {
        val session = session ?: return
        val (layerId, image) = circledImage() ?: return
        uiCircledImage = null
        if (!session.removeImage(layerId, image.id)) return
        refreshUndoRedo()
        renderPage(cleanRefresh = true)
        scheduleAutosave()
    }

    /**
     * One image drag in flight: which picture, on which layer, and by which corner (null when the
     * whole image is being moved). Holds the image as it was at pen-down, so every frame is computed
     * from the original rectangle and the total delta rather than accumulating rounding.
     */
    private class ImageDrag(
        val layerId: LayerId,
        val image: PageImage,
        val grip: ImageGrip?,
    ) {
        fun rectAfter(dx: Float, dy: Float): PageRect =
            if (grip == null) {
                ImagePlacement.moved(image.rect, dx, dy)
            } else {
                ImagePlacement.resized(image.rect, grip, dx, dy, MIN_IMAGE_SIZE_PX)
            }
    }

    // --- link target picker ----------------------------------------------------------------

    /** Opens the target picker for the current main-layer selection, to link its region to a page. */
    private fun openLinkPickerForSelection() {
        val selection = selection ?: return
        if (selection.layerId != session?.page?.mainLayerId) return
        openLinkPicker(LinkPickerMode.Create(selection.bounds))
    }

    /**
     * Shows the picker in [mode] and loads the notebooks it offers off the main thread. Suppresses
     * pen capture while it is open (via [updateBackendEnabled]), like an open chrome panel, so a tap
     * meant for the picker never draws.
     */
    private fun openLinkPicker(mode: LinkPickerMode) {
        uiLinkPicker = LinkPickerState(mode = mode)
        updateBackendEnabled()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    storage.listNotebooks().mapNotNull { ref ->
                        runCatching { storage.loadNotebook(ref.name) }.getOrNull()
                    }
                }.getOrDefault(emptyList())
            }
            // Drop the result if the picker was dismissed while loading. The list is the same for any
            // open picker, so filling in whichever one is now up is correct.
            uiLinkPicker = uiLinkPicker?.copy(notebooks = loaded)
        }
    }

    /** Closes the picker and restores pen capture (which the open picker suppressed). The selection stays. */
    private fun closeLinkPicker() {
        uiLinkPicker = null
        updateBackendEnabled()
    }

    /**
     * A page was chosen in the picker: act on it per the picker's mode. A fresh link still needs
     * its sticker, so [LinkPickerMode.Create] hands off to the sticker panel rather than adding the
     * link itself ([openStickerFlow] closes the picker); retargeting an existing link touches
     * nothing else, so [LinkPickerMode.EditTarget] still commits immediately.
     */
    private fun confirmLinkTarget(picker: LinkPickerState, targetNotebook: Notebook, targetPageId: PageId) {
        when (val mode = picker.mode) {
            is LinkPickerMode.Create -> openStickerFlow(StickerFlowMode.Create(mode.bounds, targetNotebook, targetPageId))
            is LinkPickerMode.EditTarget -> retargetLink(mode.linkId, targetNotebook, targetPageId)
        }
    }

    /**
     * Attaches a link over the lassoed region ([bounds] padded outward) pointing at ([targetNotebook],
     * [targetPageId]) with [sticker] (possibly null — Skip, or a Done on an empty draft), then drops
     * the selection and sticker flow and repaints so the new affordance shows — the single-present
     * tail of [deleteSelection] (null the state, then one [renderPage]), not a [clearSelection] that
     * would blit a link-less frame first. Re-enables pen capture last, after the present, so raw
     * drawing does not resume before the frame reaches the panel. The only caller is
     * [commitStickerFlow], one step after the picker that gathered [bounds]/[targetNotebook]/
     * [targetPageId] — see [confirmLinkTarget] — so a link with a sticker is still added in this one
     * undoable step ([PageEditSession.addLink]).
     */
    private fun createLink(bounds: SelectionBounds, targetNotebook: Notebook, targetPageId: PageId, sticker: LinkSticker?) {
        val session = session ?: return
        val region = PageRect(
            left = bounds.left - LINK_REGION_PADDING_PX,
            top = bounds.top - LINK_REGION_PADDING_PX,
            right = bounds.right + LINK_REGION_PADDING_PX,
            bottom = bounds.bottom + LINK_REGION_PADDING_PX,
        )
        session.addLink(PageLink(LinkId.random(), region, targetNotebook.id, targetPageId, sticker))
        selection = null
        selectionPolygon = null
        uiStickerFlow = null
        updateSelectionUi()
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
        updateBackendEnabled()
    }

    /**
     * Opens the target picker to retarget the circled link. The circled id can be stale — the link may
     * have been undone since it was circled — so a link that no longer exists just clears the circle
     * instead of opening the picker onto nothing.
     */
    private fun editCircledLink() {
        val session = session ?: return
        val id = uiCircledLink ?: return
        if (session.page.links.none { it.id == id }) {
            uiCircledLink = null
            return
        }
        openLinkPicker(LinkPickerMode.EditTarget(id))
    }

    /**
     * Opens the sticker panel to replace the circled link's sticker — the same stale-id guard as
     * [editCircledLink], since the circle can outlive the link it named.
     */
    private fun editCircledLinkSticker() {
        val session = session ?: return
        val id = uiCircledLink ?: return
        if (session.page.links.none { it.id == id }) {
            uiCircledLink = null
            return
        }
        openStickerFlow(StickerFlowMode.Edit(id))
    }

    /**
     * Shows the sticker panel for [mode], seeding its draft from the link's existing sticker for
     * [StickerFlowMode.Edit] (empty for a still-unattached [StickerFlowMode.Create]) and suppressing
     * pen capture for as long as it is open, exactly like the target picker it usually follows —
     * closing the picker here continues that same suppressed-capture span rather than briefly
     * re-enabling capture between the two.
     */
    private fun openStickerFlow(mode: StickerFlowMode) {
        uiLinkPicker = null
        val existingStrokes = when (mode) {
            is StickerFlowMode.Create -> emptyList()
            is StickerFlowMode.Edit -> session?.page?.links?.firstOrNull { it.id == mode.linkId }?.sticker?.strokes.orEmpty()
        }
        uiStickerFlow = StickerFlowState(mode, StickerDraft(uiTool, uiWidth.px, uiShade.level, existingStrokes))
        // Native capture engages once the box's own bounds land (updateStickerCaptureBox); until then
        // capture stays off, same as commitStickerFlow/cancelStickerFlow leave it on the way out.
        stickerCaptureBox = null
        stickerCaptureRegionActive = false
        updateBackendEnabled()
    }

    /**
     * Finishes the open sticker panel with [sticker] (null for Skip, or a Done on an empty draft):
     * adds the pending link for a Create flow ([createLink]), or updates the existing link's sticker
     * for an Edit flow. Both branches repaint and autosave, matching [createLink]'s own tail for the
     * Edit case since it is not the one that already does so.
     */
    private fun commitStickerFlow(sticker: LinkSticker?) {
        val flow = uiStickerFlow ?: return
        // Restored before either branch's own updateBackendEnabled() call, so it decides capture
        // on/off against the already-normal region/mode rather than the sticker box's.
        closeStickerCapture()
        when (val mode = flow.mode) {
            is StickerFlowMode.Create -> createLink(mode.bounds, mode.targetNotebook, mode.targetPageId, sticker)
            is StickerFlowMode.Edit -> {
                session?.setLinkSticker(mode.linkId, sticker)
                uiCircledLink = null
                uiStickerFlow = null
                refreshUndoRedo()
                renderPage()
                scheduleAutosave()
                updateBackendEnabled()
            }
        }
        updateChromeExclude(STICKER_RECT_KEY, null)
    }

    /**
     * Dismisses the sticker panel with no change: no link at all for a cancelled Create (nothing was
     * ever added to the page), and whatever sticker the link already had for a cancelled Edit.
     * Mirrors [closeLinkPicker]'s own minimalism.
     */
    private fun cancelStickerFlow() {
        uiStickerFlow = null
        closeStickerCapture()
        updateChromeExclude(STICKER_RECT_KEY, null)
        updateBackendEnabled()
    }

    /**
     * Restricts pen capture to the sticker box, or reports that this backend cannot — reported by
     * [StickerPanel]'s own drawing-box bounds callback, distinct from the whole-panel bounds
     * [updateStickerPanelExclude] handles. A stale callback from a panel that has already closed is
     * dropped. Forces [PenBackend.captureMode] to INK once engaged: native capture wants exactly the
     * draft's own semantics for a finished gesture — an ink stroke to fold in, never an erase or a
     * lasso — regardless of whatever the main canvas's own tool was set to before this flow opened
     * (the bar itself is unreachable while the flow's full-screen catcher is up, so nothing else can
     * change it meanwhile).
     */
    private fun updateStickerCaptureBox(windowRect: Rect) {
        if (uiStickerFlow == null) return
        val rect = toSurfaceLocal(windowRect)
        if (rect == stickerCaptureBox) return
        stickerCaptureBox = rect
        stickerCaptureRegionActive = backend.setCaptureRegion(rect)
        Log.d(TAG, "stickerCaptureBox rect=$rect nativeCaptureActive=$stickerCaptureRegionActive")
        if (stickerCaptureRegionActive) {
            backend.captureMode = CaptureMode.INK
            // The whole-panel exclude (updateStickerPanelExclude) can land before this box's own
            // bounds do, on the layout pass that opens the panel — that rect fully contains the box,
            // so drop it now rather than let it linger in chromeRects: any later, unrelated chrome
            // exclude push (e.g. the bar reporting new bounds) would resend it and exclude the whole
            // box right back out of capture.
            updateChromeExclude(STICKER_RECT_KEY, null)
        } else {
            backend.setCaptureRegion(null)
        }
        // Compose isn't watching stickerCaptureRegionActive itself (see the field's doc) — bump the
        // same tick StickerDraft's own mutations use so the panel recomposes and drops its Compose
        // pointerInput fallback now that native capture owns the box (stickerPanelState).
        uiStickerDraftTick++
        updateBackendEnabled()
    }

    /**
     * Restores ordinary pen capture after the sticker flow closes, undoing [updateStickerCaptureBox]
     * — the box's own restricted region and, if it was engaged, the forced INK capture mode (back to
     * whatever the toolbar's tool/eraser/lasso state now asks for). Safe to call unconditionally:
     * [PenBackend.setCaptureRegion] with `null` is a no-op on a backend that was never restricted.
     */
    private fun closeStickerCapture() {
        val wasActive = stickerCaptureRegionActive
        stickerCaptureBox = null
        stickerCaptureRegionActive = false
        backend.setCaptureRegion(null)
        if (wasActive) applyCaptureMode()
    }

    /**
     * The sticker panel's own window bounds, pushed as a chrome exclude rect like any other panel —
     * but skipped while native capture has already restricted raw drawing to just the drawing box
     * ([stickerCaptureRegionActive]): that box sits strictly inside the panel, so excluding the whole
     * panel would exclude the box too, leaving nothing left to ink. The box's own restricted region
     * already keeps every other panel pixel (buttons, borders) out of capture on its own.
     */
    private fun updateStickerPanelExclude(windowRect: Rect) {
        if (stickerCaptureRegionActive) return
        updateChromeExclude(STICKER_RECT_KEY, windowRect)
    }

    /**
     * Retargets the circled link to ([targetNotebook], [targetPageId]) and repaints with the same
     * single-present tail as [createLink], restoring pen capture last (the picker had suppressed it).
     * Only the link is touched: any stroke selection made by the same circle is left intact. A link
     * gone since the picker opened makes [PageEditSession.setLinkTarget] a no-op; the tail still tidies.
     */
    private fun retargetLink(linkId: LinkId, targetNotebook: Notebook, targetPageId: PageId) {
        val session = session ?: return
        session.setLinkTarget(linkId, targetNotebook.id, targetPageId)
        uiCircledLink = null
        uiLinkPicker = null
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
        updateBackendEnabled()
    }

    /**
     * Removes the circled link and repaints so its affordance disappears (the single-present tail).
     * Only the link is touched; any stroke selection stays. The circled id can be stale (the link was
     * undone while circled), so a [PageEditSession.removeLink] that finds nothing just clears the
     * circle without a redundant repaint or save.
     */
    private fun deleteCircledLink() {
        val session = session ?: return
        val id = uiCircledLink ?: return
        uiCircledLink = null
        if (!session.removeLink(id)) return
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
    }

    // --- toolbar actions -------------------------------------------------------------------

    private fun selectTool(tool: Tool) {
        if (uiTool == tool && !uiEraser && !uiLasso) return
        uiTool = tool
        uiEraser = false
        uiLasso = false
        clearSelection()
        applyCaptureMode()
        pushStrokeAppearance()
    }

    private fun selectEraser() {
        if (uiEraser) return
        uiEraser = true
        uiLasso = false
        clearSelection()
        applyCaptureMode()
    }

    /** Enters lasso mode: a lasso encloses strokes into a selection to move, copy, or delete. */
    private fun selectLasso() {
        if (uiLasso) return
        uiLasso = true
        uiEraser = false
        clearSelection()
        applyCaptureMode()
    }

    /**
     * Arms the lasso latch for exactly the next pen stroke, from a two-finger hold
     * ([PenBackend.Listener.onLassoArmed]). One stroke, not a held modifier like [selectLasso]'s
     * toolbar tool: the fingers are free the instant this arms, because pausing raw drawing to switch
     * into LASSO makes this firmware destroy the finger touch stream the hold was made of (see
     * [com.nomadnotes.app.editor.MultiFingerGestures]'s KDoc) — there is no "the fingers are still
     * down" state left to hold the capture mode open with. So the latch instead covers exactly the
     * next pen gesture, released by [endLassoGesture] when that gesture finishes, or by
     * [LASSO_LATCH_MS] expiring if the user never draws into it.
     *
     * Re-arming while already latched (a second hold before the first stroke lands) restarts the
     * expiry window rather than doing nothing, so the user always gets the full window from their
     * latest hold.
     *
     * Goes through [withChromeRefresh] because the arming's only feedback is now the bar's tool
     * bracket flipping to `[✎ lasso]` (see [currentEditorTool]), a Compose repaint that — like any
     * other bar-mode change — needs raw drawing briefly paused to reach the panel. That costs the
     * same [CHROME_REFRESH_MS] dead window every other chrome action already accepts; if a device
     * pass ever shows the lasso's opening stroke landing as ink instead, this window is the first
     * suspect.
     */
    private fun armLassoForNextStroke() = withChromeRefresh {
        uiLassoLatched = true
        applyCaptureMode()
        scheduleLassoLatchExpiry()
    }

    /**
     * Releases the lasso latch [armLassoForNextStroke] set, restoring whatever capture mode the
     * toolbar's own tool currently asks for and refreshing the bar so its tool bracket drops the
     * `[✎ lasso]` label. A no-op when nothing is latched, which is what makes this safe to call from
     * [endLassoGesture] even when the toolbar's own [uiLasso] (not a two-finger hold) is what put the
     * backend in LASSO mode.
     */
    private fun clearLassoLatch() {
        if (!uiLassoLatched) return
        cancelLassoLatchExpiry()
        withChromeRefresh {
            uiLassoLatched = false
            applyCaptureMode()
        }
    }

    /**
     * Pushes the capture mode the toolbar tool and the lasso latch together ask for — the single
     * place that decides [PenBackend.captureMode], so [selectTool], [selectEraser], [selectLasso],
     * [armLassoForNextStroke], and [clearLassoLatch] all route through it instead of each assigning
     * the backend directly.
     *
     * The latch is a one-shot borrow and the toolbar tool is durable, so releasing it must fall back
     * to whatever the toolbar currently asks for rather than blindly resetting to INK; and picking a
     * tool while the latch is armed must not cancel the lasso stroke it is waiting on. Reading both
     * inputs from one function, called from every place either can change, is what keeps the two from
     * fighting over the backend's one capture mode.
     */
    private fun applyCaptureMode() {
        backend.captureMode = when {
            uiLasso || uiLassoLatched -> CaptureMode.LASSO
            uiEraser -> CaptureMode.ERASE
            else -> CaptureMode.INK
        }
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
     * A two-finger tap: undo the last edit. The page change itself is the acknowledgement — no badge
     * — so a tap with nothing to undo is silent, same as the toolbar's Undo button disabled.
     *
     * Ghosting — undo *removes* ink, which the fast additive e-ink update leaves faintly ghosted
     * behind. The repaint here is immediate but not a clean refresh, so a burst of undos stays cheap;
     * [scheduleHistorySettle] queues the one clean refresh that clears the ghosts once the burst pauses.
     *
     * Deliberately not wrapped in [withChromeRefresh], unlike the toolbar's Undo button: this path
     * does not need to pause pen capture for a Compose frame right when the user is about to keep
     * writing. The cost is that the toolbar's Undo/Redo buttons can show a stale enabled state on the
     * panel until the next chrome refresh.
     */
    private fun undoByGesture() {
        val session = session ?: return
        val undone = session.undo()
        scheduleHistorySettle()
        if (undone) afterModelEdit() else present()
    }

    /**
     * A three-finger tap: redo the last undone edit. Mirrors [undoByGesture] exactly — same ghosting
     * settle, same reason for not wrapping in [withChromeRefresh] — because a redo that restores
     * erased ink ghosts the panel exactly as an undo that removes ink does, just by the update
     * running in the other direction.
     */
    private fun redoByGesture() {
        val session = session ?: return
        val redone = session.redo()
        scheduleHistorySettle()
        if (redone) afterModelEdit() else present()
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
        // bounds, so drop it; renderPage then repaints without decorations. The circled link is dropped
        // for the same reason — the edit may remove or hide it, which would strand its edit/delete actions.
        selection = null
        selectionPolygon = null
        uiCircledLink = null
        uiCircledImage = null
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

    // --- links map ---------------------------------------------------------------------------

    /**
     * Loads the links map fresh, centred on the page currently open, off the main thread — the same
     * synchronous-begin/async-finish split [LinksMapController] keeps ([LinksMapController.beginLoad]
     * flips its state before this method returns, so the panel shows "loading" the instant it opens;
     * [LinksMapController.finishLoad] lands once the scan completes). Reopening always recentres here
     * rather than resuming wherever a previous visit wandered off to, per the design doc: "[⋈] reopens
     * the map, now centred on the page you're on".
     */
    private fun openLinksMap() {
        val notebook = notebook ?: return
        val pageId = session?.page?.id ?: return
        linksMapController.beginLoad(NodeRef(notebook.id, pageId))
        uiLinksMapTick++
        lifecycleScope.launch {
            val index = withContext(Dispatchers.IO) { storage.loadLinkIndex() }
            linksMapController.finishLoad(index)
            uiLinksMapTick++
        }
    }

    // --- panels ----------------------------------------------------------------------------

    /**
     * Opens [panel] anchored at [anchor], or closes it if it is already open — the "tap the same
     * glyph again" half of [EditorBarActions]'s `onToggleXPanel` contract. Opening a different panel
     * than the one already open replaces it, since [uiOpenPanel] holds at most one. Always wrapped in
     * [withChromeRefresh]: harmless to call repeatedly while a panel stays open (pen capture is
     * already off), and it is what gets the resulting Compose frame to the e-ink panel promptly.
     */
    private fun togglePanel(panel: EditorPanel, anchor: ComposeRect) = withChromeRefresh {
        if (uiOpenPanel == panel) {
            closePanel()
        } else {
            uiOpenPanel = panel
            uiOpenPanelAnchor = anchor
            // No Activity callback fires for drilling into More's Template sub-page (MorePanel.kt's
            // own local state), so the file list is refreshed here, on every More open, instead.
            if (panel == EditorPanel.MORE) loadTemplateFiles()
            if (panel == EditorPanel.LINKS) openLinksMap()
        }
    }

    /** Closes whichever panel is open. Only mutates state; callers wrap it in [withChromeRefresh]. */
    private fun closePanel() {
        uiOpenPanel = EditorPanel.NONE
        updateChromeExclude(PANEL_RECT_KEY, null)
    }

    /** The panel-open tap-catcher's dismiss: an outside tap or a pen touch on the page, neither inked. */
    private fun closePanelViaChrome() = withChromeRefresh { closePanel() }

    /**
     * Pen capture is off while a chrome panel or the link picker is open, so a tap meant for one of
     * them never draws a stroke — and off while the sticker panel is open too, *unless* native raw
     * drawing has been restricted to just its drawing box ([stickerCaptureRegionActive]): there,
     * capture must stay on for the box to ink at all, and [PenBackend.setCaptureRegion] already keeps
     * every other pixel (the panel's buttons, the page underneath) out of reach.
     */
    private fun updateBackendEnabled() {
        val chromeOpen = uiOpenPanel != EditorPanel.NONE || uiLinkPicker != null
        val stickerBlocksCapture = uiStickerFlow != null && !stickerCaptureRegionActive
        backend.setEnabled(!chromeOpen && !stickerBlocksCapture)
    }

    // --- chrome actions (EditorBarActions / ToolPanelActions / PagePanelActions / MorePanelActions) --

    override fun onOpenLibrary() {
        // NotebookListActivity starts this Activity with startActivity and never finishes itself, so
        // finishing here is all that is needed to return to it.
        finish()
    }

    override fun onShowChrome() = withChromeRefresh {
        uiChromeHidden = false
        prefs.chromeHidden = false
    }

    override fun onToggleToolPanel(anchor: ComposeRect) = togglePanel(EditorPanel.TOOL, anchor)
    override fun onTogglePagePanel(anchor: ComposeRect) = togglePanel(EditorPanel.PAGE, anchor)
    override fun onToggleLinksMap(anchor: ComposeRect) = togglePanel(EditorPanel.LINKS, anchor)
    override fun onToggleMorePanel(anchor: ComposeRect) = togglePanel(EditorPanel.MORE, anchor)

    /** Muted and inert for the whole of Phase 1 (`BarMode.Normal.findEnabled` is always false). */
    override fun onToggleFind(anchor: ComposeRect) = Unit

    override fun onJumpBack() = withChromeRefresh { jumpBack() }
    override fun onPaste() = withChromeRefresh { pasteClipboard() }

    override fun onDeselect() = withChromeRefresh { clearSelection() }
    override fun onCopySelection() = withChromeRefresh { copySelection() }
    override fun onLinkSelection() = withChromeRefresh { openLinkPickerForSelection() }
    override fun onDeleteSelection() = withChromeRefresh { deleteSelection() }
    override fun onEditCircledLink() = withChromeRefresh { editCircledLink() }
    override fun onEditCircledLinkSticker() = withChromeRefresh { editCircledLinkSticker() }
    override fun onDeleteCircledLink() = withChromeRefresh { deleteCircledLink() }
    override fun onDeleteCircledImage() = withChromeRefresh { deleteCircledImage() }

    /** Picking a tool both switches it and closes the panel (the spec: you opened it to switch tools). */
    override fun onSelectTool(tool: EditorTool) = withChromeRefresh {
        when (tool) {
            EditorTool.ERASER -> selectEraser()
            EditorTool.LASSO -> selectLasso()
            EditorTool.PEN, EditorTool.PENCIL, EditorTool.MARKER -> tool.asCoreTool()?.let(::selectTool)
        }
        closePanel()
    }

    // The rating rows keep the panel open, so more than one can be adjusted in a single visit.
    override fun onDecreaseWidth() = withChromeRefresh { stepWidth(-1) }
    override fun onIncreaseWidth() = withChromeRefresh { stepWidth(1) }
    override fun onDecreaseShade() = withChromeRefresh { stepShade(-1) }
    override fun onIncreaseShade() = withChromeRefresh { stepShade(1) }
    override fun onToggleSmoothing() = withChromeRefresh { toggleSmoothing() }

    private fun stepWidth(delta: Int) {
        val entries = StrokeWidth.entries
        setWidth(entries[(uiWidth.ordinal + delta).coerceIn(0, entries.lastIndex)])
    }

    private fun stepShade(delta: Int) {
        val entries = InkShade.entries
        setShade(entries[(uiShade.ordinal + delta).coerceIn(0, entries.lastIndex)])
    }

    // Prev/next keep the panel open (paging through several pages in one visit); selecting a strip
    // thumbnail, inserting a page, or confirming a delete all close it.
    override fun onPrevPage() = withChromeRefresh { goToPage(uiPageIndex - 1) }
    override fun onNextPage() = withChromeRefresh { goToPage(uiPageIndex + 1) }

    override fun onSelectPage(pageIndex: Int) = withChromeRefresh {
        goToPage(pageIndex)
        closePanel()
    }

    override fun onInsertPageAfterCurrent() = withChromeRefresh {
        insertPageAfterCurrent()
        closePanel()
    }

    /** Called once, after PagePanel's own inline confirm says yes — never a raw button tap. */
    override fun onDeleteCurrentPage() = withChromeRefresh {
        deleteCurrentPage()
        closePanel()
    }

    override fun onInsertImage() = withChromeRefresh {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        closePanel()
    }

    // Layer toggles keep the panel open, like the Tool panel's rating rows.
    override fun onToggleLayerVisible(id: LayerId, visible: Boolean) = withChromeRefresh { toggleLayerVisible(id, visible) }
    override fun onSelectActiveLayer(id: LayerId) = withChromeRefresh { setActiveLayer(id) }
    override fun onAddLayer() = withChromeRefresh { addLayer() }
    override fun onRemoveLayer(id: LayerId) = withChromeRefresh { removeLayer(id) }

    override fun onSelectTemplate(ref: String?) = withChromeRefresh {
        setTemplate(ref)
        closePanel()
    }

    override fun onUndo() = withChromeRefresh { undo() }
    override fun onRedo() = withChromeRefresh { redo() }

    override fun onHideToolbar() = withChromeRefresh {
        closePanel()
        uiChromeHidden = true
        prefs.chromeHidden = true
    }

    // --- sticker panel actions (StickerPanelActions) ----------------------------------------

    // The three pointer-stream callbacks below fire many times a second while the pen is down and
    // skip withChromeRefresh, unlike every other override here: the backend is already fully
    // disabled for the sticker panel's whole lifetime (uiStickerFlow gates updateBackendEnabled), so
    // there is no e-ink repaint to pause for, and re-scheduling its resume on every sample would
    // just be churn.
    override fun onStrokeStart(x: Float, y: Float, pressure: Float, t: Long) {
        uiStickerFlow?.draft?.beginStroke(x, y, pressure, t)
        uiStickerDraftTick++
    }

    override fun onStrokeSample(x: Float, y: Float, pressure: Float, t: Long) {
        uiStickerFlow?.draft?.addPoint(x, y, pressure, t)
        uiStickerDraftTick++
    }

    override fun onStrokeEnd() {
        uiStickerFlow?.draft?.endStroke()
        uiStickerDraftTick++
    }

    override fun onClear() = withChromeRefresh {
        uiStickerFlow?.draft?.clear()
        uiStickerDraftTick++
    }

    override fun onDone() = withChromeRefresh { commitStickerFlow(uiStickerFlow?.draft?.toSticker()) }
    override fun onSkip() = withChromeRefresh { commitStickerFlow(sticker = null) }
    override fun onCancel() = withChromeRefresh { cancelStickerFlow() }

    // --- links map panel actions (LinksMapPanelActions) -------------------------------------

    override fun onSelectMapNode(ref: NodeRef?) = withChromeRefresh {
        linksMapController.select(ref)
        uiLinksMapTick++
    }

    override fun onCentreMapNode(ref: NodeRef) = withChromeRefresh {
        linksMapController.centreOn(ref)
        uiLinksMapTick++
    }

    /** Commits the jump: closes the map now, then navigates exactly as a tapped link would. */
    override fun onOpenMapNode(ref: NodeRef) {
        withChromeRefresh { closePanel() }
        navigateTo(ref.notebookId, ref.pageId)
    }

    override fun onMapBack() = withChromeRefresh {
        linksMapController.back()
        uiLinksMapTick++
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
     * Queues the repaint that swaps the panel's raw wet ink for our own rendered ink, once the pen
     * has been still for [INK_SETTLE_MS].
     *
     * Only a [PenBackend.rendersWetInkNatively] backend needs this. There the hardware paints the
     * stroke with its own nib as it is drawn, so what stays on the panel is the hardware's ink, not
     * the tapered/curved ink the renderer draws for the committed page; without a repaint the two
     * disagree until some later structural change. This is no longer about smoothing — our ink
     * differs from the hardware's wet ink even with smoothing OFF — so it always runs.
     *
     * The repaint cannot simply follow the stroke, because blitting suspends pen capture and a writer
     * starts the next stroke within a few tens of milliseconds — landing the dead window mid-word.
     * Waiting for the pen to settle puts it in the gap between words instead, and
     * [PenBackend.Listener.onGestureStarted] cancels it if the pen comes back down first. During
     * continuous writing it therefore never fires, and the strokes reconcile at the first pause.
     * [INK_SETTLE_MS] balances the two ends of that gap: 200ms sits closer to the stroke than the ink
     * mismatch really needs, but still lands between letters for most writers; going much below
     * ~150ms risks clipping a fast writer's very next stroke instead.
     */
    private fun scheduleInkSettle() {
        cancelInkSettle()
        val settle = Runnable {
            pendingInkSettle = null
            // Through present(), not presentComposite(): this settle can land while a badge is live
            // (e.g. a lasso latch still waiting for its stroke), which must not be wiped.
            present()
        }
        pendingInkSettle = settle
        surfaceView.postDelayed(settle, INK_SETTLE_MS)
    }

    private fun cancelInkSettle() {
        pendingInkSettle?.let { surfaceView.removeCallbacks(it) }
        pendingInkSettle = null
    }

    /**
     * Queues the deferred clean refresh that clears the ghosting undo (or a redo that removes ink)
     * leaves on the panel, once [HISTORY_SETTLE_MS] has passed without another undo or redo gesture.
     * The window is what keeps a burst of rapid undos/redos cheap: each tap only repaints additively
     * (see [undoByGesture]/[redoByGesture]), and the expensive clean pass waits for the burst to stop.
     */
    private fun scheduleHistorySettle() {
        cancelHistorySettle()
        val settle = Runnable {
            pendingHistorySettle = null
            present(cleanRefresh = true)
        }
        pendingHistorySettle = settle
        surfaceView.postDelayed(settle, HISTORY_SETTLE_MS)
    }

    private fun cancelHistorySettle() {
        pendingHistorySettle?.let { surfaceView.removeCallbacks(it) }
        pendingHistorySettle = null
    }

    /**
     * Queues the release of a lasso latch that [LASSO_LATCH_MS] goes by without the pen stroke it was
     * armed for, so arming the lasso via a two-finger hold and then not drawing cannot strand the user
     * in selection mode. Restarted by every [armLassoForNextStroke] rather than left running from the
     * first arm, so a second hold before the window lapses gets the full [LASSO_LATCH_MS] again.
     */
    private fun scheduleLassoLatchExpiry() {
        cancelLassoLatchExpiry()
        val expiry = Runnable {
            pendingLassoLatchExpiry = null
            clearLassoLatch()
        }
        pendingLassoLatchExpiry = expiry
        surfaceView.postDelayed(expiry, LASSO_LATCH_MS)
    }

    private fun cancelLassoLatchExpiry() {
        pendingLassoLatchExpiry?.let { surfaceView.removeCallbacks(it) }
        pendingLassoLatchExpiry = null
    }

    /**
     * Flips smoothing between off and [SmoothingLevel.AUTO], and remembers it. Once AUTO derives its
     * strength per stroke there is nothing left to hunt for, so this is a toggle rather than the old
     * cycle through hand-picked levels — a cycle existed only so the user could search for a level
     * they liked, and each step costs a Compose chrome refresh on e-ink. Off stays reachable because
     * "ink exactly what I drew" is a legitimate choice, not a level to tune past.
     *
     * A value a previous build stored as [SmoothingLevel.LIGHT] or [SmoothingLevel.STRONG] self-heals
     * here: the first press lands on AUTO or OFF like any other stored level would.
     */
    private fun toggleSmoothing() {
        uiSmoothing = if (uiSmoothing == SmoothingLevel.OFF) SmoothingLevel.AUTO else SmoothingLevel.OFF
        prefs.smoothing = uiSmoothing
        backend.setInkSmoothing(uiSmoothing)
    }

    /**
     * Pushes [windowRect] into the backend's raw-drawing exclude set under [key] (`null` removes that
     * key), so a pen stroke starting on a chrome element is never captured as ink. Replaces the old
     * single `updateToolbarExclude`: more than one chrome element can be on screen at once now (the
     * bar, plus at most one open panel), each reporting its own bounds under its own key ("bar",
     * "panel") — see [chromeRects] — and this pushes their union to [PenBackend.setExcludeRects],
     * which already accepts a list. Deduped per key so a stable layout does not reconfigure the
     * capture region.
     *
     * [windowRect] arrives in window pixels ([EditorBar.onBoundsChanged]/[PanelAnchor.onBoundsChanged]
     * both report `positionInWindow`/`boundsInWindow`), the same space the old `updateToolbarExclude`
     * converted from — that conversion (subtracting [surfaceView]'s own window-relative location) is
     * redone here, in the one place both callers funnel through, rather than in each of them.
     */
    private fun updateChromeExclude(key: String, windowRect: Rect?) {
        val rect = windowRect?.let(::toSurfaceLocal)
        if (rect == null) {
            if (chromeRects.remove(key) == null) return
        } else if (chromeRects[key] == rect) {
            return
        } else {
            chromeRects[key] = rect
        }
        Log.d(TAG, "Chrome exclude rects: $chromeRects")
        backend.setExcludeRects(chromeRects.values.toList())
    }

    /** [windowRect] (window pixels) translated into [surfaceView]-local pixels. */
    private fun toSurfaceLocal(windowRect: Rect): Rect {
        val surfaceLocation = IntArray(2).also { surfaceView.getLocationInWindow(it) }
        return Rect(
            windowRect.left - surfaceLocation[0],
            windowRect.top - surfaceLocation[1],
            windowRect.right - surfaceLocation[0],
            windowRect.bottom - surfaceLocation[1],
        )
    }

    // --- page navigation -------------------------------------------------------------------

    private fun goToPage(index: Int) {
        val notebook = notebook ?: return
        if (index < 0 || index >= notebook.pageIds.size || index == uiPageIndex) return
        switchNotebook(notebook, notebook.pageIds[index])
    }

    /**
     * Flushes the current page to its own notebook, then loads and installs [pageId] of [target],
     * switching the active notebook when [target] differs from the current one. The single page-load
     * primitive: [goToPage] is the same-notebook case routed through here, and a cross-notebook link
     * jump (or step-back) is the differing-notebook case — no Activity restart either way. A load
     * failure is logged and the current page left in place.
     */
    private fun switchNotebook(target: Notebook, pageId: PageId) {
        val index = target.pageIds.indexOf(pageId)
        if (index < 0) return
        autosaveJob?.cancel()
        lifecycleScope.launch {
            saveCurrentPage()
            val loaded = withContext(Dispatchers.IO) {
                runCatching { storage.loadPage(target, pageId) }
            }
            loaded
                .onSuccess { page ->
                    notebook = target
                    installPage(page, index)
                    renderPage()
                }
                .onFailure { e -> Log.e(TAG, "Could not load ${target.name} page $index", e) }
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

    // --- link navigation -------------------------------------------------------------------

    /**
     * The link whose region — or, for a link with a sticker, whose sticker card
     * ([StickerPlacement.stickerRect]) — contains ([x], [y]), or null. Hit-tested only while the main
     * layer is visible (see [mainLayerVisible]). The first match wins; overlapping regions are a
     * documented degenerate case (see [Page.links]). Takes plain coordinates rather than a
     * [StrokePoint] so both a stylus gesture's first point ([penListener]) and a bare finger-tap
     * position ([onFingerTapAt]) can hit-test through the same method.
     */
    private fun linkAt(session: PageEditSession, x: Float, y: Float): PageLink? {
        val page = session.page
        if (!mainLayerVisible(page)) return null
        return page.links.firstOrNull { link ->
            link.region.contains(x, y) || link.stickerCardContains(x, y)
        }
    }

    /** Whether ([x], [y]) falls inside this link's sticker card, or false when it has no sticker. */
    private fun PageLink.stickerCardContains(x: Float, y: Float): Boolean {
        if (sticker == null) return false
        val card = StickerPlacement.stickerRect(region, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        return card.contains(x, y)
    }

    /**
     * The id of the first link [polygon] circles (its region centre enclosed by [lassoCoversRegion]),
     * or null. Gated on the main layer being visible, exactly like the tap hit-test ([linkAt]), since
     * a link binds to that layer's handwriting.
     */
    private fun circledLinkId(session: PageEditSession, polygon: List<Vec2>): LinkId? {
        val page = session.page
        if (!mainLayerVisible(page)) return null
        return page.links.firstOrNull { lassoCoversRegion(it.region, polygon) }?.id
    }

    /**
     * Whether [page]'s main layer is currently visible — the gate both link hit-tests share, since a
     * link is bound to the main layer's handwriting and a hidden layer hides its links along with it.
     */
    private fun mainLayerVisible(page: Page): Boolean =
        page.layers.first { it.id == page.mainLayerId }.visible

    /**
     * Follows a tapped [link]: resolves its target and either offers to remove it (broken — its
     * notebook or page is gone) or jumps to it. The only caller-specific part of [navigateTo] a link
     * tap needs over the links map's Open ([onOpenMapNode]): something to do when the target cannot
     * be found.
     */
    private fun navigateToLink(link: PageLink) {
        navigateTo(link.targetNotebookId, link.targetPageId, onUnresolved = { uiBrokenLinkDialog = link })
    }

    /**
     * Resolves [notebookId] by id off the main thread and, once found, records the current position
     * on the jump stack and jumps to [pageId] there — the machinery a tapped link ([navigateToLink])
     * and the links map's `[open]` ([onOpenMapNode]) both need. [onUnresolved] runs instead when the
     * notebook or page can no longer be found (a link tap offers to delete the dead link; the map has
     * nothing analogous to offer, so it takes the default no-op and just leaves the panel closed).
     *
     * The jump's full render clears the wet tap dot the pen left on the panel; wrapped in
     * [withChromeRefresh] here (rather than relying on the caller's own wrap) so the page-counter
     * repaint reaches the e-ink panel however this was reached — a pen tap on a link never runs
     * inside any [withChromeRefresh] of its own, unlike a bar/panel action.
     */
    private fun navigateTo(notebookId: NotebookId, pageId: PageId, onUnresolved: () -> Unit = {}) {
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { storage.findNotebookById(notebookId) }.getOrNull()
            }
            if (target == null || pageId !in target.pageIds) {
                onUnresolved()
                return@launch
            }
            pushJumpOrigin()
            withChromeRefresh { jumpTo(target, pageId) }
        }
    }

    /**
     * Jumps to [pageId] of [target], the machinery shared by a link tap and the step-back:
     *  - same notebook, another page → [goToPage] (flush, load, install);
     *  - same notebook, the current page → a plain repaint that keeps the session (and its undo
     *    history) and clears the wet tap dot — a link may legitimately target its own page;
     *  - a different notebook → [switchNotebook].
     */
    private fun jumpTo(target: Notebook, pageId: PageId) {
        val current = notebook
        if (current != null && target.id == current.id) {
            val index = target.pageIds.indexOf(pageId)
            when {
                index < 0 -> Unit
                index == uiPageIndex -> renderPage()
                else -> goToPage(index)
            }
        } else {
            switchNotebook(target, pageId)
        }
    }

    /** Records the current position as a jump origin, capped at [MAX_JUMP_STACK] (oldest dropped). */
    private fun pushJumpOrigin() {
        val notebook = notebook ?: return
        val page = session?.page ?: return
        jumpStack.addLast(JumpOrigin(notebook.name, page.id, uiPageIndex))
        while (jumpStack.size > MAX_JUMP_STACK) jumpStack.removeFirst()
        uiJumpDepth = jumpStack.size
    }

    /**
     * Steps back to the most recently recorded jump origin and, crucially, does NOT push one (so
     * repeated presses walk the stack down instead of ping-ponging). A same-notebook origin jumps
     * straight there; an origin in another notebook is loaded by name first (the stack holds names,
     * still current within the session). A failed load is logged, not fatal.
     */
    private fun jumpBack() {
        val origin = jumpStack.removeLastOrNull() ?: return
        uiJumpDepth = jumpStack.size
        val current = notebook
        if (current != null && origin.notebookName == current.name) {
            jumpTo(current, origin.pageId)
            return
        }
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { storage.loadNotebook(origin.notebookName) }.getOrNull()
            }
            if (target == null) {
                Log.e(TAG, "Could not load origin notebook ${origin.notebookName}")
                return@launch
            }
            jumpTo(target, origin.pageId)
        }
    }

    /** Removes the broken [link] and repaints so its affordance disappears (single-present tail). */
    private fun deleteBrokenLink(link: PageLink) {
        val session = session ?: return
        session.removeLink(link.id)
        refreshUndoRedo()
        renderPage()
        scheduleAutosave()
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

    // --- chrome state builders ---------------------------------------------------------------

    /**
     * The bar's own "current tool" input: the toolbar tool plus the eraser/lasso flags layered on
     * top — including [uiLassoLatched], so a two-finger-hold arm shows `[✎ lasso]` even though the
     * toolbar's own tool selection never changed. Lasso wins over eraser here, matching
     * [applyCaptureMode]'s priority, so the bracket never claims eraser while a hold has actually
     * latched the backend into LASSO.
     */
    private fun currentEditorTool(): EditorTool = when {
        uiLasso || uiLassoLatched -> EditorTool.LASSO
        uiEraser -> EditorTool.ERASER
        else -> when (uiTool) {
            Tool.PEN -> EditorTool.PEN
            Tool.PENCIL -> EditorTool.PENCIL
            Tool.MARKER -> EditorTool.MARKER
        }
    }

    /** The label of the page most recently jumped from ("research #3"), or null with nothing to step back to. */
    private fun jumpBackLabel(): String? {
        val origin = jumpStack.lastOrNull() ?: return null
        return "${origin.notebookName} #${origin.pageIndex + 1}"
    }

    private fun barMode(): BarMode = when {
        uiChromeHidden -> BarMode.Hidden
        uiHasSelection || uiCircledLink != null || uiCircledImage != null -> BarMode.Selection(
            strokeCount = if (uiHasSelection) uiSelectionStrokeCount else null,
            canLink = uiHasSelection && uiSelectionOnMainLayer,
            circledLink = uiCircledLink != null,
            circledImage = uiCircledImage != null,
        )
        else -> BarMode.Normal(
            activeTool = currentEditorTool(),
            pageIndex = uiPageIndex,
            pageCount = uiPageCount,
            jumpBackLabel = jumpBackLabel(),
            canPaste = uiClipboardHasContent,
            findEnabled = false, // Wired in Phase 3; present but muted until then.
        )
    }

    private fun toolPanelState(): ToolPanelState {
        val tool = currentEditorTool()
        val isInkTool = tool == EditorTool.PEN || tool == EditorTool.PENCIL || tool == EditorTool.MARKER
        return ToolPanelState(
            activeTool = tool,
            width = if (isInkTool) uiWidth else null,
            shade = if (isInkTool) uiShade else null,
            smoothingOn = if (isInkTool) uiSmoothing != SmoothingLevel.OFF else null,
        )
    }

    private fun pagePanelState(): PagePanelState = PagePanelState(
        pageIndex = uiPageIndex,
        pageCount = uiPageCount,
        canGoPrev = uiPageIndex > 0,
        canGoNext = uiPageIndex < uiPageCount - 1,
        canDelete = uiPageCount > 1,
        strip = pageWindow(uiPageIndex, uiPageCount),
        thumbnailFor = null, // Phase 2 wires a real thumbnail cache in; number cells stand in until then.
    )

    private fun morePanelState(): MorePanelState = MorePanelState(
        canUndo = uiCanUndo,
        canRedo = uiCanRedo,
        layers = uiLayers,
        activeLayerId = uiActiveLayer,
        canAddLayer = uiLayers.size < PageEditSession.MAX_LAYERS,
        templateRef = uiTemplateRef,
        templateFiles = uiTemplateFiles,
    )

    /** Reads [uiLinksMapTick] so a controller mutation (load, select, centre, back) recomposes the panel. */
    private fun linksMapPanelState(): LinksMapPanelState {
        uiLinksMapTick
        return linksMapController.panelState()
    }

    private fun stickerPanelState(flow: StickerFlowState): StickerPanelState {
        uiStickerDraftTick // read to depend on drawing/capture changes — see the field's doc.
        return StickerPanelState(
            strokes = flow.draft.strokes,
            liveStroke = flow.draft.liveStroke,
            tool = flow.draft.tool,
            widthBase = flow.draft.widthBase,
            grayLevel = flow.draft.grayLevel,
            nativeCapture = stickerCaptureRegionActive,
        )
    }

    // --- Compose UI ------------------------------------------------------------------------

    @Composable
    private fun EditorScreen() {
        // Full-bleed canvas with the bar and at most one open panel overlaid on top: the SurfaceView
        // fills the window, so a measured chrome exclude rect lands as a positive on-surface region.
        // While a panel is open, a full-screen transparent tap-catcher sits between the canvas and the
        // bar, so a tap — or a pen touch, since raw drawing is off while any panel is open and the
        // touch takes the ordinary Android path instead — that misses the panel's own controls closes
        // it rather than reaching the canvas as ink. See [PanelTapCatcher] and [closePanelViaChrome].
        BackHandler(enabled = uiStickerFlow != null) { onCancel() }
        Box(Modifier.fillMaxSize()) {
            CanvasView()
            if (uiOpenPanel != EditorPanel.NONE) {
                PanelTapCatcher(onDismiss = ::closePanelViaChrome)
            }
            EditorBar(
                state = EditorBarState(barMode()),
                actions = this@EditorActivity,
                onBoundsChanged = { updateChromeExclude(BAR_RECT_KEY, it) },
            )
            EditorPanelContent()
            EditorDialogs()
        }
    }

    /** The drawing surface. Reads no Compose state and has no update block, so it never recomposes. */
    @Composable
    private fun CanvasView() {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
    }

    /**
     * A full-screen, invisible click target shown only while a panel is open, sandwiched between the
     * canvas and the bar (see [EditorScreen]) so the bar's own glyphs keep working (tapping the one
     * that opened the panel closes it; tapping another switches panels) while every other tap or pen
     * touch dismisses. No scrim — the no-scrims rule means the page stays legible behind an open
     * panel — so this exists purely to catch and dismiss, not to dim anything.
     */
    @Composable
    private fun PanelTapCatcher(onDismiss: () -> Unit) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
    }

    /** Whichever panel [uiOpenPanel] names, anchored at [uiOpenPanelAnchor]. At most one at a time. */
    @Composable
    private fun BoxScope.EditorPanelContent() {
        val onBounds: (Rect) -> Unit = { updateChromeExclude(PANEL_RECT_KEY, it) }
        when (uiOpenPanel) {
            EditorPanel.TOOL -> ToolPanel(toolPanelState(), this@EditorActivity, uiOpenPanelAnchor, onBounds)
            EditorPanel.PAGE -> PagePanel(pagePanelState(), this@EditorActivity, uiOpenPanelAnchor, onBounds)
            EditorPanel.MORE -> MorePanel(morePanelState(), this@EditorActivity, uiOpenPanelAnchor, onBounds)
            EditorPanel.LINKS -> LinksMapPanel(linksMapPanelState(), this@EditorActivity, uiOpenPanelAnchor, onBounds)
            EditorPanel.FIND, EditorPanel.NONE -> Unit
        }
    }

    /**
     * The link target picker, the sticker panel, and the broken-link confirm — everything else
     * moved into the chrome panels.
     */
    @Composable
    private fun BoxScope.EditorDialogs() {
        uiLinkPicker?.let { picker ->
            Scrim { closeLinkPicker() }
            LinkPickerPanel(picker, Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
        uiStickerFlow?.let { flow ->
            // No Scrim here: e-ink shows no dimming, and unlike a chrome panel's outside-tap-dismiss
            // an outside tap on the sticker flow must NOT cancel it (a stray pen touch just past the
            // box must not lose a half-drawn sticker) — only Back or the panel's own [skip]/[done] do
            // (EditorScreen's BackHandler, onSkip, onDone). This full-screen, invisible catcher exists
            // only so such a tap lands on nothing rather than falling through to the bar or canvas.
            // It is left out while native capture owns the box, so pen touches reach the surface the
            // raw-drawing backend listens on; penListener already ignores page gestures while the
            // flow is open. Native sticker capture has only been confirmed working without it.
            val panelState = stickerPanelState(flow)
            if (!panelState.nativeCapture) Box(Modifier.fillMaxSize().clickable(onClick = {}))
            StickerPanel(
                state = panelState,
                actions = this@EditorActivity,
                modifier = Modifier.align(Alignment.Center),
                onBoundsChanged = ::updateStickerPanelExclude,
                onDrawBoundsChanged = ::updateStickerCaptureBox,
            )
        }
        uiBrokenLinkDialog?.let { link ->
            ConfirmDialog(
                title = stringResource(R.string.broken_link_title),
                message = stringResource(R.string.broken_link_message),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    uiBrokenLinkDialog = null
                    withChromeRefresh { deleteBrokenLink(link) }
                },
                onDismiss = { uiBrokenLinkDialog = null },
            )
        }
    }

    @Composable
    private fun Scrim(onDismiss: () -> Unit) {
        Box(Modifier.fillMaxSize().background(ScrimColor).clickable(onClick = onDismiss))
    }

    /**
     * The two-step target picker: first the notebooks (current one first, marked as such), then a
     * page number within the chosen notebook. Restyled onto the chrome tokens and bracket voice
     * ([EinkBracket]/[EinkTypography]) in Phase 1; the two-step flow itself, and its scrim-to-cancel,
     * are unchanged. Each list wraps in a [FlowRow] so a long notebook or page list stays reachable.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun LinkPickerPanel(picker: LinkPickerState, modifier: Modifier) {
        Column(
            modifier = modifier
                .width(360.dp)
                .background(EinkWhite)
                .padding(EinkSpacing.S)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(EinkSpacing.S),
        ) {
            Text(stringResource(R.string.link_picker_title), style = EinkTypography.Title)
            val chosen = picker.chosenNotebook
            if (chosen == null) {
                val notebooks = picker.notebooks
                if (notebooks != null) {
                    val currentId = notebook?.id
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                        verticalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                    ) {
                        // Current notebook first; the rest keep listNotebooks' name order (stable sort).
                        for (nb in notebooks.sortedByDescending { it.id == currentId }) {
                            val label = if (nb.id == currentId) {
                                stringResource(R.string.link_picker_current, nb.name)
                            } else {
                                nb.name
                            }
                            EinkBracket(label) { uiLinkPicker = picker.copy(chosenNotebook = nb) }
                        }
                    }
                }
            } else {
                Text(chosen.name, style = EinkTypography.Body)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                    verticalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                ) {
                    chosen.pageIds.forEachIndexed { index, pageId ->
                        LinkPickerThumbCell(
                            notebook = chosen,
                            pageId = pageId,
                            index = index,
                            isCurrent = chosen.id == notebook?.id && pageId == session?.page?.id,
                            onClick = { confirmLinkTarget(picker, chosen, pageId) },
                        )
                    }
                }
                EinkBracket(stringResource(R.string.link_picker_back)) {
                    uiLinkPicker = picker.copy(chosenNotebook = null)
                }
            }
        }
    }

    /**
     * One page cell in the link picker's target grid: a bordered thumbnail-sized box — the current
     * page marked with a 3 dp border like [PagePanel]'s own strip cell, any other with the ordinary
     * [BorderWidth] — with an `#N` caption. Starts as an empty box and fills in once
     * [PageThumbnailCache.load] resolves; no placeholder spinner or transition (the e-ink no-animation
     * rule), just the bordered box until the bitmap lands.
     */
    @Composable
    private fun LinkPickerThumbCell(notebook: Notebook, pageId: PageId, index: Int, isCurrent: Boolean, onClick: () -> Unit) {
        var thumbnail by remember(notebook.id, pageId) {
            mutableStateOf(thumbnailCache.peek(notebook.id, pageId)?.asImageBitmap())
        }
        LaunchedEffect(notebook.id, pageId) {
            if (thumbnail != null) return@LaunchedEffect
            // The open notebook's current page carries edits the disk copy does not; draw those
            // instead of the last save (see PageThumbnailCache.load's inMemoryPage).
            val isOpenCurrentPage = notebook.id == this@EditorActivity.notebook?.id && pageId == session?.page?.id
            val inMemory = if (isOpenCurrentPage) session?.page else null
            thumbnail = thumbnailCache.load(notebook, pageId, surfaceWidth, surfaceHeight, inMemory)?.asImageBitmap()
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = EinkSpacing.MinTouchTarget, minHeight = EinkSpacing.MinTouchTarget)
                .clickable(onClick = onClick)
                .padding(EinkSpacing.XS),
            contentAlignment = Alignment.Center,
        ) {
            // Caption under the preview, not over it, so the page's own ink stays readable.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(LinkPickerThumbWidth)
                        .aspectRatio(LinkPickerThumbAspect)
                        .background(EinkWhite)
                        .border(if (isCurrent) 3.dp else BorderWidth, EinkBlack),
                ) {
                    thumbnail?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val caption = "#${index + 1}"
                if (isCurrent) {
                    Text(
                        caption,
                        style = EinkTypography.Caption,
                        color = EinkWhite,
                        modifier = Modifier.padding(top = 4.dp).background(EinkBlack).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                } else {
                    Text(caption, style = EinkTypography.Caption, color = EinkMuted, modifier = Modifier.padding(top = 4.dp))
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

/**
 * How long the pen must be still before our own rendered ink is blitted over the panel's raw wet
 * ink (see [scheduleInkSettle]).
 *
 * Measured intra-word gaps run 100-300ms and gaps between words upward of 600ms, so 200ms sits
 * inside the intra-word band: it can land mid-word, and the settle's blit briefly suspends pen
 * capture when it does. Chosen knowingly anyway, for a faster wet-to-dry swap than a between-words
 * value would give — most writers clear the band before their next stroke lands. If strokes start
 * getting clipped in practice, 700ms is the measured value that sits past the intra-word band and
 * lands in the pause between words instead.
 *
 * Deliberately no "pen is down" guard inside [scheduleInkSettle]'s Runnable: it would add nothing.
 * The post and the Runnable both run on the main thread, so either
 * [PenBackend.Listener.onGestureStarted]'s `removeCallbacks` already won by the time this would fire,
 * or it did not run yet — in which case a flag would not be set either.
 */
private const val INK_SETTLE_MS = 200L

/**
 * How long a burst of two-finger undo or three-finger redo gestures must go quiet before
 * [scheduleHistorySettle] fires its deferred repaint (badge retired, ghosting cleared). This is what
 * makes rapid repeated undos/redos cheap: each tap only costs an additive repaint, and the one
 * expensive clean pass waits for the burst to end.
 */
private const val HISTORY_SETTLE_MS = 700L

/**
 * How long an armed lasso latch waits for the pen stroke it was armed for before
 * [scheduleLassoLatchExpiry] releases it unprompted, so arming the lasso via a two-finger hold and
 * then not drawing does not strand the user in selection mode.
 */
private const val LASSO_LATCH_MS = 5_000L

/** How much of the page a freshly inserted image may cover, before the user resizes it. */
private const val INSERTED_IMAGE_PAGE_FRACTION = 0.4f

/** Stored file type for a picked image whose MIME type the system does not name. */
private const val DEFAULT_IMAGE_EXT = "png"

/** Smallest an image may be resized to on either side, so it stays big enough to grab again. */
private const val MIN_IMAGE_SIZE_PX = 48f

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

        /**
         * How far a link's tappable region is grown beyond the selection's bounding box, in page
         * pixels on each side, so the affordance frames the circled handwriting rather than clipping it.
         */
        private const val LINK_REGION_PADDING_PX = 8f

        /** How many jump origins the step-back stack keeps; past this the oldest is dropped. */
        private const val MAX_JUMP_STACK = 20

        /** Keys into [chromeRects]: the bar's own bounds, and the currently open panel's bounds. */
        private const val BAR_RECT_KEY = "bar"
        private const val PANEL_RECT_KEY = "panel"
        private const val STICKER_RECT_KEY = "sticker"

        /** Image extensions offered as user templates from the `templates/` directory. */
        private val TEMPLATE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
}
