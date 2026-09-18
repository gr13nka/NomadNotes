package com.nomadnotes.pen.onyx

import android.util.Log
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.PenPointInk
import com.onyx.android.sdk.pen.PenPointResult
import com.onyx.android.sdk.pen.PenResult
import kotlin.math.sqrt

/**
 * Asks Onyx's own fountain-pen ink engine (`NeoFountainPen`, native code under `NeoPenNative`) how
 * wide it would draw each point of a just-finished PEN stroke.
 *
 * The firmware paints the *wet* ink under the pen with this exact engine (raw drawing's
 * `STROKE_STYLE_FOUNTAIN`, set in [OnyxRawDrawingController.setStrokeAppearance]); once the pen
 * lifts, :app repaints the stroke as *dry* ink using :core's own pressure/speed width law
 * (`inkOutline`), which only approximates what the firmware just drew. Running the finished
 * stroke's points back through the same engine and storing the widths it reports
 * ([com.nomadnotes.core.StrokePoint.nibFactor]) makes wet and dry match by construction instead of
 * by tuning our law to look similar.
 *
 * `NeoFountainPen` is a native-backed, per-stroke resource (a `penHandle` into `libneopen_jni.so`),
 * so a call is created and destroyed for exactly one stroke rather than kept around: sizing is not
 * on any latency-sensitive path (it runs once, after the pen is already up), and holding a native
 * handle open between strokes would be a leak waiting for a missed [NeoPen.destroy].
 */
internal object FountainInkSizer {
    private const val TAG = "FountainInkSizer"

    /**
     * One width per [points], in [points]' own pixel space — or null if the engine could not be
     * reached at all (missing native library, an SDK shape this code no longer matches) or
     * produced no ink whatsoever. The caller falls back to unset [com.nomadnotes.core.StrokePoint.nibFactor]s
     * either way; this never throws.
     *
     * [strokeWidthPx] and [maxTouchPressure] mirror exactly what the hardware's own `TouchHelper`
     * nib was configured with for this stroke (see [OnyxRawDrawingController.setStrokeAppearance]
     * and the device pressure range it reads). Every other [NeoPenConfig] knob (dpi, smoothLevel,
     * pressure/velocity sensitivity, ...) is left at the SDK's own default — this code does not
     * know what the firmware's live nib actually uses for them, so a device pass comparing wet and
     * dry ink is what would tell us to override one.
     */
    fun sizesFor(points: List<TouchPoint>, strokeWidthPx: Float, maxTouchPressure: Float): FloatArray? {
        if (points.isEmpty()) return null
        val config = NeoPenConfig().apply {
            width = strokeWidthPx
            this.maxTouchPressure = maxTouchPressure
        }
        return try {
            val pen = NeoFountainPen.create(config)
            if (pen == null) {
                Log.w(TAG, "NeoFountainPen.create returned no pen; drawing without measured nib widths")
                return null
            }
            try {
                sizesFrom(pen, points)
            } finally {
                pen.destroy()
            }
        } catch (t: Throwable) {
            // Anything from a missing native symbol to an SDK shape this code no longer matches:
            // never worth losing the stroke over, so log once and let the caller fall back.
            Log.w(TAG, "NeoFountainPen sizing failed; drawing without measured nib widths", t)
            null
        }
    }

    /**
     * Replays [points] as a down/move.../up gesture and collects the *real* ink each call reports
     * (never the look-ahead prediction ink the same calls also return — see [PenPointResult]'s
     * sibling in each [Pair] — since that is a guess about where the pen was headed, not a
     * measurement of where it was).
     *
     * `isFinger = false` on every call: raw drawing's drawing channel is stylus-only by construction
     * (finger touches never reach it), so this is always a real pen contact — worth confirming on a
     * device pass if that ever turns out to matter to the engine's output.
     */
    private fun sizesFrom(pen: NeoPen, points: List<TouchPoint>): FloatArray? {
        val ink = ArrayList<PenPointInk>(points.size)
        fun collectReal(result: Pair<PenResult?, PenResult?>) {
            (result.first as? PenPointResult)?.points?.let { ink += it }
        }
        collectReal(pen.onPenDown(points[0], false))
        for (i in 1 until points.size - 1) {
            val point = points[i]
            // Fed one at a time (a length-1 batch) because raw drawing hands us the whole finished
            // stroke at once, not the live down/move/up stream this API was designed to be driven by.
            collectReal(pen.onPenMove(listOf(point), point, false))
        }
        if (points.size > 1) collectReal(pen.onPenUp(points.last(), false))
        if (ink.isEmpty()) return null
        return mapToInputPoints(points, ink)
    }

    /**
     * [ink]'s widths, one per [points], in [points]' order.
     *
     * If the engine echoed exactly one ink sample per input point, that is a direct correspondence.
     * Otherwise (this code cannot tell from the API alone whether `brushSpacing`/`smoothLevel`
     * resample FOUNTAIN's ink the way they do for the stamp-based Neo*Pens) each input point takes
     * the width of whichever ink sample sits closest to it by arc length along the stroke — the
     * only correspondence that still makes sense when the two lists don't line up index-for-index.
     */
    private fun mapToInputPoints(points: List<TouchPoint>, ink: List<PenPointInk>): FloatArray {
        if (ink.size == points.size) return FloatArray(points.size) { ink[it].size }
        val inkArc = arcLengths(ink.size, { ink[it].x }, { ink[it].y })
        val pointArc = arcLengths(points.size, { points[it].x }, { points[it].y })
        return FloatArray(points.size) { i -> ink[nearestByArc(inkArc, pointArc[i])].size }
    }

    /** Running distance from the first of [count] positions given by [x]/[y]; non-decreasing. */
    private fun arcLengths(count: Int, x: (Int) -> Float, y: (Int) -> Float): FloatArray {
        val arc = FloatArray(count)
        for (i in 1 until count) {
            val dx = x(i) - x(i - 1)
            val dy = y(i) - y(i - 1)
            arc[i] = arc[i - 1] + sqrt(dx * dx + dy * dy)
        }
        return arc
    }

    /** Index into the non-decreasing [haystack] whose value is closest to [target]. */
    private fun nearestByArc(haystack: FloatArray, target: Float): Int {
        var lo = 0
        var hi = haystack.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (haystack[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(haystack[lo - 1] - target) <= kotlin.math.abs(haystack[lo] - target)) return lo - 1
        return lo
    }
}
