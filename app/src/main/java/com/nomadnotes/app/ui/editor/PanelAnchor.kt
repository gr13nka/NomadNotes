package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nomadnotes.app.ui.BorderWidth
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkWhite
import kotlin.math.roundToInt

/**
 * The one deep module for anchored-panel geometry: the Tool, Page and More panels, and the
 * library's `⋯` popover, all place their content through this rather than each computing its own
 * position. It positions [content] under [anchor]'s left/bottom edge (a glyph's bounds in window
 * pixels, as `Modifier.onGloballyPositioned { it.boundsInWindow() }` reports them — the same
 * coordinate space [com.nomadnotes.app.EditorActivity.updateToolbarExclude] already converts from),
 * clamped by [margin] so the panel never draws past the screen edge, and capped at [maxWidth].
 *
 * Call this once per open panel, as a direct child of the full-screen overlay Box the editor
 * stacks over the canvas — [PanelAnchor] measures its own size to know the screen bounds to clamp
 * against, so a smaller parent would clamp against less room than the actual screen.
 *
 * Deliberately two-pass: [content] is measured first (at up to [maxWidth] wide, minus margins) so
 * the clamp knows the panel's real height before placing it — placing first and clamping only the
 * top-left, the one-pass approach, would let a tall panel run off the bottom edge it was supposed
 * to be kept inside.
 *
 * Paper fill, a [BorderWidth] ink border, and no elevation — no shadow, matching the e-ink rule that
 * elevation is a border, never a cast shadow. This composable also draws no scrim: the no-scrims
 * rule means the page must stay legible behind an open panel, so dismissing on an outside tap or a
 * pen touch is the caller's job (the panel itself has no dimmed backdrop to catch that tap).
 *
 * [onBoundsChanged] reports the panel's own bounds in window pixels once placed, so the Activity can
 * fold them into its raw-drawing exclude rects the same way it already does for the toolbar — every
 * frame the panel's rect changes, not just once, since a panel's height depends on [content].
 */
@Composable
fun PanelAnchor(
    anchor: Rect,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 360.dp,
    margin: Dp = 16.dp,
    onBoundsChanged: (android.graphics.Rect) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // The screen-space origin of this composable's own top-left corner, in window pixels — the anchor
    // (also in window pixels) is converted into this composable's local coordinate space by
    // subtracting it. Zero until the first layout pass reports it, which only ever costs one extra
    // composition before the panel settles into place (see the class doc's "two-pass" note).
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { windowOrigin = it.positionInWindow() },
        content = {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .background(EinkWhite)
                    .border(BorderWidth, EinkBlack)
                    .onGloballyPositioned { coords ->
                        val topLeft = coords.positionInWindow()
                        onBoundsChanged(
                            android.graphics.Rect(
                                topLeft.x.roundToInt(),
                                topLeft.y.roundToInt(),
                                (topLeft.x + coords.size.width).roundToInt(),
                                (topLeft.y + coords.size.height).roundToInt(),
                            ),
                        )
                    },
                content = content,
            )
        },
    ) { measurables, constraints ->
        val marginPx = margin.roundToPx()
        val availableWidth = (constraints.maxWidth - 2 * marginPx).coerceAtLeast(0)
        val placeable = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = availableWidth),
        )
        val desiredLeft = (anchor.left - windowOrigin.x).roundToInt()
        val desiredTop = (anchor.bottom - windowOrigin.y).roundToInt()
        val x = desiredLeft.coerceIn(marginPx, (constraints.maxWidth - marginPx - placeable.width).coerceAtLeast(marginPx))
        val y = desiredTop.coerceIn(marginPx, (constraints.maxHeight - marginPx - placeable.height).coerceAtLeast(marginPx))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(x, y)
        }
    }
}
