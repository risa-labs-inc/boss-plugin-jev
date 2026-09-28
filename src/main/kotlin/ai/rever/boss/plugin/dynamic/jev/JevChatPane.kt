package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Chat reads best at a measure narrower than the editor's. */
private val ThreadMaxWidth = 620.dp

internal val EXAMPLE_PROMPTS = listOf(
    "Route a support ticket to the right team, and decide whether to page on-call",
    "Decide whether a pull request is ready to merge",
    "Qualify an inbound sales lead by fit and urgency",
)

/** The conversation column: thread or empty state, then the composer and Run pinned at the bottom. */
@Composable
internal fun ChatPane(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, hasKey: Boolean) {
    val compose by viewModel.compose.collectAsState()
    val runs by viewModel.runs.collectAsState()
    LaunchedEffect(compose.models.isEmpty()) { if (compose.models.isEmpty()) viewModel.refreshModels() }
    val empty = compose.items.isEmpty() && compose.pending == null
    BoxWithConstraints(Modifier.fillMaxSize().background(JevTokens.Content)) {
        val compact = maxWidth < CompactWidth
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (empty) EmptyState(compose, viewModel) else Thread(state, compose, runs, viewModel)
            }
            Dock(state, compose, viewModel, hasKey, compact, showComposer = !empty)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(compose: JevComposeState, viewModel: JevPlaygroundViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = ThreadMaxWidth), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What do you want to decide?", color = JevTokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Describe it in plain language. Jev drafts the context and questions, you check them, then run.",
                    color = JevTokens.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            ComposerInput(compose, viewModel, minHeight = 84, placeholder = "For example: decide which team owns a support ticket, and whether it should page on-call")
            ModelFooter(compose, viewModel)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaLabel("Try")
                EXAMPLE_PROMPTS.forEach { prompt -> PromptChip(prompt, fill = true) { viewModel.sendCompose(prompt) } }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetaLabel("Or start from a template")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JevStarter.entries.forEach { starter -> JevLink(starter.label, { viewModel.useStarter(starter); viewModel.setPane(JevPane.DRAFT) }) }
                }
            }
        }
    }
}

@Composable
private fun Thread(state: JevPlaygroundState, compose: JevComposeState, runs: List<JevRunRecord>, viewModel: JevPlaygroundViewModel) {
    val list = rememberLazyListState()
    val showFill = state.placeholders.isNotEmpty() && compose.pending == null
    val count = compose.items.size + (if (compose.pending != null) 1 else 0) + (if (showFill) 1 else 0)
    LaunchedEffect(count) { if (count > 0) list.scrollToItem(count - 1) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val latestTurnId = compose.latestTurn?.id
        compose.items.forEach { item ->
            item(key = item.id) {
                Box(Modifier.widthIn(max = ThreadMaxWidth)) {
                    when (item) {
                        is JevThreadItem.Turn -> TurnCard(item, latest = item.id == latestTurnId, compose, viewModel)
                        is JevThreadItem.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            UserBubble(item.user)
                            FailedCard(item, viewModel)
                        }
                        is JevThreadItem.Ran -> runs.firstOrNull { it.id == item.runId }?.let { RunCard(it, viewModel) }
                        is JevThreadItem.RunFailed -> RunFailedLine(item, viewModel)
                        is JevThreadItem.Edited -> EditedLine(item)
                    }
                }
            }
        }
        compose.pending?.let { pending ->
            item(key = "pending") {
                Column(Modifier.widthIn(max = ThreadMaxWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UserBubble(pending.user)
                    PendingCard(pending, viewModel)
                }
            }
        }
        if (showFill) item(key = "fill") { Box(Modifier.widthIn(max = ThreadMaxWidth)) { FillCard(state, viewModel) } }
    }
}

@Composable
private fun UserBubble(text: String) {
    // Inset from the left so a long message still reads as the user's, not as a card.
    Box(Modifier.fillMaxWidth().padding(start = 36.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = JevTokens.Text, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.widthIn(max = 520.dp).clip(JevTokens.Shape).background(JevTokens.Raised).padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, border: Color = JevTokens.Border, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, border, JevTokens.Shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnCard(turn: JevThreadItem.Turn, latest: Boolean, compose: JevComposeState, viewModel: JevPlaygroundViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UserBubble(turn.user)
        val showUndo = latest && compose.canRevert && !turn.reverted
        Card {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(turn.reply, color = if (turn.reverted) JevTokens.TextMuted else JevTokens.Text, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.weight(1f))
                if (showUndo) JevLink("Undo", viewModel::revertLastCompose, color = JevTokens.TextSecondary)
            }
            if (turn.changes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { turn.changes.forEach { ChangeLine(it, turn.reverted) } }
            } else {
                Text("No changes to the draft", color = JevTokens.TextMuted, fontSize = 11.sp)
            }
            if (turn.issues > 0 || turn.runId != null || turn.reverted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (turn.reverted) JevChip("Undone")
                    if (turn.issues > 0 && !turn.reverted) JevChip(if (turn.issues == 1) "1 issue to fix" else "${turn.issues} issues to fix", ChipTone.WARNING)
                    turn.runId?.let { Text("Used run #$it", color = JevTokens.TextMuted, fontSize = 11.sp) }
                }
            }
        }
        if (latest && !turn.reverted && turn.suggestions.isNotEmpty() && !compose.busy) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                turn.suggestions.forEach { s -> PromptChip(s) { viewModel.sendCompose(s) } }
            }
        }
    }
}

