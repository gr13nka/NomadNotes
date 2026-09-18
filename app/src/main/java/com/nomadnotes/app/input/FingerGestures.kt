package com.nomadnotes.app.input

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.nomadnotes.app.editor.FingerContact
import com.nomadnotes.app.editor.MultiFingerGestures

/**
 * Turns a [SurfaceView][android.view.SurfaceView]'s [MotionEvent] stream into the finger contacts
 * a multi-finger gesture recognizer needs, and drives that recognizer's deadline timer. It owns
 * only the touch grammar — which pointers in an event are fingers, and when the recognizer's next
 * deadline falls due — exactly as [GestureCollector] owns the pen's. All judgement (is this a tap,
 * how many fingers, is a hold armed) lives in the pure [MultiFingerGestures] this class wraps; that
 * split is deliberate, not incidental — `:app` has no Robolectric, so `MotionEvent.obtain` throws
 * in a unit test, which makes this class untestable and is exactly why every decision is pushed
 * into the pure recognizer next door instead.
 *
 * The 2026-09-17 Boox Go 10.3 probe (docs/BACKLOG.md item 4) found finger events do reach the
 * SurfaceView's own listener while raw drawing is on, but roughly half of what reaches
 * `dispatchTouchEvent` never gets there (143 vs. 73 observed). If the recognizer ever proves lossy
 * fed from here, the fallback is to feed it from an `Activity.dispatchTouchEvent` hook instead.
 *
 * No exclude rects, unlike the pen path: finger [MotionEvent]s travel the ordinary view hierarchy,
 * so the Compose toolbar overlaying the canvas already consumes its own touches before they reach
 * the surface. Raw pen input needs exclude rects only because it is captured below the window
 * system, bypassing that hierarchy entirely.
 *
 * @param handler posts the recognizer's deadlines; the caller supplies the main-thread [Handler] so
 *   the deadline (an absolute [SystemClock.uptimeMillis] value) can be scheduled with
 *   [Handler.postAtTime] and land exactly on time.
 * @param onUndoGesture a two-finger tap was recognized.
 * @param onRedoGesture a three-finger tap was recognized.
 * @param onLassoArmed the fingers were held long enough to arm the lasso latch (see
 *   [MultiFingerGestures]'s KDoc for why this is a one-shot event rather than an on/off state).
 */
class FingerGestures(
    private val handler: Handler,
    onUndoGesture: () -> Unit,
    onRedoGesture: () -> Unit,
    onLassoArmed: () -> Unit,
) {
    private val recognizer = MultiFingerGestures(
        onTwoFingerTap = { Log.d("GestureDebug", "TAP"); onUndoGesture() },
        onThreeFingerTap = { Log.d("GestureDebug", "TAP3"); onRedoGesture() },
        onHoldArmed = { Log.d("GestureDebug", "HOLD ARMED"); onLassoArmed() },
    )

    // The recognizer is pure and schedules nothing itself; it only names its next deadline as a
    // return value. This single Runnable is what turns that value into an actual callback, always
    // rescheduled (never stacked) to whichever deadline was most recently returned.
    private val deadlineRunnable = Runnable {
        scheduleDeadline(recognizer.onDeadlineReached(SystemClock.uptimeMillis()))
    }

    /** Feeds one [event]; returns true when it carried at least one finger pointer. */
    fun onTouch(event: MotionEvent): Boolean {
        val contacts = fingerContactsOf(event)
        // The pen writing with no finger on the panel is overwhelmingly the common event here, and
        // ink latency is this project's core bet: there is no candidate a fingerless move could
        // advance, so skip the recognizer and the handler queue entirely. Every edge that does change
        // the contact set (DOWN/POINTER_DOWN/POINTER_UP/UP/CANCEL) still goes through below.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && contacts.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            Log.d("GestureDebug", "finger CANCEL (contacts=${contacts.size})")
            handler.removeCallbacks(deadlineRunnable)
            recognizer.onCancelled()
        } else {
            scheduleDeadline(recognizer.onContactsChanged(contacts, event.eventTime))
        }
        return contacts.isNotEmpty()
    }

    /** The pen touched down. */
    fun onPenDown() = recognizer.onPenDown(SystemClock.uptimeMillis())

    /** The pen lifted. */
    fun onPenUp() = recognizer.onPenUp(SystemClock.uptimeMillis())

    /** Back to idle; see [MultiFingerGestures.reset]. */
    fun reset() {
        handler.removeCallbacks(deadlineRunnable)
        recognizer.reset()
    }

    private fun scheduleDeadline(atMs: Long?) {
        handler.removeCallbacks(deadlineRunnable)
        if (atMs != null) handler.postAtTime(deadlineRunnable, atMs)
    }

    // Projects one MotionEvent onto the finger pointers a gesture recognizer should see: never a
    // stylus/eraser (the pen has its own path through GestureCollector), and never a pointer this
    // very event is lifting — by the time the recognizer would act on it, that contact is gone.
    private fun fingerContactsOf(event: MotionEvent): List<FingerContact> {
        val liftingIndex = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> event.actionIndex
            else -> -1
        }
        // Allocated only once a finger is actually found, so the common pen-only event costs nothing.
        var contacts: ArrayList<FingerContact>? = null
        for (i in 0 until event.pointerCount) {
            if (i == liftingIndex) continue
            when (event.getToolType(i)) {
                MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> continue
                else -> (contacts ?: ArrayList<FingerContact>(event.pointerCount).also { contacts = it })
                    .add(
                        FingerContact(
                            id = event.getPointerId(i),
                            x = event.getX(i),
                            y = event.getY(i),
                            pressure = event.getPressure(i),
                        ),
                    )
            }
        }
        return contacts ?: emptyList()
    }
}
