package com.nomadnotes.core.ink

import com.nomadnotes.core.NotesJson
import com.nomadnotes.core.Stroke
import com.nomadnotes.core.StrokePoint
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Not a correctness test — a diagnostic that measures how [SmoothingLevel.AUTO]'s tuning behaves
 * against a real corpus of handwriting, so the constants in Smoothing.kt are picked from
 * measurement rather than intuition. Skipped unless `-Dcalibration.corpus=<path>` points at a
 * pulled `.nnote` directory (a `pages/` folder of page JSON, as `NotebookStorage` writes it), so a
 * normal `./gradlew :core:test` run never touches it:
 *
 * ```
 * ./gradlew :core:test --tests '*SmoothingCalibrationReport*' -Dcalibration.corpus=/path/to/Notes.nnote
 * ```
 *
 * Everything below runs the *real* tuning and pipeline code ([autoEpsilonPx], [simplify],
 * [smoothStrokeTuned]) rather than a reimplementation of it, so a finding here cannot be an
 * artifact of the report's own arithmetic drifting from the app's. Prints to stdout only, writes
 * nothing to disk; a red run here means the corpus path was wrong or empty, not that smoothing
 * regressed.
 */
class SmoothingCalibrationReport {

    @Test
    fun `print calibration report`() {
        val corpusPath = System.getProperty("calibration.corpus")
        assumeTrue("set -Dcalibration.corpus=/path/to/Notes.nnote to run this report", corpusPath != null)
        val pagesDir = File(corpusPath, "pages")
        assumeTrue("no pages/ directory under $corpusPath", pagesDir.isDirectory)

        val strokes = pagesDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .flatMap { file -> NotesJson.decodePage(file.readText()).layers.flatMap { it.strokes } }
            .filter { it.points.size >= MIN_SMOOTHABLE_POINTS }
        assumeTrue("no smoothable strokes found under $corpusPath", strokes.isNotEmpty())

        println()
        println("=== SmoothingCalibrationReport: ${strokes.size} strokes from $corpusPath ===")
        characterizeInput(strokes)
        val results = sweepOutputQuality(strokes)
        selectBest(results)
    }

    // --- 1: input characterization -------------------------------------------------------------

    private fun characterizeInput(strokes: List<Stroke>) {
        println()
        println("--- input characterization ---")
        val byBucket = strokes.groupBy { bucketOf(shapeScalePx(withoutRepeats(it.points))) }
        for (bucket in ShapeBucket.entries) {
            val inBucket = byBucket[bucket].orEmpty()
            println("[$bucket] n=${inBucket.size}")
            if (inBucket.isEmpty()) continue
            printPercentiles("  sample spacing (px)", inBucket.map { medianSampleSpacingPx(withoutRepeats(it.points)) })
            printPercentiles("  inter-sample interval (ms)", inBucket.map { medianIntervalMs(it.points) })
            printPercentiles("  tremor amplitude (px)", inBucket.map { localTremorPx(it.points) })
            printPercentiles("  shape scale (px)", inBucket.map { shapeScalePx(withoutRepeats(it.points)) })
        }
    }

    // --- 2: output quality, swept over candidate constants --------------------------------------

