package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.ui.BossSecondaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Plain-language composer: a chat model writes the context and questions below it. */
@Composable
internal fun ComposeSection(viewModel: JevPlaygroundViewModel) {
    val compose by viewModel.compose.collectAsState()
    val runs by viewModel.runs.collectAsState()
    LaunchedEffect(Unit) { if (!compose.modelsLoaded) viewModel.refreshModels() }
    val pendingRun = runs.firstOrNull()?.takeIf { run -> compose.turns.isNotEmpty() && compose.turns.none { it.runId == run.id } }

    Column {
        PaneLabel("Describe") {
            if (compose.turns.isNotEmpty()) {
                Row(
                    Modifier.clip(JevTokens.Shape).clickable(onClick = viewModel::toggleThread).pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val n = compose.turns.size
                    Text(if (n == 1) "1 turn" else "$n turns", color = JevTokens.TextMuted, fontSize = 11.sp)
                    Icon(
                        if (compose.threadOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (compose.threadOpen) "Hide thread" else "Show thread",
                        tint = JevTokens.TextMuted, modifier = Modifier.size(14.dp),
                    )
                }
            }
            Box(Modifier.weight(1f))
            if (compose.canRevert && !compose.busy) {
                JevIconButton(Icons.AutoMirrored.Outlined.Undo, "Revert last compose", onClick = viewModel::revertLastCompose)
            }
            ComposeModelPicker(compose, viewModel)
        }

        if (compose.threadOpen && compose.turns.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                compose.turns.forEach { TurnRow(it) }
            }
        }

        val placeholder = if (compose.turns.isEmpty()) {
            "What should Jev decide? For example: route support tickets to identity, billing or support, and decide whether to page on-call"
        } else {
            "Refine the draft, for example: add a question about cost, or make severity a score from 1 to 5"
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JevInput(
                value = compose.input,
                onValueChange = viewModel::setComposeInput,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter sends, Shift+Enter adds a line. Cmd+Enter stays the panel's Run shortcut.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed && !e.isMetaPressed && !e.isCtrlPressed) {
                        viewModel.sendCompose(); true
                    } else false
                },
                placeholder = placeholder,
                singleLine = false,
                minHeight = 52,
            )
            if (compose.busy) {
                BossSecondaryButton("Stop", onClick = viewModel::cancelCompose, modifier = Modifier.heightIn(min = 32.dp))
            } else {
                JevIconButton(
                    Icons.Outlined.ArrowUpward, "Send (Enter)", onClick = viewModel::sendCompose,
                    enabled = compose.input.isNotBlank(), tint = JevTokens.Accent,
                )
            }
        }

        when {
            compose.busy -> Text("Drafting…", color = JevTokens.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            compose.error != null -> ComposeErrorRow(compose.error!!, viewModel)
            pendingRun != null -> Text(
                "Your next message includes run #${pendingRun.id}, so you can ask why it chose what it did.",
                color = JevTokens.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TurnRow(turn: JevComposeTurn) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(turn.user, color = if (turn.reverted) JevTokens.TextMuted else JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(vertical = 2.dp).width(2.dp).fillMaxHeight().background(JevTokens.Border))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(turn.reply, color = JevTokens.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    turn.runId?.let { JevChip("saw run #$it") }
                    if (turn.issues > 0) JevChip(if (turn.issues == 1) "1 issue left" else "${turn.issues} issues left", ChipTone.WARNING)
                    if (turn.reverted) JevChip("reverted")
                }
            }
        }
    }
}

@Composable
private fun ComposeErrorRow(error: JevComposeError, viewModel: JevPlaygroundViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(error.message, color = JevTokens.Error, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error.needsProviders) JevLink("Open AI Providers", viewModel::openSettings)
            JevLink("Dismiss", viewModel::dismissComposeError, color = JevTokens.TextSecondary)
        }
    }
}

/** Which chat model writes the draft. Separate from the decision model in the header. */
@Composable
private fun ComposeModelPicker(compose: JevComposeState, viewModel: JevPlaygroundViewModel) {
    var open by remember { mutableStateOf(false) }
    val label = compose.model?.label ?: if (compose.models.isEmpty()) "No chat model" else "Choose model"
    Box {
        Row(
            Modifier.clip(JevTokens.Shape).clickable { open = true }.pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (compose.model == null) JevTokens.Warning else JevTokens.TextSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose chat model", tint = JevTokens.TextMuted, modifier = Modifier.size(14.dp))
        }
        if (open) {
            JevMenu(onDismiss = { open = false }, width = 300) {
                MenuGroup("Drafting model")
                if (compose.models.isEmpty()) {
                    Text(
                        "No chat models found. Add a provider and pick a model in Secret Manager → AI Providers.",
                        color = JevTokens.Warning, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        compose.models.forEach { model ->
                            MenuItem(
                                model.label,
                                onClick = { open = false; viewModel.setComposeModel(model) },
                                detail = model.providerName,
                                selected = model == compose.model,
                                trailing = {
                                    if (model == compose.model) Icon(Icons.Outlined.Check, null, tint = JevTokens.Accent, modifier = Modifier.size(14.dp))
                                    else Spacer(Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                }
                MenuDivider()
                MenuItem("Manage AI providers", onClick = { open = false; viewModel.openSettings() })
            }
        }
    }
}
