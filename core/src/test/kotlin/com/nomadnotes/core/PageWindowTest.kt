package com.nomadnotes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [pageWindow]'s sliding-clamp contract: away from the ends it centers on `current`, and near
 * either end it still returns a full `2 * radius + 1`-wide window (when `total` allows) by sliding
 * rather than shrinking.
 */
class PageWindowTest {

    @Test
    fun `centers on current away from the ends`() {
        assertEquals(9..13, pageWindow(current = 11, total = 40, radius = 2))
    }

    @Test
    fun `slides instead of shrinking at the start`() {
        assertEquals(0..4, pageWindow(current = 0, total = 40, radius = 2))
        assertEquals(0..4, pageWindow(current = 1, total = 40, radius = 2))
    }

    @Test
    fun `slides instead of shrinking at the end`() {
        val lastIndex = 39
        assertEquals(35..39, pageWindow(current = lastIndex, total = 40, radius = 2))
        assertEquals(35..39, pageWindow(current = 38, total = 40, radius = 2))
    }

    @Test
    fun `covers the whole range when total does not allow a full window`() {
        assertEquals(0..2, pageWindow(current = 0, total = 3, radius = 2))
        assertEquals(0..2, pageWindow(current = 2, total = 3, radius = 2))
        assertEquals(0..0, pageWindow(current = 0, total = 1, radius = 2))
    }

    @Test
    fun `an empty notebook has no window`() {
        assertEquals(IntRange.EMPTY, pageWindow(current = 0, total = 0, radius = 2))
    }

    @Test
    fun `clamps an out-of-range current and a negative radius instead of throwing`() {
        assertEquals(35..39, pageWindow(current = 999, total = 40, radius = 2))
        assertEquals(0..4, pageWindow(current = -5, total = 40, radius = 2))
        assertEquals(11..11, pageWindow(current = 11, total = 40, radius = -3))
    }

    @Test
    fun `radius zero is just the current page`() {
        assertEquals(11..11, pageWindow(current = 11, total = 40, radius = 0))
    }
}
