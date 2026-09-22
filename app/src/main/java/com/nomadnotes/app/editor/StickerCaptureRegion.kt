package com.nomadnotes.app.editor

import com.nomadnotes.core.LinkSticker

/**
 * Maps a surface pixel — a point from a raw-drawing gesture the backend captured natively inside
 * the sticker box ([com.nomadnotes.app.input.PenBackend.setCaptureRegion]) — onto [LinkSticker]'s
 * fixed sticker-space coordinates. [boxLeft]/[boxTop]/[boxWidth]/[boxHeight] describe the box in the
 * same surface-local pixel space those gestures already arrive in (see `EditorActivity.toSurfaceLocal`).
 *
 * The native-capture counterpart to `StickerPanel`'s own Compose-pointer `boxToStickerSpace`: same
 * scale-to-fit mapping, but built from plain floats rather than a pointer event, so it is
 * unit-testable with no Android or Compose types. A degenerate (zero-size) box maps everything to
 * the sticker space's own origin rather than dividing by zero.
 */
fun surfaceToStickerSpace(
    x: Float,
    y: Float,
    boxLeft: Float,
    boxTop: Float,
    boxWidth: Float,
    boxHeight: Float,
): Pair<Float, Float> {
    if (boxWidth <= 0f || boxHeight <= 0f) return 0f to 0f
    val scaleX = LinkSticker.WIDTH / boxWidth
    val scaleY = LinkSticker.HEIGHT / boxHeight
    return (x - boxLeft) * scaleX to (y - boxTop) * scaleY
}
