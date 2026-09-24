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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class JevEditorMode { FORM, JSON }
enum class JevPane { ASK, ANSWER }

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
    val pane: JevPane = JevPane.ASK,
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

data class JevComposeState(
    val input: String = "",
    val turns: List<JevComposeTurn> = emptyList(),
    val busy: Boolean = false,
    val error: JevComposeError? = null,
    val canRevert: Boolean = false,
    val threadOpen: Boolean = true,
    val models: List<JevChatModel> = emptyList(),
    val model: JevChatModel? = null,
    val modelsLoaded: Boolean = false,
)

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
    private var composeJob: Job? = null
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
                    update { if (pane == JevPane.ASK) copy(unseenRun = true) else this }
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
    }

    // ---- compose ----
    fun setComposeInput(value: String) = _compose.updateFlow { it.copy(input = value) }
    fun toggleThread() = _compose.updateFlow { it.copy(threadOpen = !it.threadOpen) }

    /** Reads the gateway's chat models and the remembered choice. Cheap; safe to repeat. */
    suspend fun refreshModels() {
        val chat = services.chat
        val models = runCatching { chat.models() }.getOrDefault(emptyList())
        val remembered = runCatching { services.storage?.get(COMPOSE_MODEL_KEY) }.getOrNull()
            ?.let { runCatching { (Json.parseToJsonElement(it) as JsonObject)["model"] as? JsonPrimitive }.getOrNull()?.content }
        val chosen = models.firstOrNull { it.key == remembered } ?: runCatching { chat.defaultModel(models) }.getOrNull()
        _compose.updateFlow { it.copy(models = models, model = it.model?.takeIf { m -> m in models } ?: chosen, modelsLoaded = true) }
    }

    fun setComposeModel(model: JevChatModel) {
        _compose.updateFlow { it.copy(model = model, error = null) }
        val storage = services.storage ?: return
        scope.launch {
            runCatching { storage.put(COMPOSE_MODEL_KEY, buildJsonObject { put("model", model.key) }.toString()) }
        }
    }

    /** Sends the composer input from the panel. */
    fun sendCompose() {
        val message = _compose.value.input.trim()
        if (message.isEmpty() || _compose.value.busy) return
        composeJob = scope.launch {
            try {
                compose(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // compose() already put the error in the thread state.
            }
        }
    }

    fun cancelCompose() {
        composeJob?.cancel()
        composeJob = null
    }

    /**
     * One compose turn against the live draft: the chat model rewrites the context and/or
     * questions, the result lands through the normal recompute path, and the previous inputs
     * are kept for one revert. Throws [JevFailure]; the error is also shown in the panel.
     */
    suspend fun compose(message: String): JevComposeResult {
        val text = message.trim()
        if (text.isEmpty()) throw JevFailure("INVALID_INPUT", "Describe what to decide", "message")
        synchronized(composeLock) {
            if (_compose.value.busy) throw JevFailure("BUSY", "A compose turn is already in progress")
            _compose.updateFlow { it.copy(busy = true, error = null) }
        }
        try {
            if (!_compose.value.modelsLoaded) refreshModels()
            val s = _state.value
            val draft = JevComposeDraft(
                contextText = s.contextText,
                sendAsText = s.sendAsText,
                questions = s.questionsJson(),
                questionsText = if (s.editorMode == JevEditorMode.JSON) s.jsonText else s.questions.toApi().toString(),
                model = s.model,
                timeoutMs = s.timeoutMs,
            )
            val threadState = _compose.value
            // A run is summarized into the first turn after it, not repeated in every later turn.
            val lastRun = decisionService.runs.value.firstOrNull()?.takeIf { run -> threadState.turns.none { it.runId == run.id } }
            val result = services.composer.compose(text, draft, threadState.turns, lastRun?.let(JevComposer::summarizeRun), threadState.model)
            var previous: JevDraftContent? = null
            update {
                previous = content()
                var next = this
                result.context?.let { next = next.copy(contextText = it, sendAsText = false) }
                result.questions?.let { next = next.applyQuestions(it) }
                next.copy(dirty = true, pane = JevPane.ASK).recomputed()
            }
            composeUndo = previous
            _compose.updateFlow {
                it.copy(
                    busy = false,
                    input = if (it.input.trim() == text) "" else it.input,
                    turns = it.turns + JevComposeTurn(text, result.reply, result.issues.size, lastRun?.id),
                    canRevert = true,
                    threadOpen = true,
                )
            }
            return result
        } catch (cancelled: CancellationException) {
            _compose.updateFlow { it.copy(busy = false) }
            throw cancelled
        } catch (failure: JevFailure) {
            _compose.updateFlow { it.copy(busy = false, error = JevComposeError(failure.code, failure.message)) }
            throw failure
        } catch (failure: Exception) {
            val wrapped = JevFailure(JevComposer.COMPOSE_FAILED, failure.message?.take(240) ?: "Could not draft the request")
            _compose.updateFlow { it.copy(busy = false, error = JevComposeError(wrapped.code, wrapped.message)) }
            throw wrapped
        }
    }

    fun revertLastCompose() {
        val undo = composeUndo ?: return
        composeUndo = null
        edit { restore(undo) }
        _compose.updateFlow { s ->
            val last = s.turns.indexOfLast { !it.reverted }
            s.copy(
                canRevert = false,
                turns = if (last < 0) s.turns else s.turns.toMutableList().also { it[last] = it[last].copy(reverted = true) },
            )
        }
        notice("Reverted the last compose")
    }

    fun dismissComposeError() = _compose.updateFlow { it.copy(error = null) }

    private fun resetCompose() {
        composeUndo = null
        _compose.updateFlow { it.copy(turns = emptyList(), canRevert = false, error = null) }
    }

    // ---- running ----
    fun run() {
        when (val start = startRun()) {
            JevRunStart.MissingKey -> update {
                copy(pane = JevPane.ANSWER, selectedRunId = null, error = JevRunError("MISSING_OPENROUTER_KEY", "Add an OpenRouter key in Secret Manager → AI Providers"))
            }
            is JevRunStart.Invalid -> notice(if (start.issues == 1) "Fix 1 issue before running" else "Fix ${start.issues} issues before running")
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
        update { copy(running = true, runStartedAtMs = System.currentTimeMillis(), error = null, pane = JevPane.ANSWER, unseenRun = false) }
        val job = scope.async {
            try {
                val decision = decisionService.decide(request, JevRunSource.PLAYGROUND)
                val record = decisionService.runs.value.firstOrNull { it.decision === decision }
                update { copy(running = false, selectedRunId = record?.id, unseenRun = false) }
                record
            } catch (cancelled: CancellationException) {
                update { copy(running = false) }
                throw cancelled
            } catch (failure: JevFailure) {
                update { copy(running = false, selectedRunId = null, error = JevRunError(failure.code, failure.message)) }
                null
            } catch (_: Exception) {
                update { copy(running = false, selectedRunId = null, error = JevRunError("SERVICE_UNAVAILABLE", "Jev could not complete the request")) }
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

    fun selectRun(id: Long) = update { copy(selectedRunId = id, error = null, pane = JevPane.ANSWER) }

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
                pane = JevPane.ASK,
            )
        }
        notice("Loaded run #${run.id} into the editor")
    }

    fun setPane(pane: JevPane) = update { copy(pane = pane, unseenRun = if (pane == JevPane.ANSWER) false else unseenRun) }

    // ---- documents ----
    fun useStarter(starter: JevStarter) {
        update {
            copy(
                presetName = null, title = starter.label, dirty = false,
                contextText = starter.context, sendAsText = false,
                questions = starter.questions(), editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false,
                timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.ASK,
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
                timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.ASK,
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
                        timeoutMs = preset.timeoutMs, model = preset.model, pane = JevPane.ASK,
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
        return copy(contextFormat = format, issues = issues, apiIssues = apiIssues, request = request)
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

    private inline fun edit(crossinline block: JevPlaygroundState.() -> JevPlaygroundState) =
        update { block().copy(dirty = true).recomputed() }

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
