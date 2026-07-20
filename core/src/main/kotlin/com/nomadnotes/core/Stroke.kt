package com.nomadnotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One drawn stroke: the ordered points the stylus traced plus how they should be inked.
 *
 * A stroke is immutable — editing (erase, move, restyle) produces a new [Stroke] rather than
 * mutating this one — so the same instance can be shared freely between undo history and the
 * live page without defensive copying.
 *
 * @property id stable identity, unchanged across edits and re-serialization.
 * @property tool the drawing tool that produced the stroke; see [Tool].
 * @property widthBase the nib width before pressure is applied, in page pixels.
 * @property grayLevel target ink darkness on the e-ink panel, 0 (white) to 255 (black); the
 *   Boox panel is grayscale, so colour is intentionally absent from the model.
 * @property points the pressure-sampled path, in page pixels (see [Page] for the coordinate
 *   space). A well-formed stroke has at least one point.
 * @property extra forward-compatibility escape hatch: fields written by a newer app version
 *   land here on decode and are re-emitted verbatim on encode, so an older build never drops
 *   data it does not understand. Empty for strokes this version fully models.
 */
@Serializable
data class Stroke(
    val id: StrokeId,
    val tool: Tool,
    val widthBase: Float,
    val grayLevel: Int,
    val points: List<StrokePoint>,
    val extra: Map<String, JsonElement> = emptyMap(),
)
