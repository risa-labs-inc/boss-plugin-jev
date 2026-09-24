package ai.rever.boss.plugin.dynamic.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update as updateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class JevEditorMode { FORM, JSON }
/** Narrow layouts show one of these as a tab; wide layouts keep CHAT on the left. */
enum class JevPane { CHAT, DRAFT, ANSWER }

data class JevRunError(val code: String, val message: String)

data class JevPlaygroundState(
    /** Name of the saved preset being edited; null until saved. */
    val presetName: String? = null,
    val title: String = JevStarter.ROUTING.label,
    val dirty: Boolean = false,
    val contextText: String = JevStarter.ROUTING.context,
    val sendAsText: Boolean = false,
    val questions: List<JevQuestionDraft> = JevStarter.ROUTING.questions(),
    val editorMode: JevEditorMode = JevEditorMode.FORM,
    val jsonText: String = "",
    val jsonError: String? = null,
    /** The JSON uses shapes the form cannot show, so the form is unavailable. */
    val jsonOnly: Boolean = false,
    val timeoutMs: Long = JevLimits.DEFAULT_TIMEOUT_MS,
    val model: String = JevModelCatalog.DEFAULT.id,
    val contextFormat: JevContextFormat = JevContextFormat.Empty,
    val issues: List<JevFormIssue> = emptyList(),
    /** [issues] with API paths such as `questions.route.criteria`, for MCP callers. */
    val apiIssues: List<JevIssue> = emptyList(),
    /** The request the current inputs produce, or null while there are issues. */
    val request: JevRequest? = null,
    val running: Boolean = false,
    val runStartedAtMs: Long = 0,
    val error: JevRunError? = null,
    val selectedRunId: Long? = null,
    val pane: JevPane = JevPane.CHAT,
    /** Right side of the two-pane layout: DRAFT or ANSWER. */
    val side: JevPane = JevPane.DRAFT,
    /** `<...>` values in the context that must be filled before running. */
    val placeholders: List<JevPlaceholder> = emptyList(),
    val unseenRun: Boolean = false,
    val presetNames: List<String> = emptyList(),
    val notice: String? = null,
    val noticeSerial: Long = 0,
) {
    fun issue(field: JevField): String? = issues.firstOrNull { it.field == field }?.message

    /** The questions as the API shape, or null while the JSON editor does not parse. */
    fun questionsJson(): JsonObject? = when (editorMode) {
        JevEditorMode.FORM -> questions.toApi()
        JevEditorMode.JSON -> if (jsonError != null) null else runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
    }
}

data class JevComposeError(val code: String, val message: String) {
    /** Fixed in Secret Manager → AI Providers rather than by rewording the message. */
    val needsProviders: Boolean get() = code in setOf(JevComposer.NO_GATEWAY, JevComposer.NO_MODEL, JevComposer.MODEL_UNROUTABLE)
}

/** One entry in the conversation column. */
sealed interface JevThreadItem {
    val id: Long

    /** A compose turn: the user's message, the model's one-line reply, and the locally computed changes. */
    data class Turn(
        override val id: Long,
        val user: String,
        val reply: String,
        val changes: List<JevChange>,
        val suggestions: List<String> = emptyList(),
        val issues: Int = 0,
        val runId: Long? = null,
        val reverted: Boolean = false,
    ) : JevThreadItem

    data class Failed(override val id: Long, val user: String, val error: JevComposeError) : JevThreadItem
    data class Ran(override val id: Long, val runId: Long) : JevThreadItem
    data class RunFailed(override val id: Long, val error: JevRunError) : JevThreadItem
    data class Edited(override val id: Long, val byAgent: Boolean) : JevThreadItem
}

data class JevComposePending(val user: String, val stage: JevComposeStage, val startedAtMs: Long, val modelLabel: String)

data class JevComposeState(
    val input: String = "",
    val items: List<JevThreadItem> = emptyList(),
    val pending: JevComposePending? = null,
    /** Undo applies only while the latest turn is the last thing that changed the draft. */
    val canRevert: Boolean = false,
    val models: List<JevChatModel> = emptyList(),
    val model: JevChatModel? = null,
) {
    val busy: Boolean get() = pending != null
    val turns: List<JevComposeTurn>
        get() = items.filterIsInstance<JevThreadItem.Turn>().map { JevComposeTurn(it.user, it.reply, it.issues, it.runId, it.reverted) }
    val latestTurn: JevThreadItem.Turn? get() = items.lastOrNull() as? JevThreadItem.Turn
    val modelLabel: String get() = model?.label ?: "the default model"
}

