package com.nomadnotes.core.geometry

/**
 * A plain 2D point in page-pixel space, used as the input vocabulary for geometry.
 *
 * Geometry works in terms of bare coordinates, so callers convert their richer types — a
 * [com.nomadnotes.core.StrokePoint], an eraser centre, a lasso vertex — into [Vec2] at the
 * boundary. This keeps the geometry functions independent of the model's point type and its
 * pressure/timing fields, which do not affect where a point is.
 */
data class Vec2(val x: Float, val y: Float)
