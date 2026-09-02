package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * How aggressively a finished stroke is cleaned up before it becomes ink.
 *
 * This is the user-facing vocabulary of the smoothing setting, not a tuning knob: the pixel
 * thresholds each level implies are an implementation detail of [smoothStroke], so they can be
 * retuned against real firmware without changing anything a caller stores or displays.
 */
enum class SmoothingLevel { OFF, LIGHT, STRONG }

/**
 * Returns [points] with digitizer jitter removed and the remaining path refitted as a smooth
 * curve — the "auto-smoothing" applied to a stroke as it is committed.
 *
 * Two stages. First the path is simplified (Ramer–Douglas–Peucker), which drops the sample-to-sample
 * tremor and the redundant points a slow pen leaves behind while keeping the corners that carry the
 * letter's shape. The survivors are then treated as the knots of a centripetal Catmull–Rom spline and
 * resampled at a fixed spacing, which puts back a dense, evenly spaced path that follows a curve
 * rather than a chain of straight hops.
 *
 * The result is still a plain point list, so nothing downstream has to know smoothing happened: the
 * renderer draws the refitted curve with the same per-segment lines it already uses.
 *
 * Contract relied on by callers:
 *  - [SmoothingLevel.OFF] returns [points] itself, unchanged.
 *  - A gesture too short to have a shape (fewer than [MIN_SMOOTHABLE_POINTS] distinct positions, i.e.
 *    a tap or a dot) is returned unchanged, so tap-sized marks are never reshaped away.
 *  - The first and last points are always preserved exactly, including their pressure and
 *    `timestampDelta`. The stroke therefore keeps its total duration, which
 *    `TapClassifier` reads from the last point.
 *  - Interpolated points carry pressure clamped to 0..1 and a `timestampDelta` between those of the
 *    knots they lie between, so timing stays ordered.
 *
 * [points] is expected in capture order, with non-decreasing `timestampDelta` (what every backend
 * produces); the ordering of the output is only as good as the input's.
 */
fun smoothStroke(points: List<StrokePoint>, level: SmoothingLevel): List<StrokePoint> {
    val tuning = tuningFor(level) ?: return points
    if (points.size < MIN_SMOOTHABLE_POINTS) return points
    // Repeated positions are common when the pen rests, and would put zero-length segments into the
    // spline's parameterization, which divides by their length.
    val distinct = withoutRepeatedPositions(points)
    if (distinct.size < MIN_SMOOTHABLE_POINTS) return points
    val knots = simplify(distinct, tuning.epsilonPx)
    if (knots.size < 2) return points
    return resample(knots, tuning.spacingPx, first = points.first(), last = points.last())
}

/** Fewest distinct positions a gesture needs before it is treated as a shape worth smoothing. */
const val MIN_SMOOTHABLE_POINTS = 4

/**
 * The subset of [points] that keeps the path's shape to within [epsilonPx] — the Ramer–Douglas–Peucker
 * line simplification, iterative so a long stroke cannot overflow the stack.
 *
 * Deviation is measured to the *segment* joining the range's endpoints rather than to the infinite
 * line through them, so a stroke that loops back on itself (endpoints in the same place) still
 * simplifies sensibly instead of dividing by a degenerate direction.
 *
 * The first and last points are always kept, and the survivors stay in their original order, so this
 * only ever removes points — it never moves one.
 *
 * Adapted from the OpenInkBridge project's `simplify_stroke` (Apache License 2.0,
 * Copyright OpenInkBridge Contributors).
 */
internal fun simplify(points: List<StrokePoint>, epsilonPx: Float): List<StrokePoint> {
    if (points.size < 3 || epsilonPx <= 0f) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    // Ranges still to examine, as (first, last) index pairs; a work list rather than recursion.
    val pending = ArrayDeque<Int>()
    pending.addLast(0)
    pending.addLast(points.size - 1)
    while (pending.isNotEmpty()) {
        val last = pending.removeLast()
        val first = pending.removeLast()
        if (last <= first + 1) continue
        var farthest = first
        var farthestDistance = -1f
        for (i in first + 1 until last) {
            val d = distanceToSegment(points[i], points[first], points[last])
            if (d > farthestDistance) {
                farthestDistance = d
                farthest = i
            }
        }
        if (farthestDistance > epsilonPx) {
            keep[farthest] = true
            pending.addLast(first)
            pending.addLast(farthest)
            pending.addLast(farthest)
            pending.addLast(last)
        }
    }
    return points.filterIndexed { index, _ -> keep[index] }
}

/** Per-level tuning, kept private so [SmoothingLevel] stays a plain vocabulary type. */
private class Tuning(
    /** How far the simplified path may stray from the captured one, in page pixels. */
    val epsilonPx: Float,
    /** Spacing between resampled points along the refitted curve, in page pixels. */
    val spacingPx: Float,
)

// Device-tuned against the Boox Go 10.3 panel; expect these to move after a device pass.
private fun tuningFor(level: SmoothingLevel): Tuning? = when (level) {
    SmoothingLevel.OFF -> null
    SmoothingLevel.LIGHT -> Tuning(epsilonPx = 1.2f, spacingPx = 2.5f)
    SmoothingLevel.STRONG -> Tuning(epsilonPx = 3.0f, spacingPx = 2.5f)
}

/** Drops points that sit exactly where the previous one did, keeping the rest in order. */
private fun withoutRepeatedPositions(points: List<StrokePoint>): List<StrokePoint> {
    val kept = ArrayList<StrokePoint>(points.size)
    for (point in points) {
        val previous = kept.lastOrNull()
        if (previous != null && previous.x == point.x && previous.y == point.y) continue
        kept.add(point)
    }
    return kept
}

