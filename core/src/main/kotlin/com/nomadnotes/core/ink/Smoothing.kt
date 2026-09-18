package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
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
 * Returns [points] with digitizer jitter removed — the "auto-smoothing" applied to a stroke as
 * it is committed.
 *
 * Three stages. [smoothPath] first nudges interior positions with an arc-length Gaussian average
 * of their neighbours (corners excepted), which is what actually removes tremor: a threshold can
 * only drop points, it cannot move one closer to the "true" line — and because the averaging
 * window is sized in page pixels rather than sample count, it reaches hand wobble's wavelength
 * regardless of how densely the digitizer happened to sample. [simplify] (Ramer–Douglas–Peucker)
 * then drops the points the smoothed path no longer needs to keep its shape. [splitLongSpans]
 * puts a few back wherever that left two knots far enough apart to cut a visible corner across
 * real curvature — a slack RDP tolerance would otherwise straighten.
 *
 * The result is a *sparse* list of knots, each one still a captured sample with its own pressure
 * and timestamp — smoothing only ever nudges a position, by about the level's epsilon at most.
 * It is not a path to draw directly: it is the knot list of a centripetal Catmull–Rom curve, and
 * a caller renders it through [inkCurve] or [inkOutline]. Knots stay within about epsilon of the
 * captured path, and the rendered curve within about 1.5x epsilon of the knot polyline in turn —
 * comfortably inside the tolerance eraser and lasso hit-testing already allow on that polyline.
 *
 * Contract relied on by callers:
 *  - [SmoothingLevel.OFF] returns [points] itself, unchanged.
 *  - A gesture too short to have a shape (fewer than [MIN_SMOOTHABLE_POINTS] distinct positions, i.e.
 *    a tap or a dot) is returned unchanged, so tap-sized marks are never reshaped away.
 *  - The first and last points are always preserved exactly, including their pressure and
 *    `timestampDelta`. The stroke therefore keeps its total duration, which
 *    `TapClassifier` reads from the last point.
 *
 * [points] is expected in capture order, with non-decreasing `timestampDelta` (what every backend
 * produces); the ordering of the output is only as good as the input's.
 */
fun smoothStroke(points: List<StrokePoint>, level: SmoothingLevel): List<StrokePoint> {
    val tuning = tuningFor(level) ?: return points
    if (points.size < MIN_SMOOTHABLE_POINTS) return points
    // Repeated positions are common when the pen rests, and would put zero-length segments into
    // both the corner test and the spline's parameterization, which divides by their length.
    val distinct = withoutRepeatedPositions(points)
    if (distinct.size < MIN_SMOOTHABLE_POINTS) return points
    val smoothed = smoothPath(distinct, tuning.sigmaPx)
    val knots = splitLongSpans(simplify(smoothed, tuning.epsilonPx), smoothed, tuning.maxKnotSpanPx)
    val result = knots.toMutableList()
    result[0] = points.first()
    result[result.size - 1] = points.last()
    return result
}

/** Fewest distinct positions a gesture needs before it is treated as a shape worth smoothing. */
const val MIN_SMOOTHABLE_POINTS = 4

/**
 * The turn angle beyond which [isCorner] flags a point as a corner: sharper than this and the
 * point carries the letter's shape, not tremor, so smoothing across it would round the shape away.
 */
private val CORNER_COS_THRESHOLD = cos(70.0 * PI / 180.0).toFloat()

/**
 * The position-nudging stage of [smoothStroke]: arc-length Gaussian smoothing at scale [sigmaPx]
 * page pixels. Sizing the averaging window in pixels rather than in sample count is the point —
 * it is what lets this reach hand wobble's 10-30px wavelength no matter how densely or sparsely
 * the digitizer sampled, unlike a fixed-pass, fixed-tap kernel whose reach is capped by however
 * many samples happen to fall within a few pixels of each other.
 *
 * Both stroke endpoints and every point [isCorner] flags — probed at least [sigmaPx] out, so
 * wobble at the smoothing scale itself is never mistaken for a corner — are anchors: the path is
 * split into anchor-to-anchor pieces first, and each piece is smoothed independently of its
 * neighbours. Anchors carry the letter's real shape, so they never move and never blend across
 * into an adjacent piece, which keeps this much heavier smoothing from rounding a corner away.
 */
