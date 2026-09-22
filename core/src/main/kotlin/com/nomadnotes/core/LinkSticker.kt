package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * A handwritten label drawn onto a [PageLink]. Because pages have no titles, a link's sticker
 * doubles as its destination page's display name wherever it is shown (the link's own card, the
 * links-map chip); a link with no sticker falls back to showing `#N`.
 *
 * @property strokes the sticker's ink, in a fixed sticker-space coordinate system — [0, WIDTH] x
 *   [0, HEIGHT] — rather than page pixels, so the same strokes render correctly at whatever size
 *   the card is currently drawn (the link box on the source page, a smaller map chip, ...).
 */
@Serializable
data class LinkSticker(
    val strokes: List<Stroke>,
) {
    companion object {
        /** Sticker-space width; a fixed 2:1 ratio with [HEIGHT]. */
        const val WIDTH = 600f

        /** Sticker-space height. */
        const val HEIGHT = 300f
    }
}
