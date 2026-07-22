package com.nomadnotes.app.input

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.SurfaceView
import com.nomadnotes.app.render.StrokeRenderer
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool

/**
 * A [PenBackend] built on ordinary touch input, for the emulator and non-Onyx tablets.
 *
 * Unlike the e-ink panel, plain touch has no hardware "wet ink", so this backend paints the
 * in-progress gesture itself: on every sample it re-blits the current committed page under a live
 * preview of the points collected so far. That is cheap enough for a normal display and would be
 * wrong on e-ink (which is why the Onyx path never inks this way). When the gesture ends the whole
 * point list is handed to the listener — as a drawn stroke, an erase gesture, or a lasso gesture per
 * the current [captureMode] — and the editor repaints the committed result over this preview.
 *
 * The touch grammar (start/append/finish/cancel, point building) is delegated to a shared
 * [GestureCollector]; this backend only decides what to draw and how to route the finished gesture.
 * Main-thread only: touch events arrive there and nothing here is synchronized.
 *
 * @param currentComposite supplies the page bitmap to show beneath the live preview — the editor's
 *   up-to-date composite of everything already committed.
 */
class AndroidPenBackend(
    private val currentComposite: () -> Bitmap,
) : PenBackend {

    override var captureMode: CaptureMode = CaptureMode.INK

    // Plain touch has no hardware wet ink, so this backend must draw and present every change itself.
    override val rendersWetInkNatively: Boolean = false

    private var surfaceView: SurfaceView? = null
    private var listener: PenBackend.Listener? = null
    private var enabled: Boolean = true

    // Rebuilt by [setStrokeAppearance] so the live preview approximates the committed stroke.
    private var inkPreviewPaint = strokePaint(Color.BLACK, INK_PREVIEW_WIDTH)
    private val erasePreviewPaint = strokePaint(ERASE_PREVIEW_COLOR, ERASE_PREVIEW_WIDTH)
    private val previewPath = Path()

    // Any pointer is a drawing tool here (the emulator and plain tablets use touch, not a stylus).
    private val collector = GestureCollector(
        stylusOnly = false,
        onSample = { points -> onGestureSample(points) },
        onFinished = { points -> onGestureFinished(points) },
        onCancelled = { onGestureCancelled() },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun attach(surfaceView: SurfaceView, listener: PenBackend.Listener) {
        this.surfaceView = surfaceView
        this.listener = listener
        surfaceView.setOnTouchListener { _, event -> enabled && collector.onTouch(event) }
        // Bring the surface up to the committed page as the first thing the user sees.
        present(currentComposite(), cleanRefresh = false)
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) collector.reset()
    }

    override fun setExcludeRects(rects: List<Rect>) {
        collector.setExcludeRects(rects)
    }

    override fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        val color = StrokeRenderer.grayLevelToColor(grayLevel)
        val width = if (tool == Tool.MARKER) widthBase * StrokeRenderer.MARKER_WIDTH_MULTIPLIER else widthBase
        inkPreviewPaint = strokePaint(color, width).apply {
            if (tool == Tool.MARKER) alpha = StrokeRenderer.MARKER_ALPHA
        }
    }

    override fun present(composite: Bitmap, cleanRefresh: Boolean) {
        // Plain touch has no e-ink ghosting, so a clean refresh looks no different here; ignore it.
        lockedSurface { canvas ->
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(composite, 0f, 0f, null)
        }
    }

    // Plain touch has no hardware capture to abort, so a during-capture blit is just an ordinary one.
    override fun presentDuringCapture(composite: Bitmap) = present(composite)

    @SuppressLint("ClickableViewAccessibility")
    override fun detach() {
        surfaceView?.setOnTouchListener(null)
        surfaceView = null
        listener = null
        collector.reset()
    }

    /** A pen-down or move sample: draw the live preview, or (in LASSO) hand it to the editor's preview. */
    private fun onGestureSample(points: List<StrokePoint>) {
        if (captureMode == CaptureMode.LASSO) {
            // The editor renders the lasso preview (a move drag, or the outline) from these live
            // samples; forward the latest one and draw nothing here.
            listener?.onLassoMove(points.last())
        } else {
            blitPreview(points)
        }
    }

    private fun onGestureFinished(points: List<StrokePoint>) {
        // The editor repaints the committed page (the new stroke, the page with strokes erased, or
        // the selection decorations) right after this callback, overwriting the preview on the surface.
        val listener = listener ?: return
        when (captureMode) {
            CaptureMode.INK -> listener.onStrokeFinished(points)
            CaptureMode.ERASE -> listener.onEraseGesture(points)
            CaptureMode.LASSO -> listener.onLassoGesture(points)
        }
    }

    private fun onGestureCancelled() {
        // A cancelled lasso: tell the editor with an empty gesture so it drops its live preview and
        // restores the page. Other modes just wipe their self-drawn preview.
        if (captureMode == CaptureMode.LASSO) listener?.onLassoGesture(emptyList()) else blitComposite()
    }

    private fun blitPreview(points: List<StrokePoint>) {
        lockedSurface { canvas ->
            drawComposite(canvas)
            drawPreview(canvas, points)
        }
    }

    private fun blitComposite() {
        lockedSurface { canvas -> drawComposite(canvas) }
    }

    private inline fun lockedSurface(draw: (Canvas) -> Unit) {
        val holder = surfaceView?.holder ?: return
        val canvas = holder.lockCanvas() ?: return
        try {
            draw(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawComposite(canvas: Canvas) {
        canvas.drawBitmap(currentComposite(), 0f, 0f, null)
    }

    private fun drawPreview(canvas: Canvas, points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val paint = when (captureMode) {
            CaptureMode.INK -> inkPreviewPaint
            CaptureMode.ERASE -> erasePreviewPaint
            // The editor renders the lasso preview itself (from onLassoMove), so blitPreview is never
            // called in LASSO mode. Guard defensively.
            CaptureMode.LASSO -> return
        }
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        previewPath.reset()
        previewPath.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) previewPath.lineTo(points[i].x, points[i].y)
        canvas.drawPath(previewPath, paint)
    }

    private fun strokePaint(color: Int, width: Float) = Paint().apply {
        isAntiAlias = true
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private companion object {
        /** The preview width before [setStrokeAppearance] runs; the finished stroke replaces it with real ink. */
        const val INK_PREVIEW_WIDTH = 3f

        /** The eraser preview hints its path and rough reach without looking like drawn ink. */
        const val ERASE_PREVIEW_WIDTH = 12f
        const val ERASE_PREVIEW_COLOR = 0x66808080 // translucent gray
    }
}
