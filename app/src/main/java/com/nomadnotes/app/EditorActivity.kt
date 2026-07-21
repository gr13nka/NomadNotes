package com.nomadnotes.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nomadnotes.R
import com.nomadnotes.app.input.AndroidPenBackend
import com.nomadnotes.app.input.PenBackend
import com.nomadnotes.app.render.PageRenderer
import com.nomadnotes.core.Layer
import com.nomadnotes.core.Page
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import com.nomadnotes.core.edit.PageEditSession
import com.nomadnotes.core.geometry.Vec2
import com.nomadnotes.core.geometry.eraserHit
import com.nomadnotes.pen.onyx.OnyxRawDrawingController

/**
 * A minimal end-to-end harness for the page-editing pipeline, exercising it on a device before the
 * real editor UI exists.
 *
 * It wires one in-memory [Page] through the three pieces this step introduces: a [PageEditSession]
 * (the undoable model), a [PageRenderer] (page → the bitmap shown on screen), and a [PenBackend]
 * (hardware input → strokes). The views are built in code — a full-bleed [SurfaceView] under a row
 * of tool buttons — and are intentionally throwaway; the real UI is a later step.
 *
 * Input always runs through [AndroidPenBackend], even on Boox hardware. There it means laggy
 * touch-based ink rather than the raw-drawing pen: wiring the Onyx backend into the editor is a
 * separate step, and the device spike remains the proof that raw drawing works.
 */
class EditorActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var topBar: LinearLayout

    private val session = PageEditSession(Page.create())
    private val renderer = PageRenderer()
    private val backend = AndroidPenBackend { renderer.composite() }

    private var currentTool: Tool = Tool.PEN
    private var backendAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Input backend: AndroidPenBackend (Boox=${OnyxRawDrawingController.isBooxDevice()})")
        setContentView(buildContentView())
        surfaceView.holder.addCallback(surfaceCallback)
    }

    override fun onDestroy() {
        backend.detach()
        renderer.release()
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
            addToolButton(R.string.action_pen) { selectTool(Tool.PEN) }
            addToolButton(R.string.action_marker) { selectTool(Tool.MARKER) }
            addToolButton(R.string.action_eraser) { backend.eraseMode = true }
            addToolButton(R.string.action_undo) { undo() }
            addToolButton(R.string.action_redo) { redo() }
            addToolButton(R.string.action_clear) { clearPage() }
        }
        root.addView(topBar)

        return root
    }

    private fun LinearLayout.addToolButton(labelRes: Int, onClick: () -> Unit) {
        addView(
            Button(this@EditorActivity).apply {
                setText(labelRes)
                setOnClickListener { onClick() }
            },
        )
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            renderer.resize(width, height)
            renderer.renderFull(session.page)
            presentComposite()
            if (!backendAttached) {
                backend.attach(surfaceView, penListener)
                backendAttached = true
            }
            // Keep the toolbar tappable: pen input landing on it is ignored.
            backend.setExcludeRects(listOf(relativeRect(surfaceView, topBar)))
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private val penListener = object : PenBackend.Listener {
        override fun onStrokeFinished(points: List<StrokePoint>) {
            if (points.isEmpty()) return
            val layerId = session.page.mainLayerId
            val stroke = Stroke(
                id = StrokeId.random(),
                tool = currentTool,
                widthBase = STROKE_WIDTH_BASE,
                grayLevel = STROKE_GRAY_LEVEL,
                points = points,
            )
            session.addStroke(layerId, stroke)
            renderer.appendStroke(layerId, stroke)
            presentComposite()
        }

        override fun onEraseGesture(points: List<StrokePoint>) = eraseAlong(points)
    }

    private fun selectTool(tool: Tool) {
        currentTool = tool
        backend.eraseMode = false
    }

    private fun undo() {
        if (session.undo()) rerenderPage()
    }

    private fun redo() {
        if (session.redo()) rerenderPage()
    }

    private fun clearPage() {
        val layerId = session.page.mainLayerId
        val ids = activeLayer().strokes.map { it.id }
        if (ids.isEmpty()) return
        session.eraseStrokes(layerId, ids)
        rerenderPage()
    }

    /**
     * Erases every stroke the eraser gesture passed over: each gesture point is a small eraser
     * disc, and any active-layer stroke it touches is removed as one undoable step.
     */
    private fun eraseAlong(points: List<StrokePoint>) {
        val strokes = activeLayer().strokes
        val hits = LinkedHashSet<StrokeId>()
        for (point in points) {
            val center = Vec2(point.x, point.y)
            for (stroke in strokes) {
                if (stroke.id !in hits && eraserHit(stroke, center, ERASER_RADIUS)) hits.add(stroke.id)
            }
        }
        if (hits.isNotEmpty()) session.eraseStrokes(session.page.mainLayerId, hits)
        rerenderPage()
    }

    private fun activeLayer(): Layer {
        val layerId = session.page.mainLayerId
        return session.page.layers.first { it.id == layerId }
    }

    private fun rerenderPage() {
        renderer.renderFull(session.page)
        presentComposite()
    }

    private fun presentComposite() {
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(renderer.composite(), 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
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

    private companion object {
        const val TAG = "EditorActivity"

        /** Nib width new strokes carry before the renderer applies pressure; tuned later. */
        const val STROKE_WIDTH_BASE = 4f

        /** New strokes are full black ([Stroke.grayLevel] 255); the palette is a later step. */
        const val STROKE_GRAY_LEVEL = 255

        /** Eraser disc radius, in page pixels, hit-tested at each gesture point. */
        const val ERASER_RADIUS = 20f
    }
}
