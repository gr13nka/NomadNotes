package com.nomadnotes.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.nomadnotes.R
import com.nomadnotes.app.ui.BorderWidth
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkBracket
import com.nomadnotes.app.ui.EinkMuted
import com.nomadnotes.app.ui.EinkSpacing
import com.nomadnotes.app.ui.EinkTypography
import com.nomadnotes.app.ui.EinkWhite
import com.nomadnotes.core.links.Offset01

/**
 * The links map panel, anchored under the bar's `[⋈]` glyph (spec §4 "Links mini map", mockup
 * `2026-09-22-links-minimap-mockups.html`): a "map" header, the square [MapBody] — the centred page
 * plus its one-hop neighbours on a ring — and a footer that appears only once there is something for
 * it to say (`[open] [centre]` once a chip is selected, `[‹ back]` once the map has been recentred).
 * The mockup's `[⤢]` full-frame expand is left for a later pass (`docs/BACKLOG.md`); this panel is
 * always the anchored size.
 */
@Composable
internal fun LinksMapPanel(
    state: LinksMapPanelState,
    actions: LinksMapPanelActions,
    anchor: Rect,
    onBoundsChanged: (android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelAnchor(anchor = anchor, maxWidth = 560.dp, onBoundsChanged = onBoundsChanged, modifier = modifier) {
        Column(
            modifier = Modifier.padding(EinkSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(EinkSpacing.XS),
        ) {
            Text(stringResource(R.string.map_header), style = EinkTypography.Caption)
            PanelHairline()
            if (state.loading) {
                MapCaption(stringResource(R.string.map_loading))
            } else {
                MapBody(state, actions)
                if (state.isEmpty) {
                    Text(stringResource(R.string.map_empty), style = EinkTypography.Caption)
                }
            }
            if (state.selected != null || state.canGoBack) {
                PanelHairline()
                MapFooter(state, actions)
            }
        }
    }
}

/** A caption centred in the same square [MapBody] occupies, so settling into place never resizes the panel. */
@Composable
private fun MapCaption(text: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Text(text, style = EinkTypography.Caption)
    }
}

/**
 * The square map body: the centre chip, its neighbours placed on [LinksMapNeighbour.position]'s ring
 * (`com.nomadnotes.core.links.radialLayout`), and the undirected edges between them drawn underneath
 * — plain lines, dashed for a cross-notebook connection, exactly like the chip border that names the
 * same crossing. Every chip's centre is clamped [CHIP_CLAMP_HALF_WIDTH]/[CHIP_CLAMP_HALF_HEIGHT] in
 * from each edge, so a chip near the ring's rim never draws partly outside the box.
 *
 * The empty-area tap catcher sits *first*, underneath the edges and chips: Compose gives a later
 * sibling first claim on a tap where they overlap, so a chip's own tap target still wins there, and
 * the catcher only ever fires on a tap that reaches no chip.
 */
@Composable
private fun MapBody(state: LinksMapPanelState, actions: LinksMapPanelActions) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        fun clampedCentre(position: Offset01): DpOffset {
            val hiX = (maxWidth - CHIP_CLAMP_HALF_WIDTH).coerceAtLeast(CHIP_CLAMP_HALF_WIDTH)
            val hiY = (maxHeight - CHIP_CLAMP_HALF_HEIGHT).coerceAtLeast(CHIP_CLAMP_HALF_HEIGHT)
            return DpOffset(
                x = (maxWidth * position.x).coerceIn(CHIP_CLAMP_HALF_WIDTH, hiX),
                y = (maxHeight * position.y).coerceIn(CHIP_CLAMP_HALF_HEIGHT, hiY),
            )
        }

        val centreAt = clampedCentre(Offset01(0.5f, 0.5f))
        val neighbourAt = state.neighbours.map { clampedCentre(it.position) }

        Box(Modifier.matchParentSize().clickable(onClick = { actions.onSelectMapNode(null) }))

        Canvas(Modifier.matchParentSize()) {
            val from = Offset(centreAt.x.toPx(), centreAt.y.toPx())
            for ((i, neighbour) in state.neighbours.withIndex()) {
                val to = Offset(neighbourAt[i].x.toPx(), neighbourAt[i].y.toPx())
                drawLine(
                    color = EinkBlack,
                    start = from,
                    end = to,
                    strokeWidth = EDGE_WIDTH.toPx(),
                    pathEffect = if (neighbour.isCrossNotebook) dashEffect() else null,
                )
            }
        }

        MapChip(
            label = state.centreLabel,
            bitmap = state.centreBitmap,
            linkCount = null,
            isCentre = true,
            isCrossNotebook = false,
            isSelected = false,
            onClick = null,
            modifier = chipOffset(centreAt),
        )
        for ((i, neighbour) in state.neighbours.withIndex()) {
            MapChip(
                label = neighbour.label,
                bitmap = neighbour.bitmap,
                linkCount = neighbour.linkCount,
                isCentre = false,
                isCrossNotebook = neighbour.isCrossNotebook,
                isSelected = state.selected == neighbour.ref,
                onClick = { actions.onSelectMapNode(neighbour.ref) },
                modifier = chipOffset(neighbourAt[i]),
            )
        }
    }
}

