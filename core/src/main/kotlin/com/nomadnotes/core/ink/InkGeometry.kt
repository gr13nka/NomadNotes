package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a captured stroke becomes ink: a curve fit through its points, and a tapered outline around
 * that curve for tools whose nib responds to pressure and speed.
 *
 * This is pure geometry — no rendering API, no Android — so :app draws the pixels (a `Path` built
 * from [inkCurve]'s segments or [inkOutline]'s ring) and :pen-onyx configures the hardware nib
 * from the same [NibProfile], and neither can drift from the other's idea of how wide or how
 * curved a stroke is.
 */

/**
 * The shared width law: the one definition of how wide a tool's nib is, used by the :app renderer,
 * the :app touch preview, and the :pen-onyx hardware nib config.
 */
data class NibProfile(
    val maxWidth: Float,
    val minWidthFactor: Float,
    val speedWeight: Float,
    val slowPxPerMs: Float,
    val fastPxPerMs: Float,
    val taperFraction: Float,
) {
    companion object {
        /**
         * [tool]'s nib at [widthBase] (its width before pressure or speed drive it). Only PEN
         * tapers and answers to speed; PENCIL and MARKER keep a flat nib and differ solely in
         * width, per the user-facing distinction between a live pen and a flat-tipped tool.
         */
        fun forTool(tool: Tool, widthBase: Float): NibProfile = when (tool) {
            Tool.PEN -> NibProfile(
                maxWidth = widthBase,
                minWidthFactor = 0.35f,
                speedWeight = 0.6f,
                slowPxPerMs = 0.25f,
                fastPxPerMs = 2.5f,
                taperFraction = 0.18f,
            )
            Tool.PENCIL -> NibProfile(
                maxWidth = widthBase,
                minWidthFactor = 1f,
                speedWeight = 0f,
                slowPxPerMs = 0.25f,
                fastPxPerMs = 2.5f,
                taperFraction = 0f,
            )
            Tool.MARKER -> NibProfile(
                maxWidth = widthBase * 2.5f,
                minWidthFactor = 1f,
                speedWeight = 0f,
                slowPxPerMs = 0.25f,
                fastPxPerMs = 2.5f,
                taperFraction = 0f,
            )
        }
    }
}

/** One cubic Bézier piece of a fitted curve, in absolute page-pixel coordinates. */
data class CubicSegment(
    val startX: Float,
    val startY: Float,
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val endX: Float,
    val endY: Float,
)

/** The centreline through [points] as centripetal Catmull–Rom cubics. Empty for < 2 points. */
fun inkCurve(points: List<StrokePoint>): List<CubicSegment> {
    val knots = withoutRepeatedPositions(points)
    if (knots.size < 2) return emptyList()
    return catmullRomSegments(knots)
}

/**
 * Closed ring of interleaved x,y floats: the filled body of a tapered stroke. Fill with WINDING.
 * Empty for < 2 points. [pendingEnd] = pen still down: skip the trailing taper.
 *
 * When every point carries a [StrokePoint.nibFactor] (a hardware backend's own ink engine already
 * measured this stroke's width), that replaces our pressure/speed drive and taper entirely — see
 * [widthsFromFactor] — rather than blending the two, because the engine's own widths already carry
 * whatever start/end behaviour its firmware paints wet. A single missing factor (touch fallback,
 * an older note, a stroke mid-migration) falls back to the drive/taper law for the whole stroke,
 * since a mix of measured and guessed widths would be a visible seam.
 */
fun inkOutline(points: List<StrokePoint>, nib: NibProfile, pendingEnd: Boolean = false): FloatArray {
    val knots = withoutRepeatedPositions(points)
    if (knots.size < 2) return FloatArray(0)
    val samples = flatten(knots, catmullRomSegments(knots))
    val widths = if (knots.all { it.nibFactor != null }) {
        widthsFromFactor(samples, nib)
    } else {
        val drive = driveOf(knots, samples, nib)
        widthsOf(samples, drive, nib, pendingEnd)
    }
    val normals = normalsOf(samples)
    return buildRing(samples, widths, normals)
}

