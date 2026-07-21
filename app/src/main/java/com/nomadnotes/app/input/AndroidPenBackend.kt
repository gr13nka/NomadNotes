package com.nomadnotes.app.input

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceView
import com.nomadnotes.app.render.StrokeRenderer
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool

/**
 * A [PenBackend] built on ordinary [MotionEvent] touch input, for the emulator and non-Onyx
 * tablets (and, for now, the editor on Onyx hardware too, until raw drawing is wired in).
 *
 * Unlike the e-ink panel, plain touch has no hardware "wet ink", so this backend paints the
 * in-progress gesture itself: on every move it re-blits the current committed page under a live
 * preview of the points collected so far. That is cheap enough for a normal display and would be
 * wrong on e-ink (which is why the Onyx path never does it). When the gesture ends the whole point
 * list is handed to the listener — as a drawn stroke, or, when [eraseMode] is set, as an erase
 * gesture — and the editor repaints the committed result over this preview.
 *
 * Main-thread only: touch events arrive there and nothing here is synchronized.
 *
 * @param currentComposite supplies the page bitmap to show beneath the live preview — the editor's
 *   up-to-date composite of everything already committed.
 */
class AndroidPenBackend(
    private val currentComposite: () -> Bitmap,
) : PenBackend {

    override var eraseMode: Boolean = false

    // Plain touch has no hardware wet ink, so this backend must draw and present every change itself.
    override val rendersWetInkNatively: Boolean = false

    private var surfaceView: SurfaceView? = null
    private var listener: PenBackend.Listener? = null
    private var enabled: Boolean = true
    private var excludeRects: List<Rect> = emptyList()

    private val points = ArrayList<StrokePoint>()
    private var gestureStart = 0L
    private var capturing = false

    // Rebuilt by [setStrokeAppearance] so the live preview approximates the committed stroke.
    private var inkPreviewPaint = strokePaint(Color.BLACK, INK_PREVIEW_WIDTH)
    private val erasePreviewPaint = strokePaint(ERASE_PREVIEW_COLOR, ERASE_PREVIEW_WIDTH)
    private val previewPath = Path()

    @SuppressLint("ClickableViewAccessibility")
    override fun attach(surfaceView: SurfaceView, listener: PenBackend.Listener) {
        this.surfaceView = surfaceView
        this.listener = listener
        surfaceView.setOnTouchListener { _, event -> onTouch(event) }
        // Bring the surface up to the committed page as the first thing the user sees.
        present(currentComposite())
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) resetGesture()
    }

    override fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.toList()
    }

    override fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        val color = StrokeRenderer.grayLevelToColor(grayLevel)
        val width = if (tool == Tool.MARKER) widthBase * StrokeRenderer.MARKER_WIDTH_MULTIPLIER else widthBase
        inkPreviewPaint = strokePaint(color, width).apply {
            if (tool == Tool.MARKER) alpha = StrokeRenderer.MARKER_ALPHA
        }
    }

    override fun present(composite: Bitmap) {
        lockedSurface { canvas ->
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(composite, 0f, 0f, null)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun detach() {
        surfaceView?.setOnTouchListener(null)
        surfaceView = null
        listener = null
        resetGesture()
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (!enabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isExcluded(event.x, event.y)) return false
                capturing = true
                gestureStart = event.eventTime
                points.clear()
                points.add(currentPointOf(event))
                blitPreview()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!capturing) return false
                // A MOVE batches the samples between it and the previous event; replay them in
                // order so fast strokes keep their shape.
                for (i in 0 until event.historySize) points.add(historicalPointOf(event, i))
                points.add(currentPointOf(event))
                blitPreview()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!capturing) return false
                points.add(currentPointOf(event))
                finishGesture()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!capturing) return false
                resetGesture()
                blitComposite()
                true
            }
            else -> false
        }
    }

    private fun finishGesture() {
        val gesture = ArrayList(points)
        resetGesture()
        // The editor repaints the committed page (the new stroke, or the page with strokes erased)
        // right after this callback, which overwrites the preview left on the surface.
        listener?.let { if (eraseMode) it.onEraseGesture(gesture) else it.onStrokeFinished(gesture) }
    }

    private fun blitPreview() {
        lockedSurface { canvas ->
            drawComposite(canvas)
            drawPreview(canvas)
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

    private fun drawPreview(canvas: Canvas) {
        if (points.isEmpty()) return
        val paint = if (eraseMode) erasePreviewPaint else inkPreviewPaint
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        previewPath.reset()
        previewPath.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) previewPath.lineTo(points[i].x, points[i].y)
        canvas.drawPath(previewPath, paint)
    }

    private fun resetGesture() {
        capturing = false
        points.clear()
        previewPath.reset()
    }

    private fun isExcluded(x: Float, y: Float): Boolean =
        excludeRects.any { it.contains(x.toInt(), y.toInt()) }

    private fun currentPointOf(event: MotionEvent) = StrokePoint(
        x = event.x,
        y = event.y,
        pressure = event.pressure,
        timestampDelta = event.eventTime - gestureStart,
    )

    private fun historicalPointOf(event: MotionEvent, index: Int) = StrokePoint(
        x = event.getHistoricalX(index),
        y = event.getHistoricalY(index),
        pressure = event.getHistoricalPressure(index),
        timestampDelta = event.getHistoricalEventTime(index) - gestureStart,
    )

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
