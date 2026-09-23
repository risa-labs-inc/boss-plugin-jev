package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JevMcpToolProvider(
    override val providerId: String,
    internal val service: JevDecisionService,
    private val presets: JevPresetRepository? = null,
) : McpToolProvider {
    private val json = Json

    override fun tools(): List<McpToolDefinition> = listOf(
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
