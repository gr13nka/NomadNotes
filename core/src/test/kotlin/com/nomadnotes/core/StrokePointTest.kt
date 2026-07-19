package com.nomadnotes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Proves the :core test infrastructure runs. The assertions also pin down the value-type
 * contract [StrokePoint] relies on (structural equality of its four components).
 */
class StrokePointTest {

    @Test
    fun `points with the same components are equal`() {
        val a = StrokePoint(x = 12f, y = 34f, pressure = 0.5f, timestampDelta = 8L)
        val b = StrokePoint(x = 12f, y = 34f, pressure = 0.5f, timestampDelta = 8L)
        assertEquals(a, b)
    }

    @Test
    fun `a different timestamp makes points unequal`() {
        val a = StrokePoint(x = 12f, y = 34f, pressure = 0.5f, timestampDelta = 8L)
        val b = a.copy(timestampDelta = 9L)
        assertNotEquals(a, b)
    }
}