/**
 * Walks the centripetal Catmull–Rom spline through [knots], emitting a point roughly every
 * [spacingPx] along it. [first] and [last] are the captured stroke's own endpoints, emitted verbatim
 * so smoothing cannot nudge where the stroke starts or ends.
 */
private fun resample(
    knots: List<StrokePoint>,
    spacingPx: Float,
    first: StrokePoint,
    last: StrokePoint,
): List<StrokePoint> {
    val smoothed = ArrayList<StrokePoint>(knots.size * 2)
    smoothed.add(first)
    for (i in 0 until knots.size - 1) {
        val start = knots[i]
        val end = knots[i + 1]
        // The curve needs a neighbour on each side. At the ends there is none, so reflect the segment
        // outwards: a mirrored neighbour keeps the knot spacing non-zero (which the parameterization
        // divides by) and leaves the curve heading straight out of the endpoint.
        val beforeX: Float
        val beforeY: Float
        if (i == 0) {
            beforeX = 2f * start.x - end.x
            beforeY = 2f * start.y - end.y
        } else {
            beforeX = knots[i - 1].x
            beforeY = knots[i - 1].y
        }
        val afterX: Float
        val afterY: Float
        if (i + 2 < knots.size) {
            afterX = knots[i + 2].x
            afterY = knots[i + 2].y
        } else {
            afterX = 2f * end.x - start.x
            afterY = 2f * end.y - start.y
        }
        val steps = max(1, ceil(distance(start.x, start.y, end.x, end.y) / spacingPx).toInt())
        // Each step lands on the segment's far knot at u == 1, so the shared knot between two
        // segments is emitted once. The final knot is skipped and [last] appended instead.
        val upper = if (i == knots.size - 2) steps - 1 else steps
        for (step in 1..upper) {
            smoothed.add(sample(beforeX, beforeY, start, end, afterX, afterY, step.toFloat() / steps))
        }
    }
    smoothed.add(last)
    return smoothed
}

/**
 * The spline point a fraction [u] of the way from [start] to [end], given the neighbouring knots
 * either side. Position follows the Barry–Goldman evaluation of a non-uniform Catmull–Rom spline;
 * pressure and timing are interpolated straight between [start] and [end], which is what they mean
 * along that piece of the path.
 *
 * Knots are spaced by the square root of the distance between them (centripetal, alpha = 0.5) rather
 * than uniformly. Pen samples are unevenly spaced, and uniform spacing overshoots and can form cusps
 * on exactly that input; the centripetal spacing provably cannot.
 */
private fun sample(
    beforeX: Float,
    beforeY: Float,
    start: StrokePoint,
    end: StrokePoint,
    afterX: Float,
    afterY: Float,
    u: Float,
): StrokePoint {
    val t0 = 0f
    val t1 = t0 + knotSpacing(beforeX, beforeY, start.x, start.y)
    val t2 = t1 + knotSpacing(start.x, start.y, end.x, end.y)
    val t3 = t2 + knotSpacing(end.x, end.y, afterX, afterY)
    val t = t1 + u * (t2 - t1)

    val a1x = ((t1 - t) * beforeX + (t - t0) * start.x) / (t1 - t0)
    val a1y = ((t1 - t) * beforeY + (t - t0) * start.y) / (t1 - t0)
    val a2x = ((t2 - t) * start.x + (t - t1) * end.x) / (t2 - t1)
    val a2y = ((t2 - t) * start.y + (t - t1) * end.y) / (t2 - t1)
    val a3x = ((t3 - t) * end.x + (t - t2) * afterX) / (t3 - t2)
    val a3y = ((t3 - t) * end.y + (t - t2) * afterY) / (t3 - t2)

    val b1x = ((t2 - t) * a1x + (t - t0) * a2x) / (t2 - t0)
    val b1y = ((t2 - t) * a1y + (t - t0) * a2y) / (t2 - t0)
    val b2x = ((t3 - t) * a2x + (t - t1) * a3x) / (t3 - t1)
    val b2y = ((t3 - t) * a2y + (t - t1) * a3y) / (t3 - t1)

    return StrokePoint(
        x = ((t2 - t) * b1x + (t - t1) * b2x) / (t2 - t1),
        y = ((t2 - t) * b1y + (t - t1) * b2y) / (t2 - t1),
        pressure = (start.pressure + u * (end.pressure - start.pressure)).coerceIn(0f, 1f),
        timestampDelta = start.timestampDelta +
            ((end.timestampDelta - start.timestampDelta) * u).roundToLong(),
    )
}

/**
 * Centripetal knot spacing: the square root of the distance between two knots, floored at a tiny
 * positive value so a pair that rounds to the same position cannot divide by zero.
 */
private fun knotSpacing(x1: Float, y1: Float, x2: Float, y2: Float): Float =
    max(sqrt(distance(x1, y1, x2, y2)), MIN_KNOT_SPACING)

private const val MIN_KNOT_SPACING = 1e-4f

/** Shortest distance from [point] to the segment [start]-[end], or to the shared point if degenerate. */
private fun distanceToSegment(point: StrokePoint, start: StrokePoint, end: StrokePoint): Float {
    val abx = end.x - start.x
    val aby = end.y - start.y
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared == 0f) return distance(point.x, point.y, start.x, start.y)
    val t = (((point.x - start.x) * abx) + ((point.y - start.y) * aby)) / lengthSquared
    val clamped = t.coerceIn(0f, 1f)
    return distance(point.x, point.y, start.x + clamped * abx, start.y + clamped * aby)
}

private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}
