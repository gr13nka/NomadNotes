package com.nomadnotes.app.editor

import com.nomadnotes.app.editor.TapClassifier.MAX_DURATION_MS
import com.nomadnotes.app.editor.TapClassifier.MAX_SPREAD_PX
import com.nomadnotes.core.StrokePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [TapClassifier]: a gesture counts as a tap only when every point stays
 * within [TapClassifier.MAX_SPREAD_PX] of the first and the whole gesture lasts no longer than
 * [TapClassifier.MAX_DURATION_MS]. Both thresholds are inclusive at the edge.
 */
class TapClassifierTest {

    private fun point(x: Float, y: Float, tMs: Long) =
        StrokePoint(x = x, y = y, pressure = 1f, timestampDelta = tMs)

    @Test
    fun `an empty gesture is not a tap`() {
        assertFalse(TapClassifier.isTap(emptyList()))
    }

    @Test
    fun `a single point is a tap`() {
        assertTrue(TapClassifier.isTap(listOf(point(10f, 10f, 0L))))
    }

    @Test
    fun `a tight, quick cluster is a tap`() {
        val points = listOf(
            point(10f, 10f, 0L),
            point(12f, 11f, 40L),
            point(11f, 13f, 90L),
        )
        assertTrue(TapClassifier.isTap(points))
    }

    @Test
    fun `a tight but slow cluster is not a tap`() {
        // Every point is within spread, but the gesture outlasts MAX_DURATION_MS.
        val points = listOf(
            point(10f, 10f, 0L),
            point(11f, 12f, MAX_DURATION_MS + 1),
        )
        assertFalse(TapClassifier.isTap(points))
    }

    @Test
    fun `a fast but wide drag is not a tap`() {
        // Quick, but a later point is farther than MAX_SPREAD_PX from the first.
        val points = listOf(
            point(10f, 10f, 0L),
            point(10f + MAX_SPREAD_PX + 1f, 10f, 50L),
        )
        assertFalse(TapClassifier.isTap(points))
    }

    @Test
    fun `a point exactly at the spread limit is still a tap`() {
        // MAX_SPREAD_PX away from the first point counts as within spread (inclusive edge).
        val points = listOf(
            point(10f, 10f, 0L),
            point(10f + MAX_SPREAD_PX, 10f, 20L),
        )
        assertTrue(TapClassifier.isTap(points))
    }

    @Test
    fun `a gesture exactly at the duration limit is still a tap`() {
        // Lasting exactly MAX_DURATION_MS counts as within duration (inclusive edge).
        val points = listOf(
            point(10f, 10f, 0L),
            point(11f, 11f, MAX_DURATION_MS),
        )
        assertTrue(TapClassifier.isTap(points))
    }
}
