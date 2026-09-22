package com.nomadnotes.pen.onyx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The native fountain engine itself only runs on a Boox, so this covers the one thing standing
 * between it and a runaway: the pressure it is handed. Raw device pressure at pen-down or pen-up
 * (a firm stroke on the Boox Go 10.3 reads up to 4095) made a single engine call emit ink samples
 * by the million and OOM-kill the app, so every value must arrive as a 0..1 fraction.
 */
class FountainInkSizerTest {

    private val deviceMax = 4096f

    @Test
    fun rawDevicePressureBecomesAFraction() {
        assertEquals(0.5f, FountainInkSizer.normalizedPressure(2048f, deviceMax), 1e-6f)
        assertEquals(4095f / 4096f, FountainInkSizer.normalizedPressure(4095f, deviceMax), 1e-6f)
    }

    @Test
    fun pressureAtOrBeyondTheReportedMaximumIsClampedToOne() {
        assertEquals(1f, FountainInkSizer.normalizedPressure(deviceMax, deviceMax), 0f)
        assertEquals(1f, FountainInkSizer.normalizedPressure(8000f, deviceMax), 0f)
        assertEquals(1f, FountainInkSizer.normalizedPressure(Float.POSITIVE_INFINITY, deviceMax), 0f)
    }

    @Test
    fun zeroNegativeAndNaNPressureReadAsNoPressure() {
        assertEquals(0f, FountainInkSizer.normalizedPressure(0f, deviceMax), 0f)
        assertEquals(0f, FountainInkSizer.normalizedPressure(-3f, deviceMax), 0f)
        assertEquals(0f, FountainInkSizer.normalizedPressure(Float.NaN, deviceMax), 0f)
    }

    @Test
    fun aMissingOrBrokenPressureRangeNeverPassesRawPressureThrough() {
        assertEquals(0f, FountainInkSizer.normalizedPressure(2048f, 0f), 0f)
        assertEquals(0f, FountainInkSizer.normalizedPressure(2048f, -1f), 0f)
        assertEquals(0f, FountainInkSizer.normalizedPressure(2048f, Float.NaN), 0f)
    }
}