/**
 * Radius of the dot a one-point stroke inks: half the measured nib width at [point] if it has one
 * ([StrokePoint.nibFactor]), else the same pressure-scaled radius [inkOutline] falls back to.
 */
fun dotRadius(point: StrokePoint, nib: NibProfile): Float {
    val factor = point.nibFactor
    return if (factor != null) {
        nib.maxWidth * factor / 2f
    } else {
        nib.maxWidth * (nib.minWidthFactor + (1f - nib.minWidthFactor) * point.pressure.coerceIn(0f, 1f)) / 2f
    }
}

// --- centreline: centripetal Catmull-Rom to Bezier -------------------------------------------

/** Centripetal (alpha = 1/2) knot spacing floors at this so two coincident neighbours — only
 *  possible via the mirrored points at either end of the curve — cannot divide by zero. */
private const val CENTRIPETAL_EPS = 1e-4f

/**
 * One cubic per pair of consecutive [knots] (already deduplicated), via the closed-form
 * centripetal Catmull–Rom-to-Bézier conversion. The knot before the first and after the last are
 * mirrored across the endpoint (`2*P1 - P2`, `2*P2 - P1`) rather than omitted, so the curve still
 * has a tangent to work with at the ends instead of needing a separate formula there.
 */
private fun catmullRomSegments(knots: List<StrokePoint>): List<CubicSegment> {
    val segments = ArrayList<CubicSegment>(knots.size - 1)
    for (i in 0 until knots.size - 1) {
        val p1 = knots[i]
        val p2 = knots[i + 1]
        val p0x: Float
        val p0y: Float
        if (i == 0) {
            p0x = 2f * p1.x - p2.x
            p0y = 2f * p1.y - p2.y
        } else {
            p0x = knots[i - 1].x
            p0y = knots[i - 1].y
        }
        val p3x: Float
        val p3y: Float
        if (i + 2 < knots.size) {
            p3x = knots[i + 2].x
            p3y = knots[i + 2].y
        } else {
            p3x = 2f * p2.x - p1.x
            p3y = 2f * p2.y - p1.y
        }

        val a = max(sqrt(distance(p1.x, p1.y, p0x, p0y)), CENTRIPETAL_EPS)
        val b = max(sqrt(distance(p2.x, p2.y, p1.x, p1.y)), CENTRIPETAL_EPS)
        val c = max(sqrt(distance(p3x, p3y, p2.x, p2.y)), CENTRIPETAL_EPS)

        val m1x = b * ((p1.x - p0x) / a - (p2.x - p0x) / (a + b) + (p2.x - p1.x) / b)
        val m1y = b * ((p1.y - p0y) / a - (p2.y - p0y) / (a + b) + (p2.y - p1.y) / b)
        val m2x = b * ((p2.x - p1.x) / b - (p3x - p1.x) / (b + c) + (p3x - p2.x) / c)
        val m2y = b * ((p2.y - p1.y) / b - (p3y - p1.y) / (b + c) + (p3y - p2.y) / c)

        segments.add(
            CubicSegment(
                startX = p1.x,
                startY = p1.y,
                c1x = p1.x + m1x / 3f,
                c1y = p1.y + m1y / 3f,
                c2x = p2.x - m2x / 3f,
                c2y = p2.y - m2y / 3f,
                endX = p2.x,
                endY = p2.y,
            ),
        )
    }
    return segments
}

// --- outline: flatten, drive, taper, ring -----------------------------------------------------

/** How far apart flattened samples land along a cubic; fine enough that the chord looks curved. */
private const val FLATTEN_STEP_PX = 1.5f
private const val MAX_FLATTEN_STEPS = 64

/** A point flattened off [catmullRomSegments], carrying what the outline needs from its knots. */
private class FlatPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampDelta: Long,
    val arcLength: Float,
    val rawVelocity: Float,
    /** [StrokePoint.nibFactor] interpolated the same way as [pressure]; null unless both knots either
     *  side of this sample have one — see [widthsFromFactor], the only reader that requires it. */
    val nibFactor: Float?,
)

/**
 * Walks every cubic in [segments] at a fixed step, carrying pressure, timestamp and velocity
 * linearly in `t` between the [knots] on either side of the piece, plus the running arc length.
 * The shared knot between two segments is only ever emitted once (the second segment starts at
 * `t` just past 0), so knot positions land exactly once each, including the stroke's own ends.
 */
