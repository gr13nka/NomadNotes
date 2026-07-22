package com.nomadnotes.app.input

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool

/**
 * How a pen gesture is captured, which fixes both whether in-progress ink is shown and how a
 * finished gesture is reported:
 *  - [INK] draws wet ink (hardware or a preview) and reports a stroke ([Listener.onStrokeFinished]).
 *  - [ERASE] and [LASSO] both show no wet ink — the gesture is a selection, not a mark — and report
 *    it for erasing ([Listener.onEraseGesture]) or lasso selection ([Listener.onLassoGesture]).
 */
enum class CaptureMode { INK, ERASE, LASSO }

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

    /**
     * How the next finished gesture is captured and reported (see [CaptureMode]). In [CaptureMode.INK]
     * the backend shows wet ink and reports a stroke; in [CaptureMode.ERASE]/[CaptureMode.LASSO] it
     * shows none and reports the gesture for erasing or lasso selection.
     */
    var captureMode: CaptureMode

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
     *
     * [cleanRefresh] asks for a ghost-free repaint, for a change that *removes* ink (erasing) where
     * the fast additive e-ink update would leave the cleared strokes faintly ghosted until a later
     * full refresh; it costs a slightly heavier update. Backends without e-ink ghosting ignore it.
     */
    fun present(composite: Bitmap, cleanRefresh: Boolean = false)

    /**
     * Blits [composite] *during* an in-progress [CaptureMode.LASSO] gesture, for the editor's live
     * move/draw preview. Unlike [present], a raw-drawing backend must NOT bracket this with a
     * raw-drawing disable/enable — that would abort the in-flight capture. Doing so is safe only
     * because wet ink is off throughout a lasso gesture, so there is no hardware stroke render for
     * the blit to corrupt. Call only while a lasso gesture is being captured; a backend with no
     * hardware ink blits exactly as [present] does.
     */
    fun presentDuringCapture(composite: Bitmap)

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

        /**
         * A lasso gesture finished: its full path. The editor decides what it means — a new
         * selection polygon, or (when it started inside the current selection's box) a move. An
         * empty list means the gesture was abandoned (e.g. a touch cancel): nothing to commit, drop
         * any live preview and restore the page.
         */
        fun onLassoGesture(points: List<StrokePoint>)

        /**
         * A live sample of an in-progress LASSO-mode gesture, delivered repeatedly between pen-down
         * and the finishing [onLassoGesture]. Emitted only while [captureMode] is [CaptureMode.LASSO],
         * so the editor can preview a move-drag (or, on a backend without hardware wet ink, the lasso
         * outline) as the pen moves. [point] is the latest sample, in page coordinates. A backend that
         * cannot report live samples simply never calls this — the finishing [onLassoGesture] still
         * carries the whole path.
         */
        fun onLassoMove(point: StrokePoint)
    }
}
