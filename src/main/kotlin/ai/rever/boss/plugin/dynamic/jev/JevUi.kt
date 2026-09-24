package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossPopup
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** BOSS palette roles as the Jev panel uses them. */
internal object JevTokens {
    val Panel = BossThemeColors.SurfaceColor
    val Content = BossThemeColors.BackgroundColor
    val Raised = BossColors.darkSurface
    val Border = BossThemeColors.BorderColor
    val Text = BossThemeColors.TextPrimary
    val TextSecondary = BossThemeColors.TextSecondary
    val TextMuted = BossThemeColors.TextMuted
    val Accent = BossThemeColors.AccentColor
    /** Content on an accent fill, as BossPrimaryButton draws it. */
    val OnAccent = Color.White
    val Error = BossThemeColors.ErrorColor
    val Success = BossThemeColors.SuccessColor
    val Warning = BossThemeColors.WarningColor
    val MenuBackground = BossColors.contextMenuBackground
    val MenuBorder = BossColors.contextMenuBorder
    val MenuHover = BossColors.contextMenuHover
    val Mono = FontFamily.Monospace
    val Shape = RoundedCornerShape(6.dp)
}

/** Registry that lets the run bar scroll the first issue into view. */
internal class JevIssueAnchors {
    val requesters = mutableStateMapOf<JevField, BringIntoViewRequester>()

    suspend fun reveal(field: JevField) {
        val fallback = when (field) {
            is JevField.QuestionId -> JevField.Question(field.index)
            is JevField.Instructions -> JevField.Question(field.index)
            is JevField.YesWhen -> JevField.Question(field.index)
            is JevField.NoWhen -> JevField.Question(field.index)
            is JevField.Options -> JevField.Question(field.index)
            is JevField.OptionName -> JevField.Question(field.index)
            is JevField.OptionDescription -> JevField.Question(field.index)
            is JevField.Levels -> JevField.Question(field.index)
            is JevField.Level -> JevField.Question(field.index)
            else -> null
        }
        (requesters[field] ?: fallback?.let(requesters::get))?.bringIntoView()
    }
}

internal val LocalIssueAnchors = compositionLocalOf { JevIssueAnchors() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.issueAnchor(field: JevField): Modifier {
    val anchors = LocalIssueAnchors.current
    val requester = remember(field) { BringIntoViewRequester() }
    DisposableEffect(field, anchors) {
        anchors.requesters[field] = requester
        onDispose { if (anchors.requesters[field] === requester) anchors.requesters.remove(field) }
    }
    return bringIntoViewRequester(requester)
}

@Composable
internal fun PaneLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text.uppercase(), color = JevTokens.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        trailing()
    }
}

internal enum class ChipTone { NEUTRAL, ERROR, WARNING, SUCCESS }

@Composable
internal fun JevChip(text: String, tone: ChipTone = ChipTone.NEUTRAL, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        ChipTone.NEUTRAL -> JevTokens.Raised to JevTokens.TextSecondary
        ChipTone.ERROR -> JevTokens.Error.copy(alpha = 0.16f) to JevTokens.Error
        ChipTone.WARNING -> JevTokens.Warning.copy(alpha = 0.14f) to JevTokens.Warning
        ChipTone.SUCCESS -> JevTokens.Success.copy(alpha = 0.14f) to JevTokens.Success
    }
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
        color = fg,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun JevLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = JevTokens.Accent) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 2.dp, vertical = 1.dp),
        color = color,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

/** Segmented control in the BOSS input style. */
@Composable
internal fun <T> JevSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(JevTokens.Shape).background(JevTokens.Content).border(1.dp, JevTokens.Border, JevTokens.Shape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (on) JevTokens.Raised else Color.Transparent)
                    .clickable { onSelect(value) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = if (on) JevTokens.Text else JevTokens.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

/** Label-less text input with the same surface, border, and radius as BossTextField. */
@Composable
internal fun JevInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 0,
) {
    val style = TextStyle(
        color = JevTokens.Text,
        fontSize = if (mono) 12.sp else 13.sp,
        fontFamily = if (mono) JevTokens.Mono else FontFamily.Default,
        lineHeight = if (mono) 18.sp else 18.sp,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(JevTokens.Accent),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(min = minHeight.dp)
                    .background(JevTokens.Panel, JevTokens.Shape)
                    .border(1.dp, if (isError) JevTokens.Error else JevTokens.Border, JevTokens.Shape)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = style.copy(color = JevTokens.TextMuted), maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                }
                inner()
            }
        },
    )
}

@Composable
internal fun FieldError(message: String?) {
    if (message != null) Text(message, color = JevTokens.Error, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
}

@Composable
internal fun FieldLabel(text: String, hint: String? = null) {
    Row(Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        hint?.let { Text(it, color = JevTokens.TextMuted, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JevIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = JevTokens.TextSecondary) {
    TooltipArea(tooltip = {
        Surface(color = JevTokens.MenuBackground, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, JevTokens.MenuBorder)) {
            Text(label, color = JevTokens.Text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else JevTokens.TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
        }
    }
}

/** Menu surface anchored under its caller, rendered through BossPopup so it works over heavyweight content. */
@Composable
internal fun JevMenu(onDismiss: () -> Unit, width: Int = 260, content: @Composable ColumnScope.() -> Unit) {
    BossPopup(onDismissRequest = onDismiss, focusable = true, anchoring = BossPopupAnchoring.AnchorBounds) {
        Column(
            Modifier.width(width.dp)
                .clip(JevTokens.Shape)
                .background(JevTokens.MenuBackground)
                .border(1.dp, JevTokens.MenuBorder, JevTokens.Shape)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun MenuGroup(text: String) {
    Text(text.uppercase(), color = JevTokens.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp))
}

@Composable
internal fun MenuItem(
    text: String,
    onClick: () -> Unit,
    detail: String? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = JevTokens.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        detail?.let { Text(it, color = JevTokens.TextMuted, fontSize = 11.sp, maxLines = 1) }
        trailing?.invoke()
    }
}

@Composable
internal fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 1.dp).background(JevTokens.MenuBorder))
}

internal fun percent(value: Double): String =
    if (value >= 0.9995 || value < 0.0005) "%.0f%%".format(value * 100) else "%.1f%%".format(value * 100)
