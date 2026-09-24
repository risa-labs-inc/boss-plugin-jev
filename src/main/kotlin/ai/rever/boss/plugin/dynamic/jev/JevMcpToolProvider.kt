package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JevMcpToolProvider(
    override val providerId: String,
    internal val service: JevDecisionService,
    private val presets: JevPresetRepository? = null,
    /** The live panel draft; lazy because the view model is created on first use. */
    private val playground: (() -> JevPlaygroundViewModel)? = null,
) : McpToolProvider {
    private val json = Json
    private val pretty = Json { prettyPrint = true }

    override fun tools(): List<McpToolDefinition> = decisionTools() + (if (playground != null) draftTools() else emptyList())

    private fun decisionTools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "jev_decide",
            description = "Ask Jev (typesafe/jev-1.13 via the user's OpenRouter key) to judge `state` against questions. " +
                "Question types: noul = yes/no probability; choice = pick one named option; score = position on an ordered rubric. " +
                "Pass `questions`, or `preset` to reuse a rubric saved in the Jev panel (see jev_presets). " +
                "Each call is a paid external request. Returns assessments only and never acts on them. " +
                "Errors carry a `path` naming the field to fix; check first with jev_validate.",
            inputSchema = DECIDE_SCHEMA,
            // The call does not mutate local state, but it is not read-only: it can incur provider cost.
            readOnly = false,
            handler = McpToolHandler(::call),
        ),
        McpToolDefinition(
            name = "jev_validate",
            description = "Check jev_decide arguments locally without calling OpenRouter. Free. " +
                "Returns every issue with its path, or valid: true.",
            inputSchema = DECIDE_SCHEMA,
            readOnly = true,
            handler = McpToolHandler(::validate),
        ),
        McpToolDefinition(
            name = "jev_presets",
            description = "List rubrics saved in the Jev panel: name, questions, and timeout. " +
                "Use a name as `preset` in jev_decide. Saved context text is never returned.",
            readOnly = true,
            handler = McpToolHandler(::listPresets),
        ),
    )

    internal suspend fun call(args: McpToolArgs): McpToolResult = guarded {
        val result = service.decide(parse(args), JevRunSource.MCP)
        McpToolResult(buildJsonObject {
            put("response", result.response)
            put("latency_ms", result.latencyMs)
        }.toString())
    }

    internal suspend fun validate(args: McpToolArgs): McpToolResult = guarded {
        val issues = JevValidation.requestIssues(parse(args), service.limits)
        McpToolResult(buildJsonObject {
            put("valid", issues.isEmpty())
            put("issues", buildJsonArray {
                issues.forEach { add(buildJsonObject { put("path", it.pathText); put("message", it.message) }) }
            })
        }.toString())
    }

    internal suspend fun listPresets(@Suppress("UNUSED_PARAMETER") args: McpToolArgs): McpToolResult = guarded {
        val repo = presets ?: throw JevFailure("PRESET_STORAGE_ERROR", "Preset storage is unavailable")
        McpToolResult(buildJsonObject {
            put("presets", buildJsonArray {
                repo.all().forEach { preset ->
                    val questions = runCatching { json.parseToJsonElement(preset.questionsText) as? JsonObject }.getOrNull()
                        ?: return@forEach
                    add(buildJsonObject {
                        put("name", preset.name)
                        put("questions", questions)
                        put("model", preset.model)
                        put("timeout_ms", preset.timeoutMs)
                    })
                }
            })
        }.toString())
    }

    private fun draftTools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "jev_draft_get",
            description = "Read the draft open in the user's Jev panel: context (state), questions, model, timeout, " +
                "validation issues with paths, and a summary of the last run. Free. " +
                "Use it before changing a draft the user is working on, and after jev_draft_set to see what is left to fix. " +
                "Typical loop: jev_draft_get -> jev_draft_set -> fix issues -> jev_draft_run -> refine.",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{}}""",
            readOnly = true,
            handler = McpToolHandler(::draftGet),
        ),
        McpToolDefinition(
            name = "jev_draft_set",
            description = "Change the draft in the user's Jev panel. The panel updates immediately. Free; never runs. " +
                "Pass any of state, questions, model, timeout_ms. questions uses the jev_decide shape. " +
                "mode replace (default) swaps the whole questions object; merge upserts questions by ID and keeps the rest. " +
                "remove_questions deletes IDs after the upsert. Returns the new draft with every issue and its path; " +
                "fix issues with another jev_draft_set before calling jev_draft_run.",
            inputSchema = DRAFT_SET_SCHEMA,
            readOnly = false,
            handler = McpToolHandler(::draftSet),
        ),
        McpToolDefinition(
            name = "jev_draft_run",
            description = "Run the draft in the user's Jev panel exactly as its Run button does, so the answer shows in the panel " +
                "and its history. Each call is a paid OpenRouter request; run only after jev_draft_get or jev_draft_set reports no issues. " +
                "Returns the response and run ID, or an error with a code (INVALID_INPUT, BUSY, MISSING_OPENROUTER_KEY, TIMEOUT, ...).",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{}}""",
            readOnly = false,
            handler = McpToolHandler(::draftRun),
        ),
        McpToolDefinition(
            name = "jev_preset_save",
            description = "Save a reusable rubric (preset) under a name, replacing one with the same name. " +
                "Without questions it saves the panel draft, which becomes the open preset. With questions it saves only those, " +
                "with no context. Presets are reusable in jev_decide via `preset` and listed by jev_presets.",
            inputSchema = PRESET_SAVE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler(::presetSave),
        ),
        McpToolDefinition(
            name = "jev_compose",
            description = "Have the panel's built-in chat model write or revise the draft from a plain-language message, " +
                "such as \"route support tickets and decide on paging\" or \"make severity a score from 1 to 5\". " +
                "The panel shows the turn and can revert it. Costs one or two chat-model calls; it never runs Jev. " +
                "An agent that can write JSON itself should prefer jev_draft_set.",
            inputSchema = """{"type":"object","additionalProperties":false,"required":["message"],"properties":{"message":{"type":"string","description":"What to decide, or how to change the current draft"}}}""",
            readOnly = false,
            handler = McpToolHandler(::composeDraft),
        ),
    )

    internal suspend fun draftGet(args: McpToolArgs): McpToolResult = guarded {
        val vm = playground()
        objectArgs(args, emptySet())
        McpToolResult(draftJson(vm, vm.state.value).toString())
    }

    internal suspend fun draftSet(args: McpToolArgs): McpToolResult = guarded {
        val vm = playground()
        val root = objectArgs(args, setOf("state", "context", "questions", "mode", "remove_questions", "model", "timeout_ms"))
        if (root.containsKey("state") && root.containsKey("context")) {
            throw JevFailure("INVALID_INPUT", "Pass state or context, not both", "context")
        }
        val stateKey = if (root.containsKey("context")) "context" else "state"
        var contextText: String? = null
        var sendAsText: Boolean? = null
        when (val value = root[stateKey]) {
            null -> Unit
            is JsonPrimitive -> {
                contextText = value.takeIf { it.isString }?.content
                    ?: throw JevFailure("INVALID_INPUT", "$stateKey must be a string, object, or array", stateKey)
                // A string is text even when it looks like JSON, as in jev_decide.
                sendAsText = looksLikeJson(contextText)
            }
            is JsonObject, is JsonArray -> {
                contextText = pretty.encodeToString(JsonElement.serializer(), value)
                sendAsText = false
            }
        }
        val questions = root["questions"]?.let {
            it as? JsonObject ?: throw JevFailure("INVALID_INPUT", "questions must be an object keyed by question ID", "questions")
        }
        val mode = when (val m = root["mode"]) {
            null -> JevDraftMode.REPLACE
            else -> when ((m as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "replace" -> JevDraftMode.REPLACE
                "merge" -> JevDraftMode.MERGE
                else -> throw JevFailure("INVALID_INPUT", "mode must be replace or merge", "mode")
            }
        }
        val remove = root["remove_questions"]?.let { value ->
            (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw JevFailure("INVALID_INPUT", "remove_questions must be an array of question IDs", "remove_questions") }
                ?: throw JevFailure("INVALID_INPUT", "remove_questions must be an array of question IDs", "remove_questions")
        }.orEmpty()
        val change = JevDraftChange(
            contextText = contextText,
            sendAsText = sendAsText,
            questions = questions,
            mode = mode,
            removeQuestions = remove,
            model = root["model"]?.let { stringArg(it, "model") },
            timeoutMs = root["timeout_ms"]?.let { longArg(it, "timeout_ms") },
        )
        val next = vm.applyDraft(change)
        McpToolResult(draftJson(vm, next).toString())
    }

    internal suspend fun draftRun(args: McpToolArgs): McpToolResult = guarded {
        val vm = playground()
        objectArgs(args, emptySet())
        val run = vm.runDraft()
        McpToolResult(buildJsonObject {
            put("run_id", run.id)
            put("response", run.decision.response)
            put("latency_ms", run.decision.latencyMs)
        }.toString())
    }

    internal suspend fun presetSave(args: McpToolArgs): McpToolResult = guarded {
        val vm = playground()
        val root = objectArgs(args, setOf("name", "questions", "model", "timeout_ms"))
        val name = root["name"]?.let { stringArg(it, "name") } ?: throw JevFailure("INVALID_INPUT", "name is required", "name")
        val questions = root["questions"]?.let {
            it as? JsonObject ?: throw JevFailure("INVALID_INPUT", "questions must be an object keyed by question ID", "questions")
        }
        val model = root["model"]?.let { stringArg(it, "model") }
        val timeout = root["timeout_ms"]?.let { longArg(it, "timeout_ms") }
        if (questions != null) {
            // A preset saved from arguments is only useful if jev_decide will accept it.
            val issues = JevValidation.requestIssues(
                JevRequest(JsonPrimitive("preset"), questions, timeout ?: JevLimits.DEFAULT_TIMEOUT_MS, model ?: JevModelCatalog.DEFAULT.id),
                service.limits,
            )
            if (issues.isNotEmpty()) return@guarded issuesError(issues)
        }
        val saved = vm.savePreset(name, questions, model, timeout)
        McpToolResult(buildJsonObject {
            put("saved", saved.name)
            put("source", if (questions == null) "draft" else "questions")
            put("model", saved.model)
            put("timeout_ms", saved.timeoutMs)
            if (questions == null) put("issues", issuesJson(vm.state.value.apiIssues))
        }.toString())
    }

    internal suspend fun composeDraft(args: McpToolArgs): McpToolResult = guarded {
        val vm = playground()
        val root = objectArgs(args, setOf("message"))
        val message = root["message"]?.let { stringArg(it, "message") } ?: throw JevFailure("INVALID_INPUT", "message is required", "message")
        val result = vm.compose(message)
        McpToolResult(buildJsonObject {
            put("reply", result.reply)
            put("repaired", result.repaired)
            put("draft", draftJson(vm, vm.state.value))
        }.toString())
    }

    private fun playground(): JevPlaygroundViewModel =
        playground?.invoke() ?: throw JevFailure("SERVICE_UNAVAILABLE", "The Jev panel draft is unavailable")

    private fun draftJson(vm: JevPlaygroundViewModel, s: JevPlaygroundState): JsonObject = buildJsonObject {
        put("title", s.title)
        s.presetName?.let { put("preset", it) }
        put("unsaved_changes", s.dirty)
        when (val format = s.contextFormat) {
            is JevContextFormat.Json -> { put("state", format.element); put("state_format", "json") }
            JevContextFormat.Empty -> { put("state", s.contextText); put("state_format", "empty") }
            is JevContextFormat.Invalid -> { put("state", s.contextText); put("state_format", "invalid_json") }
            is JevContextFormat.Text -> { put("state", s.contextText); put("state_format", "text") }
        }
        val questions = s.questionsJson()
        if (questions != null) put("questions", questions) else {
            put("questions", JsonNull)
            put("questions_text", s.jsonText)
        }
        put("model", s.model)
        put("timeout_ms", s.timeoutMs)
        put("valid", s.request != null)
        put("issues", issuesJson(s.apiIssues))
        put("running", s.running)
        vm.runs.value.firstOrNull()?.let { run ->
            put("last_run", buildJsonObject {
                put("run_id", run.id)
                put("source", run.source.name.lowercase())
                put("at", run.at.toString())
                put("matches_draft", run.request == s.request)
                put("summary", JevComposer.summarizeRun(run))
                (run.decision.response["answers"] as? JsonObject)?.let { put("answers", it) }
            })
        }
    }

    private fun issuesJson(issues: List<JevIssue>) = buildJsonArray {
        issues.forEach { add(buildJsonObject { put("path", it.pathText); put("message", it.message) }) }
    }

    private fun issuesError(issues: List<JevIssue>) = McpToolResult(
        buildJsonObject {
            put("error", buildJsonObject {
                put("code", "INVALID_INPUT")
                put("path", issues.first().pathText)
                put("message", issues.first().message)
            })
            put("issues", issuesJson(issues))
        }.toString(),
        isError = true,
    )

    /** Tools with no arguments accept `{}` or an empty body. */
    private fun objectArgs(args: McpToolArgs, allowed: Set<String>): JsonObject {
        if (args.raw.encodeToByteArray().size > service.limits.maxRequestBytes) {
            throw JevFailure("INPUT_TOO_LARGE", "Arguments exceed plugin limit ${service.limits.maxRequestBytes} bytes")
        }
        if (args.raw.isBlank()) return JsonObject(emptyMap())
        val root = json.parseToJsonElement(args.raw) as? JsonObject
            ?: throw JevFailure("INVALID_INPUT", "Arguments must be a JSON object")
        val unknown = root.keys - allowed
        if (unknown.isNotEmpty()) {
            throw JevFailure("INVALID_INPUT", "Unknown arguments: ${unknown.sorted().joinToString()}", unknown.sorted().first())
        }
        return root
    }

    private fun stringArg(value: JsonElement, name: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw JevFailure("INVALID_INPUT", "$name must be a string", name)

    private fun longArg(value: JsonElement, name: String): Long =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw JevFailure("INVALID_INPUT", "$name must be an integer", name)

    private suspend fun parse(args: McpToolArgs): JevRequest {
        if (args.raw.encodeToByteArray().size > service.limits.maxRequestBytes) {
            throw JevFailure("INPUT_TOO_LARGE", "Arguments exceed plugin limit ${service.limits.maxRequestBytes} bytes")
        }
        val root = json.parseToJsonElement(args.raw) as? JsonObject
            ?: throw JevFailure("INVALID_INPUT", "Arguments must be a JSON object")
        val unknown = root.keys - setOf("state", "questions", "preset", "model", "timeout_ms")
        if (unknown.isNotEmpty()) {
            throw JevFailure("INVALID_INPUT", "Unknown arguments: ${unknown.sorted().joinToString()}", unknown.sorted().first())
        }
        val state = root["state"] ?: throw JevFailure("INVALID_INPUT", "state is required", "state")
        val presetName = root["preset"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw JevFailure("INVALID_INPUT", "preset must be a string", "preset")
        }
        if (presetName != null && root.containsKey("questions")) {
            throw JevFailure("INVALID_INPUT", "Pass questions or preset, not both", "preset")
        }
        val preset = presetName?.let { name ->
            val repo = presets ?: throw JevFailure("PRESET_STORAGE_ERROR", "Preset storage is unavailable")
            repo.load(name) ?: throw JevFailure("INVALID_INPUT", "No preset named '$name'; call jev_presets for names", "preset")
        }
        val questions = if (preset != null) {
            runCatching { json.parseToJsonElement(preset.questionsText) as? JsonObject }.getOrNull()
                ?: throw JevFailure("PRESET_STORAGE_ERROR", "Preset '${preset.name}' has invalid questions")
        } else {
            root["questions"] as? JsonObject
                ?: throw JevFailure("INVALID_INPUT", "questions must be an object keyed by question ID", "questions")
        }
        val timeout = when (val value = root["timeout_ms"]) {
            null -> preset?.timeoutMs ?: JevLimits.DEFAULT_TIMEOUT_MS
            is JsonPrimitive -> value.takeIf { !it.isString }?.content?.toLongOrNull()
                ?: throw JevFailure("INVALID_INPUT", "timeout_ms must be an integer", "timeout_ms")
            else -> throw JevFailure("INVALID_INPUT", "timeout_ms must be an integer", "timeout_ms")
        }
        val model = when (val value = root["model"]) {
            null -> preset?.model ?: JevModelCatalog.DEFAULT.id
            is JsonPrimitive -> value.takeIf { it.isString }?.content
                ?: throw JevFailure("INVALID_INPUT", "model must be a string", "model")
            else -> throw JevFailure("INVALID_INPUT", "model must be a string", "model")
        }
        return JevRequest(state, questions, timeout, model)
    }

    private suspend fun guarded(block: suspend () -> McpToolResult): McpToolResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: JevFailure) {
        error(failure.code, failure.message, failure.path)
    } catch (_: SerializationException) {
        error("INVALID_INPUT", "Arguments must be valid JSON", null)
    } catch (_: Exception) {
        error("SERVICE_UNAVAILABLE", "Jev could not complete the request", null)
    }

    private fun error(code: String, message: String, path: String?) = McpToolResult(
        buildJsonObject {
            put("error", buildJsonObject {
                put("code", code)
                path?.let { put("path", it) }
                put("message", message)
            })
        }.toString(),
        isError = true,
    )

    companion object {
        val DRAFT_SET_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "state":{"description":"Decision context: a string is sent as text, an object or array as JSON","oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},
              "context":{"description":"Alias of state","oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},
              "questions":{"type":"object","description":"Questions keyed by snake_case ID, in the jev_decide shape: {type: noul|choice|score, instructions, criteria}"},
              "mode":{"type":"string","enum":["replace","merge"],"default":"replace","description":"replace swaps all questions; merge upserts by ID"},
              "remove_questions":{"type":"array","items":{"type":"string"},"description":"Question IDs to delete"},
              "model":{"type":"string","enum":[${JevModelCatalog.all.joinToString(",") { "\"${it.id}\"" }}]},
              "timeout_ms":{"type":"integer","minimum":1000,"maximum":120000}
            }}
        """.trimIndent()

        val PRESET_SAVE_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["name"],"properties":{
              "name":{"type":"string","minLength":1,"maxLength":80},
              "questions":{"type":"object","description":"Save these questions instead of the panel draft; no context is stored"},
              "model":{"type":"string","enum":[${JevModelCatalog.all.joinToString(",") { "\"${it.id}\"" }}]},
              "timeout_ms":{"type":"integer","minimum":1000,"maximum":120000}
            }}
        """.trimIndent()

        val DECIDE_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "state":{"description":"Decision context as a string, object, or array","oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},
              "preset":{"type":"string","description":"Name of a rubric saved in the Jev panel; use instead of questions"},
              "model":{"type":"string","enum":[${JevModelCatalog.all.joinToString(",") { "\"${it.id}\"" }}],"default":"${JevModelCatalog.DEFAULT.id}","description":"Decision model"},
              "questions":{"type":"object","minProperties":1,"maxProperties":32,"additionalProperties":{"oneOf":[
                {"type":"object","additionalProperties":false,"properties":{"type":{"const":"noul"},"instructions":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},"criteria":{"type":"object","additionalProperties":false,"properties":{"true":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},"false":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]}}}},"required":["type","instructions"]},
                {"type":"object","additionalProperties":false,"properties":{"type":{"const":"choice"},"instructions":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},"criteria":{"type":"object","minProperties":1,"maxProperties":255,"additionalProperties":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"},{"type":"null"}]}}},"required":["type","instructions","criteria"]},
                {"type":"object","additionalProperties":false,"properties":{"type":{"const":"score"},"instructions":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]},"criteria":{"type":"array","minItems":2,"maxItems":10,"items":{"oneOf":[{"type":"string"},{"type":"object"},{"type":"array"}]}}},"required":["type","instructions","criteria"]}
              ]}},
              "timeout_ms":{"type":"integer","minimum":1000,"maximum":120000,"default":30000}
            },"required":["state"]}
        """.trimIndent()
    }
}
