package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Behavioural tests for stroke smoothing: what it must leave alone (endpoints, timing, taps) as much
 * as what it changes (jitter), plus the sparse-knot contract the curve fit ([inkCurve]/[inkOutline])
 * relies on.
 */
class SmoothingTest {

    private val allLevels = listOf(SmoothingLevel.AUTO, SmoothingLevel.LIGHT, SmoothingLevel.STRONG)

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

    // --- an "e"-like glyph, for testing whether AUTO preserves a real counter/loop -------------

    // Just short of a full turn, so the loop has a start and an end rather than closing on itself.
    private val glyphSweepTurns = 0.92f

    /** The point a fraction [t] along [heightPx]'s loop, sharing one parametric curve for every use. */
    private fun loopPosition(heightPx: Float, t: Float): Pair<Float, Float> {
        val radius = heightPx / 2f
        val angle = -Math.PI.toFloat() / 2f + t * glyphSweepTurns * 2f * Math.PI.toFloat()
        return (radius + radius * cos(angle)) to (radius + radius * sin(angle))
    }

    /**
     * An idealized, noiseless loop the size of a lowercase letter's counter — most of a full turn,
     * like the loop of an "e" — scaled so its bounding box is [heightPx] tall, and sampled at a fixed
     * digitizer rate (~125Hz) tight enough to give roughly the 1.6px median spacing measured off real
     * hardware (see the constants comment in Smoothing.kt), whatever the loop's size.
     */
    private fun smallGlyph(heightPx: Float, spacingPx: Float = TYPICAL_SAMPLE_SPACING_PX): List<StrokePoint> {
        val radius = heightPx / 2f
        val arcLengthPx = glyphSweepTurns * 2f * Math.PI.toFloat() * radius
        val targetSpacingPx = spacingPx
        val sampleCount = max(MIN_SMOOTHABLE_POINTS, (arcLengthPx / targetSpacingPx).roundToInt() + 1)
        val sampleIntervalMs = 8L
        return (0 until sampleCount).map { i ->
            val (x, y) = loopPosition(heightPx, i.toFloat() / (sampleCount - 1))
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = i * sampleIntervalMs)
        }
    }

    /**
     * The same loop as [smallGlyph], sampled far more densely than any real capture — a near-continuous
     * reference curve to measure error against, not a stroke to feed into [smoothStroke].
     */
    private fun idealCurve(heightPx: Float, sampleCount: Int = 600): List<StrokePoint> =
        (0 until sampleCount).map { i ->
            val (x, y) = loopPosition(heightPx, i.toFloat() / (sampleCount - 1))
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = 0L)
        }

    /**
     * Sample spacings from the same 115-stroke corpus: p50 1.57px overall, p10 0.27px, p90 2.64px.
     *
     * Spacing is pen speed in disguise, and speed is not independent of letter size — a small letter
     * is written slowly and lands many samples per millimetre, while a large sweep is fast and lands
     * few. Sampling every glyph at the median regardless of size would starve a small one of exactly
     * the data the algorithm needs, and test a capture the digitizer never produces.
     */
    private val TYPICAL_SAMPLE_SPACING_PX = 1.6f
    private val SMALL_LETTER_SPACING_PX = 0.3f
    private val LARGE_SWEEP_SPACING_PX = 2.6f

    /**
     * The jitter amplitude that models this panel, used wherever a test needs "a captured stroke".
     *
     * Measured over 115 real strokes pulled off the Boox Go 10.3: deviation from the local chord runs
     * p50 0.10px, p95 0.44px, p99 1.0px. [withJitter] displaces uniformly over 0..amplitude, so this
     * value puts the modelled p95 on the measured one. Driving every sample at the measured p99
     * instead would be a noise floor an order of magnitude above the median the digitizer actually
     * produces, and no tuning that keeps a 20px letter intact can also remove it — the test would be
     * asserting against a device that does not exist.
     */
    private val DEVICE_TREMOR_PX = 0.45f

    /**
     * [points] with each interior point displaced by up to [amplitudePx], in a random direction, by
     * up to that full amount — a stand-in for digitizer tremor. Deterministic for a given [seed],
     * never random per run, so a failing test reproduces. The first and last points are left exactly
     * where they are: a captured stroke's endpoints are the pen actually landing and lifting, not a
     * mid-stroke sample, so they are the least jittery points of a real capture.
     */
    private fun withJitter(points: List<StrokePoint>, amplitudePx: Float, seed: Long): List<StrokePoint> {
        val rng = Random(seed)
        return points.mapIndexed { index, point ->
            if (index == 0 || index == points.size - 1) return@mapIndexed point
            val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
            val magnitude = rng.nextFloat() * amplitudePx
            point.copy(x = point.x + magnitude * cos(angle), y = point.y + magnitude * sin(angle))
        }
    }

    private fun distanceToPath(point: StrokePoint, path: List<StrokePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until path.size - 1) {
            best = minOf(best, distanceToSegment(point, path[i], path[i + 1]))
        }
        return best
    }

    /**
     * The *typical* distance from [ideal] to [path], as a median rather than a worst case.
     *
     * The companion to [maxDistanceToPath], and the two answer different questions. Simplification
     * keeps captured points as knots and the spline passes exactly through them, so a single knot that
     * happened to land on a noisy sample fixes the worst case no matter how much tremor was removed
     * everywhere else. A max therefore cannot show that smoothing helped on the whole — only that
     * nothing was destroyed, which is what [maxDistanceToPath] is for.
     */
    private fun medianDistanceToPath(ideal: List<StrokePoint>, path: List<StrokePoint>): Float {
        val sorted = ideal.map { distanceToPath(it, path) }.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * The worst-case distance from any point of [ideal] to the nearest point on [output] — "how far
     * did the truth stray from what we drew", the reverse of [distanceToPath]'s "how far did we stray
     * from what we captured". The forward direction (used by "stays close to the captured path" below)
     * cannot detect a collapsed loop: simplification that eats a loop leaves its output comfortably
     * near the *input* path the whole time, since RDP only ever removes points, it never moves the
     * survivors away from the original path. Only measuring outward from the shape the hand meant
     * catches over-smoothing.
     */
    private fun maxDistanceToPath(ideal: List<StrokePoint>, output: List<StrokePoint>): Float =
        ideal.maxOf { distanceToPath(it, output) }

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

    /**
     * Median distance of each interior point of [path] to the segment joining the neighbours roughly
     * [neighborArcPx] of arc length away on either side — a proxy for high-frequency wiggle that a
     * shape feature this size or larger cannot produce but leftover tremor can. The same metric
     * `SmoothingCalibrationReport` computes on real strokes. Arc length, not index offset, so it means
     * the same thing regardless of how densely [path] happens to be sampled.
     */
    private fun highFrequencyDeviationPx(path: List<StrokePoint>, neighborArcPx: Float = 4f): Float {
        if (path.size < 3) return 0f
        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        }
        val half = neighborArcPx / 2.0
        val deviations = ArrayList<Float>()
        for (i in 1 until path.size - 1) {
            var before = i
            while (before > 0 && cumulative[i] - cumulative[before - 1] < half) before--
            var after = i
            while (after < path.size - 1 && cumulative[after + 1] - cumulative[i] < half) after++
            // A capture sparser than the window would otherwise find no neighbour inside it and be
            // reported as perfectly smooth; one sample either side is the shortest real chord.
            if (before == i) before = i - 1
            if (after == i) after = i + 1
            deviations.add(distanceToSegment(path[i], path[before], path[after]))
        }
        if (deviations.isEmpty()) return 0f
        deviations.sort()
        val mid = deviations.size / 2
        return if (deviations.size % 2 == 0) (deviations[mid - 1] + deviations[mid]) / 2f else deviations[mid]
    }

    private fun boundingBoxPx(points: List<StrokePoint>): Pair<Float, Float> {
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        return width to height
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
        allLevels.forEach { level -> assertSame("level $level", tap, smoothStroke(tap, level)) }
    }

    @Test
    fun `a dot whose samples all land on one spot is returned unchanged`() {
        // Enough samples to pass the count check, but only one distinct position between them.
        val dot = pointsOf(10f to 10f, 10f to 10f, 10f to 10f, 10f to 10f, 10f to 10f)
        allLevels.forEach { level -> assertSame("level $level", dot, smoothStroke(dot, level)) }
    }

    @Test
    fun `the first and last points survive smoothing exactly`() {
        val points = arc(count = 40, radius = 90f)
        allLevels.forEach { level ->
            val smoothed = smoothStroke(points, level)
            assertEquals("level $level", points.first(), smoothed.first())
            assertEquals("level $level", points.last(), smoothed.last())
        }
    }

    @Test
    fun `the stroke keeps its total duration, which tap classification reads`() {
        val points = arc(count = 40, radius = 90f)
        allLevels.forEach { level ->
            val smoothed = smoothStroke(points, level)
            assertEquals("level $level", points.last().timestampDelta, smoothed.last().timestampDelta)
        }
    }

    @Test
    fun `timestamps stay ordered through smoothing`() {
        allLevels.forEach { level ->
            val smoothed = smoothStroke(arc(count = 40, radius = 90f), level)
            smoothed.zipWithNext { earlier, later ->
                assertTrue(
                    "level $level: timestamps went backwards: ${earlier.timestampDelta} then ${later.timestampDelta}",
                    later.timestampDelta >= earlier.timestampDelta,
                )
            }
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
        allLevels.forEach { level ->
            smoothStroke(varying, level).forEach {
                assertTrue("level $level: pressure out of range: ${it.pressure}", it.pressure in 0f..1f)
            }
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
        allLevels.forEach { level ->
            smoothStroke(resting, level).forEach {
                assertTrue("level $level: non-finite point: $it", it.x.isFinite() && it.y.isFinite())
            }
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
        val caps = mapOf(SmoothingLevel.AUTO to 48f, SmoothingLevel.LIGHT to 48f, SmoothingLevel.STRONG to 48f)
        for ((level, cap) in caps) {
            val smoothed = smoothStroke(points, level)
            smoothed.zipWithNext { a, b ->
                val gap = hypot(a.x - b.x, a.y - b.y)
                assertTrue("$level knot gap ${gap}px exceeds its ${cap}px cap", gap <= cap + 1e-3f)
            }
        }
    }

    // --- AUTO: tuning derived from the stroke ---------------------------------------------------
    //
    // These measure error against the *ideal* glyph, not the jittered input: the fidelity tests
    // above measure closeness to noise (does the output stay near what we captured), which cannot
    // tell a good smooth from a loop eaten alive. These measure recovery of truth instead (does the
    // output stay near what the hand meant), via maxDistanceToPath's reverse direction.

    @Test
    fun `AUTO keeps a small letter-sized loop's counter`() {
        // At a 20px shape scale AUTO's sigma is min(10, 0.2*20) = 4px (LIGHT's own sigma) and its
        // epsilon is (0.03*20).coerceIn(0.45, 3) = 0.6px — both near their gentlest — so the loop's
        // counter should survive rather than being smoothed or simplified shut.
        val ideal = idealCurve(20f)
        val captured = withJitter(smallGlyph(20f, SMALL_LETTER_SPACING_PX), amplitudePx = DEVICE_TREMOR_PX, seed = 2)
        val smoothed = smoothStroke(captured, SmoothingLevel.AUTO)
        val lostDetail = maxDistanceToPath(ideal, smoothed)
        assertTrue("lost detail: ${lostDetail}px", lostDetail <= 2.0f)
    }

    @Test
    fun `AUTO smooths a large wobbly stroke about as much as STRONG`() {
        // At this shape scale both of AUTO's terms are pinned at STRONG's own values (sigma at
        // STRONG_SIGMA_PX, epsilon at STRONG_EPSILON_PX — see the sigma-cap test below), so the two
        // levels should leave about the same residual tremor behind.
        val captured = withJitter(smallGlyph(160f, LARGE_SWEEP_SPACING_PX), amplitudePx = DEVICE_TREMOR_PX, seed = 3)
        val auto = highFrequencyDeviationPx(smoothStroke(captured, SmoothingLevel.AUTO))
        val strong = highFrequencyDeviationPx(smoothStroke(captured, SmoothingLevel.STRONG))
        assertTrue(
            "AUTO's residual tremor ${auto}px should be close to STRONG's ${strong}px",
            auto <= strong * 1.5f + 0.05f,
        )
    }

    @Test
    fun `AUTO's sigma never exceeds STRONG's`() {
        // A shape large enough that SIGMA_FRACTION_OF_SHAPE * shape would run well past STRONG's
        // sigma if the cap did not bind.
        val sigma = autoSigmaPx(arc(count = 40, radius = 5000f), sigmaFractionOfShape = 0.2f)
        assertTrue("AUTO's sigma ${sigma}px exceeded STRONG's 10px cap", sigma <= 10f)
    }

    @Test
    fun `AUTO leaves a jittery dot its size`() {
        // A pen held nearly still: six distinct positions, all within a few pixels of each other —
        // smaller than MIN_SHAPE_SCALE_PX, so the floored shape keeps both sigma and epsilon tiny,
        // which should leave the dot's own spread intact rather than collapsing it toward a point.
        val dot = pointsOf(
            10f to 10f, 13f to 11f, 9f to 14f, 14f to 9f, 11f to 15f, 12f to 10f,
        )
        val (widthBefore, heightBefore) = boundingBoxPx(dot)
        val smoothed = smoothStroke(dot, SmoothingLevel.AUTO)
        val (widthAfter, heightAfter) = boundingBoxPx(smoothed)
        assertTrue("width shrank from $widthBefore to $widthAfter", widthBefore - widthAfter < 1f)
        assertTrue("height shrank from $heightBefore to $heightAfter", heightBefore - heightAfter < 1f)
    }

    @Test
    fun `AUTO does not add points beyond what was captured`() {
        // simplify only ever removes points, and splitLongSpans only ever re-inserts ones already in
        // the (same-sized) smoothed path, so the knot list can never grow past the de-duplicated
        // input — regardless of how densely the stroke happened to be sampled. A slow pen sampled at
        // the digitizer's native rate over a short, straight travel is the densest realistic case.
        val slow = (0 until 2000).map { i ->
            val t = i.toFloat() / 1999
            StrokePoint(x = t * 50f, y = 0f, pressure = 1f, timestampDelta = i * 5L)
        }
        val smoothed = smoothStroke(slow, SmoothingLevel.AUTO)
        assertTrue("expected at most ${slow.size} points, got ${smoothed.size}", smoothed.size <= slow.size)
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
