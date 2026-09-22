package com.nomadnotes.app.input

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.ink.SmoothingLevel
import com.nomadnotes.pen.onyx.OnyxHiddenApi
import com.nomadnotes.pen.onyx.OnyxRawDrawingController

/**
 * A [PenBackend] for Onyx Boox e-ink hardware.
 *
 * For inking and erasing the panel's raw drawing paints the wet stroke itself so ink is lag-free
 * ([rendersWetInkNatively] is true). Lasso is different: this firmware keeps painting ephemeral wet
 * ink and stops delivering per-sample move callbacks even when raw drawing's rendering is "disabled",
 * so a live move preview is impossible through raw drawing. In LASSO mode this backend therefore turns
 * raw drawing fully OFF and captures the stylus as ordinary [android.view.MotionEvent]s through a
 * [GestureCollector] instead — no wet ink, full-rate samples, ordinary preview blits (see [reconcile]).
 *
 * All Onyx SDK contact is delegated to [OnyxRawDrawingController] in :pen-onyx; this adapter maps the
 * editor's device-neutral vocabulary (a [Tool], rectangles, listener callbacks) onto it, and is the
 * app's single gate onto Onyx support ([isSupported]/[prepareProcess]) so the editor never imports
 * :pen-onyx directly. The controller delivers finished raw gestures on Onyx's own input thread, so
 * this adapter marshals those to the main thread; the lasso touch path already runs on the main
 * thread, matching the [PenBackend.Listener] UI-thread contract.
 *
 * A single [SurfaceView] touch listener also feeds [FingerGestures], which recognizes a two-finger
 * tap (undo), a three-finger tap (redo), and a hold (which arms the next pen stroke as a lasso), and
 * [PageSwipeGestures], which recognizes a one-finger horizontal swipe (page turn) — all alongside
 * whichever pen path is active. Pen and resting fingers share one [android.view.MotionEvent] stream,
 * so no path may steal it from another. Every pen-down/up, from either capture path, is funneled
 * through [onPenGestureStarted]/[onPenGestureFinished], which also latch [captureMode] for the
 * gesture in flight (see that property's doc) so a finger arming the lasso mid-stroke can never
 * drop or reclassify the stroke already under the pen — and tell [PageSwipeGestures] the pen is no
 * longer free for a swipe either.
 *
 * @param currentComposite supplies the committed page bitmap, blitted to clean the surface before
 *   raw drawing is enabled — Onyx requires an already-drawn surface (see [OnyxRawDrawingController]).
 */
