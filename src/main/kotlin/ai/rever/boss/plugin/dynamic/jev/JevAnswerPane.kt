package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val prettyJson = Json { prettyPrint = true }

@Composable
internal fun AnswerPane(state: JevPlaygroundState, runs: List<JevRunRecord>, viewModel: JevPlaygroundViewModel, hasKey: Boolean) {
    val displayed = if (state.error != null && state.selectedRunId == null) null
    else runs.firstOrNull { it.id == state.selectedRunId } ?: runs.firstOrNull()
    Column(Modifier.fillMaxSize().background(JevTokens.Content)) {
        if (runs.isNotEmpty()) RunStrip(runs, displayed?.id, viewModel)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxWidth < CompactWidth
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Column(Modifier.widthIn(max = PaneMaxWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val error = state.error
                    when {
                        state.running -> RunningView(state)
                        error != null && state.selectedRunId == null ->
                            if (error.code == "MISSING_OPENROUTER_KEY") SetupCard(viewModel) else ErrorCard(error, viewModel, hasRuns = runs.isNotEmpty())
                        displayed != null -> RunView(displayed, runs, state, viewModel, compact)
                        !hasKey -> SetupCard(viewModel)
                        else -> Starters(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunStrip(runs: List<JevRunRecord>, selectedId: Long?, viewModel: JevPlaygroundViewModel) {
    Column(Modifier.background(JevTokens.Panel)) {
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(runs, key = { it.id }) { run ->
                val on = run.id == selectedId
                Row(
                    Modifier.clip(JevTokens.Shape)
                        .background(if (on) JevTokens.Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (on) JevTokens.Accent else JevTokens.Border, JevTokens.Shape)
                        .clickable { viewModel.selectRun(run.id) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("#${run.id}", color = JevTokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (run.source == JevRunSource.MCP) JevChip("MCP", ChipTone.WARNING)
                    Text("${clock.format(run.at)} · ${summary(run)}", color = JevTokens.TextSecondary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(JevTokens.Border))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunView(run: JevRunRecord, runs: List<JevRunRecord>, state: JevPlaygroundState, viewModel: JevPlaygroundViewModel, compact: Boolean) {
    val response = run.decision.response
    val usage = response["usage"] as? JsonObject
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetaItem("Run", "#${run.id}")
        MetaItem("", "%,d ms".format(run.decision.latencyMs))
        usage?.let {
            MetaItem("", "${it.numberText("input_tokens")} in / ${it.numberText("output_tokens")} out")
            it["cost"]?.jsonPrimitive?.doubleOrNull?.let { cost -> MetaItem("", "$%.5f".format(cost)) }
        }
        (response["model"] as? JsonPrimitive)?.content?.let { MetaItem("", it) }
    }

    if (run.source == JevRunSource.MCP) {
        Banner(
            text = "Called over MCP at ${clock.format(run.at)}",
            action = "Load into editor",
            onAction = { viewModel.restoreRun(run.id) },
            tone = ChipTone.WARNING,
        )
    } else if (state.request != run.request) {
        Banner(text = "Inputs edited since run #${run.id}", action = "Restore #${run.id}", onAction = { viewModel.restoreRun(run.id) })
    }

    val answers = response["answers"] as? JsonObject
    answers?.entries?.forEach { (id, value) ->
        val answer = value as? JsonObject ?: return@forEach
        AnswerCard(id, answer, run, runs, compact)
    }

    var showRaw by remember(run.id) { mutableStateOf(false) }
    JevLink(if (showRaw) "Hide raw response" else "Show raw response", { showRaw = !showRaw }, color = JevTokens.TextSecondary)
    if (showRaw) {
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, JevTokens.Border, JevTokens.Shape)
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(prettyJson.encodeToString(JsonObject.serializer(), response), color = JevTokens.TextSecondary,
                    fontFamily = JevTokens.Mono, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun MetaItem(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, color = JevTokens.TextMuted, fontSize = 12.sp)
        Text(value, color = JevTokens.TextSecondary, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, tone: ChipTone = ChipTone.NEUTRAL) {
    val border = if (tone == ChipTone.WARNING) JevTokens.Warning.copy(alpha = 0.45f) else JevTokens.Border
    FlowRow(
        Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, border, JevTokens.Shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (tone == ChipTone.WARNING) JevChip("MCP", ChipTone.WARNING, Modifier.align(Alignment.CenterVertically))
        Text(text, color = JevTokens.Text, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        JevLink(action, onAction, Modifier.align(Alignment.CenterVertically))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCard(id: String, answer: JsonObject, run: JevRunRecord, runs: List<JevRunRecord>, compact: Boolean) {
    val question = run.request.questions[id] as? JsonObject
    val type = JevQuestionType.fromWire(answer.string("type"))
    Column(
        Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, JevTokens.Border, JevTokens.Shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(id, color = JevTokens.TextSecondary, fontSize = 12.sp, fontFamily = JevTokens.Mono, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            type?.let { Text(it.label, color = JevTokens.TextMuted, fontSize = 11.sp) }
        }
        val delta = delta(id, run, runs)
        when (type) {
            JevQuestionType.CHOICE -> {
                val choice = answer.string("choice").orEmpty()
                val probabilities = answer.probabilities()
                Verdict(choice, percent(probabilities[choice] ?: 0.0), delta)
                val description = (question?.get("criteria") as? JsonObject)?.get(choice)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                Text(
                    listOfNotNull(description, "provider confidence ${percent(answer.number("confidence"))}").joinToString(" · "),
                    color = JevTokens.TextSecondary, fontSize = 12.sp,
                )
                probabilities.entries.sortedByDescending { it.value }.forEach { (option, p) ->
                    ProbabilityBar(option, p, option == choice, compact)
                }
            }
            JevQuestionType.NOUL -> {
                val p = answer.number("noul")
                val yes = p >= 0.5
                Verdict(if (yes) "Yes" else "No", "${percent(if (yes) p else 1 - p)} likely", delta)
                val criteria = question?.get("criteria") as? JsonObject
                ((criteria?.get(if (yes) "true" else "false") as? JsonPrimitive)?.takeIf { it.isString }?.content)?.let {
                    Text(it, color = JevTokens.TextSecondary, fontSize = 12.sp)
                }
                SplitBar(p)
            }
            JevQuestionType.SCORE -> {
                val score = answer.number("score")
                val legend = (answer["legend"] as? JsonObject).orEmpty()
                val top = (legend.size - 1).coerceAtLeast(1)
                val nearest = score.roundToInt().coerceIn(0, top)
                Verdict("%.1f".format(score), "of $top", delta)
                Text(
                    "Closest level $nearest: ${(legend[nearest.toString()] as? JsonPrimitive)?.content.orEmpty()} · provider confidence ${percent(answer.number("confidence"))}",
                    color = JevTokens.TextSecondary, fontSize = 12.sp,
                )
                ScoreScale(score, top)
                answer.probabilities().forEach { (level, p) ->
                    val label = (legend[level] as? JsonPrimitive)?.content.orEmpty()
                    ProbabilityBar("$level · $label", p, level == nearest.toString(), compact)
                }
            }
            null -> Text(answer.toString(), color = JevTokens.TextSecondary, fontSize = 12.sp, fontFamily = JevTokens.Mono)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Verdict(value: String, detail: String, delta: Delta?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = JevTokens.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Bottom))
        Text(detail, color = JevTokens.TextSecondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp))
        delta?.let { JevChip(it.text, it.tone, Modifier.align(Alignment.CenterVertically)) }
    }
}

@Composable
private fun ProbabilityBar(label: String, value: Double, winner: Boolean, compact: Boolean) {
    val labelView: @Composable (Modifier) -> Unit = { m ->
        Text(label, color = if (winner) JevTokens.Text else JevTokens.TextSecondary, fontSize = 12.sp,
            fontWeight = if (winner) FontWeight.SemiBold else FontWeight.Normal, maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis, modifier = m)
    }
    val pct: @Composable () -> Unit = {
        Text(percent(value), color = JevTokens.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { labelView(Modifier.weight(1f)); pct() }
            Track(value, winner, Modifier.fillMaxWidth())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labelView(Modifier.weight(0.38f))
            Track(value, winner, Modifier.weight(0.62f))
            pct()
        }
    }
}

@Composable
private fun Track(value: Double, winner: Boolean, modifier: Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(JevTokens.Raised)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp)).background(if (winner) JevTokens.Accent else JevTokens.TextMuted),
        )
    }
}

@Composable
private fun SplitBar(yes: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(JevTokens.TextMuted)) {
            if (yes > 0) Box(Modifier.weight(yes.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(JevTokens.Accent))
            if (yes < 1) Box(Modifier.weight((1 - yes).toFloat().coerceAtLeast(0.001f)).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth()) {
            Text("yes ${percent(yes)}", color = JevTokens.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("no ${percent(1 - yes)}", color = JevTokens.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ScoreScale(score: Double, top: Int) {
    val track = JevTokens.Raised
    val tick = JevTokens.TextMuted
    val marker = JevTokens.Accent
    val ring = JevTokens.Panel
    Column(Modifier.padding(horizontal = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val y = size.height / 2
            drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            for (k in 0..top) {
                val x = size.width * k / top
                drawLine(tick, Offset(x, y - 5.dp.toPx()), Offset(x, y + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            val x = (size.width * (score / top)).toFloat().coerceIn(0f, size.width)
            drawCircle(ring, radius = 8.dp.toPx(), center = Offset(x, y))
            drawCircle(marker, radius = 6.dp.toPx(), center = Offset(x, y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (k in 0..top) Text("$k", color = JevTokens.TextMuted, fontSize = 10.sp, fontFamily = JevTokens.Mono)
        }
    }
}

@Composable
private fun RunningView(state: JevPlaygroundState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.runStartedAtMs) {
        while (true) { now = System.currentTimeMillis(); delay(100) }
    }
    val elapsed = (now - state.runStartedAtMs).coerceAtLeast(0) / 1000.0
    Text(
        "Asking ${JevModelCatalog.find(state.model)?.label ?: state.model} · %.1f s of %d s".format(elapsed, state.timeoutMs / 1000),
        color = JevTokens.TextSecondary, fontSize = 12.sp,
    )
    val ids = state.request?.questions?.keys?.toList().orEmpty()
    ids.forEach { id ->
        Column(
            Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, JevTokens.Border, JevTokens.Shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(id, color = JevTokens.TextSecondary, fontSize = 12.sp, fontFamily = JevTokens.Mono)
            Placeholder(0.4f, 20); Placeholder(1f, 8); Placeholder(0.7f, 8)
        }
    }
}

@Composable
private fun Placeholder(fraction: Float, height: Int) {
    Box(Modifier.fillMaxWidth(fraction).height(height.dp).clip(RoundedCornerShape(4.dp)).background(JevTokens.Raised))
}

@Composable
private fun SetupCard(viewModel: JevPlaygroundViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel)
            .border(1.dp, JevTokens.Warning.copy(alpha = 0.45f), JevTokens.Shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Connect OpenRouter to run Jev", color = JevTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("1. Open Secret Manager → AI Providers and add your OpenRouter key.", color = JevTokens.TextSecondary, fontSize = 12.sp)
        Text("2. Select any OpenRouter model there. Jev uses the model picked in this panel.", color = JevTokens.TextSecondary, fontSize = 12.sp)
        BossPrimaryButton("Open AI Providers", onClick = viewModel::openSettings)
        Text("You can write questions now. Run unlocks once the key is found.", color = JevTokens.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorCard(error: JevRunError, viewModel: JevPlaygroundViewModel, hasRuns: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel)
            .border(1.dp, JevTokens.Error.copy(alpha = 0.5f), JevTokens.Shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(error.code, color = JevTokens.Error, fontSize = 11.sp, fontFamily = JevTokens.Mono)
        Text("Jev did not answer", color = JevTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("${error.message}. ${adviceFor(error.code)}", color = JevTokens.TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (error.code == "AUTH_ERROR") BossSecondaryButton("Open AI Providers", onClick = viewModel::openSettings)
            BossSecondaryButton("Run again", onClick = viewModel::run)
        }
        if (hasRuns) Text("Earlier runs are still in the strip above.", color = JevTokens.TextMuted, fontSize = 12.sp)
    }
}

private fun adviceFor(code: String): String = when (code) {
    "TIMEOUT" -> "Raise the timeout or trim the questions, then run again."
    "RATE_LIMITED" -> "Wait a minute, then run again."
    "AUTH_ERROR" -> "Check the OpenRouter key in Secret Manager → AI Providers."
    "BUSY" -> "Wait for running calls to finish, then run again."
    "NETWORK_ERROR" -> "Check the connection, then run again."
    "INPUT_TOO_LARGE" -> "Shorten the context or remove questions."
    else -> "Jev never retries on its own, so run again when ready."
}

@Composable
private fun Starters(viewModel: JevPlaygroundViewModel) {
    Text(
        "Answers show here, next to the questions that produced them. Start from an example, or write your own under Ask.",
        color = JevTokens.TextSecondary, fontSize = 12.sp,
    )
    JevStarter.entries.forEach { starter ->
        Column(
            Modifier.fillMaxWidth().clip(JevTokens.Shape).background(JevTokens.Panel).border(1.dp, JevTokens.Border, JevTokens.Shape)
                .clickable { viewModel.useStarter(starter) }.pointerHoverIcon(PointerIcon.Hand).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(starter.label, color = JevTokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(starter.blurb, color = JevTokens.TextSecondary, fontSize = 12.sp)
        }
    }
}

internal data class Delta(val text: String, val tone: ChipTone)

/** Change against the previous run from the same source that asked the same question. */
internal fun delta(id: String, run: JevRunRecord, runs: List<JevRunRecord>): Delta? {
    val previous = runs.firstOrNull { it.id < run.id && it.source == run.source && it.decision.response.answers()?.containsKey(id) == true }
        ?: return null
    if (run.request.questions[id] != previous.request.questions[id]) return Delta("rubric changed since #${previous.id}", ChipTone.NEUTRAL)
    val now = run.decision.response.answers()?.get(id) as? JsonObject ?: return null
    val before = previous.decision.response.answers()?.get(id) as? JsonObject ?: return null
    return when (now.string("type")) {
        "choice" -> {
            val choice = now.string("choice")
            val was = before.string("choice")
            if (choice != was) Delta("flipped from $was in #${previous.id}", ChipTone.WARNING)
            else points((now.probabilities()[choice] ?: 0.0) - (before.probabilities()[choice] ?: 0.0), previous.id)
        }
        "noul" -> {
            val a = now.number("noul"); val b = before.number("noul")
            if ((a >= 0.5) != (b >= 0.5)) Delta("flipped from ${if (b >= 0.5) "yes" else "no"} in #${previous.id}", ChipTone.WARNING)
            else points(a - b, previous.id)
        }
        "score" -> {
            val d = now.number("score") - before.number("score")
            if (abs(d) < 0.05) Delta("same as #${previous.id}", ChipTone.NEUTRAL)
            else Delta("%+.2f vs #%d".format(d, previous.id), if (d > 0) ChipTone.SUCCESS else ChipTone.ERROR)
        }
        else -> null
    }
}

private fun points(diff: Double, previousId: Long): Delta {
    val pts = diff * 100
    return if (abs(pts) < 0.5) Delta("same as #$previousId", ChipTone.NEUTRAL)
    else Delta("%+.0f pts vs #%d".format(pts, previousId), if (pts > 0) ChipTone.SUCCESS else ChipTone.ERROR)
}

internal fun summary(run: JevRunRecord): String = run.decision.response.answers()?.entries?.joinToString(" · ") { (_, v) ->
    val a = v as? JsonObject ?: return@joinToString "?"
    when (a.string("type")) {
        "choice" -> a.string("choice") ?: "?"
        "noul" -> if (a.number("noul") >= 0.5) "yes" else "no"
        "score" -> "%.1f".format(a.number("score"))
        else -> "?"
    }
}.orEmpty()

private fun JsonObject.answers(): JsonObject? = get("answers") as? JsonObject
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun JsonObject.numberText(key: String): String = (get(key) as? JsonPrimitive)?.content ?: "?"
private fun JsonObject.probabilities(): Map<String, Double> =
    (get("probabilities") as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.doubleOrNull ?: 0.0 }.orEmpty()
