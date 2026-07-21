package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.io.File
import kotlin.math.max

/**
 * Turns a page's stored template ref into the background [Bitmap] the renderer blends beneath the
 * ink, or null when the page should just be white.
 *
 * The built-in rulings ([TemplateRef.Lines], [TemplateRef.Grid]) are drawn in code onto a
 * transparent bitmap so the renderer's white fill shows through the gaps; a [TemplateRef.UserImage]
 * is decoded from the notebooks' `templates/` directory and center-crop-scaled to fill the canvas.
 * A blank page, a missing or unreadable image, or a ref this version does not understand all
 * resolve to null — the renderer then shows plain white — and the unreadable/unknown cases are
 * logged rather than crashing.
 *
 * One canvas-size bitmap is cached per ref, so repeatedly rendering the same page (every stroke,
 * undo, erase) does not redraw or re-decode the template. The cache is keyed by the current surface
 * size and is dropped and recycled when the surface is resized; [release] frees it when the surface
 * goes away.
 *
 * Not thread-safe, and it owns the cached bitmaps' native memory: drive it from the one (UI) thread
 * that drives the renderer.
 */
class TemplateResolver(private val templatesDir: File) {

    private var width = 0
    private var height = 0

    // Each cached template is a full-screen ARGB_8888 bitmap — ~18 MB on the 1860×2418 panel — and a
    // page already keeps up to ~100 MB of layer bitmaps, on a memory-constrained device. So the two
    // built-ins stay pinned (cheap to keep, cheaper than redrawing), but decoded user images are held
    // in an LRU capped at [MAX_USER_CACHE]: cycling through several image templates can never pile
    // their bitmaps up. Both caches hold bitmaps at the current width×height and are dropped on resize.
    private val builtinCache = HashMap<String, Bitmap>()
    private val userCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size <= MAX_USER_CACHE) return false
            eldest.value.recycle()
            return true
        }
    }

    /**
     * The background bitmap for [ref] at the given surface size, or null for a white page. Reuses a
     * cached bitmap when one exists for this ref and size; a size change drops the whole cache first.
     */
    fun resolve(ref: String?, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (width != this.width || height != this.height) {
            clearCaches()
            this.width = width
            this.height = height
        }
        val template = TemplateRef.parse(ref)
        if (template == null) {
            Log.w(TAG, "Unknown template ref, showing a blank page: $ref")
            return null
        }
        return when (template) {
            TemplateRef.Blank -> null
            TemplateRef.Lines -> builtinCache.getOrPut(TemplateRef.LINES) { drawLines() }
            TemplateRef.Grid -> builtinCache.getOrPut(TemplateRef.GRID) { drawGrid() }
            is TemplateRef.UserImage -> resolveUserImage(ref!!, template.filename)
        }
    }

    /** Recycles every cached bitmap. Safe to call repeatedly; the next [resolve] repopulates. */
    fun release() {
        clearCaches()
        width = 0
        height = 0
    }

    private fun resolveUserImage(ref: String, filename: String): Bitmap? {
        // A get bumps the ref to most-recently-used; a put may evict (and recycle) the eldest.
        userCache[ref]?.let { return it }
        val decoded = decodeSampled(File(templatesDir, filename)) ?: run {
            Log.w(TAG, "Template image missing or unreadable, showing a blank page: $filename")
            return null
        }
        val filled = centerCrop(decoded)
        decoded.recycle()
        userCache[ref] = filled
        return filled
    }

    private fun drawLines(): Bitmap = transparentCanvas { canvas, paint ->
        var y = LINE_SPACING_PX
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += LINE_SPACING_PX
        }
    }

    private fun drawGrid(): Bitmap = transparentCanvas { canvas, paint ->
        var y = LINE_SPACING_PX
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += LINE_SPACING_PX
        }
        var x = LINE_SPACING_PX
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += LINE_SPACING_PX
        }
    }

    private inline fun transparentCanvas(draw: (Canvas, Paint) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RULE_COLOR
            strokeWidth = RULE_WIDTH
        }
        draw(Canvas(bitmap), paint)
        return bitmap
    }

    /** Scales [source] to cover width×height and crops the overflow evenly, onto an opaque bitmap. */
    private fun centerCrop(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(Color.WHITE) }
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + drawW, top + drawH), IMAGE_PAINT)
        return out
    }

    /**
     * Decodes [file] downsampled to roughly the surface size, so a large photo does not allocate a
     * full-resolution bitmap only to be scaled down. Returns null if the file is absent or not a
     * decodable image.
     */
    private fun decodeSampled(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun sampleSize(srcWidth: Int, srcHeight: Int): Int {
        var sample = 1
        while (srcWidth / (sample * 2) >= width && srcHeight / (sample * 2) >= height) {
            sample *= 2
        }
        return sample
    }

    private fun clearCaches() {
        builtinCache.values.forEach { it.recycle() }
        builtinCache.clear()
        userCache.values.forEach { it.recycle() }
        userCache.clear()
    }

    private companion object {
        const val TAG = "TemplateResolver"

        /** How many decoded user-image templates to keep before evicting the least-recently-used. */
        const val MAX_USER_CACHE = 2

        /** Spacing between ruled lines (and grid squares), in page pixels. */
        const val LINE_SPACING_PX = 90f

        /** Rules are thin and light so they guide writing without competing with the ink. */
        const val RULE_WIDTH = 1.5f
        val RULE_COLOR = Color.rgb(200, 200, 200)

        /** Filtered scaling keeps a downsized user image from looking jagged. */
        val IMAGE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
