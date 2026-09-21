package com.nomadnotes.app.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nomadnotes.R

/**
 * The look-and-feel every NomadNotes screen is built from: plain high-contrast black on white,
 * with the two things e-ink cannot tolerate — ripple and animation — designed out rather than
 * merely disabled.
 *
 * The controls here are deliberately not Material buttons/checkboxes/switches: those ripple and
 * animate their state changes, which on an e-ink panel means ghosting and a full-panel refresh.
 * These flat controls change appearance instantly (a border, or an inverted fill) with no
 * transition. [EinkTheme] additionally routes every `clickable`'s indication through [NoIndication]
 * so even ad-hoc clickable areas stay ripple-free.
 */

val EinkBlack = Color(0xFF000000)
val EinkWhite = Color(0xFFFFFFFF)

/** Captions, disabled text, and receded items — the spec's `muted` token (`#808080`). */
val EinkGray = Color(0xFF808080)

/** Spec-named alias of [EinkGray] for new chrome code; both names point at the same flat grey. */
val EinkMuted = EinkGray

private val EinkColors = lightColorScheme(
    primary = EinkBlack,
    onPrimary = EinkWhite,
    secondary = EinkBlack,
    onSecondary = EinkWhite,
    background = EinkWhite,
    onBackground = EinkBlack,
    surface = EinkWhite,
    onSurface = EinkBlack,
    outline = EinkBlack,
)

/** A no-op [androidx.compose.foundation.Indication]: pressing a control paints nothing (no ripple). */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColors) {
        CompositionLocalProvider(LocalIndication provides NoIndication) {
            content()
        }
    }
}

/** Control border (buttons, fields, panel edges) — unchanged from the app's original controls. */
val BorderWidth = 1.5.dp

/** Structural rule width: the bar's bottom edge, a panel's separators, the sidebar's right edge. */
val Hairline = 1.dp

/**
 * The typeface the landing page (`site/`) already uses, bundled so the app builds offline (see
 * `docs/internals/geist-font.md`). Only the two weights [EinkTypography] needs are vendored.
 */
val EinkFontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

/**
 * The three text roles every e-ink screen is built from, replacing the ad hoc 15/16/17/18/24 sp
 * sizes at each call site. [Title] names a place (a notebook), [Body] is what you read and act on
 * (rows, panel items, the page counter), [Caption] is secondary (counts, timestamps) and carries
 * [EinkMuted] as its default color. A composable can still override `color=` for a state the base
 * style doesn't know about (inverted, disabled, active).
 */
object EinkTypography {
    val Title = TextStyle(fontFamily = EinkFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = EinkBlack)
    val Body = TextStyle(fontFamily = EinkFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp, color = EinkBlack)
    val Caption = TextStyle(fontFamily = EinkFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = EinkMuted)
}

/**
 * The 8 dp grid every margin and gap on the e-ink chrome is drawn from, named like the existing
 * [S]/[M]/[L] stroke-width presets. [XL] doubles as [MinTouchTarget]: the spec's floor for anything
 * tappable, glyph plus surrounding hit area.
 */
object EinkSpacing {
    val XS = 8.dp
    val S = 16.dp
    val M = 24.dp
    val L = 32.dp
    val XL = 48.dp

    /** The e-ink touch-target floor ("glyphs are 24 dp inside a 48 dp hit area"). */
    val MinTouchTarget = XL
}

/** A bordered, tappable label. Greys out (and stops responding) when [enabled] is false. */
@Composable
fun EinkButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (enabled) EinkBlack else EinkGray
    Box(
        modifier = modifier
            .border(BorderWidth, ink)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * A bordered label that shows its [selected] state by inverting to a solid black fill with white
 * text — the whole vocabulary of "on" for a toolbar toggle, with no press animation.
 */
@Composable
fun EinkToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val outline = if (enabled) EinkBlack else EinkGray
    val fill = if (selected) EinkBlack else EinkWhite
    val text = when {
        !enabled -> EinkGray
        selected -> EinkWhite
        else -> EinkBlack
    }
    Box(
        modifier = modifier
            .background(fill)
            .border(BorderWidth, outline)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * A bash.org-style bracket control: `[label]`, drawn with no border. This composable adds the
 * brackets itself — pass the bare label (`"pen"`, `"≡"`, `"layers ›"`), not `"[pen]"`.
 *
 * [inverted] is the whole vocabulary of "on" or "pressed" here (a solid black fill, white text),
 * matching [EinkToggle]'s `selected` but without a border. [enabled] false mutes the text to
 * [EinkMuted] and stops clicks, taking precedence over [inverted] (a disabled control never shows
 * the active fill). Always at least [EinkSpacing.MinTouchTarget] on each side regardless of the
 * label's own size — the visible brackets stay tight around the text; the rest is invisible hit
 * area, per the e-ink touch-target rule.
 */
@Composable
fun EinkBracket(
    label: String,
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val activeText = if (inverted) EinkWhite else EinkBlack
    val text = if (enabled) activeText else EinkMuted
    val fill = if (enabled && inverted) EinkBlack else Color.Transparent
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = EinkSpacing.MinTouchTarget, minHeight = EinkSpacing.MinTouchTarget)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = EinkSpacing.XS, vertical = EinkSpacing.XS),
        contentAlignment = Alignment.Center,
    ) {
        Text("[$label]", style = EinkTypography.Body, color = text, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * A bash.org-style rating row for a stepped value, e.g. `width  [−] 3 [+]`: a muted caption
 * [label] on the left, the current [valueText] flanked by `[−]`/`[+]` [EinkBracket]s on the
 * right. [canDecrement]/[canIncrement] false mutes and disables the corresponding bracket rather
 * than hiding it, so the row's width and the value's position never shift as it walks to an end.
 */
@Composable
fun EinkRatingRow(
    label: String,
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = EinkTypography.Caption)
        Row(verticalAlignment = Alignment.CenterVertically) {
            EinkBracket("−", enabled = canDecrement, onClick = onDecrement)
            Text(
                valueText,
                style = EinkTypography.Body,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 30.dp),
            )
            EinkBracket("+", enabled = canIncrement, onClick = onIncrement)
        }
    }
}

/** A square check: an outlined box that fills solid black when [checked] (used for layer visibility). */
@Composable
fun EinkCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .border(BorderWidth, EinkBlack)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Box(Modifier.size(14.dp).background(EinkBlack))
    }
}

/** A round selector: an outlined circle with a solid centre when [selected] (the active layer). */
@Composable
fun EinkRadioDot(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(BorderWidth, EinkBlack, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(EinkBlack))
    }
}

/** A single-line text field in a plain box, with a grey [placeholder] and no floating-label animation. */
@Composable
fun EinkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(BorderWidth, EinkBlack)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = EinkBlack, fontSize = 16.sp),
            cursorBrush = SolidColor(EinkBlack),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = EinkGray, fontSize = 16.sp)
                inner()
            },
        )
    }
}

/** A modal card: a white, black-bordered [Column] centred over a dimmed screen. Dismiss is instant. */
@Composable
fun EinkDialogCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(EinkWhite)
                .border(BorderWidth, EinkBlack)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A yes/no confirmation over [EinkDialogCard]: a [title], a [message], and cancel/confirm buttons. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    EinkDialogCard(onDismiss = onDismiss) {
        Text(title, color = EinkBlack, fontSize = 18.sp)
        Text(message, color = EinkBlack, fontSize = 15.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            EinkButton(cancelLabel) { onDismiss() }
            EinkButton(confirmLabel) { onConfirm() }
        }
    }
}
