package com.nomadnotes.app.editor

/**
 * Decides whether one finger's down-to-up touch was a deliberate tap — small travel, short
 * duration, never joined by a second pointer — from its endpoints, timing, and how many pointers
 * were ever down at once, the same touch-grammar split [classifySwipe] uses. See
 * [com.nomadnotes.app.input.FingerTapGestures], which drives this and owns the touch grammar
 * (which pointer started the gesture, whether it was ever a finger rather than the pen, and whether
 * a second pointer ever joined it) — mirroring how [com.nomadnotes.app.input.PageSwipeGestures]
 * owns [classifySwipe]'s.
 *
 * Reuses two already-tuned thresholds rather than inventing new ones: [MultiFingerGestures]'s
 * [MultiFingerGestures.MAX_TAP_TRAVEL_PX] (measured ~0 px of travel for a real tap on the Boox Go
 * 10.3) for the travel budget, and [TapClassifier.MAX_DURATION_MS] (the pen's own tap window,
 * which a finger tap has no reason to differ from) for the duration budget.
 *
 * A gesture that travels far enough for [classifySwipe] to call it a swipe is, by construction,
 * already past [MultiFingerGestures.MAX_TAP_TRAVEL_PX] — a swipe needs [MIN_TRAVEL_DP] of travel,
 * an order of magnitude more — so the two can never both fire for the same lift.
 */
fun classifyFingerTap(
    downX: Float,
    downY: Float,
    downTimeMs: Long,
    upX: Float,
    upY: Float,
    upTimeMs: Long,
    maxPointerCount: Int,
): Boolean {
    if (maxPointerCount != 1) return false
    val durationMs = upTimeMs - downTimeMs
    if (durationMs !in 0..TapClassifier.MAX_DURATION_MS) return false
    val dx = upX - downX
    val dy = upY - downY
    val travel = MultiFingerGestures.MAX_TAP_TRAVEL_PX
    return dx * dx + dy * dy <= travel * travel
}
