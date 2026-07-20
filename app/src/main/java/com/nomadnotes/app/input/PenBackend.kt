package com.nomadnotes.app.input

import android.graphics.Rect
import android.view.SurfaceView
import com.nomadnotes.core.StrokePoint

/**
 * The seam between the editor and whatever hardware captures pen input.
 *
 * A backend owns one [SurfaceView]'s pen input from [attach] to [detach] and reports finished
 * gestures back through [Listener] as device-neutral [StrokePoint]s in page coordinates — so the
 * editor above it never learns which backend (a plain touch listener, or the Onyx raw-drawing pen)
 * produced a stroke. What the panel does *during* a gesture — whether the hardware paints wet ink
 * itself or the backend must draw a preview — is likewise the backend's business, hidden here.
 *
 * The interface is intentionally minimal for now: the tool, nib width, and ink darkness live in the
 * editor, not the backend, so they are absent. The Onyx backend will need some of them in a later
 * step; the interface grows then rather than in advance.
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

    /** Stops capturing and releases the surface. */
    fun detach()

    /** Receives finished gestures. Called on the UI thread. */
    interface Listener {
        /** A drawing gesture finished: its full path, to be turned into a stroke. */
        fun onStrokeFinished(points: List<StrokePoint>)

        /** An erasing gesture finished: its path, to be hit-tested against existing strokes. */
        fun onEraseGesture(points: List<StrokePoint>)
    }
}
