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
 * as what it changes (jitter).
 */
class SmoothingTest {

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
    fun `interpolated pressure stays within the normalized range`() {
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
