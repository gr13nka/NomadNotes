package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * How aggressively a finished stroke is cleaned up before it becomes ink.
 *
 * This is the user-facing vocabulary of the smoothing setting, not a tuning knob: the pixel
 * thresholds each level implies are an implementation detail of [smoothStroke], so they can be
 * retuned against real firmware without changing anything a caller stores or displays.
 */
enum class SmoothingLevel {
    OFF,

    /**
     * Derives its tolerance from the stroke itself (see [autoTuning]) rather than spending a
     * fixed pixel budget, so one setting suits both a word-long cursive stroke and a single
     * printed letter.
     *
     * AUTO describes a *finished* stroke: its tolerance depends on the whole stroke's bounding
     * box and median sample spacing, both of which change as more points arrive. Smoothing a
     * prefix of a stroke with AUTO therefore does not produce a prefix of the final result — a
     * caller that smooths incrementally (a live preview, say) cannot assume otherwise.
     */
    AUTO,
    LIGHT,
    STRONG,
}

/**
 * Returns [points] with digitizer jitter removed and the remaining path refitted as a smooth
 * curve — the "auto-smoothing" applied to a stroke as it is committed.
 *
 * Two stages. First the path is simplified (Ramer–Douglas–Peucker), which drops the sample-to-sample
 * tremor and the redundant points a slow pen leaves behind while keeping the corners that carry the
 * letter's shape. The survivors are then treated as the knots of a centripetal Catmull–Rom spline and
 * resampled at a fixed spacing, which puts back a dense, evenly spaced path that follows a curve
 * rather than a chain of straight hops. [SmoothingLevel.AUTO] additionally picks the simplification
 * tolerance from the stroke itself instead of a fixed constant; see [autoTuning].
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
    if (level == SmoothingLevel.OFF) return points
    if (points.size < MIN_SMOOTHABLE_POINTS) return points
    // Repeated positions are common when the pen rests, and would put zero-length segments into the
    // spline's parameterization, which divides by their length.
    val distinct = withoutRepeatedPositions(points)
    if (distinct.size < MIN_SMOOTHABLE_POINTS) return points
    // Tuning is chosen from the de-duplicated stroke, not the raw capture: AUTO's speed term reads
    // median sample spacing, and a resting pen's repeated samples would otherwise report that as
    // zero — read as an infinitely slow pen — and spend the full tremor budget exactly when the pen
    // isn't trembling, it's stopped.
    val tuning = tuningFor(level, distinct)
    return smoothDistinct(points, distinct, tuning)
}

/**
 * [smoothStroke] with the tuning supplied rather than derived — the calibration report sweeps
 * candidate constants through the very code the app runs.
 */
internal fun smoothStrokeTuned(points: List<StrokePoint>, epsilonPx: Float, spacingPx: Float): List<StrokePoint> {
    if (points.size < MIN_SMOOTHABLE_POINTS) return points
    val distinct = withoutRepeatedPositions(points)
    if (distinct.size < MIN_SMOOTHABLE_POINTS) return points
    return smoothDistinct(points, distinct, Tuning(epsilonPx, spacingPx))
}

/**
 * The simplify-then-refit pipeline shared by [smoothStroke] and [smoothStrokeTuned], once a
 * [Tuning] has been chosen and [points] de-duplicated into [distinct]. This is the only place
 * either entry point turns a tuning into pixels, so they cannot drift apart.
 */