    private fun sweepOutputQuality(strokes: List<Stroke>): Map<Candidate, Map<ShapeBucket, BucketMetrics>> {
        println()
        println("--- output quality (MAX_EPSILON_FRACTION_OF_SHAPE x SLOW_PEN_GAIN), via smoothStrokeTuned ---")

        val fractions = listOf(0.02f, 0.04f, 0.06f, 0.08f, 0.10f, 0.12f)
        val gains = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
        val results = LinkedHashMap<Candidate, Map<ShapeBucket, BucketMetrics>>()

        for (fraction in fractions) {
            for (slowPenGain in gains) {
                val candidate = Candidate(fraction, slowPenGain)
                val perBucket = ShapeBucket.entries.associateWith { BucketMetrics() }
                for (stroke in strokes) {
                    val distinct = withoutRepeats(stroke.points)
                    if (distinct.size < MIN_SMOOTHABLE_POINTS) continue
                    val epsilon = autoEpsilonPx(distinct, fraction, slowPenGain)
                    val knots = simplify(distinct, epsilon)
                    if (knots.size < 2) continue
                    val output = smoothStrokeTuned(stroke.points, epsilon, RESAMPLE_SPACING_PX)
                    val metrics = perBucket.getValue(bucketOf(shapeScalePx(distinct)))

                    // 7: directed errors — lostDetail (input->output) is the over-smoothing detector,
                    // stray (output->input) the under-smoothing one; see maxDistanceToPath's doc in
                    // SmoothingTest for why only the first direction can see a collapsed loop.
                    distinct.forEach { metrics.lostDetail.add(distanceToPath(it, output)) }
                    output.forEach { metrics.stray.add(distanceToPath(it, stroke.points)) }
                    // 9: corner preservation, restricted to input points that actually turn a corner.
                    corners(distinct).forEach { metrics.cornerError.add(distanceToPath(it, output)) }
                    // 6: residual tremor, the same metric as input characterization's #3, recomputed
                    // on the smoothed output.
                    metrics.tremorResidual.add(localTremorPx(output))
                    // 5 and 8: how much simplification and resampling changed the point count.
                    metrics.knotReduction.add(knots.size.toFloat() / distinct.size.toFloat())
                    metrics.pointCountRatio.add(output.size.toFloat() / stroke.points.size.toFloat())
                }
                results[candidate] = perBucket
                println("[eps%=${"%.2f".format(fraction)} slowGain=${"%.1f".format(slowPenGain)}]")
                for (bucket in ShapeBucket.entries) {
                    val m = perBucket.getValue(bucket)
                    if (m.lostDetail.isEmpty()) {
                        println("  $bucket: no smoothable strokes")
                        continue
                    }
                    println(
                        "  $bucket: knotReduction p50=${"%.2f".format(percentile(m.knotReduction, 0.50))} " +
                            "pointCountRatio p50=${"%.2f".format(percentile(m.pointCountRatio, 0.50))} " +
                            "tremorResidual(px) p50/p95=${fmt2(m.tremorResidual)} " +
                            "stray(px) p50/p95/p99=${fmt3(m.stray)} " +
                            "lostDetail(px) p50/p95/p99=${fmt3(m.lostDetail)} " +
                            "corner(px) p50/p95/p99=${fmt3(m.cornerError)}",
                    )
                }
            }
        }
        return results
    }

    // --- selection rule ---------------------------------------------------------------------

    private fun selectBest(results: Map<Candidate, Map<ShapeBucket, BucketMetrics>>) {
        println()
        println("--- selection rule ---")
        val qualifying = results.filter { (_, perBucket) ->
            val small = perBucket[ShapeBucket.SMALL]
            small != null && small.lostDetail.isNotEmpty() && small.cornerError.isNotEmpty() &&
                percentile(small.lostDetail, 0.99) <= 1.0f &&
                percentile(small.cornerError, 0.99) <= 1.0f
        }
        if (qualifying.isEmpty()) {
            println(
                "No candidate keeps the small bucket's p99 lostDetail and p99 corner error at or under " +
                    "1.0px. That means the shape ceiling (MAX_EPSILON_FRACTION_OF_SHAPE) is doing all the " +
                    "work here, and the speed term (SLOW_PEN_GAIN / tremorBudgetGain) should be deleted " +
                    "rather than kept as untested dead weight.",
            )
            return
        }
        val best = qualifying.entries.minByOrNull { (_, perBucket) -> normalAndLargeTremor(perBucket) }!!
        println(
            "Selected eps%=${best.key.fraction} slowGain=${best.key.slowPenGain}: residual tremor on " +
                "normal+large buckets, p50 = ${"%.3f".format(normalAndLargeTremor(best.value))}px, out of " +
                "${qualifying.size}/${results.size} candidates that met the small-bucket bound.",
        )
    }

    private fun normalAndLargeTremor(perBucket: Map<ShapeBucket, BucketMetrics>): Float {
        val pooled = perBucket[ShapeBucket.NORMAL]?.tremorResidual.orEmpty() +
            perBucket[ShapeBucket.LARGE]?.tremorResidual.orEmpty()
        return if (pooled.isEmpty()) Float.MAX_VALUE else percentile(pooled, 0.50)
    }

    // --- geometry & stats, local to this report ----------------------------------------------

    /** Drops points repeating the previous position — the same rule [smoothStroke] applies before
     * tuning is chosen, kept as a local copy so this report has no dependency on a private detail
     * of Smoothing.kt beyond the [autoEpsilonPx]/[simplify]/[smoothStrokeTuned] entry points. */
    private fun withoutRepeats(points: List<StrokePoint>): List<StrokePoint> {
        val kept = ArrayList<StrokePoint>(points.size)
        for (point in points) {
            val previous = kept.lastOrNull()
            if (previous != null && previous.x == point.x && previous.y == point.y) continue
            kept.add(point)
        }
        return kept
    }