internal fun smoothPath(points: List<StrokePoint>, sigmaPx: Float): List<StrokePoint> {
    if (points.size < 3) return points
    val probePx = max(CORNER_PROBE_PX, sigmaPx)
    val anchors = sortedSetOf(0, points.size - 1)
    for (i in 1 until points.size - 1) {
        if (isCorner(points, i, probePx)) anchors.add(i)
    }
    val anchorList = anchors.toList()
    val result = points.toMutableList()
    for (k in 0 until anchorList.size - 1) {
        smoothPiece(points, anchorList[k], anchorList[k + 1], sigmaPx, result)
    }
    return result
}

/**
 * Arc-length-weighted Gaussian smoothing of the interior of one corner-to-corner piece
 * `points[from..to]` (inclusive), writing moved positions into [out]. [from] and [to] — anchors —
 * never move; each interior point's own sigma shrinks as it nears either end of the piece (down to
 * a no-op right next to an anchor), so the smoothing fades out approaching a corner rather than
 * stopping abruptly at it.
 */
private fun smoothPiece(points: List<StrokePoint>, from: Int, to: Int, sigmaPx: Float, out: MutableList<StrokePoint>) {
    if (to - from < 2) return // no interior point to move
    val arc = FloatArray(to - from + 1)
    for (i in from + 1..to) {
        arc[i - from] = arc[i - from - 1] + distance(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
    }
    val length = arc[to - from]
    for (i in from + 1 until to) {
        val s = arc[i - from]
        val sigma = min(sigmaPx, min(s, length - s) / 2f)
        if (sigma < 0.25f) continue
        val window = 3f * sigma
        var sumX = 0f
        var sumY = 0f
        var sumW = 0f
        for (j in from..to) {
            val ds = arc[j - from] - s
            if (abs(ds) > window) continue
            val w = exp(-(ds * ds) / (2f * sigma * sigma))
            sumX += w * points[j].x
            sumY += w * points[j].y
            sumW += w
        }
        out[i] = points[i].copy(x = sumX / sumW, y = sumY / sumW)
    }
}

/**
 * Floor on how far [isCorner] walks along the polyline before comparing directions — arc length,
 * not a sample count. At slow writing speed Boox samples land only 1-2px apart, close enough that
 * the immediate-neighbour angle used to swing past 70° on sub-pixel tremor alone; probing this far
 * out instead stays comfortably above tremor amplitude while staying well under the scale of an
 * actual letter stroke (6px is about 0.66mm on the ~227dpi Boox Go 10.3 panel). It is only a
 * floor: [smoothPath] probes farther out at coarser smoothing scales, so that wobble at its own
 * scale is never misread as a corner either.
 */
private const val CORNER_PROBE_PX = 6f

/**
 * True where [points] bends sharper than [CORNER_COS_THRESHOLD] at index [index], comparing the
 * directions into and out of it from about [probePx] away on each side — walking the polyline
 * rather than reading the immediate neighbours, so the test's scale tracks real curvature instead
 * of however dense this stroke happened to be sampled. A walk that runs out of points before
 * covering the probe distance settles for the end it reached. A degenerate incoming or outgoing
 * leg (zero length) has no angle to measure, so it reads as a non-corner.
 */
private fun isCorner(points: List<StrokePoint>, index: Int, probePx: Float): Boolean {
    val current = points[index]
    val back = walkPolyline(points, index, step = -1, probePx)
    val forward = walkPolyline(points, index, step = 1, probePx)
    val inX = current.x - back.x
    val inY = current.y - back.y
    val outX = forward.x - current.x
    val outY = forward.y - current.y
    val inLength = sqrt(inX * inX + inY * inY)
    val outLength = sqrt(outX * outX + outY * outY)
    if (inLength <= 0f || outLength <= 0f) return false
    val cosAngle = (inX * outX + inY * outY) / (inLength * outLength)
    return cosAngle < CORNER_COS_THRESHOLD
}

/** The point [targetPx] of accumulated chord length from `points[from]`, walking in direction
 *  [step] (+-1) — or the endpoint reached first if the polyline runs out before then. */
private fun walkPolyline(points: List<StrokePoint>, from: Int, step: Int, targetPx: Float): StrokePoint {
    var traveled = 0f
    var i = from
    while (true) {
        val next = i + step
        if (next < 0 || next >= points.size) return points[i]
        val leg = distance(points[i].x, points[i].y, points[next].x, points[next].y)
        if (traveled + leg >= targetPx) return points[next]
        traveled += leg
        i = next
    }
}

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

/**
 * Inserts knots back into [knots] wherever two consecutive ones are more than [maxSpanPx] apart,
 * so a slack [simplify] tolerance cannot cut a visible corner across a span of real curvature
 * that [smoothPath] never touched. Each gap is closed by the [smoothed] sample nearest its middle
 * *by index* — not by re-running RDP — repeating on each half until every span fits.
 *
 * [knots] must be (as [simplify] guarantees) a subsequence of [smoothed] in the same order and by
 * the same instances, which is how each knot's position in [smoothed] is found.
 */
internal fun splitLongSpans(
    knots: List<StrokePoint>,
    smoothed: List<StrokePoint>,
    maxSpanPx: Float,
): List<StrokePoint> {
    if (knots.size < 2 || maxSpanPx <= 0f) return knots
    val indices = indicesOf(knots, smoothed)
    val result = ArrayList<StrokePoint>(knots.size)
    result.add(knots.first())
    for (i in 0 until knots.size - 1) {
        appendSplits(result, indices[i], indices[i + 1], smoothed, maxSpanPx)
        result.add(knots[i + 1])
    }
    return result
}

/** Where each of [knots] sits in [smoothed], found by identity so structurally-equal points at
 *  different moments of the stroke cannot be confused with one another. */
private fun indicesOf(knots: List<StrokePoint>, smoothed: List<StrokePoint>): IntArray {
    val indices = IntArray(knots.size)
    var searchFrom = 0
    for (k in knots.indices) {
        var j = searchFrom
        while (j < smoothed.size && smoothed[j] !== knots[k]) j++
        indices[k] = j
        searchFrom = j + 1
    }
    return indices
}

/** Appends, in order, the [smoothed] samples needed between indices [loIndex] and [hiIndex] so no
 *  remaining gap exceeds [maxSpanPx]; the endpoints themselves are the caller's responsibility. */
private fun appendSplits(
    out: MutableList<StrokePoint>,
    loIndex: Int,
    hiIndex: Int,
    smoothed: List<StrokePoint>,
    maxSpanPx: Float,
) {
    if (hiIndex - loIndex <= 1) return
    val lo = smoothed[loIndex]
    val hi = smoothed[hiIndex]
    if (distance(lo.x, lo.y, hi.x, hi.y) <= maxSpanPx) return
    val midIndex = (loIndex + hiIndex) / 2
    appendSplits(out, loIndex, midIndex, smoothed, maxSpanPx)
    out.add(smoothed[midIndex])
    appendSplits(out, midIndex, hiIndex, smoothed, maxSpanPx)
}

/** Per-level tuning, kept private so [SmoothingLevel] stays a plain vocabulary type. */
private class Tuning(
    /** Gaussian smoothing scale, in page pixels — see [smoothPath]. */
    val sigmaPx: Float,
    /** How far the smoothed path may stray from itself before [simplify] drops a knot, in page pixels. */
    val epsilonPx: Float,
    /** Widest gap [splitLongSpans] allows between two consecutive knots, in page pixels. */
    val maxKnotSpanPx: Float,
)

// Device-tuned against the Boox Go 10.3 panel (about 227dpi, so 1px is roughly 0.11mm); expect
// these to move after a device pass. Hand wobble runs 10-30px in wavelength, which is what sigmaPx
// has to reach. LIGHT's 4px sigma (about 0.45mm) tidies that wobble but keeps your letterforms;
// STRONG's 10px sigma (about 1.1mm) is the Animate-like, aggressively regularizing level — gentle
// curves become clean arcs, while corners survive regardless, because they are anchors [smoothPath]
// never smooths across. epsilonPx is smaller than it used to be at both levels: the path entering
// [simplify] is already smooth, so RDP's job here is only thinning knots, not doing any of the
// actual smoothing. Reach for a stronger effect by raising sigmaPx, not epsilonPx or maxKnotSpanPx.
private fun tuningFor(level: SmoothingLevel): Tuning? = when (level) {
    SmoothingLevel.OFF -> null
    SmoothingLevel.LIGHT -> Tuning(sigmaPx = 4f, epsilonPx = 1.5f, maxKnotSpanPx = 48f)
    SmoothingLevel.STRONG -> Tuning(sigmaPx = 10f, epsilonPx = 3f, maxKnotSpanPx = 48f)
}

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