private fun flatten(knots: List<StrokePoint>, segments: List<CubicSegment>): List<FlatPoint> {
    val velocities = knotVelocities(knots)
    val points = ArrayList<FlatPoint>()
    var arc = 0f
    for (i in segments.indices) {
        val segment = segments[i]
        val p1 = knots[i]
        val p2 = knots[i + 1]
        val chord = distance(segment.startX, segment.startY, segment.endX, segment.endY)
        var steps = ceil(chord / FLATTEN_STEP_PX).toInt().coerceIn(1, MAX_FLATTEN_STEPS)
        // A lone short segment (a 2-knot stroke) would otherwise flatten to just its two ends,
        // leaving no interior sample for the ring to offset into a body; force one subdivision.
        if (segments.size == 1 && steps == 1) steps = 2
        val startStep = if (i == 0) 0 else 1
        for (step in startStep..steps) {
            val t = step.toFloat() / steps
            val x = cubicAt(segment.startX, segment.c1x, segment.c2x, segment.endX, t)
            val y = cubicAt(segment.startY, segment.c1y, segment.c2y, segment.endY, t)
            val pressure = (p1.pressure + (p2.pressure - p1.pressure) * t).coerceIn(0f, 1f)
            val timestampDelta = p1.timestampDelta +
                ((p2.timestampDelta - p1.timestampDelta) * t).roundToLong()
            val velocity = velocities[i] + (velocities[i + 1] - velocities[i]) * t
            val f1 = p1.nibFactor
            val f2 = p2.nibFactor
            val nibFactor = if (f1 != null && f2 != null) f1 + (f2 - f1) * t else null
            val previous = points.lastOrNull()
            if (previous != null) arc += distance(previous.x, previous.y, x, y)
            points.add(FlatPoint(x, y, pressure, timestampDelta, arc, velocity, nibFactor))
        }
    }
    return points
}

private fun cubicAt(p0: Float, c1: Float, c2: Float, p3: Float, t: Float): Float {
    val mt = 1f - t
    return mt * mt * mt * p0 + 3f * mt * mt * t * c1 + 3f * mt * t * t * c2 + t * t * t * p3
}

/** One-sided at the ends, central elsewhere: how fast the pen was moving through each knot. */
private fun knotVelocities(knots: List<StrokePoint>): FloatArray =
    FloatArray(knots.size) { k ->
        val from = if (k == 0) knots[0] else knots[k - 1]
        val to = if (k == knots.size - 1) knots[k] else knots[k + 1]
        val dt = max(to.timestampDelta - from.timestampDelta, 1L)
        distance(from.x, from.y, to.x, to.y) / dt
    }

/** Edge-clamped 5-tap box smooth: the width law works off a locally averaged speed, not the
 *  sample-to-sample jitter a raw one-sided velocity would carry. */
private fun boxSmooth5(values: FloatArray): FloatArray =
    FloatArray(values.size) { i ->
        var sum = 0f
        var count = 0
        for (offset in -2..2) {
            val j = i + offset
            if (j in values.indices) {
                sum += values[j]
                count++
            }
        }
        sum / count
    }

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val span = edge1 - edge0
    if (span == 0f) return if (x >= edge1) 1f else 0f
    val u = ((x - edge0) / span).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/**
 * How much of the nib's full width each sample earns: a pressure/speed blend, with two
 * fallbacks. Degenerate timing (no elapsed time to read a speed from) drops to pressure alone; an
 * input whose pressure never varies drops the pressure side of the blend instead, so a flat touch
 * digitizer still gets a speed-driven line rather than a uniformly half-driven one.
 */
