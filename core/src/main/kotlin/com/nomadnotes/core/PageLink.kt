package com.nomadnotes.core

import kotlinx.serialization.Serializable

/**
 * Axis-aligned rectangle in page coordinates, shared by everything on a page that occupies an area
 * rather than a path: a link's tappable region, an image's placement.
 *
 * A link's rectangle is fixed at creation time (selection bbox + padding), so moving the strokes
 * underneath does not move it; an image's is whatever the user last sized it to.
 *
 * Assumed well-formed (`left <= right`, `top <= bottom`); like the other model types it is not
 * validated, so callers must construct it in that order.
 */
@Serializable
data class PageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
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
    val region: PageRect,
    val targetNotebookId: NotebookId,
    val targetPageId: PageId,
)
