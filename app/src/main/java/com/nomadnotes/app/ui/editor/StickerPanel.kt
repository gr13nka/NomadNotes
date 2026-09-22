package com.nomadnotes.app.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomadnotes.R
import com.nomadnotes.app.render.StickerRenderer
import com.nomadnotes.app.render.StrokeRenderer
import com.nomadnotes.app.ui.BorderWidth
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkWhite
import com.nomadnotes.core.LinkSticker
import com.nomadnotes.core.Stroke
import kotlin.math.roundToInt

/**
 * The sticker panel: a 2:1 drawing box — [LinkSticker]'s own aspect ratio, so what is drawn here
 * maps onto the card at its natural proportions with no letterboxing — over `[clear] [skip] [done]`.
 * Opens after a link's target is chosen (the create flow) or from the link-edit selection bar's
 * `[sticker]` verb; both leave `EditorActivity.uiStickerFlow` non-null, which is this panel's only
 * input, via [state]/[actions].
 *
 * Centred over the page rather than anchored under a bar glyph with [PanelAnchor] — that geometry
 * fits a panel opened by tapping the glyph it hangs off; this one opens on its own, mid-flow, with
 * no glyph to anchor to. [Scrim] plus an outside tap dismissing (wired by the caller, as
 * `LinkPickerPanel`'s own dialogs already do) is this panel's cancel affordance.
 *
 * The box is stylus-only for the Compose fallback path — a resting finger or palm draws nothing —
 * and pen samples arrive in the box's own on-screen pixels; they are mapped into [LinkSticker]'s
 * fixed sticker-space coordinates here, before reaching [actions], so `StickerDraft` never has to
 * know how large the box is drawn. That `pointerInput` capture is only the fallback for a backend
 * that cannot restrict raw drawing to the box ([com.nomadnotes.app.input.PenBackend.setCaptureRegion]
 * returning false, or not having engaged yet) — [StickerPanelState.nativeCapture] gates it off once
 * a native backend has taken the box over, so on Onyx hardware raw drawing owns the box's input
 * instead, lag-free like the main canvas, and this composable stops attaching a second capture path
 * that would otherwise draw over (and double-count) the same gesture (`EditorActivity` reports
 * finished gestures back through [StickerPanelState] the same way either path fills the draft).
 * [onDrawBoundsChanged] reports the box's own window bounds (distinct from [onBoundsChanged]'s
 * whole-panel bounds) so the caller can convert them into the region a native backend restricts
 * capture to.
 *
 * Redraws happen through the same [StickerRenderer]/[StrokeRenderer] the committed sticker card and
 * the main canvas's own touch preview use — not a hand-rolled line draw — so ink in this box looks
 * exactly like the ink it becomes. The committed strokes are cached to a bitmap and redrawn only
 * when they change ([committedInk]); without that, every sample of the live stroke (many times a
 * second) also fully repainted every already-finished stroke, which was the box's own share of the
 * sticker-drawing lag.
 */
