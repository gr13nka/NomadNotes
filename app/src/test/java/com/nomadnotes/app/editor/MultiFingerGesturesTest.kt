package com.nomadnotes.app.editor

import com.nomadnotes.app.editor.MultiFingerGestures.Companion.HOLD_ARM_MS
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.MAX_FINGER_PRESSURE
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.MAX_FINGER_SPACING_PX
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.MAX_POINTER_STAGGER_MS
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.MAX_TAP_TRAVEL_PX
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.MIN_FINGER_SPACING_PX
import com.nomadnotes.app.editor.MultiFingerGestures.Companion.PEN_QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [MultiFingerGestures]: two fingers landing together and lifting before
 * [MultiFingerGestures.HOLD_ARM_MS] is a tap (undo), a legitimate third contact joining them fires
 * a three-finger tap (redo) immediately on landing rather than waiting for a lift, staying down at
 * two fingers past the deadline arms the hold once, and a resting palm (by pressure), a pan (by
 * travel), an illegitimate third or any fourth contact, a wide finger stagger or spacing outside
 * budget, or a pen that isn't quiet each disqualify the candidate instead of misfiring one.
 */
class MultiFingerGesturesTest {

    private val twoFingerTaps = mutableListOf<Unit>()
    private val threeFingerTaps = mutableListOf<Unit>()
    private val holdArmed = mutableListOf<Unit>()

    private fun newGestures() = MultiFingerGestures(
        onTwoFingerTap = { twoFingerTaps.add(Unit) },
        onThreeFingerTap = { threeFingerTaps.add(Unit) },
        onHoldArmed = { holdArmed.add(Unit) },
    )

    private fun finger(id: Int, x: Float, y: Float, pressure: Float = 0.1f) =
        FingerContact(id, x, y, pressure)

    @Test
    fun `two fingers landing together and lifting before the deadline fire a two-finger tap`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onContactsChanged(emptyList(), atMs = 100L)
        assertEquals(1, twoFingerTaps.size)
        assertTrue(threeFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `two fingers held past the deadline arm the hold once`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        val next = gestures.onDeadlineReached(HOLD_ARM_MS)
        assertNull(next)
        assertEquals(1, holdArmed.size)
    }

