package com.nomadnotes.app.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the downsample arithmetic behind [decodeSampled]. The decode itself needs Android's
 * BitmapFactory, which a plain JVM unit test cannot run (no Robolectric in this module); the choice
 * of sample size is pure, and it is the part that decides how much memory a photo costs.
 */
class BitmapDecodeTest {

    @Test
    fun `an image already smaller than the target is not downsampled`() {
        assertEquals(1, sampleSize(srcWidth = 400, srcHeight = 300, reqWidth = 800, reqHeight = 600))
    }

    @Test
    fun `a much larger image is halved until it just covers the target`() {
        // 4000x3000 into 500x375: halving three times gives 500x375, a fourth would undershoot.
        assertEquals(8, sampleSize(srcWidth = 4000, srcHeight = 3000, reqWidth = 500, reqHeight = 375))
    }

    @Test
    fun `the sample never undershoots either dimension`() {
        // Wide but short: height would allow more halving than width, so width governs.
        val sample = sampleSize(srcWidth = 4000, srcHeight = 100, reqWidth = 1000, reqHeight = 10)
        assertEquals(4, sample)
    }

    @Test
    fun `a nonpositive target asks for no downsampling rather than dividing by zero`() {
        assertEquals(1, sampleSize(srcWidth = 4000, srcHeight = 3000, reqWidth = 0, reqHeight = 0))
    }
}
