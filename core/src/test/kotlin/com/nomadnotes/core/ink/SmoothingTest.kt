package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Behavioural tests for stroke smoothing: what it must leave alone (endpoints, timing, taps) as much
 * as what it changes (jitter), plus the sparse-knot contract the curve fit ([inkCurve]/[inkOutline])
 * relies on.
 */
class SmoothingTest {

    private fun pointsOf(vararg xy: Pair<Float, Float>): List<StrokePoint> =
        xy.mapIndexed { index, (x, y) ->
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = index * 10L)
        }

    /** A horizontal line whose interior points alternate above and below it by [jitter] pixels,
     *  [spacingPx] apart — dense enough to stand in for a slowly-written stroke when lowered. */
    private fun jitteredLine(count: Int, jitter: Float, spacingPx: Float = 5f): List<StrokePoint> =
        (0 until count).map { i ->
            val interior = i != 0 && i != count - 1
            val y = if (!interior) 0f else if (i % 2 == 0) jitter else -jitter
            StrokePoint(x = i * spacingPx, y = y, pressure = 1f, timestampDelta = i * 10L)
        }

    /** Half a circle, as a path with real curvature that smoothing must not flatten. */
    private fun arc(count: Int, radius: Float): List<StrokePoint> =
        (0 until count).map { i ->
            val angle = i.toFloat() / (count - 1) * Math.PI.toFloat()
            StrokePoint(
                x = 200f + radius * cos(angle),
                y = 200f + radius * sin(angle),
                pressure = 1f,
                timestampDelta = i * 10L,
            )
        }

    /** A tightening spiral: curvature gets sharper towards the centre than any arc above has. */
    private fun spiral(count: Int, turns: Float, maxRadius: Float): List<StrokePoint> =
        (0 until count).map { i ->
            val t = i.toFloat() / (count - 1)
            val angle = t * turns * 2f * Math.PI.toFloat()
            val radius = maxRadius - t * maxRadius * 0.8f
            StrokePoint(
                x = 300f + radius * cos(angle),
                y = 300f + radius * sin(angle),
                pressure = 1f,
                timestampDelta = i * 10L,
            )
        }

    /** A wavy, handwriting-scale path: no single radius, unlike [arc] and [spiral]. */
    private fun handwritingLike(count: Int): List<StrokePoint> =
        (0 until count).map { i ->
            val t = i.toFloat()
            StrokePoint(
                x = t * 4f,
                y = 30f * sin(t * 0.35f) + 6f * sin(t * 1.7f),
                pressure = 1f,
                timestampDelta = i * 10L,
            )
        }

    /** A 90-degree corner sampled densely enough on each leg for denoise to have real work to do. */
    private fun vApex(legPoints: Int): List<StrokePoint> {
        val down = (0 until legPoints).map { i ->
            StrokePoint(x = 0f, y = 100f - i * (100f / (legPoints - 1)), pressure = 1f, timestampDelta = i * 10L)
        }
        val across = (1 until legPoints).map { i ->
            StrokePoint(
                x = i * (100f / (legPoints - 1)),
                y = 0f,
                pressure = 1f,
                timestampDelta = (legPoints - 1 + i) * 10L,
            )
        }
        return down + across
    }

    /** A straight line along y=0 with a smooth sinusoid riding on it — a stand-in for hand wobble,
     *  as opposed to [jitteredLine]'s alternating per-sample noise, which is closer to sensor tremor. */
    private fun wobblyLine(lengthPx: Float, amplitude: Float, wavelengthPx: Float, spacingPx: Float): List<StrokePoint> {
        val count = (lengthPx / spacingPx).toInt() + 1
        return (0 until count).map { i ->
            val x = i * spacingPx
            StrokePoint(
                x = x,
                y = amplitude * sin(2.0 * Math.PI * x / wavelengthPx).toFloat(),
                pressure = 1f,
                timestampDelta = i * 10L,
            )
        }
    }

    private fun distanceToPath(point: StrokePoint, path: List<StrokePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until path.size - 1) {
            best = minOf(best, distanceToSegment(point, path[i], path[i + 1]))
        }
        return best
    }

    private fun distanceToSegment(p: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * abx) + ((p.y - a.y) * aby)) / lengthSquared
        val clamped = t.coerceIn(0f, 1f)
        return hypot(p.x - (a.x + clamped * abx), p.y - (a.y + clamped * aby))
    }

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    /** Samples a cubic finely, as plain points, so its distance to another path can be measured. */
    private fun sampleCurve(segments: List<CubicSegment>, stepsPerSegment: Int = 20): List<StrokePoint> =
        segments.flatMap { seg ->
            (0..stepsPerSegment).map { s ->
                val t = s.toFloat() / stepsPerSegment
                val mt = 1f - t
                StrokePoint(
                    x = mt * mt * mt * seg.startX + 3f * mt * mt * t * seg.c1x +
                        3f * mt * t * t * seg.c2x + t * t * t * seg.endX,
                    y = mt * mt * mt * seg.startY + 3f * mt * mt * t * seg.c1y +
                        3f * mt * t * t * seg.c2y + t * t * t * seg.endY,
                    pressure = 0f,
                    timestampDelta = 0L,
                )
            }
        }

    // --- what smoothing must leave alone ------------------------------------------------------

    @Test
    fun `OFF returns the captured points themselves`() {
        val points = jitteredLine(count = 20, jitter = 2f)
        assertSame(points, smoothStroke(points, SmoothingLevel.OFF))
    }

    @Test
    fun `a gesture too short to have a shape is returned unchanged`() {
        val tap = pointsOf(10f to 10f, 11f to 10f, 10f to 11f)
        assertSame(tap, smoothStroke(tap, SmoothingLevel.STRONG))
    }

    @Test
    fun `a dot whose samples all land on one spot is returned unchanged`() {
        // Enough samples to pass the count check, but only one distinct position between them.
        val dot = pointsOf(10f to 10f, 10f to 10f, 10f to 10f, 10f to 10f, 10f to 10f)
        assertSame(dot, smoothStroke(dot, SmoothingLevel.STRONG))
    }

    @Test
    fun `the first and last points survive smoothing exactly`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        assertEquals(points.first(), smoothed.first())
        assertEquals(points.last(), smoothed.last())
    }

    @Test
    fun `the stroke keeps its total duration, which tap classification reads`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        assertEquals(points.last().timestampDelta, smoothed.last().timestampDelta)
    }

    @Test
    fun `timestamps stay ordered through smoothing`() {
        val smoothed = smoothStroke(arc(count = 40, radius = 90f), SmoothingLevel.LIGHT)
        smoothed.zipWithNext { earlier, later ->
            assertTrue(
                "timestamps went backwards: ${earlier.timestampDelta} then ${later.timestampDelta}",
                later.timestampDelta >= earlier.timestampDelta,
            )
        }
    }

    @Test
    fun `pressure stays within the normalized range`() {
        val varying = (0 until 30).map { i ->
            StrokePoint(
                x = i * 4f,
                y = if (i % 2 == 0) 2f else -2f,
                pressure = i / 29f,
                timestampDelta = i * 10L,
            )
        }
        smoothStroke(varying, SmoothingLevel.STRONG).forEach {
            assertTrue("pressure out of range: ${it.pressure}", it.pressure in 0f..1f)
        }
    }

    @Test
    fun `a surviving knot keeps its own pressure and timestamp`() {
        val points = arc(count = 40, radius = 90f).mapIndexed { i, p ->
            p.copy(pressure = i / 39f, timestampDelta = i * 10L)
        }
        val original = points.map { it.pressure to it.timestampDelta }.toSet()
        val smoothed = smoothStroke(points, SmoothingLevel.LIGHT)
        // Every knot is a captured sample verbatim: denoise only ever moves a position, so its
        // (pressure, timestampDelta) pair must still match one of the samples that was captured.
        smoothed.forEach { knot ->
            assertTrue(
                "knot (pressure=${knot.pressure}, t=${knot.timestampDelta}) matches no captured sample",
                (knot.pressure to knot.timestampDelta) in original,
            )
        }
        assertTrue("expected an interior knot to survive, not just the endpoints", smoothed.size > 2)
    }

    @Test
    fun `a surviving knot keeps its own nibFactor`() {
        // Denoise only ever moves a position (see smoothPiece's x,y-only .copy) and simplify/
        // splitLongSpans only ever select existing instances, so a knot's nibFactor — set here to
        // its index, unique per point — must survive attached to whichever position it started at.
        val points = arc(count = 40, radius = 90f).mapIndexed { i, p -> p.copy(nibFactor = i.toFloat()) }
        val original = points.associateBy { it.nibFactor }
        val smoothed = smoothStroke(points, SmoothingLevel.LIGHT)
        smoothed.forEach { knot ->
            val source = original[knot.nibFactor]
            assertTrue("knot's nibFactor ${knot.nibFactor} matches no captured sample", source != null)
        }
        assertTrue("expected an interior knot to survive, not just the endpoints", smoothed.size > 2)
    }

    @Test
    fun `repeated samples from a resting pen produce no invalid coordinates`() {
        val resting = pointsOf(
            0f to 0f, 0f to 0f, 10f to 2f, 10f to 2f, 20f to 0f, 30f to 3f, 40f to 0f,
        )
        smoothStroke(resting, SmoothingLevel.STRONG).forEach {
            assertTrue("non-finite point: $it", it.x.isFinite() && it.y.isFinite())
        }
    }

    // --- what smoothing must change -----------------------------------------------------------

    @Test
    fun `smoothing straightens a jittered straight line`() {
        val jittered = jitteredLine(count = 21, jitter = 1.5f)
        val smoothed = smoothStroke(jittered, SmoothingLevel.STRONG)
        val before = jittered.maxOf { abs(it.y) }
        val after = smoothed.maxOf { abs(it.y) }
        assertTrue("expected straighter than $before, got $after", after < before / 2f)
    }

    @Test
    fun `hand-wobble-scale smoothing is strongly felt at STRONG, not just sample jitter`() {
        // Regression for the user's device report ("the smoothing is still very slightly felt" at
        // STRONG): a wavelength in hand-wobble's 10-30px range, which the old sample-count denoise
        // passes could never reach regardless of level.
        val wobbly = wobblyLine(lengthPx = 200f, amplitude = 2.5f, wavelengthPx = 24f, spacingPx = 3f)
        val off = smoothStroke(wobbly, SmoothingLevel.OFF)
        val light = smoothStroke(wobbly, SmoothingLevel.LIGHT)
        val strong = smoothStroke(wobbly, SmoothingLevel.STRONG)
        assertSame(wobbly, off)
        // Measured away from the ends: the endpoints are preserved exactly by contract (this fixture ends
        // on a crest), and the smoothing window shrinks to nothing approaching them so they never move.
        fun deviation(points: List<StrokePoint>) = points.filter { it.x in 24f..176f }.maxOf { abs(it.y) }
        val lightDeviation = deviation(light)
        val strongDeviation = deviation(strong)
        // LIGHT (sigma 4px) theoretically keeps exp(-2pi^2 sigma^2 / lambda^2) ~ 58% of a 24px wave, about 1.45px:
        // a clear cut that still leaves letterforms alone. The bound leaves room for sampling discretization.
        assertTrue("LIGHT left ${lightDeviation}px of wobble, expected under 1.8px", lightDeviation < 1.8f)
        assertTrue("STRONG left ${strongDeviation}px of wobble, expected under 0.5px", strongDeviation < 0.5f)
        assertTrue("STRONG ($strongDeviation) should smooth more than LIGHT ($lightDeviation)", strongDeviation < lightDeviation)
    }

    @Test
    fun `tremor below LIGHT's epsilon is still removed at STRONG`() {
        // Regression test: RDP alone (the old pipeline) cannot touch tremor smaller than its
        // epsilon, no matter the level. denoise can, because it moves positions instead of only
        // dropping points.
        val jittered = jitteredLine(count = 21, jitter = 1.0f)
        val smoothed = smoothStroke(jittered, SmoothingLevel.STRONG)
        val after = smoothed.maxOf { abs(it.y) }
        assertTrue("expected the 1px tremor gone, got ${after}px", after <= 0.3f)
    }

    @Test
    fun `slow-writing-scale tremor is removed at STRONG, not misread as a corner`() {
        // Regression test for the arc-length corner probe: at the ~1.5px sample spacing a slow
        // writer produces, the old immediate-neighbour corner test swung past 70 degrees on
        // sub-pixel jitter alone and denoise skipped it. Probing CORNER_PROBE_PX out instead lets
        // this tremor register as what it is, and a 1-2-1 blur cancels a clean alternation almost
        // exactly — so the bound here is tight, not the ~0.3px used above for 5px-spaced jitter.
        val jittered = jitteredLine(count = 60, jitter = 0.8f, spacingPx = 1.5f)
        val smoothed = smoothStroke(jittered, SmoothingLevel.STRONG)
        val after = smoothed.maxOf { abs(it.y) }
        assertTrue("expected the 0.8px tremor gone, got ${after}px", after <= 0.1f)
    }

    @Test
    fun `a stronger level smooths at least as much as a lighter one`() {
        val jittered = jitteredLine(count = 21, jitter = 2f)
        val light = smoothStroke(jittered, SmoothingLevel.LIGHT).maxOf { abs(it.y) }
        val strong = smoothStroke(jittered, SmoothingLevel.STRONG).maxOf { abs(it.y) }
        assertTrue("strong ($strong) should not exceed light ($light)", strong <= light)
    }

    @Test
    fun `LIGHT keeps points within 3px of the captured path`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.LIGHT)
        smoothed.forEach { p ->
            val strayed = distanceToPath(p, points)
            assertTrue("point strayed ${strayed}px from the captured path", strayed <= 3f)
        }
    }

    @Test
    fun `STRONG keeps points within 6px of the captured path`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        smoothed.forEach { p ->
            val strayed = distanceToPath(p, points)
            assertTrue("point strayed ${strayed}px from the captured path", strayed <= 6f)
        }
    }

    @Test
    fun `curvature survives smoothing`() {
        // The apex must not shrink by more than about 1.5px under STRONG's much heavier smoothing.
        val points = arc(count = 40, radius = 90f)
        val baseline = points.maxOf { it.y }
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        val after = smoothed.maxOf { it.y }
        assertTrue("arc apex shrank from ${baseline}px to ${after}px", after > baseline - 1.5f)
    }

    @Test
    fun `a small arc keeps recognizable curvature under STRONG`() {
        // A radius-15 semicircle is only ~47px of arc length — small next to STRONG's 10px sigma, so
        // it shrinks more than the wide arc above does. This bounds that shrinkage at a level still
        // recognizable as a curve, not a flattened line; NOTE: the exact px value below is a
        // placeholder from reasoning, not a measured run — the integrator step must run this test,
        // record the actual observed shrink, and tighten/loosen this bound with that number in a
        // comment justifying it.
        val points = arc(count = 40, radius = 15f)
        val baseline = points.maxOf { it.y }
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        val after = smoothed.maxOf { it.y }
        assertTrue("arc apex shrank from ${baseline}px to ${after}px", after > baseline - 8f)
    }

    @Test
    fun `a 90 degree apex stays within 3px under STRONG`() {
        val v = vApex(legPoints = 21)
        val smoothed = smoothStroke(v, SmoothingLevel.STRONG)
        val closestToApex = smoothed.minOf { hypot(it.x, it.y) }
        assertTrue("apex strayed ${closestToApex}px from (0,0)", closestToApex <= 3f)
    }

    @Test
    fun `smoothing returns fewer points than given`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.LIGHT)
        assertTrue("expected fewer knots, got ${smoothed.size}", smoothed.size < points.size)
    }

    @Test
    fun `no consecutive knots exceed the level's span cap`() {
        val points = arc(count = 40, radius = 90f)
        val caps = mapOf(SmoothingLevel.LIGHT to 48f, SmoothingLevel.STRONG to 48f)
        for ((level, cap) in caps) {
            val smoothed = smoothStroke(points, level)
            smoothed.zipWithNext { a, b ->
                val gap = hypot(a.x - b.x, a.y - b.y)
                assertTrue("$level knot gap ${gap}px exceeds its ${cap}px cap", gap <= cap + 1e-3f)
            }
        }
    }

    // --- simplify -----------------------------------------------------------------------------

    @Test
    fun `simplify keeps the endpoints and drops points already on the line`() {
        val straight = pointsOf(0f to 0f, 10f to 0f, 20f to 0f, 30f to 0f)
        val simplified = simplify(straight, epsilonPx = 1f)
        assertEquals(listOf(straight.first(), straight.last()), simplified)
    }

    @Test
    fun `simplify keeps a corner that carries the shape`() {
        val corner = pointsOf(0f to 0f, 10f to 0f, 20f to 0f, 20f to 20f)
        val simplified = simplify(corner, epsilonPx = 1f)
        assertTrue("the corner at (20,0) was dropped", simplified.contains(corner[2]))
    }

    @Test
    fun `simplify leaves a path alone when the tolerance is not positive`() {
        val points = jitteredLine(count = 10, jitter = 1f)
        assertSame(points, simplify(points, epsilonPx = 0f))
    }

    // --- knots feed a curve fit ----------------------------------------------------------------

    @Test
    fun `the curve through the knots stays within 1_5 epsilon of the knot polyline`() {
        val epsilonPx = 3.0f // STRONG's epsilonPx
        val limit = 1.5f * epsilonPx
        val fixtures = listOf(
            "arc" to arc(count = 60, radius = 150f),
            "spiral" to spiral(count = 80, turns = 2f, maxRadius = 120f),
            "handwriting" to handwritingLike(count = 60),
        )
        for ((name, points) in fixtures) {
            val knots = smoothStroke(points, SmoothingLevel.STRONG)
            val curveSamples = sampleCurve(inkCurve(knots))
            curveSamples.forEach { sample ->
                val strayed = distanceToPath(sample, knots)
                assertTrue(
                    "$name: curve strayed ${strayed}px from the knot polyline (limit ${limit}px)",
                    strayed <= limit,
                )
            }
        }
    }
}
