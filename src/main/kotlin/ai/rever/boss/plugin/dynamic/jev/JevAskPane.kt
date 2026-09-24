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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun AskPane(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, hasKey: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < CompactWidth
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Column(Modifier.widthIn(max = PaneMaxWidth), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ComposeSection(viewModel)
                    ContextSection(state, viewModel)
                    QuestionsSection(state, viewModel, compact)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(JevTokens.Border))
            RunBar(state, viewModel, hasKey, compact)
        }
    }
}

@Composable
private fun ContextSection(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel) {
    Column(Modifier.issueAnchor(JevField.Context)) {
        PaneLabel("Context") {
            when (val f = state.contextFormat) {
                JevContextFormat.Empty -> JevChip("Empty", ChipTone.ERROR)
                is JevContextFormat.Invalid -> {
                    JevChip("Looks like JSON, does not parse", ChipTone.ERROR, Modifier.weight(1f, fill = false))
                    JevLink("Send as text", { viewModel.setSendAsText(true) })
                }
                is JevContextFormat.Json -> JevChip(f.label)
                is JevContextFormat.Text -> {
                    JevChip("Plain text · ${f.chars} chars", modifier = Modifier.weight(1f, fill = false))
                    if (state.sendAsText) JevLink("Detect format", { viewModel.setSendAsText(false) })
                }
            }
        }
        JevInput(
            value = state.contextText,
            onValueChange = viewModel::setContext,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = "The situation Jev should judge: plain text, or a JSON object or array",
            isError = state.issue(JevField.Context) != null,
            mono = true,
            singleLine = false,
            minHeight = 96,
        )
        FieldError(state.issue(JevField.Context))
    }
}

@Composable
private fun QuestionsSection(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, compact: Boolean) {
    val limit = viewModel.decisionService.limits.maxQuestions
    Column {
        PaneLabel("Questions") {
            val count = state.questions.size
            Text("$count / $limit", color = if (count > limit - 3) JevTokens.Warning else JevTokens.TextMuted, fontSize = 11.sp)
            Box(Modifier.weight(1f))
            JevSegmented(
                listOf(JevEditorMode.FORM to "Form", JevEditorMode.JSON to "JSON"),
                selected = state.editorMode,
                onSelect = viewModel::setEditorMode,
            )
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.editorMode) {
                JevEditorMode.FORM -> {
                    state.questions.forEachIndexed { index, question ->
                        androidx.compose.runtime.key(question.key) { QuestionCard(state, viewModel, index, question, compact) }
                    }
                    FieldError(state.issue(JevField.Questions))
                    AddQuestionRow(viewModel, enabled = state.questions.size < limit)
                }
                JevEditorMode.JSON -> JsonEditor(state, viewModel)
            }
        }
    }
}

