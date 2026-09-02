package com.nomadnotes.core.geometry

import com.nomadnotes.core.PageRect
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import com.nomadnotes.core.StrokePoint
import com.nomadnotes.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the eraser and lasso hit-testing functions. */
class GeometryTest {

    private fun strokeOf(id: String, vararg xy: Pair<Float, Float>) = Stroke(
        id = StrokeId(id),
        tool = Tool.PEN,
        widthBase = 1f,
        grayLevel = 255,
        points = xy.map { (x, y) -> StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = 0L) },
    )

    // --- eraserHit -------------------------------------------------------------------------

    @Test
    fun `eraser hits when it touches a segment between the sampled points`() {
        val stroke = strokeOf("s", 0f to 0f, 10f to 0f)
        // Nearest point on the segment is (5,0); distance 3 <= radius 4.
        assertTrue(eraserHit(stroke, Vec2(5f, 3f), radius = 4f))
    }

    @Test
    fun `eraser misses when every segment is farther than the radius`() {
        val stroke = strokeOf("s", 0f to 0f, 10f to 0f)
        assertFalse(eraserHit(stroke, Vec2(5f, 20f), radius = 4f))
    }

    @Test
    fun `eraser grazing an endpoint at exactly the radius counts as a hit`() {
        val stroke = strokeOf("s", 0f to 0f, 10f to 0f)
        // Distance from (0,5) to the nearest point (0,0) is exactly the radius.
        assertTrue(eraserHit(stroke, Vec2(0f, 5f), radius = 5f))
    }

    @Test
    fun `eraser tests a single-point stroke as that point`() {
        val stroke = strokeOf("s", 4f to 4f)
        assertTrue(eraserHit(stroke, Vec2(6f, 4f), radius = 3f))
        assertFalse(eraserHit(stroke, Vec2(20f, 4f), radius = 3f))
    }

    @Test
    fun `eraser never hits a stroke with no points`() {
        val stroke = strokeOf("s")
        assertFalse(eraserHit(stroke, Vec2(0f, 0f), radius = 1000f))
    }

    // --- lassoSelect -----------------------------------------------------------------------

    private val square = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 10f), Vec2(0f, 10f))

    @Test
    fun `lasso selects a stroke whose every point is inside the region`() {
        val stroke = strokeOf("in", 2f to 2f, 5f to 5f, 8f to 8f)
        assertEquals(listOf(StrokeId("in")), lassoSelect(listOf(stroke), square))
    }

    @Test
    fun `lasso does not select a stroke that only partially overlaps the region`() {
        val stroke = strokeOf("cross", 5f to 5f, 15f to 5f)
        assertEquals(emptyList<StrokeId>(), lassoSelect(listOf(stroke), square))
    }

    @Test
    fun `lasso does not select a stroke wholly outside the region`() {
        val stroke = strokeOf("out", 20f to 20f, 30f to 30f)
        assertEquals(emptyList<StrokeId>(), lassoSelect(listOf(stroke), square))
    }

    @Test
    fun `lasso selects only the fully-enclosed strokes, in input order`() {
        val inside = strokeOf("in", 2f to 2f)
        val crossing = strokeOf("cross", 5f to 5f, 50f to 50f)
        val alsoInside = strokeOf("in2", 6f to 6f, 7f to 7f)
        val selected = lassoSelect(listOf(inside, crossing, alsoInside), square)
        assertEquals(listOf(StrokeId("in"), StrokeId("in2")), selected)
    }

    @Test
    fun `lasso closes an open polygon implicitly`() {
        // The square vertices do not repeat the first point; the closing edge must be inferred
        // for (5,5) to read as inside.
        val stroke = strokeOf("in", 5f to 5f)
        assertEquals(listOf(StrokeId("in")), lassoSelect(listOf(stroke), square))
    }

    @Test
    fun `lasso encloses nothing with fewer than three vertices`() {
        val stroke = strokeOf("in", 5f to 5f)
        val degenerate = listOf(Vec2(0f, 0f), Vec2(10f, 10f))
        assertEquals(emptyList<StrokeId>(), lassoSelect(listOf(stroke), degenerate))
    }

    @Test
    fun `lasso never selects an empty stroke`() {
        val empty = strokeOf("empty")
        assertEquals(emptyList<StrokeId>(), lassoSelect(listOf(empty), square))
    }

    // --- lassoCoversRegion -----------------------------------------------------------------

    @Test
    fun `lassoCoversRegion is true when the region center is inside the polygon`() {
        // The square encloses the region, so its centre (5,5) reads as circled.
        val region = PageRect(3f, 3f, 7f, 7f)
        assertTrue(lassoCoversRegion(region, square))
    }

    @Test
    fun `lassoCoversRegion is false when the polygon only clips a corner`() {
        // The square overlaps the region's lower-left corner, but the region's centre (14,14)
        // is outside the square, so the link is not circled.
        val region = PageRect(8f, 8f, 20f, 20f)
        assertFalse(lassoCoversRegion(region, square))
    }

    @Test
    fun `lassoCoversRegion is false for a polygon with fewer than three vertices`() {
        val region = PageRect(3f, 3f, 7f, 7f)
        val degenerate = listOf(Vec2(0f, 0f), Vec2(10f, 10f))
        assertFalse(lassoCoversRegion(region, degenerate))
    }

    // --- pointInPolygon --------------------------------------------------------------------

    @Test
    fun `pointInPolygon is true for a point well inside the polygon`() {
        assertTrue(pointInPolygon(5f, 5f, square))
    }

    @Test
    fun `pointInPolygon is false for a point outside the polygon`() {
        assertFalse(pointInPolygon(15f, 5f, square))
    }

    @Test
    fun `pointInPolygon treats edges as half-open`() {
        // Ray-casting containment is half-open: a point on the polygon's lower-y edge counts as
        // inside, while the upper-y edge counts as outside. This pins the boundary behaviour
        // inherited from lassoSelect; exact-boundary and on-vertex cases stay unspecified.
        assertTrue(pointInPolygon(5f, 0f, square)) // y = 0 edge: inside
        assertFalse(pointInPolygon(5f, 10f, square)) // y = 10 edge: outside
    }
}
