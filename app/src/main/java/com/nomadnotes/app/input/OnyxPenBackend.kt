package com.nomadnotes.app.input

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
 * A [PenBackend] for Onyx Boox e-ink hardware, where the panel paints the wet stroke itself so ink
 * is lag-free ([rendersWetInkNatively] is true).
 *
 * All Onyx SDK contact is delegated to [OnyxRawDrawingController] in :pen-onyx; this adapter only
 * maps the editor's device-neutral vocabulary (a [Tool], rectangles, listener callbacks) onto it,
 * and is also the app's single gate onto Onyx support ([isSupported]/[prepareProcess]) so the editor
 * never imports :pen-onyx directly. Because the controller delivers finished gestures on Onyx's own
 * input thread, this adapter marshals them to the main thread before calling the [PenBackend.Listener],
 * honouring that interface's UI-thread contract.
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
    private var listener: PenBackend.Listener? = null

    // Desired capture state and configuration, held so they can be applied to the controller when it
    // is created in [attach] (some may be set by the editor before the surface is ready).
    private var enabled = true
    private var excludeRects: List<Rect> = emptyList()
    private var tool = Tool.PEN
    private var widthBase = 0f
    private var grayLevel = MAX_GRAY_LEVEL

    override var eraseMode: Boolean = false
        set(value) {
            field = value
            controller?.setEraseMode(value)
        }

    override fun attach(surfaceView: SurfaceView, listener: PenBackend.Listener) {
        this.listener = listener
        val controller = OnyxRawDrawingController(
            surfaceView = surfaceView,
            // Onyx delivers these on its input thread; the listener contract is UI-thread, so post.
            onStrokeFinished = { points -> mainHandler.post { this.listener?.onStrokeFinished(points) } },
            onEraseGesture = { points -> mainHandler.post { this.listener?.onEraseGesture(points) } },
        )
        this.controller = controller
        controller.setStrokeAppearance(tool, widthBase, grayLevel)
        controller.setEraseMode(eraseMode)
        // Clean the surface with the committed page FIRST, then bring raw drawing up LAST.
        controller.renderToScreen(currentComposite())
        controller.openRawDrawing(Rect(0, 0, surfaceView.width, surfaceView.height), excludeRects)
        if (enabled) controller.resume()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        val controller = controller ?: return
        if (enabled) controller.resume() else controller.pause()
    }

    override fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.toList()
        controller?.setExcludeRects(excludeRects)
    }

    override fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int) {
        this.tool = tool
        this.widthBase = widthBase
        this.grayLevel = grayLevel
        controller?.setStrokeAppearance(tool, widthBase, grayLevel)
    }

    override fun present(composite: Bitmap) {
        controller?.renderToScreen(composite)
    }

    override fun detach() {
        controller?.close()
        controller = null
        listener = null
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
