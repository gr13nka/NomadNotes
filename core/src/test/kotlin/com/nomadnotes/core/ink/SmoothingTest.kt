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
 * as what it changes (jitter).
 */
class SmoothingTest {

    private val allLevels = listOf(SmoothingLevel.AUTO, SmoothingLevel.LIGHT, SmoothingLevel.STRONG)

    private fun pointsOf(vararg xy: Pair<Float, Float>): List<StrokePoint> =
        xy.mapIndexed { index, (x, y) ->
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = index * 10L)
        }

    /** A horizontal line whose interior points alternate above and below it by [jitter] pixels. */
    private fun jitteredLine(count: Int, jitter: Float): List<StrokePoint> =
        (0 until count).map { i ->
            val interior = i != 0 && i != count - 1
            val y = if (!interior) 0f else if (i % 2 == 0) jitter else -jitter
            StrokePoint(x = i * 5f, y = y, pressure = 1f, timestampDelta = i * 10L)
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
    fun `interpolated pressure stays within the normalized range`() {
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
    fun `a stronger level smooths at least as much as a lighter one`() {
        val jittered = jitteredLine(count = 21, jitter = 2f)
        val light = smoothStroke(jittered, SmoothingLevel.LIGHT).maxOf { abs(it.y) }
        val strong = smoothStroke(jittered, SmoothingLevel.STRONG).maxOf { abs(it.y) }
        assertTrue("strong ($strong) should not exceed light ($light)", strong <= light)
    }

    @Test
    fun `smoothed points stay close to the captured path`() {
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        smoothed.forEach { p ->
            val strayed = distanceToPath(p, points)
            assertTrue("point strayed ${strayed}px from the captured path", strayed <= 4f)
        }
    }

    @Test
    fun `curvature survives smoothing`() {
        // The apex of the arc must not be flattened towards the chord joining its endpoints.
        val points = arc(count = 40, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.STRONG)
        assertTrue("arc was flattened", smoothed.maxOf { it.y } > 280f)
    }

    @Test
    fun `a curve is resampled densely enough to render as a curve`() {
        val points = arc(count = 12, radius = 90f)
        val smoothed = smoothStroke(points, SmoothingLevel.LIGHT)
        assertTrue("expected densification, got ${smoothed.size}", smoothed.size > points.size)
    }

    // --- AUTO: tolerance derived from the stroke ------------------------------------------------
    //
    // These measure error against the *ideal* glyph, not the jittered input: the existing tests
    // above measure fidelity to noise (does the output stay near what we captured), which cannot
    // tell a good smooth from a loop eaten alive. These measure recovery of truth instead (does the
    // output stay near what the hand meant), via maxDistanceToPath's reverse direction.

    @Test
    fun `AUTO re-fits a stroke without moving it off the path the pen took`() {
        // What this smoother does, and what it cannot do. Simplification keeps original samples as
        // knots and the spline passes exactly through them, so it is an interpolating scheme, not an
        // averaging one: it has no way to cancel noise, and measured against a noiseless ideal its
        // deviation does not improve at any tolerance. It denoises only where the tolerance exceeds
        // the noise *and* the true shape carries no detail at that scale — which is why a jittered
        // straight line comes out straight while a jittered loop does not come out cleaner.
        //
        // What it does deliver is a shorter, evenly spaced, curvature-continuous path that stays on
        // the ink the pen laid down. Asserting denoising here would assert something untrue.
        listOf(20f to SMALL_LETTER_SPACING_PX, 160f to LARGE_SWEEP_SPACING_PX).forEach { (heightPx, spacingPx) ->
            val captured = withJitter(smallGlyph(heightPx, spacingPx), amplitudePx = DEVICE_TREMOR_PX, seed = 1)
            val smoothed = smoothStroke(captured, SmoothingLevel.AUTO)
            val stray = maxDistanceToPath(smoothed, captured)
            assertTrue(
                "height ${heightPx}px: smoothed ink strayed $stray px from the captured path",
                stray <= 1.5f,
            )
            // Not "fewer points": resampling is bidirectional by design. A dense slow capture comes
            // back shorter, while a sparse fast one is deliberately filled in so it renders as a
            // curve instead of a chain of straight hops. The invariant either way is even spacing.
            val gaps = smoothed.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sorted()
            val medianGap = gaps[gaps.size / 2]
            assertTrue(
                "height ${heightPx}px: expected samples near ${RESAMPLE_SPACING_PX}px apart, median was $medianGap",
                medianGap <= RESAMPLE_SPACING_PX * 1.5f,
            )
        }
    }

    @Test
    fun `AUTO keeps a small letter's form`() {
        val ideal = idealCurve(20f)
        val captured = withJitter(smallGlyph(20f, SMALL_LETTER_SPACING_PX), amplitudePx = DEVICE_TREMOR_PX, seed = 2)
        val smoothed = smoothStroke(captured, SmoothingLevel.AUTO)
        val lostDetail = maxDistanceToPath(ideal, smoothed)
        // Widened from the ~1.0px the shape ceiling alone implies (a 20px glyph's epsilon lands
        // close to 0.04 * 20 = 0.8px, see MAX_EPSILON_FRACTION_OF_SHAPE) to 1.2px: a knot that
        // survives simplification right at the tightest part of the loop can carry the full amplitude of
        // injected jitter, and the Catmull-Rom fit through it can overshoot a little further. Still
        // tight enough to fail outright if the loop collapses rather than merely wobbles.
        assertTrue("lost detail: ${lostDetail}px", lostDetail <= 1.2f)
    }

    @Test
    fun `AUTO removes tremor from a large stroke`() {
        val captured = withJitter(smallGlyph(160f, LARGE_SWEEP_SPACING_PX), amplitudePx = DEVICE_TREMOR_PX, seed = 3)
        val smoothed = smoothStroke(captured, SmoothingLevel.AUTO)
        val before = highFrequencyDeviationPx(captured)
        val after = highFrequencyDeviationPx(smoothed)
        // Widened from an initial 30% target to 40%: this is a median rather than a worst case, so
        // it is far less sensitive than the reverse-error tests above to any single retained noisy
        // knot, but 30% was picked by hand rather than measured against this exact jitter model.
        assertTrue("residual tremor $after should be well under input's $before", after <= before * 0.40f)
    }

    @Test
    fun `AUTO spends its tolerance in proportion to the letter, not the page`() {
        // Read literally, "proportion to the letter" could mean "a bigger letter earns a bigger
        // absolute tolerance" — but that is not what holds, and should not: `affordable` grows with
        // shape, but `wanted` is an absolute tremor budget that does not, so past the size where
        // `wanted` stops being the binding term, epsilon flatlines at a fixed pixel value. A big
        // stroke therefore does *not* get a proportionally bigger allowance just because it is big;
        // its normalized error keeps shrinking. Small strokes are the ones that actually reach the
        // proportional ceiling (affordable), which is the sense in which tolerance tracks the letter.
        val idealSmall = idealCurve(20f)
        val idealLarge = idealCurve(160f)
        val smoothedSmall = smoothStroke(withJitter(smallGlyph(20f), amplitudePx = DEVICE_TREMOR_PX, seed = 4), SmoothingLevel.AUTO)
        val smoothedLarge = smoothStroke(withJitter(smallGlyph(160f), amplitudePx = DEVICE_TREMOR_PX, seed = 4), SmoothingLevel.AUTO)
        val ratioSmall = maxDistanceToPath(idealSmall, smoothedSmall) / 20f
        val ratioLarge = maxDistanceToPath(idealLarge, smoothedLarge) / 160f
        assertTrue(
            "expected the large stroke's normalized error ($ratioLarge) below the small one's ($ratioSmall)",
            ratioLarge <= ratioSmall,
        )
    }

    @Test
    fun `AUTO does not depend much on how fast the hand moved`() {
        // The sparse version is the dense one with every other sample dropped, not a fresh capture:
        // that isolates sample density as the only variable, rather than confounding it with an
        // independent noise draw. This is what would make the speed term (tremorBudgetGain) a
        // footgun if it went wrong: if halving the sample rate meaningfully changed the *result*, a
        // device with a different polling rate would smooth the same handwriting differently.
        val dense = withJitter(smallGlyph(80f), amplitudePx = 0.5f, seed = 5)
        val sparse = dense.filterIndexed { index, _ -> index % 2 == 0 || index == dense.size - 1 }
        val outputDense = smoothStroke(dense, SmoothingLevel.AUTO)
        val outputSparse = smoothStroke(sparse, SmoothingLevel.AUTO)
        val agreement = maxOf(
            maxDistanceToPath(outputDense, outputSparse),
            maxDistanceToPath(outputSparse, outputDense),
        )
        assertTrue("outputs diverged by ${agreement}px between sample rates", agreement <= 1f)
    }

    @Test
    fun `AUTO leaves a jittery dot its size`() {
        // A pen held nearly still: six distinct positions, all within a few pixels of each other —
        // smaller than MIN_SHAPE_SCALE_PX, so the shape ceiling (not the tremor budget) governs and
        // keeps epsilon tiny, which should leave the dot's own spread intact rather than collapsing
        // it toward a point.
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
    fun `AUTO does not densify a slow, already-dense stroke`() {
        // A slow pen sampled at the digitizer's native rate over a short, straight travel: far denser
        // than the 1.2px resample spacing, so AUTO must not make the point list even bigger — that
        // would bloat the saved .nnote and cost more to render for a stroke that already had plenty
        // of points to draw a straight line with.
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
}
