package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import com.nomadnotes.R
import com.nomadnotes.app.LayerRow
import com.nomadnotes.app.render.TemplateRef
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkCheckbox
import com.nomadnotes.app.ui.EinkMuted
import com.nomadnotes.app.ui.EinkRadioDot
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.Hairline

/**
 * Root list, or one of the Layers/Template sub-pages, behind a `[‹]` back row — see the file doc
 * on [MorePanelState] for why the sub-page lives here as local state rather than in the Activity.
 * `remember`ed with no key: relies on the Activity composing [MorePanel] only while the panel is
 * open (dropping it from the tree on close) so this resets to [Root] the next time it opens, rather
 * than on some explicit reset signal.
 */
private enum class MoreSubPage { Root, Layers, Template }

/** `docs/superpowers/specs/2026-09-18-things-eink-ui-design.md` § "More panel"; mockup screen 6. */
@Composable
internal fun MorePanel(
    state: MorePanelState,
    actions: MorePanelActions,
    anchor: Rect,
    onBoundsChanged: (android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var subPage by remember { mutableStateOf(MoreSubPage.Root) }

    PanelAnchor(anchor = anchor, onBoundsChanged = onBoundsChanged, modifier = modifier) {
        Column(Modifier.padding(vertical = EinkSpacing.XS)) {
            when (subPage) {
                MoreSubPage.Root -> RootList(state, actions, onOpenSubPage = { subPage = it })
                MoreSubPage.Layers -> LayersSubPage(state, actions, onBack = { subPage = MoreSubPage.Root })
                MoreSubPage.Template -> TemplateSubPage(state, actions, onBack = { subPage = MoreSubPage.Root })
            }
        }
    }
}

@Composable
private fun RootList(state: MorePanelState, actions: MorePanelActions, onOpenSubPage: (MoreSubPage) -> Unit) {
    MoreRow(stringResource(R.string.more_layers)) { onOpenSubPage(MoreSubPage.Layers) }
    MoreRow(stringResource(R.string.more_template)) { onOpenSubPage(MoreSubPage.Template) }
    MoreRow(stringResource(R.string.more_insert_image), onClick = actions::onInsertImage)
    HairlineDivider()
    MoreRow(stringResource(R.string.more_undo), enabled = state.canUndo, onClick = actions::onUndo)
    MoreRow(stringResource(R.string.more_redo), enabled = state.canRedo, onClick = actions::onRedo)
    MoreRow(stringResource(R.string.more_hide_toolbar), onClick = actions::onHideToolbar)
}

@Composable
private fun LayersSubPage(state: MorePanelState, actions: MorePanelActions, onBack: () -> Unit) {
    MoreBackRow(stringResource(R.string.more_layers_title), onBack)
    HairlineDivider()
    for (row in state.layers) {
        LayerRowItem(row = row, isActive = row.id == state.activeLayerId, actions = actions)
    }
    MoreRow(stringResource(R.string.more_layer_add), enabled = state.canAddLayer, onClick = actions::onAddLayer)
}

@Composable
private fun LayerRowItem(row: LayerRow, isActive: Boolean, actions: MorePanelActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
        horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkCheckbox(checked = row.visible) { actions.onToggleLayerVisible(row.id, it) }
        EinkRadioDot(selected = isActive) { actions.onSelectActiveLayer(row.id) }
        Text(row.name, style = EinkTypography.Body, modifier = Modifier.weight(1f), maxLines = 1)
        EinkBracket(stringResource(R.string.more_layer_delete), enabled = !row.isMain) { actions.onRemoveLayer(row.id) }
    }
}

@Composable
private fun TemplateSubPage(state: MorePanelState, actions: MorePanelActions, onBack: () -> Unit) {
    val blankLabel = stringResource(R.string.template_value_blank)
    val linesLabel = stringResource(R.string.template_value_lines)
    val gridLabel = stringResource(R.string.template_value_grid)
    val current = TemplateRef.parse(state.templateRef)
    val options = buildList {
        add(Triple(blankLabel, current == TemplateRef.Blank) { actions.onSelectTemplate(null) })
        add(Triple(linesLabel, current == TemplateRef.Lines) { actions.onSelectTemplate(TemplateRef.LINES) })
        add(Triple(gridLabel, current == TemplateRef.Grid) { actions.onSelectTemplate(TemplateRef.GRID) })
        for (file in state.templateFiles) {
            add(Triple(file, current == TemplateRef.UserImage(file)) { actions.onSelectTemplate(TemplateRef.USER_PREFIX + file) })
        }
    }

    MoreBackRow(stringResource(R.string.more_template_title), onBack)
    HairlineDivider()
    Column(
        modifier = Modifier.padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
        verticalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
    ) {
        for (pair in options.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS)) {
                for ((label, selected, onSelect) in pair) {
                    EinkBracket(label, inverted = selected, modifier = Modifier.weight(1f), onClick = onSelect)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One row of the root list or a sub-page's own list of rows, e.g. `[layers ›]`: a left-aligned
 * bracket label spanning the panel's full width. Unlike [EinkBracket] — sized for a single bar
 * glyph and centred inside its own square hit box — a list row's hit area is the whole row, so
 * this starts the bracket at the row's edge instead of centring it.
 */
@Composable
private fun MoreRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = EinkSpacing.MinTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.XS),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("[$label]", style = EinkTypography.Body, color = if (enabled) EinkBlack else EinkMuted, maxLines = 1)
    }
}

/** The back row a sub-page shows in place of the root list's rows: `[‹]`, then the sub-page's name. */
@Composable
private fun MoreBackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = EinkSpacing.XS, vertical = EinkSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkBracket(stringResource(R.string.more_back), onClick = onBack)
        Text(title, style = EinkTypography.Body, modifier = Modifier.padding(start = EinkSpacing.XS))
    }
}

/** The root list's separator ahead of Undo/Redo/Hide, and each sub-page's separator under its back row. */
@Composable
private fun HairlineDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = EinkSpacing.XS)
            .height(Hairline)
            .background(EinkBlack),
    )
}
