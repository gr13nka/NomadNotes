package com.nomadnotes.pen.onyx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
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
 * Threading: Onyx delivers raw-input callbacks on its own input thread, so [onDrawingGesture] and
 * [onEraseGesture] may run off the main thread; the caller marshals them as its contract requires.
 */
class OnyxRawDrawingController(
    private val surfaceView: SurfaceView,
    private val onDrawingGesture: (List<StrokePoint>) -> Unit,
    private val onEraseGesture: (List<StrokePoint>) -> Unit = {},
) {

    // Configuration the caller has chosen, re-applied verbatim whenever raw drawing is (re)opened:
    // TouchHelper fixes the capture region and defaults at openRawDrawing time, so a later change to
    // the region means a close/reopen (see [setExcludeRects]).
    private var limitRect = Rect()
    private var excludeRects: List<Rect> = emptyList()
    private var strokeStyle: Int = TouchHelper.STROKE_STYLE_PENCIL
    private var strokeWidthPx: Float = DEFAULT_STROKE_WIDTH
    private var strokeColor: Int = Color.BLACK

    // State mirrors: TouchHelper exposes no getters we trust, so we track these here to bracket
    // [renderToScreen] correctly and to restore state across a region reopen. `wetInkEnabled` is
    // whether the panel paints wet ink; it is off while a gesture is a selection (erase/lasso).
    private var rawDrawingOpen = false
    private var drawingEnabled = false
    private var wetInkEnabled = true

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
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onEndRawDrawing at (${point?.x}, ${point?.y})")
        }

        // Silent by design: this fires on every pen sample, so logging here floods the input thread.
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList?) {
            val points = pointList?.points ?: return
            Log.i(TAG, "onRawDrawingTouchPointListReceived: ${points.size} points (wetInk=$wetInkEnabled)")
            // Every drawing-channel gesture goes up verbatim; the caller knows from its capture mode
            // whether it is a stroke, an erase, or a lasso (wet ink is off for the latter two).
            onDrawingGesture(points.toStrokePoints())
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint?) {
            Log.i(TAG, "onBeginRawErasing")
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
     * the stroke style (and a wider nib for the marker), [widthBase] is the nib width in surface
     * pixels, and [grayLevel] (0 = white, 255 = black) picks a gray so the wet tone ≈ the committed
     * tone. Takes effect on the next stroke; may be called before or after [openRawDrawing].
     */
    fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        strokeStyle = tool.toStrokeStyle()
        strokeWidthPx = tool.strokeWidth(widthBase)
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
     * Replaces the excluded regions (e.g. when a measured toolbar moves). TouchHelper fixes its
     * capture region at [openRawDrawing], so once open the change is applied by closing and
     * reopening raw drawing, preserving the enabled state across the swap. A no-op if unchanged.
     */
    fun setExcludeRects(rects: List<Rect>) {
        val updated = rects.map { Rect(it) }
        if (updated == excludeRects) return
        excludeRects = updated
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
            val canvas = surfaceView.holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } finally {
                    surfaceView.holder.unlockCanvasAndPost(canvas)
                }
            }
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

    private fun List<TouchPoint>.toStrokePoints(): List<StrokePoint> {
        if (isEmpty()) return emptyList()
        val startTimestamp = first().timestamp
        return map { p ->
            StrokePoint(
                x = p.x,
                y = p.y,
                pressure = (p.pressure / maxPressure).coerceIn(0f, 1f),
                timestampDelta = p.timestamp - startTimestamp,
            )
        }
    }

    private fun Tool.toStrokeStyle(): Int = when (this) {
        // Fountain varies width with pressure/speed, like the editor's pressure-modulated pen.
        Tool.PEN -> TouchHelper.STROKE_STYLE_FOUNTAIN
        Tool.PENCIL -> TouchHelper.STROKE_STYLE_PENCIL
        Tool.MARKER -> TouchHelper.STROKE_STYLE_MARKER
    }

    private fun Tool.strokeWidth(widthBase: Float): Float = when (this) {
        Tool.PEN, Tool.PENCIL -> widthBase
        // Mirrors StrokeRenderer's broad marker nib so the wet stroke ≈ the committed one.
        Tool.MARKER -> widthBase * MARKER_WIDTH_MULTIPLIER
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
        private const val MARKER_WIDTH_MULTIPLIER = 2.5f

        /** Fallback pressure range when the device reports a nonpositive maximum (see [maxPressure]). */
        private const val DEFAULT_MAX_PRESSURE = 4096f

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
