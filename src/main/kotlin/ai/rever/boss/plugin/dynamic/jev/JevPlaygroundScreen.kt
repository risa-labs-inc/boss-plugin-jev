package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTabIndicator
import ai.rever.boss.plugin.ui.BossTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Below this width Chat, Draft and Answer become tabs. */
internal val TwoPaneMinWidth: Dp = 640.dp

/** Below this width headers shorten and rows stack. */
internal val CompactWidth: Dp = 380.dp

/** Readable measure for pane content on very wide windows. */
internal val PaneMaxWidth: Dp = 760.dp

@Composable
fun JevPlaygroundScreen(viewModel: JevPlaygroundViewModel) {
    val state by viewModel.state.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val compose by viewModel.compose.collectAsState()
    val hasKey = viewModel.hasOpenRouterKey()
    var saveDialog by remember { mutableStateOf(false) }
    val anchors = remember { JevIssueAnchors() }

    CompositionLocalProvider(LocalIssueAnchors provides anchors) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(JevTokens.Panel).onPreviewKeyEvent { e ->
                val mod = e.isMetaPressed || e.isCtrlPressed
                when {
                    e.type != KeyEventType.KeyDown || !mod -> false
                    e.key == Key.Enter -> { viewModel.run(); true }
                    e.key == Key.S -> { if (!viewModel.save()) saveDialog = true; true }
                    else -> false
                }
            },
        ) {
            val twoPane = maxWidth >= TwoPaneMinWidth
            val compact = maxWidth < CompactWidth
            Column(Modifier.fillMaxSize()) {
                Header(state, viewModel, hasKey, compact, onSaveAs = { saveDialog = true })
                if (twoPane) {
                    // Conversation on the left; the Draft editor or the latest answer on the right.
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { ChatPane(state, viewModel, hasKey) }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(JevTokens.Border))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            PaneTabs(state, viewModel, listOf(JevPane.DRAFT, JevPane.ANSWER), selected = state.side)
                            Box(Modifier.fillMaxSize()) {
                                if (state.side == JevPane.ANSWER) AnswerPane(state, runs, viewModel, hasKey) else DraftPane(state, viewModel)
                            }
                        }
                    }
                } else {
                    PaneTabs(state, viewModel, JevPane.entries, selected = state.pane)
                    Box(Modifier.fillMaxSize()) {
                        when (state.pane) {
                            JevPane.CHAT -> ChatPane(state, viewModel, hasKey)
                            JevPane.DRAFT -> Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) { DraftPane(state, viewModel) }
                                Dock(state, compose, viewModel, hasKey, compact, showComposer = false)
                            }
                            JevPane.ANSWER -> AnswerPane(state, runs, viewModel, hasKey)
                        }
                    }
                }
            }
            Notice(state, viewModel, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 16.dp, end = 16.dp))
        }
    }

    if (saveDialog) {
        SaveAsDialog(
            initial = state.presetName ?: state.title.takeUnless { it == "Untitled" }.orEmpty(),
            existing = state.presetNames,
            onDismiss = { saveDialog = false },
            onSave = { name -> saveDialog = false; viewModel.saveAs(name) },
        )
    }
}

@Composable
private fun Header(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, hasKey: Boolean, compact: Boolean, onSaveAs: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Title and Save stay together on the left; the model picker holds the right edge.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { DocumentMenu(state, viewModel, onSaveAs) }
            JevIconButton(
                Icons.Outlined.Save,
                if (state.presetName == null) "Save as preset (⌘S)" else "Save \"${state.presetName}\" (⌘S)",
                onClick = { if (!viewModel.save()) onSaveAs() },
                enabled = state.dirty || state.presetName == null,
            )
        }
        ModelPicker(state, viewModel, hasKey, compact)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(JevTokens.Border))
}

@Composable
private fun DocumentMenu(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, onSaveAs: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Box {
        Row(
            Modifier.clip(JevTokens.Shape).clickable { open = true }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.title, color = JevTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (state.dirty) Box(Modifier.size(7.dp).clip(CircleShape).background(JevTokens.TextSecondary))
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Open presets", tint = JevTokens.TextMuted, modifier = Modifier.size(16.dp))
        }
        if (open) {
            JevMenu(onDismiss = { open = false; pendingDelete = null }) {
                if (state.presetNames.isNotEmpty()) {
                    MenuGroup("Saved presets")
                    state.presetNames.forEach { name ->
                        if (pendingDelete == name) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Delete \"$name\"?", color = JevTokens.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                JevLink("Delete", { viewModel.deletePreset(name); pendingDelete = null }, color = JevTokens.Error)
                                JevLink("Keep", { pendingDelete = null }, color = JevTokens.TextSecondary)
                            }
                        } else {
                            MenuItem(
                                name,
                                onClick = { open = false; viewModel.loadPreset(name) },
                                selected = name == state.presetName,
                                trailing = { JevIconButton(Icons.Outlined.Close, "Delete preset", onClick = { pendingDelete = name }) },
                            )
                        }
                    }
                    MenuDivider()
                }
                MenuGroup("Starters")
                JevStarter.entries.forEach { starter ->
                    MenuItem(starter.label, onClick = { open = false; viewModel.useStarter(starter) },
                        detail = starter.questions().joinToString(" + ") { it.type.label })
                }
                MenuDivider()
                MenuItem("New blank", onClick = { open = false; viewModel.newBlank() })
                MenuItem("Save as new preset…", onClick = { open = false; onSaveAs() })
            }
        }
    }
}