enum class JevDraftMode { REPLACE, MERGE }

/** A change to the live draft. Null fields keep the current value. */
data class JevDraftChange(
    val contextText: String? = null,
    val sendAsText: Boolean? = null,
    val questions: JsonObject? = null,
    val mode: JevDraftMode = JevDraftMode.REPLACE,
    val removeQuestions: List<String> = emptyList(),
    val model: String? = null,
    val timeoutMs: Long? = null,
)

/** The inputs one compose turn replaced, kept for a single "revert last compose". */
private data class JevDraftContent(
    val contextText: String,
    val sendAsText: Boolean,
    val questions: List<JevQuestionDraft>,
    val editorMode: JevEditorMode,
    val jsonText: String,
    val jsonError: String?,
    val jsonOnly: Boolean,
)

/**
 * Owns the live draft for one plugin activation. Driven by the panel on the UI thread and by
 * MCP from any thread, including while the panel is closed, so every state change is an atomic
 * compare-and-set on [state].
 */
class JevPlaygroundViewModel(private val services: JevPluginServices) {
    internal val decisionService: JevDecisionService = services.service
    private val pretty = Json { prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runLock = Any()
    private val composeLock = Any()
    private var runJob: Deferred<JevRunRecord?>? = null
    @Volatile private var composeJob: Job? = null
    private val itemIds = java.util.concurrent.atomic.AtomicLong()

    /** Wall clock for the drafting card; replaceable so renders can show elapsed time. */
    internal var nowMs: () -> Long = System::currentTimeMillis
    @Volatile private var composeUndo: JevDraftContent? = null
    @Volatile private var newestSeenRun = 0L
    private val _state = MutableStateFlow(JevPlaygroundState().recomputed())
    val state: StateFlow<JevPlaygroundState> = _state.asStateFlow()
    val runs: StateFlow<List<JevRunRecord>> = decisionService.runs
    private val _compose = MutableStateFlow(JevComposeState())
    val compose: StateFlow<JevComposeState> = _compose.asStateFlow()

    init {
        refreshPresets()
        scope.launch {
            decisionService.runs.collect { runs ->
                val newest = runs.firstOrNull()?.id ?: 0L
                if (newest > newestSeenRun) {
                    newestSeenRun = newest
                    update { if (pane != JevPane.ANSWER) copy(unseenRun = true) else this }
                }
            }
        }
    }

    // ---- context ----
    fun setContext(value: String) = edit { copy(contextText = value) }
    fun setSendAsText(value: Boolean) = edit { copy(sendAsText = value) }

    // ---- questions ----
    fun editQuestion(index: Int, transform: JevQuestionDraft.() -> JevQuestionDraft) = edit {
        if (index !in questions.indices) this
        else copy(questions = questions.toMutableList().also { it[index] = it[index].transform() })
    }

    fun setQuestionType(index: Int, type: JevQuestionType) = editQuestion(index) { withType(type) }

    fun addQuestion(type: JevQuestionType) = edit {
        val used = questions.map { it.id }.toSet()
        val id = generateSequence(questions.size + 1) { it + 1 }.map { "q$it" }.first { it !in used }
        copy(questions = questions + JevQuestionDraft.blank(type, id))
    }

    fun removeQuestion(index: Int) = edit {
        if (index !in questions.indices) this else copy(questions = questions.filterIndexed { i, _ -> i != index })
    }

    fun addOption(index: Int) = editQuestion(index) { copy(options = options + JevOptionDraft()) }
    fun removeOption(index: Int, option: Int) = editQuestion(index) { copy(options = options.filterIndexed { i, _ -> i != option }) }
    fun setOption(index: Int, option: Int, value: JevOptionDraft) =
        editQuestion(index) { copy(options = options.toMutableList().also { if (option in it.indices) it[option] = value }) }

    fun addLevel(index: Int) = editQuestion(index) { if (levels.size >= JevValidation.MAX_LEVELS) this else copy(levels = levels + "") }
    fun removeLevel(index: Int, level: Int) = editQuestion(index) { copy(levels = levels.filterIndexed { i, _ -> i != level }) }
    fun setLevel(index: Int, level: Int, value: String) =
        editQuestion(index) { copy(levels = levels.toMutableList().also { if (level in it.indices) it[level] = value }) }

    fun setEditorMode(mode: JevEditorMode) {
        val current = _state.value
        if (mode == current.editorMode) return
        when (mode) {
            JevEditorMode.JSON -> update {
                copy(editorMode = mode, jsonText = pretty.encodeToString(JsonObject.serializer(), questions.toApi()), jsonError = null, jsonOnly = false).recomputed()
            }
            JevEditorMode.FORM -> when {
                current.jsonError != null -> notice("Fix the JSON before switching to Form")
                current.jsonOnly -> notice("Form view shows text instructions only; keep editing this rubric in JSON")
                else -> update { copy(editorMode = mode).recomputed() }
            }
        }
    }

    fun setJsonText(value: String) = edit {
        val parsed = runCatching { Json.parseToJsonElement(value) }
        val obj = parsed.getOrNull() as? JsonObject
        when {
            parsed.isFailure -> copy(jsonText = value, jsonError = "JSON does not parse: ${parsed.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()?.take(120)}")
            obj == null -> copy(jsonText = value, jsonError = "Questions must be a JSON object keyed by question ID")
            else -> {
                val drafts = draftsFromApi(obj)
                copy(jsonText = value, jsonError = null, jsonOnly = drafts == null, questions = drafts ?: questions)
            }
        }
    }

    fun setTimeout(ms: Long) = edit { copy(timeoutMs = ms) }
    fun setModel(id: String) = edit { copy(model = id) }

    // ---- draft over MCP ----

    /**
     * Applies [change] in one atomic step and returns the new state. Merge upserts questions by
     * ID in place, then [JevDraftChange.removeQuestions] deletes. Never runs.
     */
    fun applyDraft(change: JevDraftChange): JevPlaygroundState = _state.updateAndGetAtomic { s ->
        var next = s
        if (change.questions != null || change.removeQuestions.isNotEmpty()) {
            val base: Map<String, JsonElement> = if (change.mode == JevDraftMode.REPLACE && change.questions != null) {
                change.questions
            } else {
                val current = s.questionsJson()
                    ?: throw JevFailure("INVALID_INPUT", "The panel's questions JSON does not parse; use mode replace", "questions")
                LinkedHashMap<String, JsonElement>(current).apply { change.questions?.let(::putAll) }
            }
            val missing = change.removeQuestions.filter { it !in base }
            if (missing.isNotEmpty()) {
                throw JevFailure("INVALID_INPUT", "No question with ID ${missing.joinToString()}", "remove_questions")
            }
            next = next.applyQuestions(JsonObject(base - change.removeQuestions.toSet()))
        }
        change.contextText?.let { next = next.copy(contextText = it, sendAsText = change.sendAsText ?: false) }
        change.model?.let { next = next.copy(model = it) }
        change.timeoutMs?.let { next = next.copy(timeoutMs = it) }
        next.copy(dirty = true).recomputed()
    }.also { noteEdit(byAgent = true) }

    // ---- compose ----
    fun setComposeInput(value: String) = _compose.updateFlow { it.copy(input = value) }

    /**
     * Reads the gateway's chat models and the remembered choice, off the UI thread. An empty
     * catalog is never treated as final: discovery may still be running, so it is read again.
     */
    suspend fun refreshModels() {
        val chat = services.chat
        val models = withContext(Dispatchers.Default) { runCatching { chat.models() }.getOrDefault(emptyList()) }
        if (models.isEmpty()) return
        val remembered = runCatching { services.storage?.get(COMPOSE_MODEL_KEY) }.getOrNull()
            ?.let { runCatching { (Json.parseToJsonElement(it) as JsonObject)["model"] as? JsonPrimitive }.getOrNull()?.content }
        val chosen = models.firstOrNull { it.key == remembered } ?: runCatching { chat.defaultModel(models) }.getOrNull()
        _compose.updateFlow { it.copy(models = models, model = it.model?.takeIf { m -> m in models } ?: chosen) }
    }

    fun setComposeModel(model: JevChatModel) {
        _compose.updateFlow { it.copy(model = model) }
        val storage = services.storage ?: return
        scope.launch {
            runCatching { storage.put(COMPOSE_MODEL_KEY, buildJsonObject { put("model", model.key) }.toString()) }
        }
    }

    /** Sends [message], or the composer input, from the panel. */
    fun sendCompose(message: String? = null) {
        val text = (message ?: _compose.value.input).trim()
        if (text.isEmpty() || _compose.value.busy) return
        scope.launch {
            try {
                compose(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // compose() already put the failure in the thread.
            }
        }
    }

    /** Cancels the running compose turn, whether the panel or an MCP caller started it. */
    fun cancelCompose() {
        composeJob?.cancel(CancellationException("Cancelled in the Jev panel"))
    }

    /** Sends a failed turn's message again. */
    fun retryCompose(itemId: Long) {
        val failed = _compose.value.items.firstOrNull { it.id == itemId } as? JevThreadItem.Failed ?: return
        _compose.updateFlow { s -> s.copy(items = s.items.filterNot { it.id == itemId }) }
        sendCompose(failed.user)
    }

    /**
     * One compose turn against the live draft: the chat model rewrites the context and/or
     * questions, the result lands through the normal recompute path, and the previous inputs
     * are kept for one revert. Throws [JevFailure]; the failure also appears in the thread.
     */
    suspend fun compose(message: String): JevComposeResult {
        val text = message.trim()
        if (text.isEmpty()) throw JevFailure("INVALID_INPUT", "Describe what to decide", "message")
        val job = currentCoroutineContext()[Job]
        synchronized(composeLock) {
            if (_compose.value.busy) throw JevFailure("BUSY", "A compose turn is already in progress")
            // The message moves into the thread at once; a failure keeps it there with Retry.
            _compose.updateFlow {
                it.copy(
                    pending = JevComposePending(text, JevComposeStage.Drafting, nowMs(), it.modelLabel),
                    input = if (it.input.trim() == text) "" else it.input,
                )
            }
            composeJob = job
        }
        try {
            if (_compose.value.models.isEmpty()) refreshModels()
            _compose.updateFlow { it.copy(pending = it.pending?.copy(modelLabel = it.modelLabel)) }
            val s = _state.value
            val beforeQuestions = s.questionsJson()
            val thread = _compose.value
            // The first message describes a new decision: an untouched starter or preset is not a draft to revise.
            val fresh = thread.turns.isEmpty() && !s.dirty
            val draft = if (fresh) {
                JevComposeDraft("", sendAsText = false, questions = JsonObject(emptyMap()), questionsText = "{}", model = s.model, timeoutMs = s.timeoutMs)
            } else {
                JevComposeDraft(
                    contextText = s.contextText,
                    sendAsText = s.sendAsText,
                    questions = beforeQuestions,
                    questionsText = if (s.editorMode == JevEditorMode.JSON) s.jsonText else s.questions.toApi().toString(),
                    model = s.model,
                    timeoutMs = s.timeoutMs,
                )
            }
            // A run is summarized into the first turn after it, not repeated in every later turn.
            val lastRun = decisionService.runs.value.firstOrNull()?.takeIf { run -> thread.turns.none { it.runId == run.id } }
            val result = services.composer.compose(
                text, draft, thread.turns, lastRun?.let(JevComposer::summarizeRun), thread.model,
                onStage = { stage -> _compose.updateFlow { it.copy(pending = it.pending?.copy(stage = stage)) } },
            )
            var previous: JevDraftContent? = null
            val after = _state.updateAndGetAtomic { current ->
                previous = current.content()
                var next = current
                result.context?.let { next = next.copy(contextText = it, sendAsText = false) }
                result.questions?.let { next = next.applyQuestions(it) }
                if (fresh) next = next.copy(presetName = null, title = "Untitled")
                next.copy(dirty = true, pane = JevPane.CHAT, side = JevPane.DRAFT).recomputed()
            }
            composeUndo = previous
            val changes = if (fresh) JevDraftDiff.between("", JsonObject(emptyMap()), after.contextText, after.questionsJson())
            else JevDraftDiff.between(s.contextText, beforeQuestions, after.contextText, after.questionsJson())
            _compose.updateFlow {
                it.copy(
                    items = it.items + JevThreadItem.Turn(nextItemId(), text, result.reply, changes, result.suggestions, result.issues.size, lastRun?.id),
                    canRevert = true,
                )
            }
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Throwable, not Exception: a linkage error from a mismatched host must not leave the turn pending forever.
            val error = (failure as? JevFailure) ?: JevFailure(JevComposer.COMPOSE_FAILED, failure.message?.take(240) ?: "Could not draft the request")
            _compose.updateFlow { it.copy(items = it.items + JevThreadItem.Failed(nextItemId(), text, JevComposeError(error.code, error.message))) }
            throw error
        } finally {
            synchronized(composeLock) {
                composeJob = null
                _compose.updateFlow { it.copy(pending = null) }
            }
        }
    }

    fun revertLastCompose() {
        val undo = composeUndo ?: return
        composeUndo = null
        update { restore(undo).copy(dirty = true).recomputed() }
        _compose.updateFlow { s ->
            val last = s.items.indexOfLast { it is JevThreadItem.Turn && !it.reverted }
            s.copy(
                canRevert = false,
                items = if (last < 0) s.items else s.items.toMutableList().also { it[last] = (it[last] as JevThreadItem.Turn).copy(reverted = true) },
            )
        }
        notice("Reverted the last change")
    }

    /** Writes the given `<...>` values into the context; keys are [JevPlaceholder.key]. */
    fun fillPlaceholders(values: Map<String, String>) = edit {
        copy(contextText = JevPlaceholders.fill(contextText, contextFormat, values, placeholders))
    }

    private fun resetCompose() {
        composeUndo = null
        _compose.updateFlow { it.copy(items = emptyList(), canRevert = false) }
    }

    /** A manual or MCP edit after a turn gets one line in the thread, and ends that turn's undo. */
    private fun noteEdit(byAgent: Boolean) {
        _compose.updateFlow { s ->
            if (s.items.isEmpty() || s.busy) return@updateFlow s
            val last = s.items.last()
            val items = if (last is JevThreadItem.Edited && last.byAgent == byAgent) s.items else s.items + JevThreadItem.Edited(nextItemId(), byAgent)
            s.copy(items = items, canRevert = false)
        }
        composeUndo = null
    }

    private fun nextItemId(): Long = itemIds.incrementAndGet()

    // ---- running ----
    fun run() {
        when (val start = startRun()) {
            JevRunStart.MissingKey -> update {
                copy(pane = JevPane.ANSWER, side = JevPane.ANSWER, selectedRunId = null, error = JevRunError("MISSING_OPENROUTER_KEY", "Add an OpenRouter key in Secret Manager → AI Providers"))
            }
            is JevRunStart.Invalid -> notice(when {
                _state.value.placeholders.isNotEmpty() -> "Fill the ${_state.value.placeholders.size} marked fields before running"
                start.issues == 1 -> "Fix 1 issue before running"
                else -> "Fix ${start.issues} issues before running"
            })
            else -> Unit
        }
    }

    /**
     * Runs the live draft exactly as the Run button does, for MCP: the result lands in the
     * Answer pane and history. Returns the run, or throws [JevFailure].
     */
    suspend fun runDraft(): JevRunRecord {
        val job = when (val start = startRun()) {
            JevRunStart.Busy -> throw JevFailure("BUSY", "A run is already in progress in the Jev panel")
            JevRunStart.MissingKey -> throw JevFailure("MISSING_OPENROUTER_KEY", "Add an OpenRouter key in Secret Manager → AI Providers")
            is JevRunStart.Invalid -> throw JevFailure("INVALID_INPUT", "The draft has ${start.issues} issue(s); read them with jev_draft_get")
            is JevRunStart.Started -> start.job
        }
        val record = try {
            job.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw JevFailure("CANCELLED", "The run was cancelled in the Jev panel")
        }
        if (record != null) return record
        val error = _state.value.error
        throw JevFailure(error?.code ?: "SERVICE_UNAVAILABLE", error?.message ?: "Jev could not complete the request")
    }

    private sealed interface JevRunStart {
        data object Busy : JevRunStart
        data object MissingKey : JevRunStart
        data class Invalid(val issues: Int) : JevRunStart
        data class Started(val job: Deferred<JevRunRecord?>) : JevRunStart
    }

    private fun startRun(): JevRunStart = synchronized(runLock) {
        if (runJob?.isActive == true) return JevRunStart.Busy
        if (!hasOpenRouterKey()) return JevRunStart.MissingKey
        val snapshot = _state.value
        val request = snapshot.request ?: return JevRunStart.Invalid(snapshot.issues.size)
        // The thread gets a compact answer card; the full answer is on the right when there is room.
        update { copy(running = true, runStartedAtMs = System.currentTimeMillis(), error = null, side = JevPane.ANSWER, pane = if (pane == JevPane.ANSWER) pane else JevPane.CHAT) }
        val job = scope.async {
            try {
                val decision = decisionService.decide(request, JevRunSource.PLAYGROUND)
                val record = decisionService.runs.value.firstOrNull { it.decision === decision }
                update { copy(running = false, selectedRunId = record?.id, unseenRun = pane != JevPane.ANSWER && side != JevPane.ANSWER) }
                record?.let { r -> _compose.updateFlow { it.copy(items = it.items + JevThreadItem.Ran(nextItemId(), r.id)) } }
                record
            } catch (cancelled: CancellationException) {
                update { copy(running = false) }
                throw cancelled
            } catch (failure: Exception) {
                val error = (failure as? JevFailure)?.let { JevRunError(it.code, it.message) }
                    ?: JevRunError("SERVICE_UNAVAILABLE", "Jev could not complete the request")
                update { copy(running = false, selectedRunId = null, error = error) }
                _compose.updateFlow { it.copy(items = it.items + JevThreadItem.RunFailed(nextItemId(), error)) }
                null
            }
        }
        runJob = job
        JevRunStart.Started(job)
    }

    fun cancelRun() {
        synchronized(runLock) {
            runJob?.cancel()
            runJob = null
        }
        update { copy(running = false) }
        notice("Run cancelled")
    }

    /** Shows a run in the Answer view: the right side when wide, the Answer tab when narrow. */
    fun selectRun(id: Long) = update { copy(selectedRunId = id, error = null, pane = JevPane.ANSWER, side = JevPane.ANSWER, unseenRun = false) }

    fun restoreRun(id: Long) {
        val run = decisionService.runs.value.firstOrNull { it.id == id } ?: return
        val state = run.request.state
        val text = if (state is JsonPrimitive && state.isString) state.content else pretty.encodeToString(JsonElement.serializer(), state)
        edit {
            withQuestions(run.request.questions).copy(
                contextText = text,
                sendAsText = state is JsonPrimitive && looksLikeJson(text),
                timeoutMs = run.request.timeoutMs,
                model = run.request.model,
                pane = JevPane.DRAFT,
                side = JevPane.DRAFT,
            )
        }
        notice("Loaded run #${run.id} into the editor")
    }

    fun setPane(pane: JevPane) = update {
        copy(pane = pane, side = if (pane == JevPane.CHAT) side else pane, unseenRun = if (pane == JevPane.ANSWER) false else unseenRun)
    }

    // ---- documents ----
    fun useStarter(starter: JevStarter) {
        update {
            copy(
                presetName = null, title = starter.label, dirty = false,
                contextText = starter.context, sendAsText = false,
                questions = starter.questions(), editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false,
                timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.CHAT, side = JevPane.DRAFT,
            ).recomputed()
        }
        resetCompose()
    }

    fun newBlank() {
        update {
            copy(
                presetName = null, title = "Untitled", dirty = false,
                contextText = "", sendAsText = false,
                questions = listOf(JevQuestionDraft.blank(JevQuestionType.CHOICE, "q1")),
                editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false,
                timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.CHAT, side = JevPane.DRAFT,
            ).recomputed()
        }
        resetCompose()
    }

    /** Saves over the open preset. Returns false when there is none, so the caller asks for a name. */
    fun save(): Boolean {
        val name = _state.value.presetName ?: return false
        saveAs(name)
        return true
    }

    fun saveAs(name: String) {
        scope.launch {
            try {
                val saved = savePreset(name)
                notice("Saved \"${saved.name}\"")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "Could not save preset")
            }
        }
    }

    /**
     * Saves the live draft, or only [questions] when given, as a preset. Saving the draft makes
     * it the open preset; saving given questions leaves the draft alone and stores no context.
     */
    suspend fun savePreset(name: String, questions: JsonObject? = null, model: String? = null, timeoutMs: Long? = null): JevPreset {
        val repo = services.presets ?: throw JevFailure("PRESET_STORAGE_ERROR", "Preset storage is unavailable")
        val value = _state.value
        val preset = if (questions != null) {
            JevPreset(name, "", questions.toString(), stateAsJson = false, timeoutMs ?: JevLimits.DEFAULT_TIMEOUT_MS, model ?: JevModelCatalog.DEFAULT.id)
        } else {
            val questionsText = when {
                value.editorMode == JevEditorMode.JSON && value.jsonError != null -> throw JevFailure("INVALID_INPUT", "Fix the JSON before saving", "questions")
                value.editorMode == JevEditorMode.JSON -> value.jsonText
                else -> value.questions.toApi().toString()
            }
            JevPreset(name, value.contextText, questionsText, value.contextFormat is JevContextFormat.Json, timeoutMs ?: value.timeoutMs, model ?: value.model)
        }
        try {
            repo.save(preset)
        } catch (invalid: IllegalArgumentException) {
            throw JevFailure("INVALID_INPUT", invalid.message ?: "Invalid preset", "name")
        }
        val saved = preset.copy(name = name.trim())
        if (questions == null) update { copy(presetName = saved.name, title = saved.name, dirty = false) }
        refreshPresets()
        return saved
    }

    fun loadPreset(name: String) {
        val repo = services.presets ?: return
        scope.launch {
            try {
                val preset = repo.load(name) ?: return@launch notice("Preset not found")
                val questions = runCatching { Json.parseToJsonElement(preset.questionsText) as? JsonObject }.getOrNull()
                    ?: return@launch notice("Preset \"$name\" has invalid questions")
                update {
                    withQuestions(questions).copy(
                        presetName = preset.name, title = preset.name, dirty = false,
                        contextText = preset.stateText,
                        sendAsText = !preset.stateAsJson && looksLikeJson(preset.stateText),
                        timeoutMs = preset.timeoutMs, model = preset.model, pane = JevPane.CHAT, side = JevPane.DRAFT,
                    ).recomputed()
                }
                resetCompose()
                notice("Opened \"${preset.name}\"")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("Could not load preset")
            }
        }
    }

    fun deletePreset(name: String) {
        val repo = services.presets ?: return
        scope.launch {
            try {
                repo.delete(name)
                update { if (presetName == name) copy(presetName = null, dirty = true) else this }
                refreshPresets("Deleted \"$name\"")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("Could not delete preset")
            }
        }
    }

    // ---- misc ----
    fun copyMcpArguments() {
        val request = _state.value.request ?: return notice("Fix the issues before copying the call")
        val text = buildJsonObject {
            put("state", request.state)
            put("questions", request.questions)
            put("timeout_ms", request.timeoutMs)
        }.toString()
        val copied = services.context.clipboardProvider?.setText(text) == true
        notice(if (copied) "Copied jev_decide arguments" else "Clipboard is unavailable")
    }

    fun openSettings() {
        if (!services.openAiProviderSettings()) notice("Open Secret Manager → AI Providers to add an OpenRouter key")
    }

    fun hasOpenRouterKey() = services.hasOpenRouterKey()

    fun dismissNotice(serial: Long) = update { if (noticeSerial == serial) copy(notice = null) else this }

    fun dispose() { runJob?.cancel(); composeJob?.cancel(); scope.cancel() }

    private fun refreshPresets(message: String? = null) {
        val repo = services.presets ?: return
        scope.launch {
            try {
                val names = repo.names()
                update { copy(presetNames = names) }
                message?.let(::notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("Could not read presets")
            }
        }
    }

    private fun notice(message: String) = update { copy(notice = message, noticeSerial = noticeSerial + 1) }

    /** Applies API questions, falling back to JSON mode when the form cannot show them. */
    private fun JevPlaygroundState.withQuestions(questions: JsonObject): JevPlaygroundState {
        val drafts = draftsFromApi(questions)
        return if (drafts != null) {
            copy(questions = drafts, editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false)
        } else {
            copy(editorMode = JevEditorMode.JSON, jsonText = pretty.encodeToString(JsonObject.serializer(), questions), jsonError = null, jsonOnly = true)
        }
    }

    /** Like [withQuestions], but an open JSON editor stays open and shows the new text. */
    private fun JevPlaygroundState.applyQuestions(questions: JsonObject): JevPlaygroundState {
        if (editorMode == JevEditorMode.FORM) return withQuestions(questions)
        val drafts = draftsFromApi(questions)
        return copy(jsonText = pretty.encodeToString(JsonObject.serializer(), questions), jsonError = null, jsonOnly = drafts == null, questions = drafts ?: this.questions)
    }

    private fun JevPlaygroundState.content() = JevDraftContent(contextText, sendAsText, questions, editorMode, jsonText, jsonError, jsonOnly)

    private fun JevPlaygroundState.restore(c: JevDraftContent) = copy(
        contextText = c.contextText, sendAsText = c.sendAsText, questions = c.questions, editorMode = c.editorMode,
        jsonText = c.jsonText, jsonError = c.jsonError, jsonOnly = c.jsonOnly,
    )

    private fun JevPlaygroundState.recomputed(): JevPlaygroundState {
        val limits = decisionService.limits
        val format = detectContext(contextText, sendAsText)
        val issues = mutableListOf<JevFormIssue>()
        val apiIssues = mutableListOf<JevIssue>()
        fun add(field: JevField, message: String, path: List<String>, apiMessage: String = message) {
            issues += JevFormIssue(field, message)
            apiIssues += JevIssue(path, apiMessage)
        }
        val stateElement: JsonElement? = when (format) {
            JevContextFormat.Empty -> null.also { add(JevField.Context, "Add the situation Jev should judge", listOf("state")) }
            is JevContextFormat.Invalid -> null.also { add(JevField.Context, "Looks like JSON but does not parse: ${format.reason}", listOf("state")) }
            is JevContextFormat.Json -> format.element
            is JevContextFormat.Text -> JsonPrimitive(contextText)
        }
        val placeholders = JevPlaceholders.find(format, contextText)
        placeholders.forEach { add(JevField.Placeholder(it.key), "Fill in ${it.token}", listOf("state") + it.path) }
        val questionsJson: JsonObject? = when (editorMode) {
            JevEditorMode.FORM -> draftIssues(questions).let { found ->
                found.forEach { add(it.field, it.message, draftPath(it.field, questions)) }
                if (found.isEmpty()) questions.toApi() else null
            }
            JevEditorMode.JSON -> if (jsonError != null) {
                null.also { add(JevField.Json, jsonError, listOf("questions")) }
            } else {
                runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            }
        }
        var request: JevRequest? = null
        if (stateElement != null && questionsJson != null) {
            val candidate = JevRequest(stateElement, questionsJson, timeoutMs, model)
            JevValidation.requestIssues(candidate, limits).forEach { issue ->
                val field = if (issue.path.firstOrNull() == "model") JevField.Model
                else if (editorMode == JevEditorMode.FORM) fieldFor(issue, questions)
                else JevField.Json
                val message = if (field == JevField.Json) "${issue.pathText}: ${issue.message}" else issue.message
                add(field, message, issue.path, issue.message)
            }
            val bytes = buildJsonObject { put("state", stateElement); put("questions", questionsJson) }.toString().encodeToByteArray().size
            if (bytes > limits.maxRequestBytes) {
                add(JevField.Context, "Request is ${bytes / 1024} KiB; the plugin limit is ${limits.maxRequestBytes / 1024} KiB", listOf("state"))
            }
            if (issues.isEmpty()) request = candidate
        }
        return copy(contextFormat = format, issues = issues, apiIssues = apiIssues, request = request, placeholders = placeholders)
    }

    /** API-style path for a form-only issue; a blank ID is named by its position. */
    private fun draftPath(field: JevField, drafts: List<JevQuestionDraft>): List<String> {
        fun id(index: Int) = drafts.getOrNull(index)?.id?.takeIf { it.isNotBlank() } ?: "#${index + 1}"
        return when (field) {
            is JevField.QuestionId -> listOf("questions", id(field.index))
            is JevField.OptionName -> listOf("questions", id(field.index), "criteria")
            else -> listOf("questions")
        }
    }

    private inline fun edit(crossinline block: JevPlaygroundState.() -> JevPlaygroundState) {
        update { block().copy(dirty = true).recomputed() }
        noteEdit(byAgent = false)
    }

    private inline fun update(crossinline block: JevPlaygroundState.() -> JevPlaygroundState) = _state.updateFlow { it.block() }

    private inline fun MutableStateFlow<JevPlaygroundState>.updateAndGetAtomic(block: (JevPlaygroundState) -> JevPlaygroundState): JevPlaygroundState {
        while (true) {
            val prev = value
            val next = block(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    companion object {
        private const val COMPOSE_MODEL_KEY = "compose/model/v1"
    }
}
