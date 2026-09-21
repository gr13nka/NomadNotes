package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nomadnotes.R
import com.nomadnotes.app.ui.BorderWidth
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkMuted
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.EinkWhite

/**
 * The page's own width:height, taken from the canvas mockup (`viewBox="0 0 400 520"`) since no
 * page-geometry constant exists yet to read this from instead. Only [PageThumbCell] uses it, to
 * keep its empty box and any real thumbnail image at the same shape as the page it stands for.
 */
private const val PageAspectRatio = 400f / 520f

/**
 * The Page panel, anchored under the bar's `#N of M` counter (spec "Page panel", mockup screen 5):
 * the prev/counter/next row, a hairline, the [PagePanelState.strip] of page cells, a hairline, then
 * insert/delete. Deleting is a two-step local gesture — see [confirmingDelete] — because
 * [PagePanelActions] only hears the confirmed [PagePanelActions.onDeleteCurrentPage], never a raw
 * tap on `[delete]` (see that state's file doc).
 */
@Composable
internal fun PagePanel(
    state: PagePanelState,
    actions: PagePanelActions,
    anchor: Rect,
    onBoundsChanged: (android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on pageIndex so paging away from a page with its delete confirm armed doesn't leave it
    // armed for whatever page is current when the panel is next looked at.
    var confirmingDelete by remember(state.pageIndex) { mutableStateOf(false) }

    PanelAnchor(anchor = anchor, onBoundsChanged = onBoundsChanged, modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = EinkSpacing.XS)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EinkBracket(
                    label = stringResource(R.string.page_panel_prev),
                    enabled = state.canGoPrev,
                    onClick = actions::onPrevPage,
                )
                Text(
                    stringResource(R.string.chrome_page_position, state.pageIndex + 1, state.pageCount),
                    style = EinkTypography.Body,
                )
                EinkBracket(
                    label = stringResource(R.string.page_panel_next),
                    enabled = state.canGoNext,
                    onClick = actions::onNextPage,
                )
            }

            PanelHairline()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EinkSpacing.XS, vertical = EinkSpacing.S),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (i in state.strip) {
                    PageThumbCell(
                        pageIndex = i,
                        isCurrent = i == state.pageIndex,
                        thumbnail = state.thumbnailFor?.invoke(i),
                        onClick = { actions.onSelectPage(i) },
                    )
                }
            }

            PanelHairline()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EinkBracket(
                    label = stringResource(R.string.page_panel_insert_after),
                    onClick = actions::onInsertPageAfterCurrent,
                )
                EinkBracket(
                    label = stringResource(R.string.page_panel_delete),
                    enabled = state.canDelete,
                    onClick = { confirmingDelete = !confirmingDelete },
                )
            }

            if (confirmingDelete) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
                    horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.page_panel_delete_confirm, state.pageIndex + 1),
                        style = EinkTypography.Caption,
                    )
                    EinkBracket(
                        label = stringResource(R.string.page_panel_delete_yes),
                        onClick = {
                            confirmingDelete = false
                            actions.onDeleteCurrentPage()
                        },
                    )
                    EinkBracket(
                        label = stringResource(R.string.page_panel_delete_keep),
                        onClick = { confirmingDelete = false },
                    )
                }
            }
        }
    }
}

/**
 * One strip cell: a page-aspect box bordered like the current page (3 dp, inverted `#N` caption) or
 * any other (a plain [BorderWidth] border, muted caption) — see the spec's "Current and selected
 * states". Shows [thumbnail] when non-null (the [PagePanelState.thumbnailFor] Phase 2 hook); an
 * empty paper box otherwise, whether because thumbnails aren't cached for this index yet or because
 * the hook itself is `null` for the whole of Phase 1.
 */
@Composable
private fun PageThumbCell(
    pageIndex: Int,
    isCurrent: Boolean,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = EinkSpacing.MinTouchTarget, minHeight = EinkSpacing.MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(EinkSpacing.XS),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .aspectRatio(PageAspectRatio)
                .background(EinkWhite)
                .border(if (isCurrent) 3.dp else BorderWidth, EinkBlack),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val caption = "#${pageIndex + 1}"
            if (isCurrent) {
                Text(
                    caption,
                    style = EinkTypography.Caption,
                    color = EinkWhite,
                    modifier = Modifier
                        .background(EinkBlack)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            } else {
                Text(caption, style = EinkTypography.Caption, color = EinkMuted)
            }
        }
    }
}