private fun smoothDistinct(points: List<StrokePoint>, distinct: List<StrokePoint>, tuning: Tuning): List<StrokePoint> {
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

private fun tuningFor(level: SmoothingLevel, stroke: List<StrokePoint>): Tuning = when (level) {
    SmoothingLevel.AUTO -> autoTuning(stroke)
    SmoothingLevel.LIGHT -> Tuning(LIGHT_EPSILON_PX, RESAMPLE_SPACING_PX)
    SmoothingLevel.STRONG -> Tuning(STRONG_EPSILON_PX, RESAMPLE_SPACING_PX)
    SmoothingLevel.OFF -> error("OFF is answered before a tuning is chosen")
}

/**
 * The simplification tolerance for [SmoothingLevel.AUTO], derived from the stroke rather than fixed.
 *
 * Two measurements pull in opposite directions. *Tremor* — [TREMOR_PX] — is an additive error of
 * roughly constant pixel width whatever is being written; it sets how much tolerance simplification
 * *wants*. *Shape scale* — [shapeScalePx] — is the size of the smallest feature the stroke can
 * contain (the counter of an `e`, the notch of an `n`); it sets how much the stroke can *afford*.
 * Tremor is absolute pixels and letters are not — that mismatch is exactly why a single fixed
 * epsilon serves a capital letter and a comma differently well. Taking `min(wanted, affordable)`
 * means smoothing can never spend more than the letter can pay.
 *
 * A third, explicitly provisional term — [tremorBudgetGain] — scales how much of the tremor budget
 * is actually spent, from the stroke's median sample spacing (at a fixed digitizer sample rate, that
 * is pen speed in disguise). Below the tremor width the pen is moving slower than it shakes, so
 * consecutive samples are mostly noise and the full budget is safe to spend; well above it, every
 * sample is real motion, so the budget is cut back so a fast corner isn't clipped.
 */
private fun autoTuning(stroke: List<StrokePoint>): Tuning =
    Tuning(
        epsilonPx = autoEpsilonPx(stroke, MAX_EPSILON_FRACTION_OF_SHAPE, SLOW_PEN_GAIN),
        spacingPx = RESAMPLE_SPACING_PX,
    )

/**
 * [autoTuning]'s epsilon, with [maxEpsilonFractionOfShape] and [slowPenGain] supplied rather than
 * fixed to the current constants — `SmoothingCalibrationReport`'s entry point for sweeping those two
 * candidates through the real formula instead of a reimplementation of it that could drift out of
 * sync.
 */
internal fun autoEpsilonPx(stroke: List<StrokePoint>, maxEpsilonFractionOfShape: Float, slowPenGain: Float): Float {
    val affordable = max(shapeScalePx(stroke), MIN_SHAPE_SCALE_PX) * maxEpsilonFractionOfShape
    val wanted = TREMOR_PX * tremorBudgetGain(medianSampleSpacingPx(stroke), slowPenGain)
    return min(wanted, affordable)
}

/**
 * The size of the smallest feature [stroke] can contain, in page pixels: the shorter side of its
 * bounding box, not the diagonal. A diagonal overstates it — in cursive, one stroke is often a whole
 * word, where the diagonal reports several hundred pixels while the features that matter (the
 * x-height loops and notches) are a tenth of that.
 */
internal fun shapeScalePx(stroke: List<StrokePoint>): Float {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (point in stroke) {
        if (point.x < minX) minX = point.x
        if (point.x > maxX) maxX = point.x
        if (point.y < minY) minY = point.y
        if (point.y > maxY) maxY = point.y
    }
    return min(maxX - minX, maxY - minY)
}

/**
 * The true median distance between consecutive samples of [stroke], in page pixels. Median rather
 * than mean so a single paused instant — a cluster of near-zero spacings — cannot drag the estimate
 * down as far as it would drag an average. Sorts a private copy: [stroke] may be a list the caller
 * still holds a reference to.
 */
internal fun medianSampleSpacingPx(stroke: List<StrokePoint>): Float {
    if (stroke.size < 2) return 0f
    val spacings = FloatArray(stroke.size - 1)
    for (i in 1 until stroke.size) {
        spacings[i - 1] = distance(stroke[i - 1].x, stroke[i - 1].y, stroke[i].x, stroke[i].y)
    }
    spacings.sort()
    val mid = spacings.size / 2
    return if (spacings.size % 2 == 0) (spacings[mid - 1] + spacings[mid]) / 2f else spacings[mid]
}

/**
 * Clamped linear interpolation between [slowPenGain] at [SLOW_SPACING_PX] and [FAST_PEN_GAIN] at
 * [FAST_SPACING_PX]. Provisional: swept and selected by `SmoothingCalibrationReport`, not derived
 * from first principles, and a candidate that fails to earn its keep there should be deleted rather
 * than kept out of caution. [slowPenGain] is a parameter rather than always [SLOW_PEN_GAIN] so the
 * report can sweep it through this exact function.
 */
private fun tremorBudgetGain(medianSpacingPx: Float, slowPenGain: Float): Float {
    if (medianSpacingPx <= SLOW_SPACING_PX) return slowPenGain
    if (medianSpacingPx >= FAST_SPACING_PX) return FAST_PEN_GAIN
    val t = (medianSpacingPx - SLOW_SPACING_PX) / (FAST_SPACING_PX - SLOW_SPACING_PX)
    return slowPenGain + t * (FAST_PEN_GAIN - slowPenGain)
}

// Measured from 115 real strokes pulled off the author's Boox Go 10.3 (test.nnote) — see
// SmoothingCalibrationReport, which recomputes these percentiles from any corpus and sweeps the
// swept-marked constants below. All of them are in page pixels, so they are specific to this
// panel's pixel density; re-run the report rather than eyeball new numbers for other hardware.
private const val TREMOR_PX = 0.45f // p95 of measured digitizer tremor (p50 0.10, p99 1.0 px)
// What SmoothingCalibrationReport's selection rule picked over the 2026-09 corpus: the lowest value
// swept, and the only bucket it changes is the small one, where the tremor budget does not bind.
// Every larger value trades small-letter form away for nothing — a big stroke is already governed by
// TREMOR_PX, so it does not notice.
private const val MAX_EPSILON_FRACTION_OF_SHAPE = 0.02f
private const val MIN_SHAPE_SCALE_PX = 8.0f // p10 of measured stroke extent
internal const val RESAMPLE_SPACING_PX = 1.2f // below the 1.57px native median sample spacing, so it refits instead of decimating
private const val SLOW_SPACING_PX = 0.5f // p10 of measured sample spacing
private const val FAST_SPACING_PX = 4.0f // p90 of measured sample spacing
// Both 1.0, which makes the pen-speed term inert: the calibration report swept it 1.0..3.0 over real
// ink and selected the bottom of the range, i.e. no speed dependence at all. Kept rather than deleted
// only so the next corpus can re-decide it — if the author's prose run selects 1.0 again, delete
// tremorBudgetGain and medianSampleSpacingPx with it rather than leave machinery that does nothing.
private const val SLOW_PEN_GAIN = 1.0f
private const val FAST_PEN_GAIN = 1.0f
private const val LIGHT_EPSILON_PX = 1.2f // unchanged, merely named now
private const val STRONG_EPSILON_PX = 3.0f // unchanged, merely named now

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
