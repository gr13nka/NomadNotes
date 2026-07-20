package com.nomadnotes.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nomadnotes.R
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.pen.onyx.OnyxHiddenApi
import com.nomadnotes.pen.onyx.OnyxRawDrawingController

/**
 * De-risks the project's core bet: lag-free pen ink on Onyx Boox hardware.
 *
 * The screen is a full-bleed [SurfaceView] with a small top bar. Drawing has two backends that
 * both feed the same persistence path:
 *  - On Boox hardware, [OnyxRawDrawingController] captures the pen and the e-ink panel paints the
 *    wet stroke with no lag; finished strokes arrive as points and are persisted here.
 *  - Everywhere else (emulator, ordinary tablet) a plain [MotionEvent] listener draws instead, so
 *    the app still runs and can be exercised without the hardware.
 *
 * Persistence is mandatory in both cases: the wet ink an Onyx panel paints is ephemeral, so every
 * finished stroke is drawn into [strokeBitmap] and the surface is redrawn from that bitmap.
 */
class SpikeActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: LinearLayout

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // The record of finished strokes. Blitting this back to the surface is what keeps ink on
    // screen, since raw drawing's wet ink is not retained by any buffer.
    private var strokeBitmap: Bitmap? = null
    private var strokeCanvas: Canvas? = null

    // Guards the bitmap and the surface blit: Onyx delivers finished strokes on its own thread,
    // while Clear and surface callbacks run on the main thread.
    private val surfaceLock = Any()

    // Non-null only on Onyx hardware. Null means the touch fallback is active.
    private var onyxController: OnyxRawDrawingController? = null
    private var drawingInitialized = false

    // Touch-fallback gesture state (unused on the Onyx path).
    private var fallbackStrokeStart = 0L
    private var fallbackLastPoint: StrokePoint? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On Boox, lift hidden-API enforcement off the Onyx system handwriting classes before any
        // Onyx SDK class loads; without it the pen's raw-drawing region maps to empty and no ink is
        // ever captured. No-op (and skipped) off Boox.
        if (OnyxRawDrawingController.isBooxDevice()) {
            OnyxHiddenApi.exemptOnyxSystemClasses()
        }
        setContentView(buildContentView())
        surfaceView.holder.addCallback(surfaceCallback)
    }

    override fun onResume() {
        super.onResume()
        onyxController?.resume()
    }

    override fun onPause() {
        onyxController?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        onyxController?.close()
        synchronized(surfaceLock) {
            strokeBitmap?.recycle()
            strokeBitmap = null
            strokeCanvas = null
        }
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this)

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(surfaceView)

        val padding = (16 * resources.displayMetrics.density).toInt()
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.LTGRAY)
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
            addView(
                Button(this@SpikeActivity).apply {
                    text = getString(R.string.action_clear)
                    setOnClickListener { clearCanvas() }
                },
            )
        }
        root.addView(topBar)

        return root
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            // The surface can be created before the view is measured; a zero-size limit rect makes
            // TouchHelper filter out all pen input, so defer until the view has a real size.
            if (surfaceView.width <= 0 || surfaceView.height <= 0) {
                Log.i(TAG, "surfaceCreated with zero-size surface; deferring init")
                surfaceView.post { surfaceCreated(holder) }
                return
            }
            // Clean the surface first, then bring raw drawing up last: an app-side surface blit
            // while raw drawing is enabled clobbers TouchHelper, so raw drawing must start on an
            // already-drawn surface (matches Onyx's ScribbleTouchHelperDemoActivity).
            synchronized(surfaceLock) {
                ensureBitmap()
                renderToScreen()
            }
            if (!drawingInitialized) {
                drawingInitialized = true
                initDrawingBackend()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    /**
     * Picks the drawing backend once the surface exists and its rectangles are measurable. The top
     * bar is excluded from raw drawing so its button stays tappable. If anything in the Onyx SDK
     * throws, we degrade to the touch fallback rather than crash.
     */
    private fun initDrawingBackend() {
        val limitRect = Rect(0, 0, surfaceView.width, surfaceView.height)
        val excludeRects = listOf(relativeRect(surfaceView, topBar))

        if (OnyxRawDrawingController.isBooxDevice()) {
            try {
                onyxController = OnyxRawDrawingController(surfaceView, ::commitStroke).apply {
                    openRawDrawing(limitRect, excludeRects)
                    resume()
                }
                Log.i(TAG, "Onyx raw drawing enabled")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Onyx SDK init failed; falling back to touch input", t)
                onyxController = null
            }
        }
        enableTouchFallback()
    }

    private fun enableTouchFallback() {
        surfaceView.setOnTouchListener { _, event -> handleFallbackTouch(event) }
        Log.i(TAG, "Touch fallback enabled")
    }

    private fun handleFallbackTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fallbackStrokeStart = event.eventTime
                fallbackLastPoint = fallbackPointOf(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val last = fallbackLastPoint ?: return true
                val current = fallbackPointOf(event)
                // Commit each segment as it is drawn: with no hardware wet ink, this is what gives
                // live feedback, and redrawing the whole bitmap each time avoids stale-buffer
                // flicker on the double-buffered surface.
                commitStroke(listOf(last, current))
                fallbackLastPoint = current
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fallbackLastPoint = null
            }
            else -> return false
        }
        return true
    }

    private fun fallbackPointOf(event: MotionEvent) = StrokePoint(
        x = event.x,
        y = event.y,
        pressure = event.pressure,
        timestampDelta = event.eventTime - fallbackStrokeStart,
    )

    /** Draws a finished stroke (or a fallback segment) into the bitmap and redraws the surface. */
    private fun commitStroke(points: List<StrokePoint>) {
        synchronized(surfaceLock) {
            val canvas = strokeCanvas ?: return
            when (points.size) {
                0 -> return
                1 -> canvas.drawPoint(points[0].x, points[0].y, paint)
                else -> {
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x, points[i].y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            renderToScreen()
        }
    }

    private fun clearCanvas() {
        synchronized(surfaceLock) {
            strokeCanvas?.drawColor(Color.WHITE)
            renderToScreen()
        }
    }

    /** Blits [strokeBitmap] to the surface. Callers must hold [surfaceLock]. */
    private fun renderToScreen() {
        val bitmap = strokeBitmap ?: return
        val controller = onyxController
        if (controller != null) {
            controller.renderToScreen(bitmap)
        } else {
            blitToSurface(bitmap)
        }
    }

    private fun blitToSurface(bitmap: Bitmap) {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun ensureBitmap() {
        if (strokeBitmap != null) return
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) return
        strokeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            strokeCanvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        }
    }

    /** Rectangle of [child] expressed in [parent]'s local coordinates. */
    private fun relativeRect(parent: View, child: View): Rect {
        val parentLocation = IntArray(2).also { parent.getLocationOnScreen(it) }
        val childLocation = IntArray(2).also { child.getLocationOnScreen(it) }
        return Rect().also { child.getLocalVisibleRect(it) }.apply {
            offset(childLocation[0] - parentLocation[0], childLocation[1] - parentLocation[1])
        }
    }

    companion object {
        private const val TAG = "SpikeActivity"
        private const val STROKE_WIDTH = 3.0f
    }
}
