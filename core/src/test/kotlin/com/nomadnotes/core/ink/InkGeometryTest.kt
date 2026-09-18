package com.nomadnotes.core.ink

import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry tests for the curve fit ([inkCurve]) and the tapered outline built on top of it
 * ([inkOutline], [dotRadius]) — the pure math [smoothStroke]'s knots are rendered through.
 */
class InkGeometryTest {

    private fun pointsOf(vararg xy: Pair<Float, Float>): List<StrokePoint> =
        xy.mapIndexed { index, (x, y) ->
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = index * 10L)
        }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    /** Samples a cubic finely, as plain points, for a "how far from the curve" check. */
    private fun sampleCurve(segments: List<CubicSegment>, stepsPerSegment: Int): List<StrokePoint> =
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

    /** The half-width [inkOutline] gave the vertex nearest [x] on the "+" or "-" offset side. */
    private fun maxHalfWidthNear(ring: FloatArray, xRange: ClosedFloatingPointRange<Float>): Float =
        (0 until ring.size step 2)
            .filter { ring[it] in xRange }
            .maxOf { abs(ring[it + 1]) }

    // --- inkCurve -------------------------------------------------------------------------------

    @Test
    fun `cubics start and end on their knots`() {
        val knots = pointsOf(0f to 0f, 10f to 4f, 25f to -6f, 40f to 10f, 55f to 0f)
        val segments = inkCurve(knots)
        assertEquals(knots.size - 1, segments.size)
        segments.forEachIndexed { i, seg ->
            assertEquals(knots[i].x, seg.startX, 1e-4f)
            assertEquals(knots[i].y, seg.startY, 1e-4f)
            assertEquals(knots[i + 1].x, seg.endX, 1e-4f)
            assertEquals(knots[i + 1].y, seg.endY, 1e-4f)
        }
    }

    @Test
    fun `tangents point the same direction at interior knots`() {
        val knots = pointsOf(0f to 0f, 10f to 4f, 25f to -6f, 40f to 10f, 55f to 0f)
        val segments = inkCurve(knots)
        // The closed-form centripetal fit is only C1 continuous when the knots either side of the
        // shared one happen to be evenly spaced (see the uniform-spacing test); in general it
        // guarantees just G1 — the two tangent vectors, read from either side, are parallel and
        // point the same way, even though they can differ in length.
        for (i in 0 until segments.size - 1) {
            val incoming = segments[i]
            val outgoing = segments[i + 1]
            val outX = incoming.endX - incoming.c2x
            val outY = incoming.endY - incoming.c2y
            val inX = outgoing.c1x - outgoing.startX
            val inY = outgoing.c1y - outgoing.startY
            assertEquals("tangents aren't parallel at a shared knot", 0f, outX * inY - outY * inX, 1e-2f)
            assertTrue("tangents point opposite ways at a shared knot", outX * inX + outY * inY > 0f)
        }
    }

    @Test
    fun `collinear knots give collinear controls`() {
        // Non-uniformly spaced along the line y = 2x + 3, so this can't be the uniform special
        // case below in disguise.
        val knots = pointsOf(0f to 3f, 4f to 11f, 9f to 21f, 20f to 43f)
        inkCurve(knots).forEach { seg ->
            assertEquals(2f * seg.c1x + 3f, seg.c1y, 1e-2f)
            assertEquals(2f * seg.c2x + 3f, seg.c2y, 1e-2f)
        }
    }

    @Test
    fun `uniform spacing gives the classic one-sixth controls`() {
        // Four points equally spaced around a circle: equal chord length without collinearity.
        val radius = 50f
        val knots = (0..3).map { i ->
            val angle = Math.toRadians(40.0 * i).toFloat()
            StrokePoint(x = radius * cos(angle), y = radius * sin(angle), pressure = 1f, timestampDelta = i * 10L)
        }
        // Segment 1 (knots[1]..knots[2]) has a real knot on each side, so the closed form should
        // reduce to the textbook C1 = P1 + (P2-P0)/6, C2 = P2 - (P3-P1)/6.
        val seg = inkCurve(knots)[1]
        assertEquals(knots[1].x + (knots[2].x - knots[0].x) / 6f, seg.c1x, 1e-2f)
        assertEquals(knots[1].y + (knots[2].y - knots[0].y) / 6f, seg.c1y, 1e-2f)
        assertEquals(knots[2].x - (knots[3].x - knots[1].x) / 6f, seg.c2x, 1e-2f)
        assertEquals(knots[2].y - (knots[3].y - knots[1].y) / 6f, seg.c2y, 1e-2f)
    }

    @Test
    fun `repeated positions stay finite`() {
        val knots = pointsOf(0f to 0f, 0f to 0f, 10f to 2f, 10f to 2f, 20f to 0f, 30f to 3f)
        inkCurve(knots).forEach { seg ->
            listOf(seg.startX, seg.startY, seg.c1x, seg.c1y, seg.c2x, seg.c2y, seg.endX, seg.endY)
                .forEach { assertTrue("non-finite control value: $it", it.isFinite()) }
        }
    }

    @Test
    fun `fewer than 2 distinct points gives an empty curve`() {
        assertTrue(inkCurve(emptyList()).isEmpty())
        assertTrue(inkCurve(pointsOf(5f to 5f)).isEmpty())
        assertTrue(inkCurve(pointsOf(5f to 5f, 5f to 5f, 5f to 5f)).isEmpty())
    }

    // --- inkOutline -----------------------------------------------------------------------------

    @Test
    fun `the ring's ends are round caps standing off the stroke endpoints`() {
        val points = pointsOf(0f to 0f, 20f to 5f, 45f to -5f, 70f to 0f, 100f to 8f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val start = points.first()
        val end = points.last()
        val distancesToStart = (0 until ring.size step 2).map { distance(ring[it], ring[it + 1], start.x, start.y) }
        val distancesToEnd = (0 until ring.size step 2).map { distance(ring[it], ring[it + 1], end.x, end.y) }
        val nearestToStart = distancesToStart.min()
        val nearestToEnd = distancesToEnd.min()
        // A sharp tip would put a vertex exactly at the endpoint (distance 0); a round cap instead
        // stands off by its half-width, and several vertices should sit at about that same distance
        // (an arc), not just one (a point).
        assertTrue("expected the cap to stand off the start, not sit at it", nearestToStart > 0.5f)
        assertTrue("expected the cap to stand off the end, not sit at it", nearestToEnd > 0.5f)
        val nearStartCapVertices = distancesToStart.count { it <= nearestToStart + 0.2f }
        val nearEndCapVertices = distancesToEnd.count { it <= nearestToEnd + 0.2f }
        assertTrue("expected several cap vertices near the start, got $nearStartCapVertices", nearStartCapVertices >= 4)
        assertTrue("expected several cap vertices near the end, got $nearEndCapVertices", nearEndCapVertices >= 4)
    }

    @Test
    fun `every ring vertex lies within maxWidth over 2 of the centreline`() {
        val points = pointsOf(0f to 0f, 20f to 15f, 50f to -10f, 80f to 20f, 110f to 0f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 8f)
        val ring = inkOutline(points, nib)
        val curveSamples = sampleCurve(inkCurve(points), stepsPerSegment = 40)
        val limit = nib.maxWidth / 2f + 0.25f // slack for the two independent samplings not aligning exactly
        for (i in 0 until ring.size step 2) {
            val nearest = curveSamples.minOf { distance(ring[i], ring[i + 1], it.x, it.y) }
            assertTrue("ring vertex strayed ${nearest}px from the centreline (limit ${limit}px)", nearest <= limit)
        }
    }

    @Test
    fun `a slow stretch is wider than a fast one at equal pressure`() {
        val points = buildList {
            for (i in 0..10) add(StrokePoint(x = i * 15f, y = 0f, pressure = 1f, timestampDelta = i * 100L))
            for (i in 11..20) {
                add(StrokePoint(x = i * 15f, y = 0f, pressure = 1f, timestampDelta = 1000L + (i - 10) * 3L))
            }
        }
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val slow = maxHalfWidthNear(ring, 40f..110f)
        val fast = maxHalfWidthNear(ring, 190f..260f)
        assertTrue("the slow stretch ($slow) should be wider than the fast one ($fast)", slow > fast)
    }

    @Test
    fun `higher pressure is wider at equal speed`() {
        val points = buildList {
            for (i in 0..10) add(StrokePoint(x = i * 15f, y = 0f, pressure = 0.2f, timestampDelta = i * 20L))
            for (i in 11..20) add(StrokePoint(x = i * 15f, y = 0f, pressure = 0.9f, timestampDelta = i * 20L))
        }
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val lowPressure = maxHalfWidthNear(ring, 40f..110f)
        val highPressure = maxHalfWidthNear(ring, 190f..260f)
        assertTrue(
            "higher pressure ($highPressure) should be wider than lower pressure ($lowPressure)",
            highPressure > lowPressure,
        )
    }

    @Test
    fun `constant pressure falls back to speed regardless of the nib's speedWeight`() {
        val points = buildList {
            for (i in 0..10) add(StrokePoint(x = i * 15f, y = 0f, pressure = 0.5f, timestampDelta = i * 100L))
            for (i in 11..20) {
                add(StrokePoint(x = i * 15f, y = 0f, pressure = 0.5f, timestampDelta = 1000L + (i - 10) * 3L))
            }
        }
        val speedHeavy = NibProfile(
            maxWidth = 6f, minWidthFactor = 0.35f, speedWeight = 0.6f,
            slowPxPerMs = 0.25f, fastPxPerMs = 2.5f, taperFraction = 0.18f,
        )
        val pressureHeavy = speedHeavy.copy(speedWeight = 0f)
        val ringA = inkOutline(points, speedHeavy)
        val ringB = inkOutline(points, pressureHeavy)
        assertEquals(ringA.size, ringB.size)
        ringA.indices.forEach { i -> assertEquals(ringA[i], ringB[i], 1e-3f) }
    }

    @Test
    fun `degenerate timestamps fall back to pressure and stay finite`() {
        val points = pointsOf(0f to 0f, 20f to 0f, 40f to 0f, 60f to 0f, 80f to 0f).mapIndexed { i, p ->
            p.copy(pressure = if (i < 3) 0.2f else 0.9f, timestampDelta = 0L) // every dt is 0
        }
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        ring.forEach { assertTrue("non-finite ring coordinate: $it", it.isFinite()) }
        val lowPressure = maxHalfWidthNear(ring, 15f..25f)
        val highPressure = maxHalfWidthNear(ring, 55f..65f)
        assertTrue(
            "expected pressure to drive width once timing is degenerate: low=$lowPressure high=$highPressure",
            highPressure > lowPressure,
        )
    }

    @Test
    fun `the ends taper narrower than the middle`() {
        val points = pointsOf(0f to 0f, 40f to 0f, 80f to 0f, 120f to 0f, 160f to 0f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val nearStart = maxHalfWidthNear(ring, 2f..8f)
        val middle = maxHalfWidthNear(ring, 76f..84f)
        assertTrue("expected the taper near the start ($nearStart) to be narrower than the middle ($middle)", nearStart < middle)
    }

    @Test
    fun `end half-width is at least END_WIDTH_FACTOR of the adjacent body width`() {
        val points = pointsOf(0f to 0f, 40f to 0f, 80f to 0f, 120f to 0f, 160f to 0f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val nearStart = maxHalfWidthNear(ring, -1f..1f)
        val middle = maxHalfWidthNear(ring, 76f..84f)
        // Matches InkGeometry's private END_WIDTH_FACTOR: the taper stops at 60% of the body width
        // instead of reaching zero, so a PEN stroke ends in a soft point, not a needle.
        assertTrue("expected the end ($nearStart) to be at least 0.6x the body width ($middle)", nearStart >= 0.6f * middle - 0.05f)
    }

    @Test
    fun `pendingEnd suppresses the trailing taper`() {
        val points = pointsOf(0f to 0f, 40f to 0f, 80f to 0f, 120f to 0f, 160f to 0f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val finished = maxHalfWidthNear(inkOutline(points, nib, pendingEnd = false), 152f..158f)
        val pending = maxHalfWidthNear(inkOutline(points, nib, pendingEnd = true), 152f..158f)
        assertTrue("pendingEnd should widen the trailing edge ($pending vs finished $finished)", pending > finished)
    }

    @Test
    fun `the width floor holds everywhere, including the tips`() {
        val points = pointsOf(0f to 0f, 40f to 0f, 80f to 0f, 120f to 0f, 160f to 0f)
        // Even at full drive this nib's width (0.5px) sits under the 0.6px floor, so the floor must
        // clamp both the middle AND the tapered tips now that the taper never reaches zero either.
        val nib = NibProfile(
            maxWidth = 0.5f, minWidthFactor = 0.35f, speedWeight = 0f,
            slowPxPerMs = 0.25f, fastPxPerMs = 2.5f, taperFraction = 0.18f,
        )
        val ring = inkOutline(points, nib)
        val middle = maxHalfWidthNear(ring, 76f..84f)
        assertEquals(0.3f, middle, 1e-3f)
        val nearestToStart = (0 until ring.size step 2).minOf { distance(ring[it], ring[it + 1], 0f, 0f) }
        val nearestToEnd = (0 until ring.size step 2).minOf { distance(ring[it], ring[it + 1], 160f, 0f) }
        assertEquals("tip half-width should floor the same as the middle", 0.3f, nearestToStart, 1e-2f)
        assertEquals("tip half-width should floor the same as the middle", 0.3f, nearestToEnd, 1e-2f)
    }

    @Test
    fun `a 2-point stroke gives a valid ring`() {
        val points = pointsOf(0f to 0f, 1f to 0.5f) // shorter than one flatten step
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 4f)
        val ring = inkOutline(points, nib)
        assertTrue("expected at least a triangle, got ${ring.size / 2} vertices", ring.size >= 6)
        ring.forEach { assertTrue("non-finite ring coordinate: $it", it.isFinite()) }
    }

    @Test
    fun `widthBase 0 stays finite`() {
        val points = pointsOf(0f to 0f, 20f to 5f, 45f to -5f, 70f to 0f)
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 0f)
        inkOutline(points, nib).forEach { assertTrue("non-finite ring coordinate: $it", it.isFinite()) }
    }

    // --- dotRadius ------------------------------------------------------------------------------

    @Test
    fun `dotRadius scales with pressure for PEN and stays constant for MARKER`() {
        val penNib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val lightTap = StrokePoint(x = 0f, y = 0f, pressure = 0.1f, timestampDelta = 0L)
        val hardTap = StrokePoint(x = 0f, y = 0f, pressure = 0.9f, timestampDelta = 0L)
        assertTrue(dotRadius(hardTap, penNib) > dotRadius(lightTap, penNib))

        val markerNib = NibProfile.forTool(Tool.MARKER, widthBase = 6f)
        assertEquals(dotRadius(lightTap, markerNib), dotRadius(hardTap, markerNib), 1e-4f)
        assertEquals(markerNib.maxWidth / 2f, dotRadius(lightTap, markerNib), 1e-4f)
    }

    @Test
    fun `dotRadius uses the measured factor over pressure when present`() {
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        // Pressure alone would call this a hard tap (wide); the measured factor says otherwise.
        val point = StrokePoint(x = 0f, y = 0f, pressure = 0.9f, timestampDelta = 0L, nibFactor = 0.2f)
        assertEquals(nib.maxWidth * 0.2f / 2f, dotRadius(point, nib), 1e-4f)
    }

    // --- inkOutline with a measured nibFactor ----------------------------------------------------

    private fun pointsWithFactor(vararg xyf: Triple<Float, Float, Float>): List<StrokePoint> =
        xyf.mapIndexed { index, (x, y, factor) ->
            StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = index * 10L, nibFactor = factor)
        }

    @Test
    fun `a measured factor sets the width directly, ignoring pressure and speed`() {
        // Rising pressure alone would widen towards the end under the drive/taper law; the factor
        // says the opposite. If the factor wins, the ring must narrow, not widen, along the stroke.
        val points = listOf(0f, 40f, 80f, 120f, 160f).mapIndexed { i, x ->
            StrokePoint(x = x, y = 0f, pressure = i / 4f, timestampDelta = i * 100L, nibFactor = 1f - i / 4f * 0.8f)
        }
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 6f)
        val ring = inkOutline(points, nib)
        val start = maxHalfWidthNear(ring, 2f..8f)
        val end = maxHalfWidthNear(ring, 152f..158f)
        assertTrue("expected the factor-driven start ($start) wider than the end ($end)", start > end)
    }

    @Test
    fun `a measured factor scales linearly off maxWidth, not the pressure-driven law`() {
        val points = pointsWithFactor(
            Triple(0f, 0f, 0.2f),
            Triple(40f, 0f, 0.2f),
            Triple(80f, 0f, 0.2f),
            Triple(120f, 0f, 1.0f),
            Triple(160f, 0f, 1.0f),
        )
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 10f)
        val ring = inkOutline(points, nib)
        val narrowEnd = maxHalfWidthNear(ring, 2f..8f)
        val wideEnd = maxHalfWidthNear(ring, 152f..158f)
        assertEquals(nib.maxWidth * 0.2f / 2f, narrowEnd, 0.5f)
        assertEquals(nib.maxWidth * 1.0f / 2f, wideEnd, 0.5f)
    }

    @Test
    fun `a mix of measured and missing factors falls back to the pressure-speed law`() {
        val withFactor = pointsWithFactor(
            Triple(0f, 0f, 0.2f),
            Triple(40f, 0f, 0.2f),
            Triple(80f, 0f, 0.2f),
            Triple(120f, 0f, 1.0f),
            Triple(160f, 0f, 1.0f),
        )
        // Same positions/pressure/timing, but the last point never got a measured factor.
        val mixed = withFactor.mapIndexed { i, p -> if (i == withFactor.size - 1) p.copy(nibFactor = null) else p }
        val plain = withFactor.map { it.copy(nibFactor = null) }
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 10f)
        val ringMixed = inkOutline(mixed, nib)
        val ringPlain = inkOutline(plain, nib)
        assertEquals(ringPlain.size, ringMixed.size)
        ringMixed.indices.forEach { i -> assertEquals(ringPlain[i], ringMixed[i], 1e-3f) }
    }

    @Test
    fun `the width floor still holds under a near-zero measured factor`() {
        val points = pointsWithFactor(
            Triple(0f, 0f, 0.001f),
            Triple(40f, 0f, 0.001f),
            Triple(80f, 0f, 0.001f),
            Triple(120f, 0f, 0.001f),
            Triple(160f, 0f, 0.001f),
        )
        val nib = NibProfile.forTool(Tool.PEN, widthBase = 10f)
        val ring = inkOutline(points, nib)
        val middle = maxHalfWidthNear(ring, 76f..84f)
        assertEquals(0.3f, middle, 1e-3f) // WIDTH_FLOOR / 2
    }
}
