package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * The drawing tool a stroke was made with. Rendering decides how each tool turns a stroke's
 * pressure-sampled points into ink (nib shape, pressure response, opacity); the model only
 * records which one produced the stroke.
 *
 * Serialized by name so the stored value stays readable and stable as new tools are appended.
 */
@Serializable
enum class Tool {
    PEN,
    PENCIL,
    MARKER,
}
