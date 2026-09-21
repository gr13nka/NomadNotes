package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import com.nomadnotes.R
import com.nomadnotes.app.InkShade
import com.nomadnotes.app.StrokeWidth
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkRatingRow
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.Hairline

/**
 * The Tool panel, anchored under the bar's `[✎ <tool>]` glyph (spec "Tool panel", mockup screen
 * 4): the five-tool bracket row, a hairline, then the width/shade/smoothing rows — one
 * [EinkRatingRow] each for width and shade, a two-bracket row for smoothing — omitted per-row when
 * [ToolPanelState] carries `null` for it (the eraser and lasso are not ink tools).
 *
 * The tool row wraps left-aligned with a gap rather than spreading edge to edge (mockup's
 * `.tool-row{ flex-wrap:wrap; gap:6px 10px }`), since five brackets — two of them the longer
 * "pencil"/"marker" — would otherwise fight [PanelAnchor]'s fixed `maxWidth` for room at 360 dp;
 * a [FlowRow] wraps onto a second line rather than clipping or scrolling, the same opt-in the old
 * toolbar used for the same reason (`EditorActivity.kt`'s deleted `EditorToolbar`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolPanel(
    state: ToolPanelState,
    actions: ToolPanelActions,
    anchor: Rect,
    onBoundsChanged: (android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelAnchor(anchor = anchor, onBoundsChanged = onBoundsChanged, modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = EinkSpacing.S, vertical = EinkSpacing.S),
            verticalArrangement = Arrangement.spacedBy(EinkSpacing.S),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
                verticalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
            ) {
                for (tool in EditorTool.entries) {
                    EinkBracket(
                        label = stringResource(tool.labelRes),
                        inverted = tool == state.activeTool,
                        onClick = { actions.onSelectTool(tool) },
                    )
                }
            }

            if (state.width != null || state.shade != null || state.smoothingOn != null) {
                PanelHairline()
            }

            state.width?.let { width ->
                EinkRatingRow(
                    label = stringResource(R.string.rate_label_width),
                    valueText = stringResource(width.valueLabelRes),
                    canDecrement = width.ordinal > 0,
                    canIncrement = width.ordinal < StrokeWidth.entries.lastIndex,
                    onDecrement = actions::onDecreaseWidth,
                    onIncrement = actions::onIncreaseWidth,
                )
            }
            state.shade?.let { shade ->
                EinkRatingRow(
                    label = stringResource(R.string.rate_label_shade),
                    valueText = stringResource(shade.labelRes),
                    canDecrement = shade.ordinal > 0,
                    canIncrement = shade.ordinal < InkShade.entries.lastIndex,
                    onDecrement = actions::onDecreaseShade,
                    onIncrement = actions::onIncreaseShade,
                )
            }
            state.smoothingOn?.let { smoothingOn ->
                SmoothingRow(smoothingOn = smoothingOn, onToggle = actions::onToggleSmoothing)
            }
        }
    }
}

/**
 * `smoothing  [auto] [off]` (mockup screen 4): the same label-left, controls-right shape as
 * [EinkRatingRow], but a two-way choice rather than a stepped value, so it inverts whichever
 * bracket matches [smoothingOn] instead of showing `−`/`+`. Tapping the already-active bracket is a
 * no-op — [ToolPanelActions.onToggleSmoothing] only ever flips the state, so calling it again would
 * bounce back to the other one.
 */
@Composable
private fun SmoothingRow(smoothingOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.rate_label_smoothing), style = EinkTypography.Caption)
        Row(horizontalArrangement = Arrangement.spacedBy(EinkSpacing.XS)) {
            EinkBracket(
                label = stringResource(R.string.smoothing_value_auto),
                inverted = smoothingOn,
                onClick = { if (!smoothingOn) onToggle() },
            )
            EinkBracket(
                label = stringResource(R.string.smoothing_value_off),
                inverted = !smoothingOn,
                onClick = { if (smoothingOn) onToggle() },
            )
        }
    }
}

/** The 1 dp rule between a panel's sections (tool row / rating rows, or nav / strip / actions). */
@Composable
internal fun PanelHairline(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(Hairline).background(EinkBlack))
}

private val EditorTool.labelRes: Int
    get() = when (this) {
        EditorTool.PEN -> R.string.tool_pen
        EditorTool.PENCIL -> R.string.tool_pencil
        EditorTool.MARKER -> R.string.tool_marker
        EditorTool.ERASER -> R.string.tool_eraser
        EditorTool.LASSO -> R.string.tool_lasso
    }

/** The width rating row's value, as the digit the mockup shows ("3"), not the toolbar's old S/M/L. */
private val StrokeWidth.valueLabelRes: Int
    get() = when (this) {
        StrokeWidth.S -> R.string.width_value_1
        StrokeWidth.M -> R.string.width_value_2
        StrokeWidth.L -> R.string.width_value_3
    }

private val InkShade.labelRes: Int
    get() = when (this) {
        InkShade.BLACK -> R.string.shade_value_black
        InkShade.DARK -> R.string.shade_value_dark
        InkShade.LIGHT -> R.string.shade_value_light
    }
