package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomadnotes.R
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.EinkWhite
import com.nomadnotes.app.ui.Hairline
import kotlin.math.roundToInt

/**
 * The bar across the top of the editor (`docs/superpowers/specs/2026-09-18-things-eink-ui-design.md`
 * §1 "The bar", mockup screens 3/6/7/9): one of [BarMode]'s three unrelated shapes at a time, with
 * no cross-fade between them — the e-ink no-motion rule.
 *
 * [onBoundsChanged] reports the window-pixel bounds of whatever is actually drawn — the full bar,
 * plus its jump-back line while one is showing, in [BarMode.Normal]/[BarMode.Selection]; just the
 * 48 dp `[≡]` in [BarMode.Hidden] — so `EditorActivity` can fold it into the raw-drawing exclude
 * rects the way it already does for the old toolbar. This is separate from the per-glyph anchor
 * [Rect]s that [EditorBarActions]' `onToggleXPanel` methods receive: those report one glyph's own
 * bounds, at the moment it's tapped, to place a panel; this reports the whole bar's bounds, on every
 * layout pass, to keep the pen out of it.
 */
@Composable
internal fun EditorBar(
    state: EditorBarState,
    actions: EditorBarActions,
    modifier: Modifier = Modifier,
    onBoundsChanged: (android.graphics.Rect) -> Unit,
) {
    val reportBounds = Modifier.onGloballyPositioned { onBoundsChanged(it.boundsInWindow().toAndroidRect()) }
    when (val mode = state.mode) {
        is BarMode.Hidden -> EinkBracket(
            label = stringResource(R.string.chrome_library),
            modifier = modifier.then(reportBounds),
            onClick = actions::onShowChrome,
        )

        is BarMode.Normal -> Column(modifier = modifier.then(reportBounds)) {
            NormalRow(mode, actions)
            HairlineDivider()
            mode.jumpBackLabel?.let { label -> JumpBackRow(label, actions::onJumpBack) }
        }

        is BarMode.Selection -> Column(modifier = modifier.then(reportBounds)) {
            SelectionRow(mode, actions)
            HairlineDivider()
        }
    }
}

@Composable
private fun NormalRow(mode: BarMode.Normal, actions: EditorBarActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(EinkWhite)
            .padding(horizontal = EinkSpacing.S),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS), verticalAlignment = Alignment.CenterVertically) {
            EinkBracket(stringResource(R.string.chrome_library), onClick = actions::onOpenLibrary)
            AnchoredGlyph(mode.activeTool.barLabel(), onTap = actions::onToggleToolPanel)
            PageCounter(
                text = stringResource(R.string.chrome_page_position, mode.pageIndex + 1, mode.pageCount),
                onTap = actions::onTogglePagePanel,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS), verticalAlignment = Alignment.CenterVertically) {
            if (mode.canPaste) {
                EinkBracket(stringResource(R.string.sel_paste), onClick = actions::onPaste)
            }
            AnchoredGlyph(stringResource(R.string.chrome_links_map), onTap = actions::onToggleLinksMap)
            AnchoredGlyph(stringResource(R.string.chrome_find), enabled = mode.findEnabled, onTap = actions::onToggleFind)
            AnchoredGlyph(stringResource(R.string.chrome_more), onTap = actions::onToggleMorePanel)
        }
    }
}

@Composable
private fun JumpBackRow(destinationLabel: String, onJumpBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(EinkWhite).padding(horizontal = EinkSpacing.XS)) {
        EinkBracket(
            // The mockup's single bracketed phrase "[← back to research #3]" (screen 6).
            label = stringResource(R.string.chrome_jump_back_line, destinationLabel),
            onClick = onJumpBack,
        )
    }
}