/** Status and model choice in one place: what will answer, and whether it can. */
@Composable
private fun ModelPicker(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, hasKey: Boolean, compact: Boolean) {
    var open by remember { mutableStateOf(false) }
    val model = JevModelCatalog.find(state.model) ?: JevModelCatalog.DEFAULT
    val color = if (hasKey) JevTokens.Success else JevTokens.Warning
    val label = when {
        !hasKey -> if (compact) "Needs key" else "Needs OpenRouter key"
        compact -> model.label
        else -> "${model.label} · ${model.providerLabel}"
    }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .border(1.dp, if (hasKey) JevTokens.Border else JevTokens.Warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { open = true }.pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(label, color = if (hasKey) JevTokens.TextSecondary else JevTokens.Warning, fontSize = 12.sp, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose model", tint = JevTokens.TextMuted, modifier = Modifier.size(14.dp))
        }
        if (open) {
            JevMenu(onDismiss = { open = false }, width = 280) {
                MenuGroup("Decision model")
                JevModelCatalog.all.forEach { option ->
                    MenuItem(
                        option.label,
                        onClick = { open = false; viewModel.setModel(option.id) },
                        detail = option.providerLabel,
                        selected = option.id == state.model,
                        trailing = {
                            if (option.id == state.model) Icon(Icons.Outlined.Check, null, tint = JevTokens.Accent, modifier = Modifier.size(14.dp))
                            else Spacer(Modifier.size(14.dp))
                        },
                    )
                }
                MenuDivider()
                Text(
                    if (hasKey) "OpenRouter key found in Secret Manager → AI Providers." else "No OpenRouter key yet. Add one and select any OpenRouter model there.",
                    color = if (hasKey) JevTokens.TextMuted else JevTokens.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                MenuItem("Open AI Providers in Secret Manager", onClick = { open = false; viewModel.openSettings() })
            }
        }
    }
}

@Composable
private fun PaneTabs(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, panes: List<JevPane>, selected: JevPane) {
    Row(Modifier.fillMaxWidth().height(34.dp).background(JevTokens.Panel)) {
        panes.forEach { pane ->
            val label = when (pane) { JevPane.CHAT -> "Chat"; JevPane.DRAFT -> "Draft"; JevPane.ANSWER -> "Answer" }
            val on = selected == pane
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { viewModel.setPane(pane) }.pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, color = if (on) JevTokens.Text else JevTokens.TextSecondary, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    if (pane == JevPane.ANSWER && state.unseenRun && !on) Box(Modifier.size(7.dp).clip(CircleShape).background(JevTokens.Accent))
                    if (pane == JevPane.DRAFT && state.issues.isNotEmpty() && !on) {
                        JevChip("${state.issues.size}", if (state.issues.size == state.placeholders.size) ChipTone.WARNING else ChipTone.ERROR)
                    }
                }
                if (on) BossTabIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(JevTokens.Border))
}

@Composable
private fun Notice(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, modifier: Modifier) {
    val message = state.notice ?: return
    LaunchedEffect(state.noticeSerial) {
        delay(2_500)
        viewModel.dismissNotice(state.noticeSerial)
    }
    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        color = JevTokens.MenuBackground,
        shape = JevTokens.Shape,
        border = BorderStroke(1.dp, JevTokens.MenuBorder),
        elevation = 6.dp,
    ) {
        Text(message, color = JevTokens.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun SaveAsDialog(initial: String, existing: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val error = when {
        trimmed.length > 80 -> "Use 80 characters or fewer"
        else -> null
    }
    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            color = JevTokens.Panel,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, JevTokens.Border),
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save preset", color = JevTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                BossTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = "ticket-routing",
                    isError = error != null,
                    errorMessage = error,
                )
                Text(
                    if (trimmed in existing) "Replaces the saved preset with this name." else "Saves the context, questions, model, and timeout. Never the key or run history.",
                    color = if (trimmed in existing) JevTokens.Warning else JevTokens.TextMuted,
                    fontSize = 12.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    BossSecondaryButton("Cancel", onClick = onDismiss)
                    BossPrimaryButton("Save", onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty() && error == null)
                }
            }
        }
    }
}
