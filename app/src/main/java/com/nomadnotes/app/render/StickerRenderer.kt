package com.nomadnotes.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.nomadnotes.core.LinkSticker

/**
 * Renders a [LinkSticker]'s ink — captured in its fixed [LinkSticker.WIDTH] x [LinkSticker.HEIGHT]
 * coordinate space, independent of any device size — onto a device [Canvas] at whatever rectangle
 * the caller wants: the link's own card on its source page ([LinkRenderer]), the sticker panel's
 * live drawing box, or (the links map) a shrunk chip via [toBitmap].
 *
 * One reused [StrokeRenderer]; drive one instance from a single (UI) thread, like its peers.
 */
class StickerRenderer {

    private val strokeRenderer = StrokeRenderer()

    /** Draws [sticker], scaling its sticker-space strokes to fill [dst] on [canvas]. */
    fun draw(canvas: Canvas, sticker: LinkSticker, dst: RectF) {
        val save = canvas.save()
        // Clipped defensively: StickerDraft keeps every point inside sticker space, but a stroke
        // captured by some future path that does not would otherwise bleed past the card's edge.
        canvas.clipRect(dst)
        canvas.translate(dst.left, dst.top)
        val scale = dst.width() / LinkSticker.WIDTH
        canvas.scale(scale, dst.height() / LinkSticker.HEIGHT)
        // A sticker is shown much smaller on its page card and map chip than it was drawn, and ink
        // scaled down that far fades to hairlines on e-ink; hold every stroke at a legible minimum.
        val minWidth = MIN_RENDERED_WIDTH_PX / scale
        for (stroke in sticker.strokes) {
            val legible = if (stroke.widthBase < minWidth) stroke.copy(widthBase = minWidth) else stroke
            strokeRenderer.draw(canvas, legible)
        }
        canvas.restoreToCount(save)
    }

    /** A standalone [widthPx] x [heightPx] bitmap of [sticker] on white — the links-map chip image. */
    fun toBitmap(sticker: LinkSticker, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas, sticker, RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()))
        return bitmap
    }

    private companion object {
        /** Thinnest a sticker stroke is drawn on screen, in device pixels. */
        const val MIN_RENDERED_WIDTH_PX = 3f
    }
}