    private fun hypot(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    private fun distanceToSegment(p: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * abx) + ((p.y - a.y) * aby)) / lengthSquared
        val clamped = t.coerceIn(0f, 1f)
        return hypot(p.x - (a.x + clamped * abx), p.y - (a.y + clamped * aby))
    }

    private fun distanceToPath(point: StrokePoint, path: List<StrokePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until path.size - 1) {
            best = minOf(best, distanceToSegment(point, path[i], path[i + 1]))
        }
        return best
    }

    /** Interior points of [points] whose turn exceeds ~60 degrees — a corner sharp enough that
     * flattening it would be visible, not a wobble in an otherwise straight or gently curved run. */
    private fun corners(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size < 3) return emptyList()
        val result = ArrayList<StrokePoint>()
        for (i in 1 until points.size - 1) {
            val inX = points[i].x - points[i - 1].x
            val inY = points[i].y - points[i - 1].y
            val outX = points[i + 1].x - points[i].x
            val outY = points[i + 1].y - points[i].y
            val inLen = hypot(inX, inY)
            val outLen = hypot(outX, outY)
            if (inLen == 0f || outLen == 0f) continue
            val cosAngle = ((inX * outX + inY * outY) / (inLen * outLen)).coerceIn(-1f, 1f)
            val turnDeg = Math.toDegrees(acos(cosAngle.toDouble())).toFloat()
            if (turnDeg > 60f) result.add(points[i])
        }
        return result
    }

    /** Median distance of each interior point of [path] to the segment joining the neighbours
     * roughly 4px of arc length away on either side — a proxy for high-frequency wiggle that a
     * shape feature this size or larger cannot produce but leftover tremor can. */
    private fun localTremorPx(path: List<StrokePoint>, neighborArcPx: Float = 4f): Float {
        if (path.size < 3) return 0f
        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        }
        val half = neighborArcPx / 2.0
        val deviations = ArrayList<Float>()
        for (i in 1 until path.size - 1) {
            var before = i
            while (before > 0 && cumulative[i] - cumulative[before - 1] < half) before--
            var after = i
            while (after < path.size - 1 && cumulative[after + 1] - cumulative[i] < half) after++
            if (before == after) continue
            deviations.add(distanceToSegment(path[i], path[before], path[after]))
        }
        return percentile(deviations, 0.50)
    }

    private fun medianIntervalMs(points: List<StrokePoint>): Float {
        if (points.size < 2) return 0f
        val diffs = FloatArray(points.size - 1)
        for (i in 1 until points.size) {
            diffs[i - 1] = (points[i].timestampDelta - points[i - 1].timestampDelta).toFloat()
        }
        return percentile(diffs.toList(), 0.50)
    }

    private fun percentile(values: List<Float>, p: Double): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val rank = (p * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    private fun printPercentiles(label: String, values: List<Float>) {
        println(
            "$label: p05=${"%.2f".format(percentile(values, 0.05))} " +
                "p50=${"%.2f".format(percentile(values, 0.50))} " +
                "p95=${"%.2f".format(percentile(values, 0.95))}",
        )
    }

    private fun fmt2(values: List<Float>): String =
        "%.2f/%.2f".format(percentile(values, 0.50), percentile(values, 0.95))

    private fun fmt3(values: List<Float>): String =
        "%.2f/%.2f/%.2f".format(percentile(values, 0.50), percentile(values, 0.95), percentile(values, 0.99))

    // Cut points are a diagnostic convenience, not a measurement: "small" targets a single glyph's
    // counter (below which MIN_SHAPE_SCALE_PX's floor starts to matter), "large" a whole word or
    // more. They exist to see whether the tuning behaves sensibly across that range, not to claim
    // where real handwriting clusters — #4 (shape scale distribution) reports where it actually does.
    private enum class ShapeBucket { SMALL, NORMAL, LARGE }

    private fun bucketOf(shapeScalePx: Float): ShapeBucket = when {
        shapeScalePx < 20f -> ShapeBucket.SMALL
        shapeScalePx < 80f -> ShapeBucket.NORMAL
        else -> ShapeBucket.LARGE
    }

    private data class Candidate(val fraction: Float, val slowPenGain: Float)

    private class BucketMetrics {
        val lostDetail = mutableListOf<Float>()
        val stray = mutableListOf<Float>()
        val cornerError = mutableListOf<Float>()
        val tremorResidual = mutableListOf<Float>()
        val knotReduction = mutableListOf<Float>()
        val pointCountRatio = mutableListOf<Float>()
    }
}
