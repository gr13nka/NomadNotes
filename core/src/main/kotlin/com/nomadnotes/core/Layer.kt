package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * One layer of a [Page]: an ordered, independently hideable stack of strokes, over any images placed
 * on it.
 *
 * Stroke order is paint order — later strokes in [strokes] render on top of earlier ones — so
 * edits preserve position (an erased-then-restored stroke returns to its original index) to
 * keep the visual result identical.
 *
 * Images sit *below* every stroke of the same layer, so ink always stays legible over a picture and
 * a newly drawn stroke can be painted straight on top of the layer without re-rasterising it. A
 * picture that must cover ink goes on a higher layer instead; within one layer the order is fixed.
 *
 * @property id stable identity, referenced by editing operations that target a layer.
 * @property name user-facing label ("Main", "Layer 2", …).
 * @property visible whether the layer is composited when the page is drawn; hidden layers keep
 *   their strokes and images.
 * @property strokes the layer's strokes in paint order.
 * @property images the layer's images, drawn beneath [strokes] in list order. Defaulted so pages
 *   written before images existed decode unchanged.
 */
@Serializable
data class Layer(
    val id: LayerId,
    val name: String,
    val visible: Boolean = true,
    val strokes: List<Stroke>,
    val images: List<PageImage> = emptyList(),
)
