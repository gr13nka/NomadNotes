package com.nomadnotes.pen.onyx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.ink.NibProfile
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Drives Onyx TouchHelper raw drawing for a single [SurfaceView], and is the only place in the
 * app that touches the Onyx SDK.
 *
 * Raw drawing is what makes ink lag-free: while the pen is down the e-ink hardware paints the
 * "wet" stroke itself, bypassing the view hierarchy. That wet ink is ephemeral — it is not part
 * of any bitmap and disappears on the next surface refresh — so the caller must persist finished
 * strokes on its own. This controller reports every finished pen-down gesture through
 * [onDrawingGesture], and stylus side-button erasing through [onEraseGesture], as neutral
 * [StrokePoint]s (never Onyx types); [renderToScreen] blits the caller's persisted bitmap back with
 * an e-ink-appropriate update mode.
 *
 * The controller is deliberately mechanical about the two things the SDK exposes and nothing more:
 * whether the panel paints wet ink ([setWetInkEnabled]) and delivering the raw gestures. It does not
 * know what a gesture *means* — with wet ink off, a pen-down gesture might be an erase or a lasso —
 * so [onDrawingGesture] carries it up verbatim and the caller decides. Only the stylus side button,
 * which the SDK reports on its own erasing channel, is unambiguous and surfaces as [onEraseGesture].
 *
 * The controller does translate the editor's neutral drawing vocabulary onto the SDK, so the SDK's
 * own constants stay hidden here: [setStrokeAppearance] maps a [Tool], nib width, and ink darkness
 * to a stroke style/width/colour, and device pressure is normalized to the 0..1 [StrokePoint]
 * contract before a gesture leaves this class.
 *
 * The caller owns the lifecycle and must forward it: [openRawDrawing] once the surface and its
 * layout rectangles are known, [resume]/[pause] from the Activity's onResume/onPause, and [close]
 * from onDestroy.
 *
 * Threading: the SDK does not promise a thread for raw-input callbacks, so [onGestureStarted],
 * [onDrawingGesture] and [onEraseGesture] may run off the main thread and the caller marshals them as
 * its contract requires. On the Go 10.3 firmware they arrive on the main thread, so anything heavy
 * done here (such as [FountainInkSizer]) stalls the UI.
 */