@Composable
internal fun StickerPanel(
    state: StickerPanelState,
    actions: StickerPanelActions,
    modifier: Modifier = Modifier,
    onBoundsChanged: (android.graphics.Rect) -> Unit = {},
    onDrawBoundsChanged: (android.graphics.Rect) -> Unit = {},
) {
    val stickerRenderer = remember { StickerRenderer() }
    val strokeRenderer = remember { StrokeRenderer() }
    val committedCache = remember { CommittedInkCache() }
    Column(
        modifier = modifier
            .width(320.dp)
            .background(EinkWhite)
            .border(BorderWidth, EinkBlack)
            .padding(EinkSpacing.S)
            .onGloballyPositioned { onBoundsChanged(it.boundsInWindow().toAndroidRect()) },
        verticalArrangement = Arrangement.spacedBy(EinkSpacing.S),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f)
                .background(EinkWhite)
                .border(BorderWidth, EinkBlack)
                .onGloballyPositioned { onDrawBoundsChanged(it.boundsInWindow().toAndroidRect()) }
                // Native capture owns the box's input once engaged (state.nativeCapture) — attaching
                // this fallback too would give the same gesture two capture paths at once, one of
                // them the laggy one this state exists to retire (see the class doc above).
                .then(
                    if (state.nativeCapture) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // A pen's eraser end is still the pen — see GestureCollector.isStylus
                                // — a finger or resting palm is not, and draws nothing here.
                                if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) {
                                    return@awaitEachGesture
                                }
                                down.consume()
                                val toStickerSpace = boxToStickerSpace(size.width.toFloat(), size.height.toFloat())
                                val start = toStickerSpace(down.position)
                                actions.onStrokeStart(start.x, start.y, down.pressure, down.uptimeMillis)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (change.changedToUpIgnoreConsumed()) break
                                    val sample = toStickerSpace(change.position)
                                    actions.onStrokeSample(sample.x, sample.y, change.pressure, change.uptimeMillis)
                                }
                                actions.onStrokeEnd()
                            }
                        }
                    },
                ),
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val dst = RectF(0f, 0f, size.width, size.height)
                if (state.strokes.isNotEmpty()) {
                    val bitmap = committedInk(committedCache, stickerRenderer, state.strokes, size)
                    native.drawBitmap(bitmap, 0f, 0f, null)
                }
                if (state.liveStroke.isNotEmpty()) {
                    val save = native.save()
                    native.scale(dst.width() / LinkSticker.WIDTH, dst.height() / LinkSticker.HEIGHT)
                    // pendingEnd = true: the pen is still down for this one, exactly as the main
                    // canvas's own live INK preview draws it (AndroidPenBackend.drawPreview).
                    strokeRenderer.drawInk(
                        native, state.liveStroke, state.tool, state.widthBase, state.grayLevel, pendingEnd = true,
                    )
                    native.restoreToCount(save)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EinkBracket(stringResource(R.string.sticker_clear), onClick = actions::onClear)
            Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS)) {
                EinkBracket(stringResource(R.string.sticker_skip), onClick = actions::onSkip)
                EinkBracket(stringResource(R.string.sticker_done), onClick = actions::onDone)
            }
        }
    }
}

/** A box-pixel-to-sticker-space mapping for a box measuring [boxWidth] x [boxHeight]. */
private fun boxToStickerSpace(boxWidth: Float, boxHeight: Float): (Offset) -> Offset {
    val scaleX = LinkSticker.WIDTH / boxWidth
    val scaleY = LinkSticker.HEIGHT / boxHeight
    return { point -> Offset(point.x * scaleX, point.y * scaleY) }
}

/**
 * The last bitmap [committedInk] rendered [strokes] into, and how many strokes it holds — a plain
 * (non-Compose-state) holder `remember`ed once per panel instance, since it is written from the draw
 * phase and a `mutableStateOf` write there would schedule a recomposition on every frame instead of
 * just caching.
 */
private class CommittedInkCache {
    var bitmap: Bitmap? = null
    var strokeCount: Int = -1
}

/**
 * [cache]'s bitmap of [strokes] at [size], rebuilding it only when the stroke count or the box's own
 * size has changed since the last call — [StickerDraft.strokes]'s own identity never changes (it
 * mutates its backing list in place), so a count comparison is what actually detects a new or
 * cleared stroke, not a reference check. `[completed].size` distinguishes every case this cache
 * needs to: a stroke finished (grows), Clear (drops to zero, caught by the caller's own
 * `strokes.isNotEmpty()` guard before this is even called), or the edit flow's seeded strokes on
 * first draw.
 */
private fun committedInk(cache: CommittedInkCache, renderer: StickerRenderer, strokes: List<Stroke>, size: Size): Bitmap {
    val width = size.width.roundToInt().coerceAtLeast(1)
    val height = size.height.roundToInt().coerceAtLeast(1)
    val cached = cache.bitmap
    if (cached != null && cache.strokeCount == strokes.size && cached.width == width && cached.height == height) {
        return cached
    }
    val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    renderer.draw(android.graphics.Canvas(fresh), LinkSticker(strokes), RectF(0f, 0f, width.toFloat(), height.toFloat()))
    cache.bitmap = fresh
    cache.strokeCount = strokes.size
    return fresh
}


/** [Rect] (window pixels) to the `android.graphics.Rect` [onBoundsChanged] expects. */
private fun Rect.toAndroidRect(): android.graphics.Rect = android.graphics.Rect(
    left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt(),
)