/** Places a chip so [centre] lands at its middle, given the fixed clamp footprint every chip assumes. */
private fun chipOffset(centre: DpOffset): Modifier =
    Modifier.offset(x = centre.x - CHIP_CLAMP_HALF_WIDTH, y = centre.y - CHIP_CLAMP_HALF_HEIGHT)

/**
 * One map chip — the centre or a neighbour. [isCentre] with no [bitmap] fills solid black with
 * white text (the mockup's root chip); a [bitmap] present, centre or not, shows it instead of
 * [label]. [isSelected] and "the centre with a sticker" both read as a 3 dp border, one step past
 * the ordinary [BorderWidth] — [isCentre] with no [bitmap] needs no such emphasis, since the solid
 * fill already sets it apart. A [linkCount] of null (the centre) omits the caption line entirely,
 * matching the mockup's own root chip, which carries no "N links" meta.
 */
@Composable
private fun MapChip(
    label: String,
    bitmap: ImageBitmap?,
    linkCount: Int?,
    isCentre: Boolean,
    isCrossNotebook: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val solidFill = isCentre && bitmap == null
    val borderWidth = if (isSelected || (isCentre && bitmap != null)) 3.dp else BorderWidth
    val contentColor = if (solidFill) EinkWhite else EinkBlack

    var chipModifier = modifier
        .defaultMinSize(minHeight = 48.dp)
        .background(if (solidFill) EinkBlack else EinkWhite)
    chipModifier = if (isCrossNotebook) {
        chipModifier.dashedBorder(borderWidth)
    } else {
        chipModifier.border(borderWidth, EinkBlack)
    }
    if (onClick != null) chipModifier = chipModifier.clickable(onClick = onClick)
    chipModifier = chipModifier.padding(horizontal = EinkSpacing.XS, vertical = 4.dp)

    Column(
        modifier = chipModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(width = 96.dp, height = 48.dp))
        } else {
            Text(label, style = EinkTypography.Body, color = contentColor, maxLines = 1)
        }
        if (linkCount != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The label doubles as the caption's own "#N" only when it isn't already the chip's
                // main content (no bitmap) — showing it twice on the same chip would be noise.
                if (bitmap != null) {
                    Text(label, style = EinkTypography.Caption, color = contentColor)
                }
                Text(
                    stringResource(R.string.map_link_count, linkCount),
                    style = EinkTypography.Caption,
                    color = if (solidFill) EinkWhite else EinkMuted,
                )
            }
        }
    }
}

@Composable
private fun MapFooter(state: LinksMapPanelState, actions: LinksMapPanelActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EinkSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.selected?.let { ref ->
            EinkBracket(stringResource(R.string.map_open), onClick = { actions.onOpenMapNode(ref) })
            EinkBracket(stringResource(R.string.map_centre), onClick = { actions.onCentreMapNode(ref) })
        }
        if (state.canGoBack) {
            EinkBracket(stringResource(R.string.map_back), onClick = actions::onMapBack)
        }
    }
}

/** [Modifier.border] has no dashed variant; a cross-notebook chip draws its own border directly. */
private fun Modifier.dashedBorder(width: Dp): Modifier = drawWithContent {
    drawContent()
    val strokePx = width.toPx()
    drawRect(
        color = EinkBlack,
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(size.width - strokePx, size.height - strokePx),
        style = Stroke(width = strokePx, pathEffect = dashEffect()),
    )
}

/** The one dash pattern every dashed edge/border on the map shares. */
private fun dashEffect(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

/** 1.5 dp ink lines connect chip centres (spec §4 "Undirected edges"). */
private val EDGE_WIDTH = 1.5.dp

// The fixed half-footprint every chip is assumed to have for clamping purposes, matched loosely to
// a bitmap chip's own rendered size (96 dp content + padding/caption) — the same simplification the
// approved mockup's own CHIP_HALF_W/CHIP_HALF_H constants make, rather than a full two-pass measure.
private val CHIP_CLAMP_HALF_WIDTH = 64.dp
private val CHIP_CLAMP_HALF_HEIGHT = 34.dp