@Composable
private fun JsonEditor(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel) {
    val jsonIssues = state.issues.filter { it.field == JevField.Json }
    Column(Modifier.issueAnchor(JevField.Json)) {
        JevInput(
            value = state.jsonText,
            onValueChange = viewModel::setJsonText,
            modifier = Modifier.fillMaxWidth(),
            isError = jsonIssues.isNotEmpty(),
            mono = true,
            singleLine = false,
            minHeight = 240,
        )
        if (jsonIssues.isEmpty()) {
            Text(
                if (state.jsonOnly) "This rubric uses structured instructions, so it stays in JSON."
                else "Same shape as questions in jev_decide. Paste an agent's payload here.",
                color = JevTokens.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            jsonIssues.take(6).forEach { FieldError(it.message) }
            if (jsonIssues.size > 6) FieldError("…and ${jsonIssues.size - 6} more")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, index: Int, q: JevQuestionDraft, compact: Boolean) {
    Column(
        Modifier.fillMaxWidth().issueAnchor(JevField.Question(index))
            .clip(JevTokens.Shape).background(JevTokens.Content).border(1.dp, JevTokens.Border, JevTokens.Shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val types = JevQuestionType.entries.map { it to it.label }
        Column {
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IdInput(state, viewModel, index, q, Modifier.weight(1f))
                    JevIconButton(Icons.Outlined.Close, "Delete question", onClick = { viewModel.removeQuestion(index) })
                }
                JevSegmented(types, q.type, { viewModel.setQuestionType(index, it) }, Modifier.padding(top = 6.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdInput(state, viewModel, index, q, Modifier.weight(1f))
                    JevSegmented(types, q.type, { viewModel.setQuestionType(index, it) })
                    JevIconButton(Icons.Outlined.Close, "Delete question", onClick = { viewModel.removeQuestion(index) })
                }
            }
            FieldError(state.issue(JevField.QuestionId(index)))
            FieldError(state.issue(JevField.Question(index)))
        }

        LabeledInput(
            label = "Instructions",
            value = q.instructions,
            onValueChange = { v -> viewModel.editQuestion(index) { copy(instructions = v) } },
            placeholder = "State the decision explicitly",
            field = JevField.Instructions(index),
            state = state,
        )

        when (q.type) {
            JevQuestionType.NOUL -> {
                LabeledInput("Yes when", q.yesWhen, { v -> viewModel.editQuestion(index) { copy(yesWhen = v) } },
                    "What makes the answer yes", JevField.YesWhen(index), state, hint = "optional")
                LabeledInput("No when", q.noWhen, { v -> viewModel.editQuestion(index) { copy(noWhen = v) } },
                    "What makes the answer no", JevField.NoWhen(index), state, hint = "optional")
            }
            JevQuestionType.CHOICE -> Column(Modifier.issueAnchor(JevField.Options(index))) {
                FieldLabel("Options", "name · what it means")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.options.forEachIndexed { j, option -> OptionRow(state, viewModel, index, j, option, compact) }
                }
                FieldError(state.issue(JevField.Options(index)))
                JevLink("+ Add option", { viewModel.addOption(index) }, Modifier.padding(top = 6.dp))
            }
            JevQuestionType.SCORE -> Column(Modifier.issueAnchor(JevField.Levels(index))) {
                FieldLabel("Levels", "lowest first · 2 to ${JevValidation.MAX_LEVELS}")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.levels.forEachIndexed { j, level ->
                        Column(Modifier.issueAnchor(JevField.Level(index, j))) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("$j", color = JevTokens.TextMuted, fontSize = 11.sp, fontFamily = JevTokens.Mono, modifier = Modifier.width(16.dp))
                                JevInput(level, { v -> viewModel.setLevel(index, j, v) }, Modifier.weight(1f),
                                    placeholder = "What level $j means", isError = state.issue(JevField.Level(index, j)) != null, singleLine = false)
                                JevIconButton(Icons.Outlined.Close, "Remove level $j", onClick = { viewModel.removeLevel(index, j) })
                            }
                            FieldError(state.issue(JevField.Level(index, j)))
                        }
                    }
                }
                FieldError(state.issue(JevField.Levels(index)))
                if (q.levels.size < JevValidation.MAX_LEVELS) JevLink("+ Add level", { viewModel.addLevel(index) }, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun IdInput(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, index: Int, q: JevQuestionDraft, modifier: Modifier) {
    JevInput(
        q.id, { v -> viewModel.editQuestion(index) { copy(id = v) } },
        modifier.issueAnchor(JevField.QuestionId(index)),
        placeholder = "question_id",
        isError = state.issue(JevField.QuestionId(index)) != null,
        mono = true,
    )
}

@Composable
private fun OptionRow(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, index: Int, j: Int, option: JevOptionDraft, compact: Boolean) {
    val nameField = JevField.OptionName(index, j)
    val descField = JevField.OptionDescription(index, j)
    val name: @Composable (Modifier) -> Unit = { m ->
        JevInput(option.name, { v -> viewModel.setOption(index, j, option.copy(name = v)) }, m.issueAnchor(nameField),
            placeholder = "name", isError = state.issue(nameField) != null, mono = true)
    }
    val desc: @Composable (Modifier) -> Unit = { m ->
        JevInput(option.description, { v -> viewModel.setOption(index, j, option.copy(description = v)) }, m.issueAnchor(descField),
            placeholder = "Description", isError = state.issue(descField) != null, singleLine = false)
    }
    Column {
        if (compact) {
            // Name and remove on one line, description below: both stay readable in a narrow sidebar.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                name(Modifier.weight(1f))
                JevIconButton(Icons.Outlined.Close, "Remove option", onClick = { viewModel.removeOption(index, j) })
            }
            desc(Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                name(Modifier.weight(0.36f))
                desc(Modifier.weight(0.64f))
                JevIconButton(Icons.Outlined.Close, "Remove option", onClick = { viewModel.removeOption(index, j) })
            }
        }
        FieldError(state.issue(nameField))
        FieldError(state.issue(descField))
    }
}

@Composable
private fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    field: JevField,
    state: JevPlaygroundState,
    hint: String? = null,
) {
    Column(Modifier.issueAnchor(field)) {
        FieldLabel(label, hint)
        // Wraps rather than scrolling sideways, so long instructions stay readable in a sidebar.
        JevInput(value, onValueChange, Modifier.fillMaxWidth(), placeholder = placeholder, isError = state.issue(field) != null, singleLine = false)
        FieldError(state.issue(field))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddQuestionRow(viewModel: JevPlaygroundViewModel, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add question", color = JevTokens.TextMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        JevQuestionType.entries.forEach { type ->
            Text(
                type.label,
                modifier = Modifier.clip(JevTokens.Shape).border(1.dp, JevTokens.Border, JevTokens.Shape)
                    .clickable(enabled = enabled) { viewModel.addQuestion(type) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                color = if (enabled) JevTokens.Text else JevTokens.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunBar(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, hasKey: Boolean, compact: Boolean) {
    val anchors = LocalIssueAnchors.current
    val scope = rememberCoroutineScope()
    FlowRow(
        Modifier.fillMaxWidth().background(JevTokens.Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val center = Modifier.align(Alignment.CenterVertically)
        when {
            !hasKey -> BossPrimaryButton("Connect OpenRouter", onClick = viewModel::openSettings, modifier = center.height(32.dp))
            state.running -> BossSecondaryButton("Cancel", onClick = viewModel::cancelRun, modifier = center.height(32.dp))
            else -> BossPrimaryButton(
                if (compact) "Run" else "Run  ⌘↵",
                onClick = viewModel::run,
                modifier = center.height(32.dp),
                enabled = state.request != null,
                icon = Icons.Outlined.PlayArrow,
            )
        }
        Box(center) { TimeoutPicker(state, viewModel) }
        Box(center) {
            JevIconButton(Icons.Outlined.ContentCopy, "Copy as jev_decide arguments", onClick = viewModel::copyMcpArguments, enabled = state.request != null)
        }
        val n = state.issues.size
        if (n > 0) {
            JevLink(
                if (n == 1) "1 issue" else "$n issues",
                onClick = {
                    viewModel.setPane(JevPane.ASK)
                    scope.launch { anchors.reveal(state.issues.first().field) }
                },
                modifier = center,
                color = JevTokens.Error,
            )
        } else if (!compact) {
            Text("Ready", color = JevTokens.TextMuted, fontSize = 12.sp, modifier = center)
        }
    }
}

@Composable
private fun TimeoutPicker(state: JevPlaygroundState, viewModel: JevPlaygroundViewModel) {
    var open by remember { mutableStateOf(false) }
    val seconds = state.timeoutMs / 1000
    Box {
        Row(
            Modifier.clip(JevTokens.Shape).border(1.dp, JevTokens.Border, JevTokens.Shape).clickable { open = true }
                .pointerHoverIcon(PointerIcon.Hand).padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$seconds s", color = JevTokens.TextSecondary, fontSize = 12.sp)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Timeout", tint = JevTokens.TextMuted, modifier = Modifier.size(14.dp))
        }
        if (open) {
            JevMenu(onDismiss = { open = false }, width = 180) {
                MenuGroup("Timeout")
                (TIMEOUT_CHOICES + state.timeoutMs).distinct().sorted().forEach { ms ->
                    MenuItem("${ms / 1000} s", onClick = { open = false; viewModel.setTimeout(ms) }, selected = ms == state.timeoutMs)
                }
            }
        }
    }
}

private val TIMEOUT_CHOICES = listOf(10_000L, 30_000L, 60_000L, 120_000L)