class OnyxRawDrawingController(
    private val surfaceView: SurfaceView,
    private val onDrawingGesture: (List<StrokePoint>) -> Unit,
    private val onEraseGesture: (List<StrokePoint>) -> Unit = {},
    private val onGestureStarted: () -> Unit = {},
) {

    // Configuration the caller has chosen, re-applied verbatim whenever raw drawing is (re)opened:
    // TouchHelper fixes the capture region and defaults at openRawDrawing time, so a later change to
    // the region means a close/reopen (see [setExcludeRects]).
    private var limitRect = Rect()
    private var excludeRects: List<Rect> = emptyList()
    private var strokeStyle: Int = TouchHelper.STROKE_STYLE_PENCIL
    private var strokeWidthPx: Float = DEFAULT_STROKE_WIDTH
    private var strokeColor: Int = Color.BLACK

    // The tool behind strokeStyle/strokeWidthPx, kept separately because toStrokePoints needs to
    // decide (not just configure) by it: only PEN gets measured nib factors (see [nibFactorsFor]).
    private var currentTool: Tool = Tool.PENCIL

    // State mirrors: TouchHelper exposes no getters we trust, so we track these here to bracket
    // [renderToScreen] correctly and to restore state across a region reopen. `wetInkEnabled` is
    // whether the panel paints wet ink; it is off while a gesture is a selection (erase/lasso).
    private var rawDrawingOpen = false
    private var drawingEnabled = false
    private var wetInkEnabled = true

    // Off by default, matching TouchHelper's own default before this is ever called — the finger
    // probe (see setFingerTouchEnabled) is what determines whether flipping it does anything.
    private var fingerTouchEnabled = false

    // Device pressure range, read once. TouchPoint.pressure is a raw device value; the StrokePoint
    // contract requires 0..1, so points are divided by this. Guarded against a nonpositive reading.
    private val maxPressure: Float by lazy {
        val reported = runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f)
        if (reported > 0f) reported else DEFAULT_MAX_PRESSURE
    }

    // Debug instrumentation: the begin/end/list callbacks log so a pen test can confirm from logcat
    // alone that raw input reaches the app.
    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onBeginRawDrawing at (${point?.x}, ${point?.y})")
            // The pen-down edge, reported before any points exist. The caller needs it to abandon
            // deferred repaints: [renderToScreen] suspends capture while it blits, which would eat
            // part of the stroke now starting.
            onGestureStarted()
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onEndRawDrawing at (${point?.x}, ${point?.y})")
        }

        // Silent by design: this fires on every pen sample, so logging here floods the input thread.
        // Not surfaced: on this firmware raw drawing stops delivering these once its rendering is
        // disabled, so a selection's live preview is driven by ordinary touch instead (raw drawing is
        // turned off for a lasso — see OnyxPenBackend).
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList?) {
            val points = pointList?.points ?: return
            Log.i(TAG, "onRawDrawingTouchPointListReceived: ${points.size} points (wetInk=$wetInkEnabled)")
            // Every drawing-channel gesture goes up verbatim; the caller knows from its capture mode
            // whether it is a stroke, an erase, or a lasso (wet ink is off for the latter two).
            onDrawingGesture(points.toStrokePoints(withNibFactors = currentTool == Tool.PEN))
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onBeginRawErasing")
            // The same pen-down edge as onBeginRawDrawing, on the side-button erase channel. Reported
            // for the same reasons: a deferred repaint must not blit through an erase either, and the
            // caller's finger-gesture recognizer needs to know the pen is down so a palm landing
            // mid-erase cannot be read as a deliberate two-finger gesture.
            onGestureStarted()
        }

        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onEndRawErasing")
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList?) {
            // Stylus side-button erasing: always an erase gesture, independent of the UI tool.
            val points = pointList?.points ?: return
            Log.i(TAG, "onRawErasingTouchPointListReceived: ${points.size} points")
            onEraseGesture(points.toStrokePoints())
        }
    }

    private val touchHelper: TouchHelper = TouchHelper.create(surfaceView, callback)

    /**
     * Starts capturing pen input inside [limitRect] but not inside [excludeRects] (e.g. a top bar,
     * so its buttons stay tappable). Coordinates are relative to [surfaceView]. Call once, after the
     * surface is created and laid out; follow with [resume] to enable drawing. Any stroke appearance
     * or erase mode set beforehand is applied here.
     */
    fun openRawDrawing(limitRect: Rect, excludeRects: List<Rect>) {
        this.limitRect = Rect(limitRect)
        this.excludeRects = excludeRects.map { Rect(it) }
        Log.i(
            TAG,
            "openRawDrawing: surface=${surfaceView.width}x${surfaceView.height} " +
                "limitRect=$limitRect excludeRects=$excludeRects",
        )
        applyRegionAndOpen()
    }

    // Opens raw drawing with the currently stored region and appearance. openRawDrawing must be
    // preceded by setStrokeWidth/setLimitRect and followed by the style/colour/render/erase setters,
    // matching Onyx's ScribbleTouchHelperDemoActivity; this keeps that exact order in one place so a
    // region reopen restores an identical configuration.
    private fun applyRegionAndOpen() {
        touchHelper
            .setStrokeWidth(strokeWidthPx)
            .setLimitRect(limitRect, ArrayList(excludeRects))
            .openRawDrawing()
        touchHelper.setStrokeStyle(strokeStyle)
        touchHelper.setStrokeColor(strokeColor)
        touchHelper.setRawDrawingRenderEnabled(wetInkEnabled)
        touchHelper.enableFingerTouch(fingerTouchEnabled)
        // Route the stylus side button to erasing, so hardware-side erase reaches onEraseGesture.
        touchHelper.enableSideBtnErase(true)
        rawDrawingOpen = true
    }

    fun resume() {
        touchHelper.setRawDrawingEnabled(true)
        drawingEnabled = true
    }

    fun pause() {
        touchHelper.setRawDrawingEnabled(false)
        drawingEnabled = false
    }

    fun close() {
        touchHelper.closeRawDrawing()
        rawDrawingOpen = false
        drawingEnabled = false
    }

    /**
     * Sets how the wet stroke looks so it matches the ink the caller will later render: [tool] picks
     * the stroke style, [widthBase] is fed through :core's [NibProfile] for the same nib width the
     * renderer and touch preview use (wider for the marker), and [grayLevel] (0 = white, 255 =
     * black) picks a gray so the wet tone ≈ the committed tone. Takes effect on the next stroke; may
     * be called before or after [openRawDrawing].
     *
     * If a device pass finds the hardware fountain nib reading heavier than our own PEN ink, scale
     * [NibProfile.maxWidth] here by a hardware-only constant rather than adjusting the shared
     * profile — the mismatch is this panel's rendering, not the width law.
     */
    fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        currentTool = tool
        strokeStyle = tool.toStrokeStyle()
        strokeWidthPx = NibProfile.forTool(tool, widthBase).maxWidth
        strokeColor = grayLevelToColor(grayLevel)
        if (rawDrawingOpen) {
            touchHelper.setStrokeStyle(strokeStyle)
            touchHelper.setStrokeWidth(strokeWidthPx)
            touchHelper.setStrokeColor(strokeColor)
        }
    }

    /**
     * Turns the panel's wet-ink rendering on or off. The caller disables it while a pen-down gesture
     * is a selection (erase or lasso) rather than a mark, and re-enables it to ink again; the pen
     * keeps reporting through [onDrawingGesture] either way. A no-op if unchanged.
     */
    fun setWetInkEnabled(enabled: Boolean) {
        if (wetInkEnabled == enabled) return
        wetInkEnabled = enabled
        if (rawDrawingOpen) touchHelper.setRawDrawingRenderEnabled(enabled)
    }

    /**
     * Whether the panel lets finger touches through to the ordinary View event stream while raw
     * drawing is enabled. Firmware-dependent — see the 2026-09 finger probe
     * (docs/BACKLOG.md item 4) — rather than a documented SDK guarantee; two-finger undo and the
     * finger-plus-pen lasso gesture both depend on the answer. Takes effect immediately if raw
     * drawing is open, and is re-applied verbatim on a region reopen (see [setExcludeRects]). A
     * no-op if unchanged.
     */
    fun setFingerTouchEnabled(enabled: Boolean) {
        if (fingerTouchEnabled == enabled) return
        fingerTouchEnabled = enabled
        if (rawDrawingOpen) touchHelper.enableFingerTouch(enabled)
    }

    // PROBE ONLY: gives OnyxTouchProbeKnobs (same module) a way to reach the live TouchHelper
    // instance for its four speculative firmware settings without widening this class's own public
    // surface. Delete alongside OnyxTouchProbeKnobs and the finger probe.
    internal fun touchHelperForProbe(): TouchHelper = touchHelper

    /**
     * Replaces the excluded regions (e.g. when a measured toolbar moves). TouchHelper fixes its
     * capture region at [openRawDrawing], so once open the change is applied by closing and
     * reopening raw drawing, preserving the enabled state across the swap. A no-op if unchanged.
     */
    fun setExcludeRects(rects: List<Rect>) {
        val updated = rects.map { Rect(it) }
        if (updated == excludeRects) return
        excludeRects = updated
        reopenIfOpen()
    }

    /**
     * Replaces the capture rectangle outright rather than subtracting from it (unlike
     * [setExcludeRects]) — for a caller that wants raw drawing restricted to one small region
     * (e.g. the sticker panel's drawing box) instead of the whole surface. Pass the original
     * full-surface rect and `emptyList()` to restore ordinary capture. Same close/reopen dance as
     * [setExcludeRects], since TouchHelper fixes both [limitRect] and [excludeRects] at
     * [openRawDrawing] time. A no-op if neither changed.
     */
    fun setLimitRect(rect: Rect, excludeRects: List<Rect>) {
        val updatedExcludes = excludeRects.map { Rect(it) }
        if (rect == limitRect && updatedExcludes == this.excludeRects) return
        limitRect = Rect(rect)
        this.excludeRects = updatedExcludes
        reopenIfOpen()
    }

    /**
     * Closes and reopens raw drawing with the current [limitRect]/[excludeRects], preserving the
     * enabled state across the swap — shared by [setExcludeRects] and [setLimitRect], the two ways
     * either can change once raw drawing is already open. A no-op while it never has been.
     */
    private fun reopenIfOpen() {
        if (!rawDrawingOpen) return
        val wasEnabled = drawingEnabled
        if (drawingEnabled) pause()
        touchHelper.closeRawDrawing()
        rawDrawingOpen = false
        applyRegionAndOpen()
        if (wasEnabled) resume()
    }

    /**
     * Blits [bitmap] onto the surface. Used to show persisted strokes (and to clear), since raw
     * drawing's wet ink is not retained.
     *
     * When [clean] is set (a repaint that *removes* ink, i.e. erasing) the panel is then forced
     * through a full-panel [ERASE_CLEAN_UPDATE_MODE] refresh. The surface post below runs a fast
     * waveform that leaves the erased strokes ghosted (a visible "blink"); crucially,
     * [EpdController.setViewDefaultUpdateMode] governs only the view's ordinary draw path, NOT a
     * manual lockCanvas/unlockCanvasAndPost, so the waveform must be forced explicitly with
     * [EpdController.refreshScreen] after the post.
     *
     * A lockCanvas/unlockCanvasAndPost corrupts TouchHelper's rendering only while raw drawing is
     * actively rendering, so the blit is bracketed by disable/enable exactly then; when raw drawing
     * is paused or not yet open, the surface is ours and the blit runs directly. Onyx's own
     * ScribbleTouchHelperDemoActivity brackets every app-side surface draw the same way. The prior
     * enabled state is preserved, so presenting while paused (e.g. a panel is open) does not
     * re-enable drawing.
     */
    fun renderToScreen(bitmap: Bitmap, clean: Boolean = false) {
        val bracket = rawDrawingOpen && drawingEnabled
        if (bracket) touchHelper.setRawDrawingEnabled(false)
        try {
            EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE)
            lockAndBlit(bitmap)
            // TODO(erase-flash): this clears the whole panel, so every erase flashes heavily. To
            // confine the flash, refresh only the erased strokes' bounding box with
            // EpdController.refreshScreenRegion(surfaceView, l, t, r, b, mode) — that Rect would have
            // to be threaded down from the editor's erase gesture (renderPage/present carry it). A
            // lighter waveform (GC4) or a full clear only every Nth erase are cheaper alternatives
            // that trade a little ghosting for less flash.
            if (clean) EpdController.refreshScreen(surfaceView, ERASE_CLEAN_UPDATE_MODE)
        } finally {
            EpdController.resetViewUpdateMode(surfaceView)
            if (bracket) touchHelper.setRawDrawingEnabled(true)
        }
    }

    /**
     * Blits [bitmap] onto the surface for the editor's live lasso preview. A lasso is captured with
     * raw drawing turned fully off (the caller drives it from ordinary touch — see OnyxPenBackend), so
     * unlike [renderToScreen] there is no in-flight raw capture to preserve and no bracket to apply:
     * this is a plain repaint, kept as its own method only to name the preview path. LASSO-only.
     */
    fun presentDuringCapture(bitmap: Bitmap) {
        try {
            EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE)
            lockAndBlit(bitmap)
        } finally {
            EpdController.resetViewUpdateMode(surfaceView)
        }
    }

    // Locks the surface and paints [bitmap] over white. The caller owns the update-mode setup and any
    // raw-drawing bracketing around it; this is only the pixel copy.
    private fun lockAndBlit(bitmap: Bitmap) {
        val canvas = surfaceView.holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * [withNibFactors] asks [FountainInkSizer] for this PEN stroke's measured nib widths and
     * attaches each as a [StrokePoint.nibFactor]; false for every other gesture (erase, and PENCIL/
     * MARKER strokes, whose nib does not vary — see [NibProfile.forTool]'s `minWidthFactor`).
     * [FountainInkSizer] needs the *raw* device pressure this list already carries, so sizing runs
     * before pressure is normalized into the 0..1 [StrokePoint] contract below.
     */
    private fun List<TouchPoint>.toStrokePoints(withNibFactors: Boolean = false): List<StrokePoint> {
        if (isEmpty()) return emptyList()
        val nibFactors = if (withNibFactors) nibFactorsFor(this) else null
        val startTimestamp = first().timestamp
        return mapIndexed { i, p ->
            StrokePoint(
                x = p.x,
                y = p.y,
                pressure = (p.pressure / maxPressure).coerceIn(0f, 1f),
                timestampDelta = p.timestamp - startTimestamp,
                nibFactor = nibFactors?.get(i),
            )
        }
    }

    /**
     * [FountainInkSizer]'s widths for [points], as fractions of [strokeWidthPx] — the same
     * denominator [NibProfile.maxWidth] resolves to for PEN, so `nib.maxWidth * factor` at render
     * time recovers the width the engine actually reported. Null per point where the sizer
     * couldn't produce a sane value; the renderer's fallback law only ever triggers per *stroke*
     * (any missing factor demotes the whole stroke — see `inkOutline`), which is deliberate: a
     * stroke half sized by measurement and half by guesswork would show a visible seam.
     */
    private fun nibFactorsFor(points: List<TouchPoint>): List<Float?>? {
        val sizes = FountainInkSizer.sizesFor(points, strokeWidthPx, maxPressure) ?: return null
        return sizes.map { size ->
            (size / strokeWidthPx).takeIf { it.isFinite() }?.coerceIn(MIN_NIB_FACTOR, MAX_NIB_FACTOR)
        }
    }

    private fun Tool.toStrokeStyle(): Int = when (this) {
        // Fountain varies width with pressure/speed, like the editor's tapered PEN outline.
        Tool.PEN -> TouchHelper.STROKE_STYLE_FOUNTAIN
        Tool.PENCIL -> TouchHelper.STROKE_STYLE_PENCIL
        Tool.MARKER -> TouchHelper.STROKE_STYLE_MARKER
    }

    // Mirrors StrokeRenderer.grayLevelToColor: darkness 0..255 maps to a gray whose channel value is
    // its complement (255 = black). Duplicated because :pen-onyx cannot depend on :app's renderer.
    private fun grayLevelToColor(grayLevel: Int): Int {
        val channel = 255 - grayLevel.coerceIn(0, 255)
        return Color.rgb(channel, channel, channel)
    }

    companion object {
        private const val TAG = "OnyxRawDrawing"
        private const val DEFAULT_STROKE_WIDTH = 3.0f

        /** Fallback pressure range when the device reports a nonpositive maximum (see [maxPressure]). */
        private const val DEFAULT_MAX_PRESSURE = 4096f

        // Sane bounds on a StrokePoint.nibFactor built from FountainInkSizer's output (see
        // [nibFactorsFor]): wide enough that a genuinely thin or heavy nib moment survives, narrow
        // enough that a unit mismatch or a native-side glitch can't render as an invisible or
        // page-spanning stroke.
        private const val MIN_NIB_FACTOR = 0.05f
        private const val MAX_NIB_FACTOR = 3f

        /**
         * Waveform for a repaint that removes ink (see [renderToScreen]'s `clean`). GC is a full
         * grayscale-clear: it refreshes the whole panel so no erased stroke is left ghosted. The
         * lighter anti-ghost modes (REGAL/GC4) accumulate ghost residue over a few erases — the just-
         * erased stroke and the last few before it "blink" back until the panel forces a full refresh
         * — so a full clear is used despite its brief flash. Tune here if the flash is too heavy (try
         * [UpdateMode.GC4] for a lighter clear that may reintroduce some ghosting).
         */
        private val ERASE_CLEAN_UPDATE_MODE = UpdateMode.GC

        /** Onyx Boox hardware reports "ONYX" as the manufacturer; only there is raw drawing real. */
        fun isBooxDevice(): Boolean = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
