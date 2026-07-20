package com.nomadnotes.core.geometry

import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokeId
import kotlin.math.sqrt

/**
 * Pure hit-testing over strokes: no model is mutated, results are just booleans and id lists.
 *
 * The two entry points implement the two selection gestures of the app:
 *  - [eraserHit] — the stroke-eraser: does a circular eraser touch a stroke at all?
 *  - [lassoSelect] — the lasso: which strokes lie *entirely* within a drawn region?
 */

/**
 * True if any part of [stroke] lies within [radius] of [center] — the stroke-eraser's
 * "does this stroke get erased" test.
 *
 * The stroke is treated as the polyline through its points, so a hit anywhere along a segment
 * counts, not only at the sampled points. Distance is measured to the nearest point of each
 * segment and compared inclusively (a stroke grazing the eraser rim at exactly [radius] hits).
 *
 * Degenerate strokes are handled: a one-point stroke is tested as that point, and a stroke with
 * no points can never be hit.
 */
fun eraserHit(stroke: Stroke, center: Vec2, radius: Float): Boolean {
    val points = stroke.points
    if (points.isEmpty()) return false
    if (points.size == 1) {
        val only = points[0]
        return distance(center.x, center.y, only.x, only.y) <= radius
    }
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (distanceToSegment(center, a.x, a.y, b.x, b.y) <= radius) return true
    }
    return false
}

/**
 * The ids of every stroke in [strokes] that is fully enclosed by [polygon] — the lasso's
 * Supernote-style semantics: a stroke is selected only when *all* of its points are inside the
 * region, so a stroke crossing the lasso boundary is left unselected.
 *
 * [polygon] is treated as a closed loop: the edge from its last vertex back to its first is
 * always included, so a caller need not repeat the first vertex to close the shape. A stroke
 * with no points is never selected (it has no geometry to enclose), and a polygon with fewer
 * than three vertices encloses nothing.
 *
 * Order of the returned ids follows the order of [strokes].
 */
fun lassoSelect(strokes: List<Stroke>, polygon: List<Vec2>): List<StrokeId> {
    if (polygon.size < 3) return emptyList()
    return strokes
        .filter { stroke ->
            stroke.points.isNotEmpty() &&
                stroke.points.all { pointInPolygon(it.x, it.y, polygon) }
        }
        .map { it.id }
}

/**
 * Shortest distance from point `p` to the segment `a`-`b`. When the segment is degenerate
 * (`a` == `b`) this is just the distance to that shared endpoint.
 */
private fun distanceToSegment(p: Vec2, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val abx = bx - ax
    val aby = by - ay
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared == 0f) {
        return distance(p.x, p.y, ax, ay)
    }
    // Project p onto the infinite line a-b, then clamp the parameter to the segment.
    val t = (((p.x - ax) * abx) + ((p.y - ay) * aby)) / lengthSquared
    val clamped = t.coerceIn(0f, 1f)
    val closestX = ax + clamped * abx
    val closestY = ay + clamped * aby
    return distance(p.x, p.y, closestX, closestY)
}

/**
 * Even-odd ray-casting containment: casts a ray from the point and counts polygon edge
 * crossings. The polygon is implicitly closed by pairing each vertex with the previous one,
 * starting from the last, so the first-to-last edge is included without the caller repeating a
 * vertex.
 */
private fun pointInPolygon(px: Float, py: Float, polygon: List<Vec2>): Boolean {
    var inside = false
    var previous = polygon.size - 1
    for (current in polygon.indices) {
        val cx = polygon[current].x
        val cy = polygon[current].y
        val vx = polygon[previous].x
        val vy = polygon[previous].y
        if (((cy > py) != (vy > py)) &&
            (px < (vx - cx) * (py - cy) / (vy - cy) + cx)
        ) {
            inside = !inside
        }
        previous = current
    }
    return inside
}

private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}
