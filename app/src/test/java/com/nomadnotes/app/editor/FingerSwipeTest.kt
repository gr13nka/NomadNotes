package com.nomadnotes.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioural tests for [classifySwipe]: a long-enough, fast-enough, mostly-horizontal one-finger
 * drag turns the page in the direction of travel, and each of the disqualifiers — too short, too
 * slow, too vertical, or more than one pointer ever down — falls back to [SwipeDirection.NONE]
 * instead of misfiring one.
 */
class FingerSwipeTest {

    private val density = 2f
    private val minTravelPx = MIN_TRAVEL_DP * density

    @Test
    fun `a fast leftward drag past the threshold is a next-page swipe`() {
        val direction = classifySwipe(
            downX = 800f, downY = 500f, downTimeMs = 0L,
            upX = 800f - minTravelPx - 1f, upY = 500f, upTimeMs = 200L,
            maxPointerCount = 1, density = density,
        )
        assertEquals(SwipeDirection.NEXT_PAGE, direction)
    }

    @Test
    fun `a fast rightward drag past the threshold is a prev-page swipe`() {
        val direction = classifySwipe(
            downX = 200f, downY = 500f, downTimeMs = 0L,
            upX = 200f + minTravelPx + 1f, upY = 500f, upTimeMs = 200L,
            maxPointerCount = 1, density = density,
        )
        assertEquals(SwipeDirection.PREV_PAGE, direction)
    }

    @Test
    fun `travel just short of the threshold does not qualify`() {
        val direction = classifySwipe(
            downX = 800f, downY = 500f, downTimeMs = 0L,
            upX = 800f - minTravelPx + 1f, upY = 500f, upTimeMs = 200L,
            maxPointerCount = 1, density = density,
        )
        assertEquals(SwipeDirection.NONE, direction)
    }

    @Test
    fun `a mostly-vertical drag is rejected even past the horizontal threshold`() {
        val direction = classifySwipe(
            downX = 800f, downY = 500f, downTimeMs = 0L,
            upX = 800f - minTravelPx - 1f, upY = 500f + minTravelPx, upTimeMs = 200L,
            maxPointerCount = 1, density = density,
        )
        assertEquals(SwipeDirection.NONE, direction)
    }

    @Test
    fun `a slow drag past the duration budget is rejected`() {
        val direction = classifySwipe(
            downX = 800f, downY = 500f, downTimeMs = 0L,
            upX = 800f - minTravelPx - 1f, upY = 500f, upTimeMs = MAX_SWIPE_DURATION_MS + 1,
            maxPointerCount = 1, density = density,
        )
        assertEquals(SwipeDirection.NONE, direction)
    }

    @Test
    fun `a second pointer joining at any point disqualifies the swipe`() {
        val direction = classifySwipe(
            downX = 800f, downY = 500f, downTimeMs = 0L,
            upX = 800f - minTravelPx - 1f, upY = 500f, upTimeMs = 200L,
            maxPointerCount = 2, density = density,
        )
        assertEquals(SwipeDirection.NONE, direction)
    }
}
