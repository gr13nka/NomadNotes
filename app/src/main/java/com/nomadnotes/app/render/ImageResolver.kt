package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.nomadnotes.core.PageImage
import java.io.File

/**
 * Turns a [PageImage]'s stored asset ref into the bitmap the renderer draws, or null when there is
 * nothing to draw.
 *
 * Two jobs the renderer should not have to know about.
 *
 * **Fitting the panel.** A photo arrives in millions of colours; the panel shows about sixteen greys.
 * Handing it the original leaves the firmware to reduce it, which bands heavily — a face becomes
 * flat patches. Each image is therefore converted once, on decode, to grey and *dithered*: the
 * quantisation error is traded for a fine ordered pattern the eye blends back into the missing
 * shades. Doing it here means it happens once per image rather than once per repaint.
 *
 * **Staying within memory.** Images are decoded to the size they are drawn at, never full
 * resolution, and at most [MAX_CACHE] are kept — a page can hold more pictures than fit in memory at
 * full size, and a layer bitmap already costs ~18 MB on this panel.
 *
 * As with [TemplateResolver], the resolver owns its bitmaps' native memory and the renderer only
 * borrows them: never recycle a bitmap this returns. Not thread-safe — drive it from the one (UI)
 * thread that drives the renderer.
 *
 * @param imageFileOf resolves an asset ref to the file holding it, or null when the notebook has no
 *   such picture. Supplied by the caller so the resolver needs no notebook or storage layout of its
 *   own, and follows the editor as it moves between notebooks.
 */
class ImageResolver(private val imageFileOf: (String) -> File?) {

    // Keyed by ref *and* the size it was decoded for: the same picture resized on the page needs
    // more pixels than before. Access-ordered, so a get bumps an entry and a put evicts the eldest.
    private val cache = object : LinkedHashMap<Key, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>): Boolean {
            if (size <= MAX_CACHE) return false
            eldest.value.recycle()
            return true
        }
    }

    /**
     * The bitmap for [image] at the size it currently occupies, or null when its file is missing or
     * unreadable — the renderer then draws nothing, leaving the rest of the page intact.
     *
     * The returned bitmap approximates the requested size rather than matching it; the caller scales
     * it into the image's rectangle.
     */
    fun resolve(image: PageImage): Bitmap? {
        val width = image.rect.width.toInt()
        val height = image.rect.height.toInt()
        if (width <= 0 || height <= 0) return null
        val key = Key(image.assetRef, bucket(width), bucket(height))
        cache[key]?.let { return it }

        val file = imageFileOf(image.assetRef) ?: run {
            Log.w(TAG, "Image asset missing, drawing nothing: ${image.assetRef}")
            return null
        }
        val decoded = decodeSampled(file, key.width, key.height) ?: run {
            Log.w(TAG, "Image asset unreadable, drawing nothing: ${image.assetRef}")
            return null
        }
        val forPanel = ditheredToGray(decoded)
        decoded.recycle()
        cache[key] = forPanel
        return forPanel
    }

    /** Recycles every cached bitmap. Safe to call repeatedly; the next [resolve] repopulates. */
    fun release() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    /**
     * Rounds a requested dimension up to a coarse step, so nudging an image a few pixels while
     * resizing reuses the decode instead of thrashing the cache on every frame of the drag.
     */
    private fun bucket(value: Int): Int {
        val steps = (value + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX
        return maxOf(1, steps) * SIZE_BUCKET_PX
    }

    private class Key(val ref: String, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.ref == ref && other.width == width && other.height == height

        override fun hashCode(): Int = (ref.hashCode() * 31 + width) * 31 + height
    }

    private companion object {
        const val TAG = "ImageResolver"

        /** How many decoded images to keep before evicting the least-recently-used. */
        const val MAX_CACHE = 4

        /** Decode sizes are rounded up to this step so a resize drag reuses one decode. */
        const val SIZE_BUCKET_PX = 128
    }
}

/**
 * Returns [source] converted to the panel's grey levels using an ordered (Bayer) dither.
 *
 * Ordered dithering adds a fixed threshold pattern to each pixel before quantising, so pixels whose
 * true value falls between two available greys alternate between them in a regular weave. It is
 * chosen over error diffusion deliberately: the pattern is stable, so the same picture dithers
 * identically every time and an e-ink partial refresh does not shimmer, and it is a single pass with
 * no dependency between pixels.
 */
internal fun ditheredToGray(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val pixel = pixels[index]
            // Rec. 601 luma: the eye is far more sensitive to green than to blue, so a plain average
            // would render greens too dark and blues too light.
            val luma = (
                LUMA_RED * Color.red(pixel) +
                    LUMA_GREEN * Color.green(pixel) +
                    LUMA_BLUE * Color.blue(pixel)
                ) / LUMA_SCALE
            val threshold = BAYER_8X8[(y and 7) * 8 + (x and 7)]
            // Nudge by where this pixel sits in the pattern, then snap to the nearest panel level.
            val nudged = luma + (threshold - BAYER_MIDPOINT) * DITHER_STRENGTH / BAYER_LEVELS
            val level = quantize(nudged.coerceIn(0, 255))
            pixels[index] = Color.argb(Color.alpha(pixel), level, level, level)
        }
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/** Snaps a 0..255 value to the nearest of the panel's [GRAY_LEVELS] evenly spaced greys. */
private fun quantize(value: Int): Int {
    val step = 255f / (GRAY_LEVELS - 1)
    return (Math.round(value / step) * step).toInt().coerceIn(0, 255)
}

/** How many greys the e-ink panel actually renders; everything else is dithered into these. */
private const val GRAY_LEVELS = 16

private const val LUMA_RED = 299
private const val LUMA_GREEN = 587
private const val LUMA_BLUE = 114
private const val LUMA_SCALE = 1000

private const val BAYER_LEVELS = 64
private const val BAYER_MIDPOINT = 32

/** Spread of the dither nudge, in 0..255 units: roughly one quantisation step, so it fills the gap
 *  between two available greys without visibly lightening or darkening the picture. */
private const val DITHER_STRENGTH = 255 / (GRAY_LEVELS - 1)

/** The classic 8×8 ordered-dither threshold matrix, values 0..63. */
private val BAYER_8X8 = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
)
