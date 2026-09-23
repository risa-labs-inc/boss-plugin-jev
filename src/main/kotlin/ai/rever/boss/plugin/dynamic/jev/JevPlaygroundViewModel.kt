package ai.rever.boss.plugin.dynamic.jev

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

class JevPlaygroundViewModel(private val services: JevPluginServices) {
    internal val decisionService: JevDecisionService = services.service
    private val pretty = Json { prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runJob: Job? = null
    private var newestSeenRun = 0L
    private val _state = MutableStateFlow(JevPlaygroundState().recomputed())
    val state: StateFlow<JevPlaygroundState> = _state.asStateFlow()
    val runs: StateFlow<List<JevRunRecord>> = decisionService.runs

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

    // ---- running ----
    fun run() {
        if (runJob?.isActive == true) return
        val snapshot = _state.value
        if (!hasOpenRouterKey()) {
            update { copy(pane = JevPane.ANSWER, selectedRunId = null, error = JevRunError("MISSING_OPENROUTER_KEY", "Add an OpenRouter key in Settings → AI Providers")) }
            return
        }
        val request = snapshot.request ?: run {
            val n = snapshot.issues.size
            notice(if (n == 1) "Fix 1 issue before running" else "Fix $n issues before running")
            return
        }
        runJob = scope.launch {
            update { copy(running = true, runStartedAtMs = System.currentTimeMillis(), error = null, pane = JevPane.ANSWER, unseenRun = false) }
            try {
                val decision = decisionService.decide(request, JevRunSource.PLAYGROUND)
                val id = decisionService.runs.value.firstOrNull { it.decision === decision }?.id
                update { copy(running = false, selectedRunId = id, unseenRun = false) }
            } catch (cancelled: CancellationException) {
                update { copy(running = false) }
                throw cancelled
            } catch (failure: JevFailure) {
                update { copy(running = false, selectedRunId = null, error = JevRunError(failure.code, failure.message)) }
            } catch (_: Exception) {
                update { copy(running = false, selectedRunId = null, error = JevRunError("SERVICE_UNAVAILABLE", "Jev could not complete the request")) }
            }
        }
    }

    fun cancelRun() {
        runJob?.cancel()
        runJob = null
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
    fun useStarter(starter: JevStarter) = update {
        copy(
            presetName = null, title = starter.label, dirty = false,
            contextText = starter.context, sendAsText = false,
            questions = starter.questions(), editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false,
            timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.ASK,
        ).recomputed()
    }

    fun newBlank() = update {
        copy(
            presetName = null, title = "Untitled", dirty = false,
            contextText = "", sendAsText = false,
            questions = listOf(JevQuestionDraft.blank(JevQuestionType.CHOICE, "q1")),
            editorMode = JevEditorMode.FORM, jsonError = null, jsonOnly = false,
            timeoutMs = JevLimits.DEFAULT_TIMEOUT_MS, pane = JevPane.ASK,
        ).recomputed()
    }

    /** Saves over the open preset. Returns false when there is none, so the caller asks for a name. */
    fun save(): Boolean {
        val name = _state.value.presetName ?: return false
        saveAs(name)
        return true
    }

    fun saveAs(name: String) {
        val repo = services.presets ?: return notice("Preset storage is unavailable")
        val value = _state.value
        val questionsText = when {
            value.editorMode == JevEditorMode.JSON && value.jsonError != null -> return notice("Fix the JSON before saving")
            value.editorMode == JevEditorMode.JSON -> value.jsonText
            else -> value.questions.toApi().toString()
        }
        scope.launch {
            try {
                repo.save(JevPreset(name, value.contextText, questionsText, value.contextFormat is JevContextFormat.Json, value.timeoutMs, value.model))
                val saved = name.trim()
                update { copy(presetName = saved, title = saved, dirty = false) }
                refreshPresets("Saved \"$saved\"")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.message ?: "Could not save preset")
            }
        }
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
        if (!services.openAiProviderSettings()) notice("Open Settings → AI Providers to add an OpenRouter key")
    }

    fun hasOpenRouterKey() = services.hasOpenRouterKey()

    fun dismissNotice(serial: Long) = update { if (noticeSerial == serial) copy(notice = null) else this }

    fun dispose() { runJob?.cancel(); scope.cancel() }

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

    private fun JevPlaygroundState.recomputed(): JevPlaygroundState {
        val limits = decisionService.limits
        val format = detectContext(contextText, sendAsText)
        val issues = mutableListOf<JevFormIssue>()
        val stateElement: JsonElement? = when (format) {
            JevContextFormat.Empty -> null.also { issues += JevFormIssue(JevField.Context, "Add the situation Jev should judge") }
            is JevContextFormat.Invalid -> null.also { issues += JevFormIssue(JevField.Context, "Looks like JSON but does not parse: ${format.reason}") }
            is JevContextFormat.Json -> format.element
            is JevContextFormat.Text -> JsonPrimitive(contextText)
        }
        val questionsJson: JsonObject? = when (editorMode) {
            JevEditorMode.FORM -> draftIssues(questions).let { found ->
                issues += found
                if (found.isEmpty()) questions.toApi() else null
            }
            JevEditorMode.JSON -> if (jsonError != null) {
                null.also { issues += JevFormIssue(JevField.Json, jsonError) }
            } else {
                runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            }
        }
        var request: JevRequest? = null
        if (stateElement != null && questionsJson != null) {
            val candidate = JevRequest(stateElement, questionsJson, timeoutMs, model)
            JevValidation.requestIssues(candidate, limits).forEach { issue ->
                issues += if (issue.path.firstOrNull() == "model") JevFormIssue(JevField.Model, issue.message)
                else if (editorMode == JevEditorMode.FORM) JevFormIssue(fieldFor(issue, questions), issue.message)
                else JevFormIssue(JevField.Json, "${issue.pathText}: ${issue.message}")
            }
            val bytes = buildJsonObject { put("state", stateElement); put("questions", questionsJson) }.toString().encodeToByteArray().size
            if (bytes > limits.maxRequestBytes) {
                issues += JevFormIssue(JevField.Context, "Request is ${bytes / 1024} KiB; the plugin limit is ${limits.maxRequestBytes / 1024} KiB")
            }
            if (issues.isEmpty()) request = candidate
        }
        return copy(contextFormat = format, issues = issues, request = request)
    }

    private inline fun edit(block: JevPlaygroundState.() -> JevPlaygroundState) =
        update { block().copy(dirty = true).recomputed() }

    private inline fun update(block: JevPlaygroundState.() -> JevPlaygroundState) { _state.value = _state.value.block() }
}