@Composable
private fun SelectionRow(mode: BarMode.Selection, actions: EditorBarActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(EinkWhite)
            .padding(horizontal = EinkSpacing.S),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.S), verticalAlignment = Alignment.CenterVertically) {
            EinkBracket(stringResource(R.string.chrome_deselect), onClick = actions::onDeselect)
            // A caught link or image with no strokes selected still gets its own status word, so the
            // left side of the bar is never blank while a verb is showing on the right. Link wins if
            // (rare) both are circled with nothing caught, since it's listed first among the verbs.
            val statusText = when {
                mode.strokeCount != null -> stringResource(R.string.sel_strokes_caught, mode.strokeCount)
                mode.circledLink -> stringResource(R.string.sel_link_caught)
                mode.circledImage -> stringResource(R.string.sel_image_caught)
                else -> null
            }
            statusText?.let { Text(it, style = EinkTypography.Body) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.S), verticalAlignment = Alignment.CenterVertically) {
            if (mode.strokeCount != null) {
                EinkBracket(stringResource(R.string.sel_copy), onClick = actions::onCopySelection)
            }
            if (mode.canLink) {
                EinkBracket(stringResource(R.string.sel_link), onClick = actions::onLinkSelection)
            }
            if (mode.strokeCount != null) {
                EinkBracket(stringResource(R.string.sel_delete), onClick = actions::onDeleteSelection)
            }
            if (mode.circledLink) {
                EinkBracket(stringResource(R.string.sel_edit_link), onClick = actions::onEditCircledLink)
                EinkBracket(stringResource(R.string.sel_sticker), onClick = actions::onEditCircledLinkSticker)
                EinkBracket(stringResource(R.string.sel_delete_link), onClick = actions::onDeleteCircledLink)
            }
            if (mode.circledImage) {
                EinkBracket(stringResource(R.string.sel_delete_image), onClick = actions::onDeleteCircledImage)
            }
        }
    }
}

@Composable
private fun HairlineDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(Hairline).background(EinkBlack))
}

/**
 * A bar glyph that opens a panel: wraps [EinkBracket], additionally capturing its own window-pixel
 * bounds and forwarding them to [onTap] on click — the anchor [EditorBarActions]' `onToggleXPanel`
 * methods expect (see that interface's doc and `PanelAnchor.kt`).
 */
@Composable
private fun AnchoredGlyph(label: String, onTap: (Rect) -> Unit, enabled: Boolean = true) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    EinkBracket(
        label = label,
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        enabled = enabled,
        onClick = { onTap(anchor) },
    )
}

/**
 * The bar's `#N of M` page counter: plain [EinkTypography.Body] text, deliberately not bracketed
 * (mockup screens 3/5/6/7 draw it as a bare counter), but a panel-opening glyph in every other
 * respect — same anchor convention as [AnchoredGlyph], same 48 dp touch-target floor.
 */
@Composable
private fun PageCounter(text: String, onTap: (Rect) -> Unit) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = EinkSpacing.MinTouchTarget, minHeight = EinkSpacing.MinTouchTarget)
            .onGloballyPositioned { anchor = it.boundsInWindow() }
            .clickable { onTap(anchor) }
            .padding(horizontal = EinkSpacing.XS, vertical = EinkSpacing.XS),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = EinkTypography.Body, maxLines = 1)
    }
}

/** The bar's own "current tool" label, e.g. "✎ pen" — always the ✎ glyph, per `chrome_active_tool`. */
@Composable
private fun EditorTool.barLabel(): String = stringResource(
    R.string.chrome_active_tool,
    stringResource(
        when (this) {
            EditorTool.PEN -> R.string.tool_pen
            EditorTool.PENCIL -> R.string.tool_pencil
            EditorTool.MARKER -> R.string.tool_marker
            EditorTool.ERASER -> R.string.tool_eraser
            EditorTool.LASSO -> R.string.tool_lasso
        },
    ),
)

/** [Rect] (window pixels, as `boundsInWindow()` reports) to the `android.graphics.Rect` [EditorBar]'s `onBoundsChanged` expects. */
private fun Rect.toAndroidRect(): android.graphics.Rect = android.graphics.Rect(
    left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt(),
)