@Composable
private fun ChangeLine(change: JevChange, muted: Boolean) {
    val (sign, color) = when (change.kind) {
        JevChangeKind.ADDED -> "+" to JevTokens.Success
        JevChangeKind.REMOVED -> "−" to JevTokens.Error
        JevChangeKind.CHANGED -> "~" to JevTokens.Warning
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text(sign, color = if (muted) JevTokens.TextMuted else color, fontSize = 12.sp, fontFamily = JevTokens.Mono, modifier = Modifier.width(10.dp))
        Text(change.subject, color = if (muted) JevTokens.TextMuted else JevTokens.Text, fontSize = 12.sp, fontFamily = JevTokens.Mono)
        if (change.detail.isNotEmpty()) {
            Text(
                if (change.kind == JevChangeKind.ADDED && change.subject != "context") "(${change.detail})" else change.detail,
                color = JevTokens.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun PendingCard(pending: JevComposePending, viewModel: JevPlaygroundViewModel) {
    var now by remember { mutableStateOf(viewModel.nowMs()) }
    LaunchedEffect(pending.startedAtMs) {
        while (true) { now = viewModel.nowMs(); delay(1_000) }
    }
    val seconds = ((now - pending.startedAtMs) / 1000).coerceAtLeast(0)
    val stage = when (val s = pending.stage) {
        JevComposeStage.Drafting -> "Drafting"
        is JevComposeStage.Fixing -> if (s.issues == 1) "Fixing 1 issue" else "Fixing ${s.issues} issues"
        JevComposeStage.Reformatting -> "Asking for valid JSON"
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = JevTokens.Accent, strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text("$stage · ${seconds} s", color = JevTokens.Text, fontSize = 12.sp)
                Text(
                    if (seconds >= SLOW_AFTER_S) "${pending.modelLabel} is slow; each step stops at ${JevComposer.CALL_TIMEOUT_MS / 1000} s"
                    else pending.modelLabel,
                    color = if (seconds >= SLOW_AFTER_S) JevTokens.Warning else JevTokens.TextMuted,
                    fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
            BossSecondaryButton("Cancel", onClick = viewModel::cancelCompose)
        }
    }
}

private const val SLOW_AFTER_S = 20

@Composable
private fun FailedCard(item: JevThreadItem.Failed, viewModel: JevPlaygroundViewModel) {
    Card(border = JevTokens.Error.copy(alpha = 0.5f)) {
        Text(item.error.message, color = JevTokens.Error, fontSize = 12.sp, lineHeight = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JevLink("Retry", { viewModel.retryCompose(item.id) })
            if (item.error.needsProviders) JevLink("Open AI Providers", viewModel::openSettings, color = JevTokens.TextSecondary)
        }
    }
}

/** Compact answer: the verdict per question, with the full view one click away. */
@Composable
private fun RunCard(run: JevRunRecord, viewModel: JevPlaygroundViewModel) {
    val answers = run.decision.response["answers"] as? JsonObject ?: return
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Run #${run.id}", color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("%,d ms".format(run.decision.latencyMs), color = JevTokens.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            JevLink("Open full answer", { viewModel.selectRun(run.id) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            answers.entries.take(MAX_ANSWER_ROWS).forEach { (id, value) ->
                val v = verdict(value as? JsonObject ?: return@forEach) ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(id, color = JevTokens.TextSecondary, fontSize = 12.sp, fontFamily = JevTokens.Mono, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(ANSWER_ID_WIDTH))
                    Text(v.first, color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(v.second, color = JevTokens.TextMuted, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (answers.size > MAX_ANSWER_ROWS) Text("+${answers.size - MAX_ANSWER_ROWS} more", color = JevTokens.TextMuted, fontSize = 11.sp)
        }
    }
}

private const val MAX_ANSWER_ROWS = 6
private val ANSWER_ID_WIDTH = 104.dp

/** Verdict and its strength: `Yes` / `73%`, `identity` / `91%`, `Prompt but scheduled` / `0.8 of 3`. */
internal fun verdict(answer: JsonObject): Pair<String, String>? {
    fun num(key: String) = (answer[key] as? JsonPrimitive)?.doubleOrNull
    return when ((answer["type"] as? JsonPrimitive)?.content) {
        "noul" -> num("noul")?.let { p -> if (p >= 0.5) "Yes" to wholePercent(p) else "No" to wholePercent(1 - p) }
        "choice" -> {
            val choice = (answer["choice"] as? JsonPrimitive)?.content ?: return null
            val p = ((answer["probabilities"] as? JsonObject)?.get(choice) as? JsonPrimitive)?.doubleOrNull
            choice to (p?.let(::wholePercent) ?: "")
        }
        "score" -> {
            val score = num("score") ?: return null
            val legend = answer["legend"] as? JsonObject
            val top = (legend?.size ?: 1) - 1
            val nearest = (legend?.get(Math.round(score).toString()) as? JsonPrimitive)?.content ?: "%.1f".format(score)
            nearest to "%.1f of %d".format(score, top)
        }
        else -> null
    }
}

private fun wholePercent(p: Double) = "%.0f%%".format(p * 100)

@Composable
private fun RunFailedLine(item: JevThreadItem.RunFailed, viewModel: JevPlaygroundViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Run failed: ${item.error.message}", color = JevTokens.Error, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
        JevLink("Details", { viewModel.setPane(JevPane.ANSWER) }, color = JevTokens.TextSecondary)
    }
}

@Composable
private fun EditedLine(item: JevThreadItem.Edited) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(JevTokens.Border))
        Text(if (item.byAgent) "An agent edited the draft over MCP" else "You edited the draft", color = JevTokens.TextMuted, fontSize = 11.sp)
        Box(Modifier.weight(1f).height(1.dp).background(JevTokens.Border))
    }
}

