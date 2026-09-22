package com.nomadnotes.pen.onyx

import android.util.Log
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPenUtils
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
 * The engine is driven through the SDK's own finished-stroke entry point,
 * [NeoPenUtils.computeStrokePoints], rather than a hand-rolled down/move/up replay: that function
 * is how Onyx itself sizes a stored stroke, so it fixes the calling convention (one move batch, no
 * prediction point, pressure pre-normalized) that the native engine is actually exercised with. It
 * creates and destroys the native pen for exactly one stroke, which suits sizing — it runs once,
 * after the pen is already up, and is not on any latency-sensitive path.
 */
internal object FountainInkSizer {
    private const val TAG = "FountainInkSizer"

    /**
     * One width per [points], in [points]' own pixel space — or null if the stroke is too short to
     * size (fewer than two points), the engine could not be reached at all (missing native library,
     * an SDK shape this code no longer matches), or it produced no ink whatsoever. The caller falls
     * back to unset [com.nomadnotes.core.StrokePoint.nibFactor]s either way; this never throws.
     * [points] themselves are never modified.
     *
     * [strokeWidthPx] and [maxTouchPressure] mirror exactly what the hardware's own `TouchHelper`
     * nib was configured with for this stroke (see [OnyxRawDrawingController.setStrokeAppearance]
     * and the device pressure range it reads). Every other [NeoPenConfig] knob (dpi, smoothLevel,
     * pressure/velocity sensitivity, ...) is left at the SDK's own default — this code does not
     * know what the firmware's live nib actually uses for them, so a device pass comparing wet and
     * dry ink is what would tell us to override one.
     */
    fun sizesFor(points: List<TouchPoint>, strokeWidthPx: Float, maxTouchPressure: Float): FloatArray? {
        if (points.size < 2) return null
        // The engine must only ever see 0..1 pressure. Handed raw device pressure (0..4095 on the
        // Boox Go 10.3) at pen-down or pen-up, it emits ink samples by the million from a single
        // call — gigabytes of PenPointInk allocated on the main thread, and the app is OOM-killed
        // on its first firm stroke — even with NeoPenConfig.maxTouchPressure set to that range.
        // computeStrokePoints divides by the maxPressure it is given (1 here, since these copies
        // are already normalized) and writes the result back into the points, hence the copies.
        val normalized = points.map { point ->
            TouchPoint(point).apply { pressure = normalizedPressure(point.pressure, maxTouchPressure) }
        }
        return try {
            val ink = NeoPenUtils.computeStrokePoints(
                NeoPenConfig.NEOPEN_PEN_TYPE_FOUNTAIN,
                normalized,
                strokeWidthPx,
                1f,
            )
            if (ink.isNullOrEmpty()) null else mapToInputPoints(points, ink)
        } catch (t: Throwable) {
            // Anything from a missing native symbol to an SDK shape this code no longer matches:
            // never worth losing the stroke over, so log once and let the caller fall back.
            Log.w(TAG, "NeoFountainPen sizing failed; drawing without measured nib widths", t)
            null
        }
    }

    /**
     * [raw] device pressure as the 0..1 fraction of [maxTouchPressure] the ink engine requires,
     * clamped — a reading past the reported maximum, or a NaN, must not reach the engine either.
     * A nonpositive [maxTouchPressure] leaves no range to normalize against, so it reads as 0.
     */
    internal fun normalizedPressure(raw: Float, maxTouchPressure: Float): Float {
        if (!(maxTouchPressure > 0f)) return 0f
        val fraction = raw / maxTouchPressure
        return if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    }

    /**
     * [ink]'s widths (each sample's `size`), one per [points], in [points]' order.
     *
     * If the engine echoed exactly one ink sample per input point, that is a direct correspondence.
     * Otherwise (this code cannot tell from the API alone whether `brushSpacing`/`smoothLevel`
     * resample FOUNTAIN's ink the way they do for the stamp-based Neo*Pens) each input point takes
     * the width of whichever ink sample sits closest to it by arc length along the stroke — the
     * only correspondence that still makes sense when the two lists don't line up index-for-index.
     */
    private fun mapToInputPoints(points: List<TouchPoint>, ink: List<TouchPoint>): FloatArray {
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