private fun driveOf(knots: List<StrokePoint>, samples: List<FlatPoint>, nib: NibProfile): FloatArray {
    val degenerateTiming = knots.last().timestampDelta <= 0L ||
        (1 until knots.size).all { knots[it].timestampDelta == knots[it - 1].timestampDelta }
    if (degenerateTiming) {
        return FloatArray(samples.size) { samples[it].pressure }
    }
    val minPressure = knots.minOf { it.pressure }
    val maxPressure = knots.maxOf { it.pressure }
    val speedWeight = if (maxPressure - minPressure <= 0.05f) 1f else nib.speedWeight
    val velocity = boxSmooth5(FloatArray(samples.size) { samples[it].rawVelocity })
    return FloatArray(samples.size) { i ->
        val slow = 1f - smoothstep(nib.slowPxPerMs, nib.fastPxPerMs, velocity[i])
        ((1f - speedWeight) * samples[i].pressure + speedWeight * slow).coerceIn(0f, 1f)
    }
}

/** Floor under a stroke's width — including its now-rounded, never-zero-tapered ends — so a
 *  slow, light PEN stroke never thins to an invisible hairline. */
private const val WIDTH_FLOOR = 0.6f

private fun taperLength(nib: NibProfile, totalArc: Float): Float =
    if (nib.taperFraction <= 0f) 0f else min(nib.taperFraction * totalArc, 4f * nib.maxWidth)

/** PEN's taper stops at this fraction of the untapered width instead of reaching zero: a rounded,
 *  soft-brush end reads better than a needle tip, which is what the user rejected. */
private const val END_WIDTH_FACTOR = 0.6f

private fun taperFactor(s: Float, totalArc: Float, taperLength: Float, pendingEnd: Boolean): Float {
    if (taperLength <= 0f) return 1f
    val entry = taperRamp(s / taperLength)
    if (pendingEnd) return entry
    val exit = taperRamp((totalArc - s) / taperLength)
    return min(entry, exit)
}

private fun taperRamp(u: Float): Float =
    END_WIDTH_FACTOR + (1f - END_WIDTH_FACTOR) * sqrt(u.coerceIn(0f, 1f))

private fun widthsOf(
    samples: List<FlatPoint>,
    drive: FloatArray,
    nib: NibProfile,
    pendingEnd: Boolean,
): FloatArray {
    val totalArc = samples.last().arcLength
    val taperLen = taperLength(nib, totalArc)
    return FloatArray(samples.size) { i ->
        val raw = nib.maxWidth *
            (nib.minWidthFactor + (1f - nib.minWidthFactor) * drive[i]) *
            taperFactor(samples[i].arcLength, totalArc, taperLen, pendingEnd)
        max(raw, WIDTH_FLOOR)
    }
}

/**
 * Width from a measured [StrokePoint.nibFactor] alone (see [inkOutline]): no drive, no taper — the
 * backend that measured these factors already asked its own hardware ink engine for them, so its
 * start/end shaping is already baked in. Still floored like [widthsOf], so a factor near zero
 * (a very light or very fast moment) can't thin to an invisible hairline either.
 */
private fun widthsFromFactor(samples: List<FlatPoint>, nib: NibProfile): FloatArray =
    FloatArray(samples.size) { i -> max(nib.maxWidth * (samples[i].nibFactor ?: 0f), WIDTH_FLOOR) }

/**
 * The rightward unit normal at each sample, from a one-sided tangent at the ends and a central
 * one elsewhere. A tangent of (near-)zero length — the path folding back on itself — has no
 * direction to offer, so that sample reuses the previous normal rather than producing NaN.
 */
private fun normalsOf(samples: List<FlatPoint>): List<FloatArray> {
    val normals = ArrayList<FloatArray>(samples.size)
    var previous = floatArrayOf(0f, -1f)
    for (i in samples.indices) {
        val from = if (i == 0) samples[0] else samples[i - 1]
        val to = if (i == samples.size - 1) samples[i] else samples[i + 1]
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        val normal = if (len > CENTRIPETAL_EPS) floatArrayOf(-dy / len, dx / len) else previous
        normals.add(normal)
        previous = normal
    }
    return normals
}

/** Fewest/most facets a round cap gets: enough to read as round at e-ink pixel scale without
 *  spending vertices on smoothness a pixel grid can't show for a hairline-thin nib. */
private const val MIN_CAP_SEGMENTS = 6
private const val MAX_CAP_SEGMENTS = 16

private fun capSegments(radius: Float): Int =
    ceil(radius * 1.5f).toInt().coerceIn(MIN_CAP_SEGMENTS, MAX_CAP_SEGMENTS)

