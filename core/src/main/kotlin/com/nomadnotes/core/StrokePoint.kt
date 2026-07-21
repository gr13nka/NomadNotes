package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * A single sampled point of a pen stroke, in the shared vocabulary used across modules.
 *
 * This is the device-neutral hand-off type: input backends (the Onyx pen SDK, or a plain
 * touch fallback) translate their own point types into [StrokePoint] so that no module above
 * the input layer has to know which backend produced the stroke.
 *
 * @property x horizontal position in the drawing surface's pixels.
 * @property y vertical position in the drawing surface's pixels.
 * @property pressure normalized 0..1 (0 = lightest, 1 = max hardware pressure); input backends
 *   normalize device values before constructing StrokePoint.
 * @property timestampDelta milliseconds elapsed since the first point of the same stroke,
 *   so a stroke's timing is self-contained and independent of any absolute clock.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampDelta: Long,
)
