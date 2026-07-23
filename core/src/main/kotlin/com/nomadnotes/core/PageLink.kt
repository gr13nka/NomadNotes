package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * Axis-aligned rectangle in page coordinates. A link's tappable area — fixed at
 * creation time (selection bbox + padding); moving strokes does not move it.
 *
 * Assumed well-formed (`left <= right`, `top <= bottom`); like the other model types it is not
 * validated, so callers must construct it in that order.
 */
@Serializable
data class LinkRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * A Supernote-style link: a region of the page that, when tapped, jumps to a target
 * notebook page. Lives only on pages whose main layer carries the circled handwriting;
 * the strokes underneath remain ordinary ink. Targets are referenced by stable ids so
 * they survive notebook renames; resolution failure is a broken link handled by the UI.
 */
@Serializable
data class PageLink(
    val id: LinkId,
    val region: LinkRegion,
    val targetNotebookId: NotebookId,
    val targetPageId: PageId,
)
