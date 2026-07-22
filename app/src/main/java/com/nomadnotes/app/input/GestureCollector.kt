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
 * The plain-touch [AndroidPenBackend] drives one for every gesture, and [OnyxPenBackend] drives one
 * for a lasso (where it turns raw drawing off and captures the stylus as ordinary touch), so the
 * gesture grammar lives in exactly one place. Main-thread only: [MotionEvent]s arrive there and
 * nothing here is synchronized.
 *
 * @param stylusOnly when true, only a stylus/eraser tool starts and extends a gesture, so a palm or
 *   finger touch is ignored (Onyx, where the finger is not a drawing tool); when false, any pointer
 *   is captured (the emulator and plain tablets, where touch itself is the input).
 * @param onSample the growing point list, on pen-down and each move, for a live preview. The list is
 *   valid only for the duration of the call — copy it to retain it.
 * @param onFinished the gesture's full path at pen-up; this list is the caller's to keep.
 * @param onCancelled the gesture was abandoned before pen-up (e.g. a palm touch cancelled it), so
 *   there is nothing to commit.
 */
class GestureCollector(
    private val stylusOnly: Boolean,
    private val onSample: (List<StrokePoint>) -> Unit,
    private val onFinished: (List<StrokePoint>) -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val points = ArrayList<StrokePoint>()
    private var gestureStart = 0L
    private var capturing = false
    private var excludeRects: List<Rect> = emptyList()

    /** Regions where a pen-down does not start a gesture (e.g. an on-surface toolbar), in view pixels. */
    fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.toList()
    }

    /** Feeds one [event]; returns true when it was consumed as part of a gesture. */
    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if ((stylusOnly && !isStylus(event)) || isExcluded(event.x, event.y)) {
                false
            } else {
                capturing = true
                gestureStart = event.eventTime
                points.clear()
                points.add(pointOf(event))
                onSample(points)
                true
            }
        }
        MotionEvent.ACTION_MOVE -> {
            if (!capturing) {
                false
            } else {
                // A MOVE batches the samples since the previous event; replay them in order first.
                for (i in 0 until event.historySize) points.add(historicalPointOf(event, i))
                points.add(pointOf(event))
                onSample(points)
                true
            }
        }
        MotionEvent.ACTION_UP -> {
            if (!capturing) {
                false
            } else {
                points.add(pointOf(event))
                val gesture = ArrayList(points)
                reset()
                onFinished(gesture)
                true
            }
        }
        MotionEvent.ACTION_CANCEL -> {
            if (!capturing) {
                false
            } else {
                reset()
                onCancelled()
                true
            }
        }
        else -> false
    }

    /** Drops any in-progress gesture without reporting it (e.g. when capture is disabled). */
    fun reset() {
        capturing = false
        points.clear()
    }

    private fun isStylus(event: MotionEvent): Boolean = when (event.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> false
    }

    private fun isExcluded(x: Float, y: Float): Boolean =
        excludeRects.any { it.contains(x.toInt(), y.toInt()) }

    private fun pointOf(event: MotionEvent) = StrokePoint(
        x = event.x,
        y = event.y,
        pressure = event.pressure,
        timestampDelta = event.eventTime - gestureStart,
    )

    private fun historicalPointOf(event: MotionEvent, index: Int) = StrokePoint(
        x = event.getHistoricalX(index),
        y = event.getHistoricalY(index),
        pressure = event.getHistoricalPressure(index),
        timestampDelta = event.getHistoricalEventTime(index) - gestureStart,
    )
}