/** `<...>` values the composer could not know. Run stays disabled until each one is filled. */
@Composable
internal fun FillCard(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel) {
    val values = remember { mutableStateMapOf<String, String>() }
    val n = state.placeholders.size
    Card(border = JevTokens.Warning.copy(alpha = 0.55f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fill before running", color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            JevChip(if (n == 1) "1 field" else "$n fields", ChipTone.WARNING)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.placeholders.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Warning.copy(alpha = 0.07f)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(p.label, color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    JevInput(values[p.key].orEmpty(), { values[p.key] = it }, Modifier.fillMaxWidth(), placeholder = p.hint)
                }
            }
        }
        val any = values.values.any { it.isNotBlank() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BossPrimaryButton("Fill", onClick = { viewModel.fillPlaceholders(values.toMap()); values.clear() }, enabled = any, modifier = Modifier.height(30.dp))
            Text("Writes the values into the context.", color = JevTokens.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PromptChip(text: String, fill: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = JevTokens.Text, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = (if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(JevTokens.Shape).border(1.dp, JevTokens.Border, JevTokens.Shape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MetaLabel(text: String) {
    Text(text.uppercase(), color = JevTokens.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

/** Multi-line input with a send button. Enter sends, Shift+Enter adds a line; ⌘↵ stays Run. */
@Composable
private fun ComposerInput(compose: JevComposeState, viewModel: JevPlaygroundViewModel, minHeight: Int, placeholder: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        JevInput(
            value = compose.input,
            onValueChange = viewModel::setComposeInput,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed && !e.isMetaPressed && !e.isCtrlPressed) {
                    viewModel.sendCompose(); true
                } else false
            },
            placeholder = placeholder,
            singleLine = false,
            minHeight = minHeight,
        )
        SendButton(enabled = compose.input.isNotBlank() && !compose.busy, onClick = { viewModel.sendCompose() })
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    // Accent only when there is something to send, so at rest Run is the one accent in the dock.
    Box(
        Modifier.size(32.dp).clip(JevTokens.Shape)
            .background(if (enabled) JevTokens.Accent else JevTokens.Raised)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Send (Enter)", tint = if (enabled) JevTokens.OnAccent else JevTokens.TextMuted, modifier = Modifier.size(16.dp))
    }
}

/** "Drafting with <model>": which chat model writes the draft. Separate from the decision model in the header. */
@Composable
private fun ModelFooter(compose: JevComposeState, viewModel: JevPlaygroundViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(JevTokens.Shape).clickable { open = true }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Drafting with ", color = JevTokens.TextMuted, fontSize = 12.sp, maxLines = 1)
            Text(
                compose.model?.label ?: if (compose.models.isEmpty()) "no chat model yet" else "the default model",
                color = if (compose.model == null && compose.models.isEmpty()) JevTokens.Warning else JevTokens.TextSecondary,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose drafting model", tint = JevTokens.TextMuted, modifier = Modifier.size(16.dp))
        }
        if (open) {
            JevMenu(onDismiss = { open = false }, width = 300) {
                MenuGroup("Drafting model")
                if (compose.models.isEmpty()) {
                    Text(
                        "No chat models found yet. Add a provider and pick a model in Secret Manager → AI Providers.",
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

/** Pinned at the bottom: composer, the drafting model, then Run. */
@Composable
internal fun Dock(
    state: JevPlaygroundState,
    compose: JevComposeState,
    viewModel: JevPlaygroundViewModel,
    hasKey: Boolean,
    compact: Boolean,
    showComposer: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(JevTokens.Panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(JevTokens.Border))
        if (showComposer) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ComposerInput(
                    compose, viewModel, minHeight = 40,
                    placeholder = if (compose.busy) "Drafting…" else "Ask for a change, such as \"add a question about cost\"",
                )
                ModelFooter(compose, viewModel)
            }
        }
        RunBar(state, viewModel, hasKey, compact)
    }
}