    @Test
    fun `releasing after the hold is armed fires no tap`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onDeadlineReached(HOLD_ARM_MS)
        gestures.onContactsChanged(emptyList(), atMs = HOLD_ARM_MS + 50)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(threeFingerTaps.isEmpty())
    }

    @Test
    fun `a stagger wider than the budget disqualifies the candidate`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f)), atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)),
            atMs = MAX_POINTER_STAGGER_MS + 1,
        )
        assertNull(deadline)
        gestures.onContactsChanged(emptyList(), atMs = MAX_POINTER_STAGGER_MS + 50)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `a separation narrower than the minimum disqualifies the candidate`() {
        val gestures = newGestures()
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 100f + MIN_FINGER_SPACING_PX - 1f, 100f)),
            atMs = 0L,
        )
        assertNull(deadline)
    }

    @Test
    fun `a separation wider than the maximum disqualifies the candidate`() {
        val gestures = newGestures()
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 100f + MAX_FINGER_SPACING_PX + 1f, 100f)),
            atMs = 0L,
        )
        assertNull(deadline)
    }

    @Test
    fun `a palm pressure disqualifies the candidate`() {
        val gestures = newGestures()
        val deadline = gestures.onContactsChanged(
            listOf(
                finger(1, 100f, 100f, pressure = 0.1f),
                finger(2, 300f, 100f, pressure = MAX_FINGER_PRESSURE + 0.01f),
            ),
            atMs = 0L,
        )
        assertNull(deadline)
    }

    @Test
    fun `travel beyond the tap budget disqualifies the candidate`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f + MAX_TAP_TRAVEL_PX + 1f, 100f), finger(2, 300f, 100f)),
            atMs = 10L,
        )
        assertNull(deadline)
        gestures.onContactsChanged(emptyList(), atMs = 20L)
        assertTrue(twoFingerTaps.isEmpty())
    }

    @Test
    fun `a third finger landing fires a three-finger tap immediately, before any lift`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f), finger(3, 200f, 300f)),
            atMs = 10L,
        )
        assertNull(deadline)
        assertEquals(1, threeFingerTaps.size)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `a cancel and a subsequent lift after the three-finger tap fire nothing further`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f), finger(3, 200f, 300f)),
            atMs = 10L,
        )
        gestures.onCancelled()
        gestures.onContactsChanged(emptyList(), atMs = 20L)
        assertEquals(1, threeFingerTaps.size)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `a third contact arriving after the stagger budget disqualifies the candidate`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f), finger(3, 200f, 300f)),
            atMs = MAX_POINTER_STAGGER_MS + 1,
        )
        assertNull(deadline)
        gestures.onContactsChanged(emptyList(), atMs = MAX_POINTER_STAGGER_MS + 50)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(threeFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `a fourth finger disqualifies the candidate`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        // Straight from two to four: a third contact never lands on its own, so this is not the
        // "legal growth to exactly three" shape and is a palm blob regardless of timing/pressure.
        val deadline = gestures.onContactsChanged(
            listOf(
                finger(1, 100f, 100f),
                finger(2, 300f, 100f),
                finger(3, 200f, 300f),
                finger(4, 400f, 300f),
            ),
            atMs = 10L,
        )
        assertNull(deadline)
        gestures.onContactsChanged(emptyList(), atMs = 20L)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(threeFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `a third contact above the pressure budget disqualifies the candidate`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(
                finger(1, 100f, 100f),
                finger(2, 300f, 100f),
                finger(3, 200f, 300f, pressure = MAX_FINGER_PRESSURE + 0.01f),
            ),
            atMs = 10L,
        )
        assertNull(deadline)
        gestures.onContactsChanged(emptyList(), atMs = 20L)
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(threeFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `one finger alone is never a tap`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f)), atMs = 0L)
        gestures.onContactsChanged(emptyList(), atMs = 50L)
        assertTrue(twoFingerTaps.isEmpty())
    }

    @Test
    fun `no candidate starts while the pen is down`() {
        val gestures = newGestures()
        gestures.onPenDown(atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)),
            atMs = 10L,
        )
        assertNull(deadline)
    }

    @Test
    fun `no candidate starts within PEN_QUIET_MS of a pen lift`() {
        val gestures = newGestures()
        gestures.onPenUp(atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)),
            atMs = PEN_QUIET_MS - 1,
        )
        assertNull(deadline)
    }

    @Test
    fun `a candidate starting after PEN_QUIET_MS is fine`() {
        val gestures = newGestures()
        gestures.onPenUp(atMs = 0L)
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)),
            atMs = PEN_QUIET_MS,
        )
        assertEquals(PEN_QUIET_MS + HOLD_ARM_MS, deadline)
    }

    @Test
    fun `a pen-down after the hold arms fires nothing further`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onDeadlineReached(HOLD_ARM_MS)
        gestures.onPenDown(atMs = HOLD_ARM_MS + 10)
        assertEquals(1, holdArmed.size)
        gestures.onContactsChanged(emptyList(), atMs = HOLD_ARM_MS + 20)
        assertEquals(1, holdArmed.size)
    }

    @Test
    fun `a cancel after the hold arms fires nothing further`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onDeadlineReached(HOLD_ARM_MS)
        gestures.onCancelled()
        assertEquals(1, holdArmed.size)
        assertTrue(twoFingerTaps.isEmpty())
    }

    @Test
    fun `reset before the deadline fires no tap and no hold`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.reset()
        assertTrue(twoFingerTaps.isEmpty())
        assertTrue(holdArmed.isEmpty())
    }

    @Test
    fun `the returned deadline is HOLD_ARM_MS after the candidate began`() {
        val gestures = newGestures()
        val deadline = gestures.onContactsChanged(
            listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)),
            atMs = 500L,
        )
        assertEquals(500L + HOLD_ARM_MS, deadline)
    }

    @Test
    fun `no deadline is pending once idle or dead`() {
        val gestures = newGestures()
        gestures.onContactsChanged(listOf(finger(1, 100f, 100f), finger(2, 300f, 100f)), atMs = 0L)
        gestures.onDeadlineReached(HOLD_ARM_MS)
        // Dead (already armed): further movement waits on nothing further.
        val whileDead = gestures.onContactsChanged(
            listOf(finger(1, 105f, 100f), finger(2, 300f, 100f)),
            atMs = HOLD_ARM_MS + 10,
        )
        assertNull(whileDead)
        gestures.onContactsChanged(emptyList(), atMs = HOLD_ARM_MS + 20)
        // Idle: nothing down, nothing to wait for.
        val whileIdle = gestures.onContactsChanged(emptyList(), atMs = HOLD_ARM_MS + 30)
        assertNull(whileIdle)
    }
}
