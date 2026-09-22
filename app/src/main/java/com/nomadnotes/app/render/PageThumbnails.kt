package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.nomadnotes.app.storage.NotebookStorage
import com.nomadnotes.core.Notebook
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.Page
import com.nomadnotes.core.PageId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The page shape every thumbnail is scaled to fit, shared with
 * [PagePanel][com.nomadnotes.app.ui.editor.PagePanel]'s own `PageAspectRatio` — kept as the one
 * place that ratio is spelled out in pixels, so the picker cell and the Page-panel strip agree on
 * shape without either scaling the other's bitmap.
 */
private const val PAGE_ASPECT_WIDTH = 400
private const val PAGE_ASPECT_HEIGHT = 520

/**
 * Thumbnail bitmap size for the link picker's page grid — 2x the bitmap's previous 120x156, because
 * [renderPageThumbnail] draws strokes as thin polylines rather than scaling a full render down, and
 * at the old size those lines antialiased into near-invisible faint grey on e-ink; the extra
 * resolution keeps them a solid, visible black once Compose scales the bitmap back down to the
 * picker cell's own on-screen size (`EditorActivity`'s `LinkPickerThumbWidth`).
 */
const val PAGE_THUMBNAIL_WIDTH_PX = 240
const val PAGE_THUMBNAIL_HEIGHT_PX = PAGE_THUMBNAIL_WIDTH_PX * PAGE_ASPECT_HEIGHT / PAGE_ASPECT_WIDTH

/** Floor on a thumbnail stroke's on-screen width, so a fine PENCIL line stays visible at this size. */
private const val MIN_STROKE_WIDTH_PX = 2f

/**
 * Renders [page]'s visible layers' strokes — no template, no images, no link affordances, all
 * skipped as cost the picker's thumbnail size does not need — into a small white-background bitmap
 * sized [PAGE_THUMBNAIL_WIDTH_PX] x [PAGE_THUMBNAIL_HEIGHT_PX].
 *
 * Each stroke draws as a plain polyline through its own points, transformed into thumbnail
 * coordinates by hand (not [StrokeRenderer]/a canvas scale) at a floored width
 * ([MIN_STROKE_WIDTH_PX]) with antialiasing off: [StrokeRenderer]'s tapered PEN outline and
 * antialiased edges, scaled down ~15x from full page size, thin into a sub-pixel line that
 * antialiases away to near-invisible grey on e-ink — a plain crisp black line reads at this size
 * where the "real" ink shape would not. A stroke's own grey level is ignored for the same reason
 * (drawing it black is what makes it visible), and a single-point stroke (a dot) still draws one.
 *
 * [sourceWidthPx]/[sourceHeightPx] are the device's own drawing-surface pixels, the space every
 * stroke's points are already recorded in (see [Page]'s own doc); a nonpositive size (the canvas
 * SurfaceView not laid out yet, e.g. the very first picker open) falls back to the strokes' own
 * bounding box instead, so the thumbnail is never blank just because the real surface size wasn't
 * known when it was requested. A page with no strokes on any visible layer still yields a blank
 * white thumbnail.
 */
fun renderPageThumbnail(page: Page, sourceWidthPx: Int, sourceHeightPx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(PAGE_THUMBNAIL_WIDTH_PX, PAGE_THUMBNAIL_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val (width, height) = if (sourceWidthPx > 0 && sourceHeightPx > 0) {
        sourceWidthPx to sourceHeightPx
    } else {
        strokeBounds(page) ?: return bitmap
    }
    val scaleX = PAGE_THUMBNAIL_WIDTH_PX / width.toFloat()
    val scaleY = PAGE_THUMBNAIL_HEIGHT_PX / height.toFloat()
    val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    val path = Path()
    for (layer in page.layers) {
        if (!layer.visible) continue
        for (stroke in layer.strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            paint.strokeWidth = (stroke.widthBase * scaleX).coerceAtLeast(MIN_STROKE_WIDTH_PX)
            path.reset()
            path.moveTo(points[0].x * scaleX, points[0].y * scaleY)
            if (points.size == 1) {
                // A stroked path with nothing past its moveTo draws nothing; a zero-length segment
                // with a round cap draws the dot a stationary tap needs instead.
                path.lineTo(points[0].x * scaleX, points[0].y * scaleY)
            } else {
                for (i in 1 until points.size) path.lineTo(points[i].x * scaleX, points[i].y * scaleY)
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

/**
 * The furthest-right and furthest-down point across every visible layer's strokes, as the scale
 * reference [renderPageThumbnail] falls back to when it isn't handed the real surface size. Null
 * if nothing is drawn on any visible layer.
 */
private fun strokeBounds(page: Page): Pair<Int, Int>? {
    var maxX = 0f
    var maxY = 0f
    var found = false
    for (layer in page.layers) {
        if (!layer.visible) continue
        for (stroke in layer.strokes) {
            for (point in stroke.points) {
                found = true
                if (point.x > maxX) maxX = point.x
                if (point.y > maxY) maxY = point.y
            }
        }
    }
    if (!found) return null
    return maxX.roundToInt().coerceAtLeast(1) to maxY.roundToInt().coerceAtLeast(1)
}

/**
 * Small in-memory cache of rendered link-picker page thumbnails, keyed by (notebookId, pageId), so
 * reopening the picker or recomposing a cell does not re-render a bitmap already on screen.
 * [ThumbnailLru] owns the eviction policy; this class owns turning a page into a bitmap
 * ([renderPageThumbnail]) off the caller's dispatcher.
 */
class PageThumbnailCache(
    private val storage: NotebookStorage,
    maxEntries: Int = MAX_ENTRIES,
) {
    private data class Key(val notebookId: NotebookId, val pageId: PageId)

    private val lru = ThumbnailLru<Key, Bitmap>(maxEntries)

    /** The cached bitmap for (notebookId, pageId), or null if nothing is cached yet. */
    fun peek(notebookId: NotebookId, pageId: PageId): Bitmap? = lru.get(Key(notebookId, pageId))

    /**
     * Returns the cached thumbnail for [pageId] of [notebook], rendering (and caching) it first if
     * needed; null if the page cannot be loaded. Rendering runs off the calling coroutine's
     * dispatcher. [inMemoryPage], when supplied, is drawn instead of the page loaded from disk — the
     * currently open notebook passes its live [com.nomadnotes.core.edit.PageEditSession] page here
     * for its current page, so the thumbnail reflects unsaved edits rather than the last save.
     */
    suspend fun load(
        notebook: Notebook,
        pageId: PageId,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        inMemoryPage: Page? = null,
    ): Bitmap? {
        peek(notebook.id, pageId)?.let { return it }
        val page = inMemoryPage ?: withContext(Dispatchers.IO) {
            runCatching { storage.loadPage(notebook, pageId) }.getOrNull()
        } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            renderPageThumbnail(page, sourceWidthPx, sourceHeightPx)
        }
        lru.put(Key(notebook.id, pageId), bitmap)
        return bitmap
    }

    private companion object {
        /** Comfortably covers a picker visit's worth of pages without holding the whole library. */
        const val MAX_ENTRIES = 64
    }
}

/**
 * A fixed-capacity least-recently-used cache: plain and Android-free so its eviction order is
 * unit-testable without a real [Bitmap] or Robolectric. [PageThumbnailCache] is its only user.
 */
internal class ThumbnailLru<K, V>(private val maxEntries: Int) {
    init { require(maxEntries > 0) { "maxEntries must be positive" } }

    // Access-ordered (the `true` third argument) so the eldest entry removeEldestEntry evicts is
    // genuinely the least-recently-used one, not just the first inserted.
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }
}
