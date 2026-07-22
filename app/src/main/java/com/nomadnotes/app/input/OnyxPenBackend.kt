package com.nomadnotes.app.input

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
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

    // Captures the lasso gesture as ordinary touch while raw drawing is off (see [reconcile]). Stylus
    // only, so a palm resting on the panel does not start a lasso. Touch events are on the main
    // thread, so the listener is called directly — no marshalling.
    private val lassoCollector = GestureCollector(
        stylusOnly = true,
        onSample = { points -> listener?.onLassoMove(points.last()) },
        onFinished = { points -> listener?.onLassoGesture(points) },
        onCancelled = { listener?.onLassoGesture(emptyList()) },
    )

    override var captureMode: CaptureMode = CaptureMode.INK
        set(value) {
            if (field == value) return
            field = value
            reconcile()
        }

    override fun attach(surfaceView: SurfaceView, listener: PenBackend.Listener) {
        this.surfaceView = surfaceView
        this.listener = listener
        val controller = OnyxRawDrawingController(
            surfaceView = surfaceView,
            // Onyx delivers these on its input thread; the listener contract is UI-thread, so post.
            // The drawing channel is routed by captureMode on the main thread (after the post), so it
            // reads the mode the editor last set; the erase channel is the hardware side button.
            onDrawingGesture = { points -> mainHandler.post { routeDrawingGesture(points) } },
            onEraseGesture = { points -> mainHandler.post { this.listener?.onEraseGesture(points) } },
        )
        this.controller = controller
        controller.setStrokeAppearance(tool, widthBase, grayLevel)
        // Clean the surface with the committed page FIRST, then open raw drawing, then bring capture
        // (raw drawing or the lasso touch listener) up per the current mode.
        controller.renderToScreen(currentComposite())
        controller.openRawDrawing(Rect(0, 0, surfaceView.width, surfaceView.height), excludeRects)
        reconcile()
    }

    /** Routes a finished raw-drawing gesture by the current [captureMode]. Runs on the main thread. */
    private fun routeDrawingGesture(points: List<StrokePoint>) {
        val listener = listener ?: return
        when (captureMode) {
            CaptureMode.INK -> listener.onStrokeFinished(points)
            CaptureMode.ERASE -> listener.onEraseGesture(points)
            // A lasso is captured as touch, not on the raw channel (raw drawing is off in LASSO), so
            // this is not normally reached; route it anyway in case a gesture straddles a mode change.
            CaptureMode.LASSO -> listener.onLassoGesture(points)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
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

    override fun present(composite: Bitmap, cleanRefresh: Boolean) {
        controller?.renderToScreen(composite, cleanRefresh)
    }

    override fun presentDuringCapture(composite: Bitmap) {
        controller?.presentDuringCapture(composite)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun detach() {
        setLassoTouchActive(false)
        controller?.close()
        controller = null
        surfaceView = null
        listener = null
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
        setLassoTouchActive(enabled && captureMode == CaptureMode.LASSO)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setLassoTouchActive(active: Boolean) {
        if (active == lassoTouchActive) return
        lassoTouchActive = active
        val surfaceView = surfaceView ?: return
        if (active) {
            lassoCollector.setExcludeRects(excludeRects)
            surfaceView.setOnTouchListener { _, event -> lassoCollector.onTouch(event) }
        } else {
            surfaceView.setOnTouchListener(null)
            lassoCollector.reset()
        }
    }

    companion object {
        /** grayLevel default (black) until the editor pushes the real ink darkness. */
        private const val MAX_GRAY_LEVEL = 255

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
