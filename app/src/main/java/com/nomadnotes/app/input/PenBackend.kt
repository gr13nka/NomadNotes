package com.nomadnotes.app.input

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool

/**
 * The seam between the editor and whatever hardware captures pen input.
 *
 * A backend owns one [SurfaceView]'s pen input from [attach] to [detach] and reports finished
 * gestures back through [Listener] as device-neutral [StrokePoint]s in page coordinates — so the
 * editor above it never learns which backend (a plain touch listener, or the Onyx raw-drawing pen)
 * produced a stroke. What the panel does *during* a gesture — whether the hardware paints wet ink
 * itself or the backend must draw a preview — is likewise the backend's business, hidden here.
 *
 * The backend also owns writing to the surface: the editor hands it the appearance in-progress ink
 * should take ([setStrokeAppearance]) and the committed page to display ([present]), and the backend
 * applies whatever surface protocol its hardware needs. Whether the hardware paints its own wet ink
 * ([rendersWetInkNatively]) governs whether the editor even asks for a present after a stroke.
 */
interface PenBackend {

    /** Begins capturing pen input on [surfaceView], reporting gestures to [listener]. */
    fun attach(surfaceView: SurfaceView, listener: Listener)

    /** Turns capture on or off without tearing down the attachment. */
    fun setEnabled(enabled: Boolean)

    /**
     * Regions of the surface where pen input is ignored (e.g. an on-surface toolbar), in the
     * surface's own coordinates. Replaces any previously set rectangles.
     */
    fun setExcludeRects(rects: List<Rect>)

    /** When set, a finished gesture is reported as an erase gesture rather than a drawn stroke. */
    var eraseMode: Boolean

    /**
     * Sets how in-progress ink should look, so the backend's preview (a drawn one, or the panel's
     * own hardware wet ink) matches the committed stroke the editor will render. [widthBase] is the
     * nib width in surface pixels before pressure; [grayLevel] is ink darkness (0 = white, 255 =
     * black). Applies to subsequent strokes.
     */
    fun setStrokeAppearance(tool: Tool, widthBase: Float, grayLevel: Int)

    /**
     * True when the hardware paints the wet stroke itself while the pen is down (the Onyx e-ink
     * panel), so a just-finished stroke is already on screen and the editor must not [present] it —
     * only persist it. False when the backend has no hardware ink and every visible change needs a
     * [present].
     */
    val rendersWetInkNatively: Boolean

    /**
     * Blits the committed page [composite] to the surface for a structural repaint (clear, undo,
     * erase, layer/template/page change, first paint). A raw-drawing backend brackets this so it
     * cannot corrupt in-progress hardware ink; a plain backend blits directly. A lone finished
     * stroke is not shown this way on a [rendersWetInkNatively] backend.
     */
    fun present(composite: Bitmap)

    /** Stops capturing and releases the surface. */
    fun detach()

    /**
     * Receives finished gestures. Called on the UI thread. Every [StrokePoint] in a reported
     * gesture arrives with its pressure normalized to 0..1 (0 = lightest, 1 = max hardware
     * pressure); backends normalize device pressure values before constructing the points.
     */
    interface Listener {
        /** A drawing gesture finished: its full path, to be turned into a stroke. */
        fun onStrokeFinished(points: List<StrokePoint>)

        /** An erasing gesture finished: its path, to be hit-tested against existing strokes. */
        fun onEraseGesture(points: List<StrokePoint>)
    }
}