class OnyxPenBackend(
    private val currentComposite: () -> Bitmap,
) : PenBackend {

    override val rendersWetInkNatively: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private var controller: OnyxRawDrawingController? = null
    private var surfaceView: SurfaceView? = null
    private var listener: PenBackend.Listener? = null

    // Desired capture state and configuration, held so they can be applied to the controller when it
    // is created in [attach] (some may be set by the editor before the surface is ready).
    private var enabled = true
    private var excludeRects: List<Rect> = emptyList()
    private var tool = Tool.PEN
    private var widthBase = 0f
    private var grayLevel = MAX_GRAY_LEVEL

    // Whether raw drawing is currently resumed. Tracked here so the persist-blit that must precede a
    // resume→pause happens on exactly that edge (see [reconcile]), and whether the lasso touch
    // listener is currently attached, so it is set/cleared only on change.
    private var rawResumed = false
    private var lassoTouchActive = false

    // Freezes which mode the in-flight pen gesture is captured/reported under, from pen-down to
    // pen-up (see [onPenGestureStarted]/[onPenGestureFinished] and [captureMode]'s doc). This is the
    // guarantee the editor gets in its own words: it sets a mode and the backend promises no stroke
    // is dropped or reclassified — a finger going down mid-stroke to arm the lasso is
    // ignored until that stroke ends, and a finger lifting mid-stroke still lets the lasso finish,
    // because intent is expressed by the pen's path, not by how long the fingers happened to stay down.
    private var penGestureInProgress = false
    private var capturedMode = CaptureMode.INK

    // Recognizes the two-finger tap (undo), three-finger tap (redo), and hold (arms the next stroke as
    // a lasso) from the shared touch listener installed in [attach], independent of which pen path is
    // currently active.
    private val fingerGestures = FingerGestures(
        handler = mainHandler,
        onUndoGesture = { listener?.onUndoGesture() },
        onRedoGesture = { listener?.onRedoGesture() },
        onLassoArmed = { listener?.onLassoArmed() },
    )

    // Recognizes a one-finger horizontal swipe (page turn) from the same shared touch listener,
    // independent of fingerGestures — a swipe needs none of its deadline-timer machinery, just the
    // touch grammar PageSwipeGestures owns. density is read lazily because this field is built before
    // attach() gives us a SurfaceView to read Resources.getDisplayMetrics() from.
    private val pageSwipe = PageSwipeGestures(
        density = { surfaceView?.resources?.displayMetrics?.density ?: 1f },
        onNextPage = { listener?.onSwipeNextPage() },
        onPrevPage = { listener?.onSwipePrevPage() },
    )

    // Captures the lasso gesture as ordinary touch while raw drawing is off (see [reconcile]). Stylus
    // only, so a palm resting on the panel does not start a lasso. Touch events are on the main
    // thread, so the listener is called directly — no marshalling.
    private val lassoCollector = GestureCollector(
        stylusOnly = true,
        onStarted = { onPenGestureStarted() },
        onSample = { points -> listener?.onLassoMove(points.last()) },
        onFinished = { points ->
            listener?.onLassoGesture(points)
            onPenGestureFinished()
        },
        onCancelled = {
            listener?.onLassoGesture(emptyList())
            onPenGestureFinished()
        },
    )

    override var captureMode: CaptureMode = CaptureMode.INK
        set(value) {
            if (field == value) return
            field = value
            // Deferred while a gesture is in flight: reconcile() would switch raw drawing (or the
            // lasso listener) out from under the pen. onPenGestureFinished applies the change once
            // the gesture ends instead — see captureMode's doc for the guarantee this buys.
            Log.d(GTAG, "captureMode=$value penGestureInProgress=$penGestureInProgress")
            if (!penGestureInProgress) reconcile()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun attach(surfaceView: SurfaceView, listener: PenBackend.Listener) {
        this.surfaceView = surfaceView
        this.listener = listener
        val controller = OnyxRawDrawingController(
            surfaceView = surfaceView,
            // Onyx delivers these on its input thread; the listener contract is UI-thread, so post.
            // The drawing channel is routed by capturedMode (frozen at this gesture's pen-down), not
            // the live captureMode; the erase channel is the hardware side button.
            //
            // Each Runnable below drops itself when raw drawing is paused (!rawResumed) by the time
            // it actually runs. captureMode changes are deferred while a pen gesture is in progress
            // (see captureMode's setter and onPenGestureFinished), so a genuine raw gesture always
            // both starts and finishes with raw drawing resumed — the only raw callback that can run
            // here with raw drawing paused belongs to a pen-down that landed in the instant raw
            // drawing was being paused for a lasso, and the touch path has already captured that
            // pen-down as the lasso. Routing it too would corrupt it.
            onDrawingGesture = { points ->
                mainHandler.post {
                    if (!rawResumed) return@post
                    routeDrawingGesture(points)
                    onPenGestureFinished()
                }
            },
            onEraseGesture = { points ->
                mainHandler.post {
                    if (!rawResumed) return@post
                    this.listener?.onEraseGesture(points)
                    onPenGestureFinished()
                }
            },
            onGestureStarted = {
                mainHandler.post {
                    if (!rawResumed) return@post
                    onPenGestureStarted()
                }
            },
        )
        this.controller = controller
        controller.setStrokeAppearance(tool, widthBase, grayLevel)
        // Clean the surface with the committed page FIRST, then open raw drawing, then bring capture
        // (raw drawing or the lasso touch listener) up per the current mode.
        controller.renderToScreen(currentComposite())
        controller.openRawDrawing(Rect(0, 0, surfaceView.width, surfaceView.height), excludeRects)
        surfaceView.setOnTouchListener { _, event ->
            if (!enabled) return@setOnTouchListener false
            // All three see every event: during a two-finger-hold lasso the pen and the resting
            // fingers share one MotionEvent stream, so no path may short-circuit another out of it.
            val finger = fingerGestures.onTouch(event)
            val swipe = pageSwipe.onTouch(event)
            val lasso = lassoTouchActive && lassoCollector.onTouch(event)
            Log.d(
                GTAG,
                "touch action=${event.actionMasked} ptrs=${event.pointerCount} " +
                    "tool0=${event.getToolType(0)} dev=${event.deviceId} " +
                    "lassoActive=$lassoTouchActive finger=$finger swipe=$swipe lasso=$lasso",
            )
            finger || swipe || lasso
        }
        reconcile()
    }

    /**
     * The pen touched down, from whichever capture path saw it first (raw drawing's
     * `onGestureStarted`, or the lasso [lassoCollector]'s `onStarted`) — the one place that knows a
     * pen gesture began, so [captureMode] can be latched into [capturedMode] and [fingerGestures]
     * told the pen is no longer free for a hold.
     */
    private fun onPenGestureStarted() {
        Log.d(GTAG, "penGestureStarted mode=$captureMode")
        penGestureInProgress = true
        capturedMode = captureMode
        fingerGestures.onPenDown()
        pageSwipe.onPenDown()
        listener?.onGestureStarted()
    }

    /**
     * The pen lifted or its gesture was abandoned, from whichever capture path finished it (raw
     * drawing's `onDrawingGesture`/`onEraseGesture`, or the lasso [lassoCollector]'s
     * `onFinished`/`onCancelled`) — the one place that knows a pen gesture ended. Un-latches
     * [captureMode] and, if the editor changed it while the gesture was in flight, applies that
     * change now instead of mid-stroke.
     */
    private fun onPenGestureFinished() {
        penGestureInProgress = false
        fingerGestures.onPenUp()
        pageSwipe.onPenUp()
        // Un-latch before reconciling, not after: reconcile() can cancel an in-flight lasso, which
        // re-enters this method, and a capturedMode still holding the old value would make that
        // second pass reconcile all over again.
        val deferred = captureMode != capturedMode
        capturedMode = captureMode
        if (deferred) reconcile()
    }

    /** Routes a finished raw-drawing gesture by [capturedMode]. Runs on the main thread. */
    private fun routeDrawingGesture(points: List<StrokePoint>) {
        val listener = listener ?: return
        when (capturedMode) {
            CaptureMode.INK -> listener.onStrokeFinished(points)
            CaptureMode.ERASE -> listener.onEraseGesture(points)
            // Unreachable in practice: a lasso is captured as touch with raw drawing off, and the
            // raw callbacks wired in attach() already drop anything delivered while raw drawing is
            // paused. A gesture captured under LASSO here could therefore only be a straddle
            // artefact from that pause — and the touch path has already captured that pen-down as
            // the lasso, so routing it too would corrupt it. Drop it.
            CaptureMode.LASSO -> Unit
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            fingerGestures.reset()
            pageSwipe.reset()
            penGestureInProgress = false
        }
        reconcile()
    }

    override fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.toList()
        controller?.setExcludeRects(excludeRects)
        lassoCollector.setExcludeRects(excludeRects)
    }

    override fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        this.tool = tool
        this.widthBase = widthBase
        this.grayLevel = grayLevel
        controller?.setStrokeAppearance(tool, widthBase, grayLevel)
    }

    // The panel paints its own wet ink, so there is no preview here to smooth.
    override fun setInkSmoothing(level: SmoothingLevel) = Unit

    override fun present(composite: Bitmap, cleanRefresh: Boolean) {
        controller?.renderToScreen(composite, cleanRefresh)
    }

    override fun presentDuringCapture(composite: Bitmap) {
        controller?.presentDuringCapture(composite)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun detach() {
        setLassoTouchActive(false)
        surfaceView?.setOnTouchListener(null)
        controller?.close()
        controller = null
        surfaceView = null
        listener = null
        fingerGestures.reset()
        pageSwipe.reset()
        penGestureInProgress = false
        // Drop any gesture callbacks still queued for the main thread, so a late post cannot reach a
        // now-detached listener after teardown.
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Drives the controller and the lasso touch listener to match the current [captureMode] and
     * [enabled]. Raw drawing runs for INK/ERASE — the stylus inks (wet ink on) or erases (wet ink
     * off, gesture routed to erase) through the panel. LASSO turns raw drawing fully off and instead
     * arms the touch listener, because on this firmware raw drawing keeps painting ephemeral wet ink
     * and stops delivering move samples even with rendering disabled, so a live move preview is only
     * possible with raw drawing off. Turning raw drawing off refreshes the panel from the surface
     * buffer, which wipes ephemeral wet ink, so the committed page is blitted first — but only on the
     * resume→pause edge, or just-drawn strokes would vanish on a tool switch, an opening panel, or
     * backgrounding.
     */
    private fun reconcile() {
        val controller = controller ?: return
        val useRaw = enabled && captureMode != CaptureMode.LASSO
        if (useRaw) {
            controller.setWetInkEnabled(captureMode == CaptureMode.INK)
            controller.resume()
            rawResumed = true
        } else {
            if (rawResumed) controller.renderToScreen(currentComposite())
            controller.pause()
            rawResumed = false
        }
        Log.d(GTAG, "reconcile useRaw=$useRaw enabled=$enabled mode=$captureMode")
        setLassoTouchActive(enabled && captureMode == CaptureMode.LASSO)
    }

    /**
     * Arms or disarms the lasso collector's share of the touch listener installed once in [attach]
     * for the whole attachment — [fingerGestures] must see every event regardless of mode, so this
     * never installs or removes the listener itself, only flips whether [lassoCollector] also gets
     * a look at it.
     */
    private fun setLassoTouchActive(active: Boolean) {
        if (active == lassoTouchActive) return
        lassoTouchActive = active
        if (active) {
            lassoCollector.setExcludeRects(excludeRects)
        } else {
            // Turning lasso capture off mid-gesture drops a lasso the touch stream will never finish;
            // report it as an empty (cancelled) gesture so the editor restores the page and clears its
            // live-preview state, rather than leaking a stale drag into the next gesture.
            lassoCollector.reset(notifyCancel = true)
        }
    }

    companion object {
        /** grayLevel default (black) until the editor pushes the real ink darkness. */
        private const val MAX_GRAY_LEVEL = 255

        /** TEMPORARY: two-finger gesture diagnosis, 2026-09-17. Remove with the logging it tags. */
        private const val GTAG = "GestureDebug"

        /** True only on Onyx Boox hardware, where raw drawing is real. */
        fun isSupported(): Boolean = OnyxRawDrawingController.isBooxDevice()

        /**
         * Lifts hidden-API enforcement off the Onyx system classes for this process. Must run once,
         * before the first controller is constructed (before any Onyx SDK class is used). No-op off
         * Boox and on Android versions where the bypass is unavailable.
         */
        fun prepareProcess() = OnyxHiddenApi.exemptOnyxSystemClasses()
    }
}
