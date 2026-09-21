package com.nomadnotes.core

/**
 * The window of page indices the Page panel's thumbnail strip shows around [current]: up to
 * [radius] pages on either side, clamped to the valid `0 until total` range by sliding rather than
 * shrinking — a page near either end still sees a full `2 * radius + 1`-wide strip (when [total]
 * allows it) instead of a lopsided one with empty space on the short side.
 *
 * [current] and [total] follow the same 0-based indexing as `EditorActivity.uiPageIndex` /
 * `uiPageCount`; a caller displaying `#N` adds 1. Returns [IntRange.EMPTY] for a non-positive
 * [total], and clamps an out-of-range [current] or a negative [radius] rather than throwing, so a
 * transient stale index during a page-count change never crashes the strip.
 */
fun pageWindow(current: Int, total: Int, radius: Int = 2): IntRange {
    if (total <= 0) return IntRange.EMPTY
    val safeRadius = radius.coerceAtLeast(0)
    val safeCurrent = current.coerceIn(0, total - 1)
    val lastIndex = total - 1

    var start = safeCurrent - safeRadius
    var end = safeCurrent + safeRadius
    if (start < 0) {
        end = (end - start).coerceAtMost(lastIndex)
        start = 0
    }
    if (end > lastIndex) {
        start = (start - (end - lastIndex)).coerceAtLeast(0)
        end = lastIndex
    }
    return start..end
}
