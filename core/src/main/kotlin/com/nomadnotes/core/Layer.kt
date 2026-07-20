package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * One layer of a [Page]: an ordered, independently hideable stack of strokes.
 *
 * Stroke order is paint order — later strokes in [strokes] render on top of earlier ones — so
 * edits preserve position (an erased-then-restored stroke returns to its original index) to
 * keep the visual result identical.
 *
 * @property id stable identity, referenced by editing operations that target a layer.
 * @property name user-facing label ("Main", "Layer 2", …).
 * @property visible whether the layer is composited when the page is drawn; hidden layers keep
 *   their strokes.
 * @property strokes the layer's strokes in paint order.
 */
@Serializable
data class Layer(
    val id: LayerId,
    val name: String,
    val visible: Boolean = true,
    val strokes: List<Stroke>,
)
