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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

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
val EinkGray = Color(0xFF888888)

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

private val BorderWidth = 1.5.dp

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
