package com.nomadnotes.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Behavioural tests for [ThumbnailLru]'s eviction order, pure of any Android/Bitmap type. */
class ThumbnailLruTest {

    @Test
    fun `a miss returns null`() {
        val lru = ThumbnailLru<String, Int>(maxEntries = 4)
        assertNull(lru.get("missing"))
    }

    @Test
    fun `returns what was put`() {
        val lru = ThumbnailLru<String, Int>(maxEntries = 4)
        lru.put("a", 1)
        assertEquals(1, lru.get("a"))
    }

    @Test
    fun `a later put for the same key replaces the earlier value`() {
        val lru = ThumbnailLru<String, Int>(maxEntries = 4)
        lru.put("a", 1)
        lru.put("a", 2)
        assertEquals(2, lru.get("a"))
    }

    @Test
    fun `evicts the least recently used entry once over capacity`() {
        val lru = ThumbnailLru<String, Int>(maxEntries = 2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.get("a") // touches "a", so "b" becomes the least recently used
        lru.put("c", 3) // over capacity: evicts "b", not "a"

        assertEquals(1, lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals(3, lru.get("c"))
    }

    @Test
    fun `a fresh put also counts as a touch`() {
        val lru = ThumbnailLru<String, Int>(maxEntries = 2)
        lru.put("a", 1)
        lru.put("b", 2)
        lru.put("a", 10) // re-puts "a", so "b" becomes the least recently used
        lru.put("c", 3) // over capacity: evicts "b"

        assertEquals(10, lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals(3, lru.get("c"))
    }
}
