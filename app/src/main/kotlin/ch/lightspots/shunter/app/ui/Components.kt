package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lightspots.shunter.app.Operation
import ch.lightspots.shunter.app.Phase
import ch.lightspots.shunter.app.humanSize
import ch.lightspots.shunter.app.initials
import kotlinx.coroutines.delay

val CardShape = RoundedCornerShape(12.dp)
val ControlShape = RoundedCornerShape(8.dp)

/** A white (dark: raised) panel with a hairline border. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(CardShape)
            .background(colors.surface)
            .border(1.dp, colors.border, CardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, QUIET }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    val c = colors
    val (bg, fg, border) = when (style) {
        ButtonStyle.PRIMARY -> Triple(c.primary, c.onPrimary, null)
        ButtonStyle.SECONDARY -> Triple(c.surface, c.text, c.inputBorder)
        ButtonStyle.DANGER -> Triple(c.surface, c.danger, c.dangerBorder)
        ButtonStyle.QUIET -> Triple(Color.Transparent, c.textMuted, null)
    }
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier
            .heightIn(min = height)
            .clip(ControlShape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (border != null) Modifier.border(1.dp, border.copy(alpha = alpha), ControlShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = if (style == ButtonStyle.QUIET) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg.copy(alpha = alpha))
        Text(
            text,
            style = Type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (style == ButtonStyle.QUIET) 13.sp else 14.sp),
            color = fg.copy(alpha = alpha),
            maxLines = 1,
        )
    }
}

/** A small rounded label, e.g. a location or a site. */
@Composable
fun Badge(text: String, tone: Tone, modifier: Modifier = Modifier, pill: Boolean = true) {
    Text(
        text,
        modifier
            .clip(if (pill) CircleShape else RoundedCornerShape(6.dp))
            .background(tone.bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = Type.caption.copy(fontWeight = FontWeight.SemiBold),
        color = tone.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A pill that is on or off, for filters. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = colors
    Text(
        label,
        Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(if (selected) c.toggleOn else c.surface)
            .border(1.dp, if (selected) c.toggleOn else c.inputBorder, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        style = Type.small.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) c.onToggleOn else c.text,
    )
}

/** Buttons joined into one bar, one of them selected. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = colors
    Row(
        modifier
            .height(42.dp)
            .clip(ControlShape)
            .background(c.surface)
            .border(1.dp, c.inputBorder, ControlShape),
    ) {
        options.forEachIndexed { i, option ->
            val on = option == selected
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) c.toggleOn else c.surface)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onToggleOn else c.text)
            }
        }
    }
}

/** A one-line text input with a border; [leading] goes before the text (e.g. a search icon). */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    leading: ImageVector? = null,
    password: Boolean = false,
    textStyle: TextStyle = Type.body,
    description: String = placeholder,
    onSubmit: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier.semantics { contentDescription = description },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(height)
                    .clip(ControlShape)
                    .background(c.surface)
                    .border(1.dp, c.inputBorder, ControlShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = c.textMuted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = c.placeholder, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** A text input with a label above it. */
@Composable
fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    textStyle: TextStyle = Type.body,
    onSubmit: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
        TextInput(
            value,
            onValueChange,
            placeholder,
            Modifier.fillMaxWidth(),
            password = password,
            textStyle = textStyle,
            description = label,
            onSubmit = onSubmit,
            enabled = enabled,
        )
    }
}

/** Download progress with a Cancel button, for a running [Operation]. */
@Composable
fun OperationProgress(op: Operation, modifier: Modifier = Modifier) {
    val c = colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val total = op.total
        val fraction = if (total != null && total > 0) (op.downloaded.toFloat() / total).coerceIn(0f, 1f) else null
        Row(Modifier.fillMaxWidth()) {
            Text(
                when (op.phase) {
                    Phase.WAITING -> "Waiting"
                    Phase.DOWNLOADING -> "Downloading"
                    Phase.INSTALLING -> "Installing"
                    Phase.WORKING -> "Working"
                },
                Modifier.weight(1f),
                style = Type.caption,
                color = c.textSecondary,
            )
            if (op.phase == Phase.DOWNLOADING) {
                Text(
                    listOfNotNull(humanSize(op.downloaded), humanSize(total)).joinToString(" of "),
                    style = Type.caption,
                    color = c.textSecondary,
                )
            }
        }
        ProgressBar(if (op.phase == Phase.DOWNLOADING) fraction else null)
        if (op.phase == Phase.WAITING || op.phase == Phase.DOWNLOADING) {
            AppButton("Cancel", op::cancel, Modifier.align(Alignment.End), style = ButtonStyle.QUIET, height = 32.dp)
        }
    }
}

/** A thin bar; [fraction] null shows a full, lighter bar for work of unknown length. */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val c = colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.track)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction ?: 1f)
                .clip(CircleShape)
                .background(if (fraction == null) c.primary.copy(alpha = 0.45f) else c.primary),
        )
    }
}

/** A mod's picture, or its initials on a colored box while there is none. */
@Composable
fun Thumbnail(name: String, image: ImageBitmap?, tone: Tone, width: Dp, height: Dp = width, modifier: Modifier = Modifier) {
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(if (height > 48.dp) 10.dp else 8.dp)).background(tone.bg),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.size(width, height), contentScale = ContentScale.Crop)
        } else {
            val fontSize = if (height > 48.dp) 15.sp else 13.sp
            Text(initials(name), style = Type.small.copy(fontWeight = FontWeight.Bold, fontSize = fontSize), color = tone.fg)
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = colors.heading)
            Text(subtitle, style = Type.body, color = colors.textMuted)
        }
        actions()
    }
}

/** A scrolling screen with the content at most [maxWidth] wide (forms and lists read badly when very wide). */
@Composable
fun ScrollPage(maxWidth: Dp = 1104.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = maxWidth).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = Type.section, color = colors.heading)
}

/** A clickable text link. */
@Composable
fun Link(text: String, onClick: () -> Unit, color: Color = colors.link, style: TextStyle = Type.body) {
    Text(
        text,
        Modifier.clickable(role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        style = style.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
        color = color,
    )
}

/** Runs [block] once when [key] changes, after [delayMillis]; for search-as-you-type. */
@Composable
fun Debounced(key: Any?, delayMillis: Long = 350, block: suspend () -> Unit) {
    LaunchedEffect(key) {
        delay(delayMillis)
        block()
    }
}

/** Keeps a value across recompositions; shorthand for `remember { mutableStateOf(initial) }`. */
@Composable
fun <T> rememberState(initial: T) = remember { mutableStateOf(initial) }
