package com.nomadnotes.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * @property nibFactor the nib's width at this point, as a fraction of the stroke's `widthBase`
 *   (not absolute pixels, so restyling a stroke's width keeps this proportion correct), as
 *   computed by the hardware backend's own ink engine — e.g. :pen-onyx asking Onyx's fountain
 *   pen engine for the width it would give this point, so the dry ink `inkOutline` repaints can
 *   match the wet ink the firmware already painted. Null when the backend supplies none (the
 *   touch fallback, or a note captured before this existed); `inkOutline` then falls back to its
 *   own pressure/speed width law. Not encoded when null (see [EncodeDefault]), so files and
 *   backends that never set it are unaffected.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampDelta: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nibFactor: Float? = null,
)
