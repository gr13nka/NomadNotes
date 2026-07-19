package com.nomadnotes.core

/**
 * A single sampled point of a pen stroke, in the shared vocabulary used across modules.
 *
 * This is the device-neutral hand-off type: input backends (the Onyx pen SDK, or a plain
 * touch fallback) translate their own point types into [StrokePoint] so that no module above
 * the input layer has to know which backend produced the stroke.
 *
 * @property x horizontal position in the drawing surface's pixels.
 * @property y vertical position in the drawing surface's pixels.
 * @property pressure raw stylus pressure as reported by the source; not normalised here
 *   because the usable range is device-specific.
 * @property timestampDelta milliseconds elapsed since the first point of the same stroke,
 *   so a stroke's timing is self-contained and independent of any absolute clock.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampDelta: Long,
)
