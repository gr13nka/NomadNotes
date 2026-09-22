package com.nomadnotes.app.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [classifyFingerTap]: a short, still lift of a lone finger is a tap, and
 * each disqualifier — too much travel, too long a hold, or more than one pointer ever down — falls
 * back to "not a tap" instead of misfiring one.
 */
class FingerTapTest {

    private val travelPx = MultiFingerGestures.MAX_TAP_TRAVEL_PX
    private val durationMs = TapClassifier.MAX_DURATION_MS

    @Test
    fun `a still, quick lift of one finger is a tap`() {
        val isTap = classifyFingerTap(
            downX = 400f, downY = 300f, downTimeMs = 0L,
            upX = 400f, upY = 300f, upTimeMs = durationMs,
            maxPointerCount = 1,
        )
        assertTrue(isTap)
    }

    @Test
    fun `travel right at the slop still qualifies`() {
        val isTap = classifyFingerTap(
            downX = 400f, downY = 300f, downTimeMs = 0L,
            upX = 400f + travelPx, upY = 300f, upTimeMs = 10L,
            maxPointerCount = 1,
        )
        assertTrue(isTap)
    }

    @Test
    fun `travel past the slop disqualifies it`() {
        val isTap = classifyFingerTap(
            downX = 400f, downY = 300f, downTimeMs = 0L,
            upX = 400f + travelPx + 1f, upY = 300f, upTimeMs = 10L,
            maxPointerCount = 1,
        )
        assertFalse(isTap)
    }

    @Test
    fun `a hold past the duration budget disqualifies it`() {
        val isTap = classifyFingerTap(
            downX = 400f, downY = 300f, downTimeMs = 0L,
            upX = 400f, upY = 300f, upTimeMs = durationMs + 1,
            maxPointerCount = 1,
        )
        assertFalse(isTap)
    }

    @Test
    fun `a second pointer joining at any point disqualifies it`() {
        val isTap = classifyFingerTap(
            downX = 400f, downY = 300f, downTimeMs = 0L,
            upX = 400f, upY = 300f, upTimeMs = 10L,
            maxPointerCount = 2,
        )
        assertFalse(isTap)
    }
}
