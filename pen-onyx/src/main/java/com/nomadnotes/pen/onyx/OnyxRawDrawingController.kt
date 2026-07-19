package com.nomadnotes.pen.onyx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
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
 * strokes on its own. This controller reports each finished stroke through [onStrokeFinished] as
 * neutral [StrokePoint]s (never Onyx types), and [renderToScreen] blits the caller's persisted
 * bitmap back with an e-ink-appropriate update mode.
 *
 * The caller owns the lifecycle and must forward it: [openRawDrawing] once the surface and its
 * layout rectangles are known, [resume]/[pause] from the Activity's onResume/onPause, and [close]
 * from onDestroy.
 *
 * Threading: Onyx delivers raw-input callbacks on its own input thread, so [onStrokeFinished] may
 * run off the main thread.
 */
class OnyxRawDrawingController(
    private val surfaceView: SurfaceView,
    private val onStrokeFinished: (List<StrokePoint>) -> Unit,
) {

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList?) {
            val points = pointList?.points ?: return
            onStrokeFinished(points.toStrokePoints())
        }

        // Erasing is out of scope for the spike.
        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList?) = Unit
    }

    private val touchHelper: TouchHelper = TouchHelper.create(surfaceView, callback)

    /**
     * Starts capturing pen input inside [limitRect] but not inside [excludeRects] (e.g. the top
     * bar, so its buttons stay tappable). Coordinates are relative to [surfaceView]. Call once,
     * after the surface is created and laid out; follow with [resume] to enable drawing.
     */
    fun openRawDrawing(limitRect: Rect, excludeRects: List<Rect>) {
        touchHelper
            .setStrokeWidth(STROKE_WIDTH)
            .setLimitRect(limitRect, ArrayList(excludeRects))
            .openRawDrawing()
        // PENCIL keeps a near-uniform width, matching the fixed-width polyline the caller persists,
        // so the wet ink and the redrawn ink look the same.
        touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
        touchHelper.setRawDrawingRenderEnabled(true)
    }

    fun resume() {
        touchHelper.setRawDrawingEnabled(true)
    }

    fun pause() {
        touchHelper.setRawDrawingEnabled(false)
    }

    fun close() {
        touchHelper.closeRawDrawing()
    }

    /**
     * Blits [bitmap] onto the surface using the e-ink handwriting update mode. Used to show
     * persisted strokes (and to clear), since raw drawing's wet ink is not retained.
     */
    fun renderToScreen(bitmap: Bitmap) {
        EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE)
        val canvas = surfaceView.holder.lockCanvas()
        if (canvas == null) {
            EpdController.resetViewUpdateMode(surfaceView)
            return
        }
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surfaceView.holder.unlockCanvasAndPost(canvas)
            EpdController.resetViewUpdateMode(surfaceView)
        }
    }

    private fun List<TouchPoint>.toStrokePoints(): List<StrokePoint> {
        if (isEmpty()) return emptyList()
        val startTimestamp = first().timestamp
        return map { p ->
            StrokePoint(
                x = p.x,
                y = p.y,
                pressure = p.pressure,
                timestampDelta = p.timestamp - startTimestamp,
            )
        }
    }

    companion object {
        private const val STROKE_WIDTH = 3.0f

        /** Onyx Boox hardware reports "ONYX" as the manufacturer; only there is raw drawing real. */
        fun isBooxDevice(): Boolean = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
