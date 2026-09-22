package com.nomadnotes.app.editor

import kotlin.math.abs

/** Which way a recognized one-finger swipe should turn the page; [NONE] means it didn't qualify. */
enum class SwipeDirection { NONE, NEXT_PAGE, PREV_PAGE }

/**
 * Decides whether one finger's down-to-up path is a horizontal page-turning swipe, from its
 * endpoints, timing, and how many pointers were ever down at once — nothing else. See
 * [com.nomadnotes.app.input.PageSwipeGestures], which drives this and owns the touch grammar: which
 * pointer started the gesture, whether it was ever a finger rather than the pen, and whether a
 * second pointer or a pen-down ever joined it.
 *
 * Recognized entirely on lift, like [MultiFingerGestures]'s taps: e-ink has no live drag feedback to
 * draw, so there is nothing to gain from tracking the path in between, and deciding once at
 * ACTION_UP means a page turn costs exactly one chrome refresh. Unlike [MultiFingerGestures],
 * though, a swipe needs no deadline timer — there is no hold to distinguish it from — so a single
 * pure function, given both endpoints, is the whole recognizer.
 *
 * @param maxPointerCount the highest pointer count seen anywhere between down and up; a second
 *   pointer joining at any point disqualifies the gesture (it would otherwise race a two- or
 *   three-finger tap for the same touch), so this must be the max over the whole gesture, not just
 *   the count at up.
 * @param density the panel's px-per-dp ratio (`Resources.getDisplayMetrics().density` on Android),
 *   so [MIN_TRAVEL_DP] converts to this panel's pixels without this function depending on Android.
 */
fun classifySwipe(
    downX: Float,
    downY: Float,
    downTimeMs: Long,
    upX: Float,
    upY: Float,
    upTimeMs: Long,
    maxPointerCount: Int,
    density: Float,
): SwipeDirection {
    if (maxPointerCount != 1) return SwipeDirection.NONE
    val durationMs = upTimeMs - downTimeMs
    if (durationMs !in 0..MAX_SWIPE_DURATION_MS) return SwipeDirection.NONE
    val dx = upX - downX
    val dy = upY - downY
    if (abs(dx) < MIN_TRAVEL_DP * density) return SwipeDirection.NONE
    if (abs(dx) < MIN_HORIZONTAL_TO_VERTICAL_RATIO * abs(dy)) return SwipeDirection.NONE
    return if (dx < 0) SwipeDirection.NEXT_PAGE else SwipeDirection.PREV_PAGE
}

/**
 * Narrowest horizontal travel, in dp, that counts as a deliberate page-turning swipe rather than a
 * short drag. Not yet measured on device (unlike [MultiFingerGestures]'s pixel thresholds, which cite
 * a Boox Go 10.3 pass) — this is the user's own estimate of a comfortable one-handed swipe, pending a
 * device pass the way `Smoothing.kt`'s tuning constants once needed one.
 */
const val MIN_TRAVEL_DP = 120f

/** Longest a swipe may take, in ms, before it reads as a drag rather than a flick. Also unmeasured. */
const val MAX_SWIPE_DURATION_MS = 600L

/**
 * How much farther a swipe must travel horizontally than vertically. Rejects a mostly-vertical drag
 * (e.g. scrolling a long page) before it is ever mistaken for a page turn.
 */
const val MIN_HORIZONTAL_TO_VERTICAL_RATIO = 2f