/**
 * Appends a semicircular round cap centred on [point] to [out]: a sweep from the offset
 * ([fromX], [fromY]) away from [point] — where the ring's straight run already ends — through the
 * point ([outwardX], [outwardY]) away — chosen by the caller to point away from the stroke's body —
 * to the opposite offset. Replaces the single mitred vertex a flat-cut tip would leave.
 */
private fun capArc(point: FlatPoint, fromX: Float, fromY: Float, outwardX: Float, outwardY: Float, out: MutableList<Float>) {
    val radius = sqrt(fromX * fromX + fromY * fromY)
    val segments = capSegments(radius)
    for (step in 0..segments) {
        val angle = PI.toFloat() * step / segments
        val cosA = cos(angle)
        val sinA = sin(angle)
        out.add(point.x + cosA * fromX + sinA * outwardX)
        out.add(point.y + cosA * fromY + sinA * outwardY)
    }
}

/**
 * The stroke's fill outline: [samples] offset by half their width along their local normal,
 * walking out along one side and back along the other, so the result is a single closed loop a
 * WINDING fill can flood. Each end is a round cap — a semicircle through the point directly away
 * from the stroke's body — rather than a mitred vertex, because [widthsOf] no longer tapers a PEN
 * end to zero (the user rejected the needle-thin tip a single vertex used to produce).
 */
private fun buildRing(
    samples: List<FlatPoint>,
    widths: FloatArray,
    normals: List<FloatArray>,
): FloatArray {
    val last = samples.size - 1
    val ring = ArrayList<Float>(samples.size * 4 + 2 * MAX_CAP_SEGMENTS)
    fun addOffset(i: Int, sign: Float) {
        val half = widths[i] / 2f * sign
        ring.add(samples[i].x + normals[i][0] * half)
        ring.add(samples[i].y + normals[i][1] * half)
    }
    // The forward tangent at a sample, recovered from its rightward normal (nx, ny): rotating the
    // normal back onto the direction it was built from gives (ny, -nx).
    fun tangentOf(normal: FloatArray) = floatArrayOf(normal[1], -normal[0])

    // Leading cap: from the "-1" side (where the closing edge below arrives) through the point
    // behind the stroke's start, to the "+1" side (where the forward walk below begins).
    val startNormal = normals[0]
    val startTangent = tangentOf(startNormal)
    val startHalf = widths[0] / 2f
    capArc(
        samples[0],
        fromX = -startNormal[0] * startHalf,
        fromY = -startNormal[1] * startHalf,
        outwardX = -startTangent[0] * startHalf,
        outwardY = -startTangent[1] * startHalf,
        out = ring,
    )
    for (i in 1 until last) addOffset(i, 1f)

    // Trailing cap: from the "+1" side (where the forward walk above ends) through the point
    // ahead of the stroke's end, to the "-1" side (where the backward walk below begins).
    // pendingEnd only changes widths[last] upstream (no exit taper yet); the cap shape here is the
    // same either way.
    val endNormal = normals[last]
    val endTangent = tangentOf(endNormal)
    val endHalf = widths[last] / 2f
    capArc(
        samples[last],
        fromX = endNormal[0] * endHalf,
        fromY = endNormal[1] * endHalf,
        outwardX = endTangent[0] * endHalf,
        outwardY = endTangent[1] * endHalf,
        out = ring,
    )
    for (i in last - 1 downTo 1) addOffset(i, -1f)
    return ring.toFloatArray()
}

// --- shared with Smoothing.kt -------------------------------------------------------------------

/**
 * [points] with any run of exactly-repeated positions collapsed to its first member. A resting
 * pen samples the same spot repeatedly, and a zero-length step would divide by zero in both the
 * curve fit above and [denoise]'s corner test, so every consumer of a raw stroke dedupes through
 * here first.
 */
internal fun withoutRepeatedPositions(points: List<StrokePoint>): List<StrokePoint> {
    val kept = ArrayList<StrokePoint>(points.size)
    for (point in points) {
        val previous = kept.lastOrNull()
        if (previous != null && previous.x == point.x && previous.y == point.y) continue
        kept.add(point)
    }
    return kept
}

internal fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}
