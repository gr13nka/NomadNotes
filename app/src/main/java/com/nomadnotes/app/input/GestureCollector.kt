package com.nomadnotes.app.input

import android.graphics.Rect
import android.view.MotionEvent
import com.nomadnotes.core.StrokePoint

/**
 * Collects one pen gesture at a time from a view's [MotionEvent] stream into device-neutral
 * [StrokePoint]s, and reports it through callbacks. It owns only the touch grammar — begin on a
 * pen-down, append moves (replaying the batched historical samples so fast strokes keep their
 * shape), finish on pen-up, abandon on cancel — plus optional stylus-only filtering and exclude-rect
 * masking. It draws nothing and decides nothing about what a gesture means; the caller does.
 *
 * A gesture is followed by pointer *id*, not index: a stream can carry several pointers, and nothing
 * guarantees the one that started the gesture stays at index 0 — reading a fixed index would
 * silently follow whichever pointer happens to sit there. On the Boox the pen and the fingers are
 * separate input devices, so separate streams, and the case does not arise; a panel that merged
 * them into one stream would put a resting finger at index 0 under the pen.
 *
 * The plain-touch [AndroidPenBackend] drives one for every gesture, and [OnyxPenBackend] drives one
 * for a lasso (where it turns raw drawing off and captures the stylus as ordinary touch), so the
 * gesture grammar lives in exactly one place. Main-thread only: [MotionEvent]s arrive there and
 * nothing here is synchronized.
 *
 * @param stylusOnly when true, a gesture begins on `ACTION_DOWN` or `ACTION_POINTER_DOWN` whenever
 *   the newly-down pointer is a stylus/eraser, so a resting palm or finger is ignored and cannot
 *   itself start capture (Onyx, where the finger is not a drawing tool); when false, only
 *   `ACTION_DOWN` starts a gesture — `ACTION_POINTER_DOWN` is ignored — captured pointer index 0,
 *   matching plain single-touch input (the emulator and plain tablets, where touch itself is the
 *   input, so there is no second pointer to disambiguate from).
 * @param onStarted a gesture just began, reported before its first [onSample] so a caller can act on
 *   the pen-down itself rather than on the points it produces.
 * @param onSample the growing point list, on pen-down and each move, for a live preview. The list is
 *   valid only for the duration of the call — copy it to retain it.
 * @param onFinished the gesture's full path, delivered when the captured pointer lifts — at
 *   `ACTION_UP`, or at `ACTION_POINTER_UP` if other pointers are still down; this list is the
 *   caller's to keep.
 * @param onCancelled the gesture was abandoned before its pointer lifted (e.g. a touch cancel), so
 *   there is nothing to commit.
 */
class GestureCollector(
    private val stylusOnly: Boolean,
    private val onStarted: () -> Unit = {},
    private val onSample: (List<StrokePoint>) -> Unit,
    private val onFinished: (List<StrokePoint>) -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val points = ArrayList<StrokePoint>()
    private var gestureStart = 0L
    private var capturing = false
    private var capturedId = -1
    private var excludeRects: List<Rect> = emptyList()

    /** Regions where a pen-down does not start a gesture (e.g. an on-surface toolbar), in view pixels. */
    fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.toList()
    }

    /** Feeds one [event]; returns true when it was consumed as part of a gesture. */
    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> startIfEligible(event, event.actionIndex)
        // Ignored for the !stylusOnly backend: touch itself is the input there, and a second pointer
        // is just another touch, not a signal that a new gesture should begin.
        MotionEvent.ACTION_POINTER_DOWN -> if (stylusOnly) startIfEligible(event, event.actionIndex) else false
        MotionEvent.ACTION_MOVE -> {
            val index = if (capturing) event.findPointerIndex(capturedId) else -1
            if (index < 0) {
                false
            } else {
                // A MOVE batches the samples since the previous event; replay them in order first.
                for (i in 0 until event.historySize) points.add(historicalPointOf(event, index, i))
                points.add(pointOf(event, index))
                onSample(points)
                true
            }
        }
        MotionEvent.ACTION_UP -> {
            if (!capturing) {
                false
            } else {
                val index = event.findPointerIndex(capturedId)
                if (index >= 0) points.add(pointOf(event, index))
                val gesture = ArrayList(points)
                reset()
                onFinished(gesture)
                true
            }
        }
        MotionEvent.ACTION_POINTER_UP -> {
            if (!capturing) {
                false
            } else if (event.getPointerId(event.actionIndex) == capturedId) {
                points.add(pointOf(event, event.actionIndex))
                val gesture = ArrayList(points)
                reset()
                onFinished(gesture)
                true
            } else {
                // A different pointer lifted (e.g. a resting finger); the gesture keeps capturing, but
                // this event must still be consumed or the stream stops reaching us.
                true
            }
        }
        MotionEvent.ACTION_CANCEL -> {
            if (!capturing) {
                false
            } else {
                reset(notifyCancel = true)
                true
            }
        }
        else -> false
    }

    /**
     * Drops any in-progress gesture. When [notifyCancel] is set and a gesture was actually in
     * progress, it is reported as cancelled through [onCancelled] (exactly as an ACTION_CANCEL would),
     * so a caller that tears capture down mid-gesture — disabled, detached, or its touch listener
     * removed, with no ACTION_UP/ACTION_CANCEL still coming — can undo a half-drawn preview. Without
     * it, or with no gesture in progress, the drop is silent.
     */
    fun reset(notifyCancel: Boolean = false) {
        val wasCapturing = capturing
        capturing = false
        capturedId = -1
        points.clear()
        if (notifyCancel && wasCapturing) onCancelled()
    }

    // Starts a gesture on [event]'s pointer at [index] (the newly-down one), unless stylusOnly
    // filtering or an exclude rect rejects it, and captures that pointer by id from here on.
    private fun startIfEligible(event: MotionEvent, index: Int): Boolean {
        if ((stylusOnly && !isStylus(event, index)) || isExcluded(event.getX(index), event.getY(index))) {
            return false
        }
        capturing = true
        capturedId = event.getPointerId(index)
        gestureStart = event.eventTime
        points.clear()
        points.add(pointOf(event, index))
        onStarted()
        onSample(points)
        return true
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> false
    }

    private fun isExcluded(x: Float, y: Float): Boolean =
        excludeRects.any { it.contains(x.toInt(), y.toInt()) }

    private fun pointOf(event: MotionEvent, index: Int) = StrokePoint(
        x = event.getX(index),
        y = event.getY(index),
        pressure = event.getPressure(index),
        timestampDelta = event.eventTime - gestureStart,
    )

    private fun historicalPointOf(event: MotionEvent, index: Int, historyPos: Int) = StrokePoint(
        x = event.getHistoricalX(index, historyPos),
        y = event.getHistoricalY(index, historyPos),
        pressure = event.getHistoricalPressure(index, historyPos),
        timestampDelta = event.getHistoricalEventTime(historyPos) - gestureStart,
    )
}
