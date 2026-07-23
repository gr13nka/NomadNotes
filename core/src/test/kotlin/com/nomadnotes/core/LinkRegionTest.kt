package com.nomadnotes.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the boundary contract of [LinkRegion.contains]: the tappable area is closed on all four
 * edges, so a tap landing exactly on an edge still hits the link.
 */
class LinkRegionTest {

    private val region = LinkRegion(left = 0f, top = 0f, right = 10f, bottom = 10f)

    @Test
    fun `contains includes points on every edge`() {
        assertTrue(region.contains(0f, 5f)) // left edge
        assertTrue(region.contains(10f, 5f)) // right edge
        assertTrue(region.contains(5f, 0f)) // top edge
        assertTrue(region.contains(5f, 10f)) // bottom edge
    }

    @Test
    fun `contains excludes points beyond the edges`() {
        assertFalse(region.contains(-1f, 5f))
        assertFalse(region.contains(11f, 5f))
        assertFalse(region.contains(5f, -1f))
        assertFalse(region.contains(5f, 11f))
    }
}
