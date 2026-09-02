package com.nomadnotes.app.editor

import com.nomadnotes.core.PageRect
import kotlin.math.abs
import kotlin.math.max

/** Which corner grip of a selected image the pen has hold of. */
enum class ImageGrip { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * Where a dragged image ends up: the pure geometry behind moving and resizing one, with no Android
 * graphics or editor state involved, so the rules can be stated and tested on their own.
 */
object ImagePlacement {

    /**
     * The grip within [within] pixels of ([x], [y]), or null when the pen is not on one — in which
     * case a drag starting inside the rectangle moves the image instead.
     *
     * The nearest grip wins, so overlapping touch areas on a small image still resolve to the corner
     * the user actually aimed at rather than whichever is checked first.
     */
    fun gripAt(rect: PageRect, x: Float, y: Float, within: Float): ImageGrip? {
        var best: ImageGrip? = null
        var bestDistanceSquared = within * within
        for (grip in ImageGrip.entries) {
            val (cornerX, cornerY) = corner(rect, grip)
            val dx = x - cornerX
            val dy = y - cornerY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                best = grip
            }
        }
        return best
    }

    /** [rect] shifted by ([dx], [dy]) page pixels. */
    fun moved(rect: PageRect, dx: Float, dy: Float): PageRect =
        PageRect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)

    /**
     * [rect] resized by dragging [grip] a distance of ([dx], [dy]), with the opposite corner pinned.
     *
     * The aspect ratio is preserved rather than following the pen exactly on both axes: these are
     * photographs, and a stretched one reads as a mistake, so the drag scales the picture and the
     * pen leads the corner rather than pinning it. Scale follows whichever axis the pen moved
     * further along, which is what makes a diagonal drag feel like it is tracking the corner.
     *
     * The result never shrinks below [minSize] on either side, so an image cannot be resized into a
     * sliver that is then impossible to grab again.
     */
    fun resized(rect: PageRect, grip: ImageGrip, dx: Float, dy: Float, minSize: Float): PageRect {
        val width = rect.width
        val height = rect.height
        if (width <= 0f || height <= 0f) return rect

        val (anchorX, anchorY) = corner(rect, opposite(grip))
        val (cornerX, cornerY) = corner(rect, grip)
        val wantedWidth = abs(cornerX + dx - anchorX)
        val wantedHeight = abs(cornerY + dy - anchorY)

        val scale = max(wantedWidth / width, wantedHeight / height)
        val minScale = max(minSize / width, minSize / height)
        val newWidth = width * max(scale, minScale)
        val newHeight = height * max(scale, minScale)

        // The rectangle grows away from the pinned corner, in the direction the grip sits.
        val left = if (grip == ImageGrip.TOP_LEFT || grip == ImageGrip.BOTTOM_LEFT) anchorX - newWidth else anchorX
        val top = if (grip == ImageGrip.TOP_LEFT || grip == ImageGrip.TOP_RIGHT) anchorY - newHeight else anchorY
        return PageRect(left = left, top = top, right = left + newWidth, bottom = top + newHeight)
    }

    private fun corner(rect: PageRect, grip: ImageGrip): Pair<Float, Float> = when (grip) {
        ImageGrip.TOP_LEFT -> rect.left to rect.top
        ImageGrip.TOP_RIGHT -> rect.right to rect.top
        ImageGrip.BOTTOM_RIGHT -> rect.right to rect.bottom
        ImageGrip.BOTTOM_LEFT -> rect.left to rect.bottom
    }

    private fun opposite(grip: ImageGrip): ImageGrip = when (grip) {
        ImageGrip.TOP_LEFT -> ImageGrip.BOTTOM_RIGHT
        ImageGrip.TOP_RIGHT -> ImageGrip.BOTTOM_LEFT
        ImageGrip.BOTTOM_RIGHT -> ImageGrip.TOP_LEFT
        ImageGrip.BOTTOM_LEFT -> ImageGrip.TOP_RIGHT
    }
}
