package com.nomadnotes.app.input

import android.view.MotionEvent
import com.nomadnotes.app.editor.SwipeDirection
import com.nomadnotes.app.editor.classifySwipe

/**
 * Turns a [SurfaceView][android.view.SurfaceView]'s [MotionEvent] stream into a one-finger
 * horizontal swipe (turn the page), exactly as [FingerGestures] turns the same stream into taps and
 * a hold — this class owns only the touch grammar (which pointer started the gesture, whether it
 * ever stopped being alone, whether the pen came down over it); the decision itself is
 * [classifySwipe], the pure function next door.
 *
 * A swipe is finger-only, unlike [GestureCollector]'s stylus path: on the plain-touch backend the
 * finger *is* the drawing tool, so this recognizer exists only on [OnyxPenBackend], where a resting
 * or gesturing finger is never a stroke. A single instance tracks one candidate at a time; a second
 * pointer joining, or the pen coming down, drops it until the next `ACTION_DOWN` — matching
 * [classifySwipe]'s `maxPointerCount` contract, since once dropped no later lift can revive it.
 *
 * @param density supplies the panel's px-per-dp ratio at classification time (not at construction),
 *   since the owning backend builds this before it has a [SurfaceView] to read
 *   `Resources.getDisplayMetrics()` from.
 * @param onNextPage a leftward swipe was recognized.
 * @param onPrevPage a rightward swipe was recognized.
 */
class PageSwipeGestures(
    private val density: () -> Float,
    private val onNextPage: () -> Unit,
    private val onPrevPage: () -> Unit,
) {
    private var trackedPointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L

    /** The highest pointer count seen since the tracked pointer went down; see [classifySwipe]. */
    private var maxPointerCount = 0
    private var penDown = false

    /** Feeds one [event]; returns true while a candidate swipe is being tracked. */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxPointerCount = 1
                trackedPointerId = if (!penDown && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    downX = event.getX(0)
                    downY = event.getY(0)
                    downTimeMs = event.eventTime
                    event.getPointerId(0)
                } else {
                    -1
                }
            }
            // A second pointer joined; the candidate is no longer a one-finger gesture and can never
            // become one again for this touch sequence (there is no lift left that could redeem it).
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                trackedPointerId = -1
            }
            MotionEvent.ACTION_UP -> {
                val id = trackedPointerId
                trackedPointerId = -1
                if (id < 0) return false
                val index = event.findPointerIndex(id)
                if (index < 0) return false
                val direction = classifySwipe(
                    downX = downX,
                    downY = downY,
                    downTimeMs = downTimeMs,
                    upX = event.getX(index),
                    upY = event.getY(index),
                    upTimeMs = event.eventTime,
                    maxPointerCount = maxPointerCount,
                    density = density(),
                )
                when (direction) {
                    SwipeDirection.NEXT_PAGE -> onNextPage()
                    SwipeDirection.PREV_PAGE -> onPrevPage()
                    SwipeDirection.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> trackedPointerId = -1
        }
        return trackedPointerId >= 0
    }

    /** The pen touched down; drops any candidate in flight and blocks a new one from starting. */
    fun onPenDown() {
        penDown = true
        trackedPointerId = -1
    }

    /** The pen lifted; a new candidate may start again. */
    fun onPenUp() {
        penDown = false
    }

    /** Drops any candidate in flight, for a caller tearing touch capture down (e.g. disabling). */
    fun reset() {
        trackedPointerId = -1
    }
}
