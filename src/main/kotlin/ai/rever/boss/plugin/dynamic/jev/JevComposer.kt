package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A chat model reachable through the AI Gateway. [key] is what plugin storage remembers. */
data class JevChatModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val label: String = modelId,
) {
    val key: String get() = "$providerId:$modelId"
}

data class JevChatMessage(val role: String, val text: String) {
    companion object {
        fun user(text: String) = JevChatMessage(AiMessage.ROLE_USER, text)
        fun assistant(text: String) = JevChatMessage(AiMessage.ROLE_ASSISTANT, text)
    }
}

/** Chat seam for the composer, so tests run without a gateway. */
interface JevChatClient {
    fun gatewayAvailable(): Boolean
    fun models(): List<JevChatModel>

    /** The model that answers when none is picked, or null when nothing is configured. */
    fun defaultModel(models: List<JevChatModel>): JevChatModel?

    /** Returns the reply text. Throws [JevFailure] with a person-readable message. */
    suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String
}

/**
 * The AI Gateway, resolved per call: plugin load order is not guaranteed, so caching the API
 * at registration could hold a null forever.
 */
class GatewayJevChatClient(
    private val gateway: () -> AiGatewayAPI?,
    private val llmProvider: () -> LlmProvider?,
) : JevChatClient {
    override fun gatewayAvailable(): Boolean = runCatching { gateway() }.getOrNull() != null

    /** Gateway catalog, then the provider plugin's, then each configured provider's selected model. */
    override fun models(): List<JevChatModel> {
        val selected = runCatching { llmProvider()?.configuredProviders().orEmpty() }.getOrDefault(emptyList())
            .associate { it.providerId to it.modelId }
        // Each call is guarded: on a host older than the api that added it, it throws NoSuchMethodError.
        val catalog = runCatching { gateway()?.availableModels().orEmpty() }.getOrDefault(emptyList())
            .ifEmpty { runCatching { llmProvider()?.availableModels().orEmpty() }.getOrDefault(emptyList()) }
        val listed = if (catalog.isNotEmpty()) {
            catalog.flatMap { p ->
                val models = p.models.map { JevChatModel(p.providerId, p.providerName, it.id, it.displayName.ifBlank { it.id }) }
                    .ifEmpty { selected[p.providerId]?.let { listOf(JevChatModel(p.providerId, p.providerName, it)) }.orEmpty() }
                val chosen = selected[p.providerId]
                models.filter { it.modelId == chosen } + models.filter { it.modelId != chosen }
            }
        } else {
            val configured = runCatching { llmProvider()?.configuredProviders().orEmpty() }.getOrDefault(emptyList())
            configured.map { JevChatModel(it.providerId, it.displayName, it.modelId) }.ifEmpty {
                runCatching { gateway()?.activeModel() }.getOrNull()
                    ?.let { listOf(JevChatModel(it.providerId, it.providerName, it.modelId)) }.orEmpty()
            }
        }
        // Decision models are not chat models, even when a catalog lists them.
        return listed.filter { m -> JevModelCatalog.find(m.modelId) == null }.distinctBy { it.key }
    }

    override fun defaultModel(models: List<JevChatModel>): JevChatModel? {
        val usable = models.filter { it.modelId !in UNSAFE_DEFAULTS }
        val active = runCatching { gateway()?.activeModel() }.getOrNull()
        val configured = runCatching { llmProvider()?.configuredProviders().orEmpty() }.getOrDefault(emptyList())
        return usable.firstOrNull { active != null && it.providerId == active.providerId && it.modelId == active.modelId }
            ?: configured.firstNotNullOfOrNull { c -> usable.firstOrNull { it.providerId == c.providerId && it.modelId == c.modelId } }
            ?: usable.firstOrNull()
    }

    override suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String {
        val api = runCatching { gateway() }.getOrNull()
            ?: throw JevFailure(JevComposer.NO_GATEWAY, "Install the AI Gateway plugin to draft with a chat model")
        val active = runCatching { api.activeModel() }.getOrNull()
        if (model == null && active == null) {
            throw JevFailure(JevComposer.NO_MODEL, "Add a chat provider in Secret Manager → AI Providers")
        }
        val extras = when {
            model == null -> emptyMap()
            AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE in runCatching { api.capabilities() }.getOrDefault(emptySet()) -> mapOf(
                AiRequest.EXTRAS_KEY_PROVIDER_ID to model.providerId,
                AiRequest.EXTRAS_KEY_MODEL_OVERRIDE to model.modelId,
            )
            // Without override support the key would be ignored and another model would answer.
            active != null && active.providerId == model.providerId && active.modelId == model.modelId -> emptyMap()
            else -> throw JevFailure(
                JevComposer.MODEL_UNROUTABLE,
                "This AI Gateway only uses the model selected in AI Providers (${active?.modelId ?: "none"}). Pick that model or update AI Gateway.",
            )
        }
        val request = AiRequest(
            system = system,
            messages = messages.map { AiMessage(it.role, it.text) },
            // Room for reasoning models and a full 32-question draft.
            maxTokens = 8_000,
            timeoutMs = JevComposer.CALL_TIMEOUT_MS,
            extras = extras,
        )
        return api.complete(request).getOrElse { error ->
            if (error is CancellationException) throw error
            // Gateway messages are written for a person; bounded so no body is echoed at length.
            throw JevFailure(JevComposer.COMPOSE_FAILED, error.message?.take(240) ?: "The chat model request failed")
        }.text
    }

    companion object {
        /** Routes to arbitrary, sometimes non-chat, models; never picked on the user's behalf. */
        val UNSAFE_DEFAULTS = setOf("openrouter/free")
    }
}

data class JevComposeTurn(
    val user: String,
    val reply: String,
    /** Issues left after the repair turn. */
    val issues: Int = 0,
    /** The run summarized into this turn, if any. */
    val runId: Long? = null,
    val reverted: Boolean = false,
)

/** The draft as the composer sees it. [questions] is null when the JSON editor does not parse. */
data class JevComposeDraft(
    val contextText: String,
    val sendAsText: Boolean,
    val questions: JsonObject?,
    val questionsText: String,
    val model: String = JevModelCatalog.DEFAULT.id,
    val timeoutMs: Long = JevLimits.DEFAULT_TIMEOUT_MS,
)

/** Null fields keep the current value. */
data class JevComposeResult(
    val context: String?,
    val questions: JsonObject?,
    val reply: String,
    val issues: List<JevIssue> = emptyList(),
    val repaired: Boolean = false,
    /** Up to three short next edits the user can send with one click. */
    val suggestions: List<String> = emptyList(),
)

/** Where a compose turn is, for the in-progress card. */
sealed interface JevComposeStage {
    data object Drafting : JevComposeStage
    data class Fixing(val issues: Int) : JevComposeStage
    data object Reformatting : JevComposeStage
}

/** Turns plain language into a Jev request: one generation, at most one repair turn. */
class JevComposer(
    private val chat: JevChatClient,
    private val limits: JevLimits = JevLimits(),
    /** Bounds each model call here, whatever the gateway's own limits and retries do. */
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
) {
    suspend fun compose(
        message: String,
        draft: JevComposeDraft,
        turns: List<JevComposeTurn> = emptyList(),
        lastRun: String? = null,
        model: JevChatModel? = null,
        onStage: (JevComposeStage) -> Unit = {},
    ): JevComposeResult {
        require(message.isNotBlank()) { "Describe what to decide" }
        val prompt = JevChatMessage.user(userPrompt(message, draft, turns, lastRun))
        onStage(JevComposeStage.Drafting)
        val firstRaw = call(listOf(prompt), model)
        val first = runCatching { parseReply(firstRaw) }
        val firstIssues = first.getOrNull()?.let { issuesFor(it, draft) }
        if (firstIssues != null && firstIssues.isEmpty()) return first.getOrThrow()

        val feedback = if (firstIssues != null) {
            "The draft you returned has these problems:\n" +
                firstIssues.take(MAX_FEEDBACK_ISSUES).joinToString("\n") { "- ${it.pathText.ifEmpty { "(root)" }}: ${it.message}" } +
                "\nReply again with only the corrected JSON object, with the complete questions object."
        } else {
            "That reply was not usable: ${first.exceptionOrNull()?.message}. Reply with only the JSON object described, nothing else."
        }
        onStage(if (firstIssues != null) JevComposeStage.Fixing(firstIssues.size) else JevComposeStage.Reformatting)
        val repairRaw = call(listOf(prompt, JevChatMessage.assistant(firstRaw.take(MAX_ECHO_CHARS)), JevChatMessage.user(feedback)), model)
        val repair = runCatching { parseReply(repairRaw) }.getOrNull()
        val base = first.getOrNull()
        val merged = when {
            repair == null && base == null -> throw JevFailure(
                COMPOSE_FAILED,
                "The chat model did not return a usable draft: ${first.exceptionOrNull()?.message}",
            )
            repair == null -> base!!
            base == null -> repair
            // A field the repair left null keeps the first reply's value, not the original draft's.
            else -> JevComposeResult(
                repair.context ?: base.context, repair.questions ?: base.questions, repair.reply,
                suggestions = repair.suggestions.ifEmpty { base.suggestions },
            )
        }
        return merged.copy(issues = issuesFor(merged, draft), repaired = true)
    }

    private suspend fun call(messages: List<JevChatMessage>, model: JevChatModel?): String =
        withTimeoutOrNull(callTimeoutMs) { chat.complete(SYSTEM_PROMPT, messages, model) }
            ?: throw JevFailure(TIMEOUT, "${model?.label ?: "The chat model"} did not answer within ${callTimeoutMs / 1000} s. Try again, or pick a faster model.")

    /** Validates the draft that would result, with the panel's model and timeout. */
    internal fun issuesFor(result: JevComposeResult, draft: JevComposeDraft): List<JevIssue> {
        val issues = mutableListOf<JevIssue>()
        val contextText = result.context ?: draft.contextText
        val sendAsText = if (result.context != null) false else draft.sendAsText
        val state: JsonElement? = when (val format = detectContext(contextText, sendAsText)) {
            JevContextFormat.Empty -> null.also { issues += JevIssue(listOf("context"), "Context is empty") }
            is JevContextFormat.Invalid -> null.also { issues += JevIssue(listOf("context"), "Context looks like JSON but does not parse: ${format.reason}") }
            is JevContextFormat.Json -> format.element
            is JevContextFormat.Text -> JsonPrimitive(contextText)
        }
        val questions = result.questions ?: draft.questions
            ?: return issues + JevIssue(listOf("questions"), "questions is required: the current questions JSON does not parse")
        val request = JevRequest(state ?: JsonPrimitive("placeholder"), questions, draft.timeoutMs, draft.model)
        issues += JevValidation.requestIssues(request, limits).filter { it.path.firstOrNull() == "questions" || (state != null && it.path.firstOrNull() == "state") }
        questions.keys.filterNot { SNAKE_CASE.matches(it) }.forEach {
            issues += JevIssue(listOf("questions", it), "Question IDs must be snake_case, such as page_oncall")
        }
        return issues
    }

    companion object {
        const val NO_GATEWAY = "NO_GATEWAY"
        const val NO_MODEL = "NO_MODEL"
        const val MODEL_UNROUTABLE = "MODEL_UNROUTABLE"
        const val COMPOSE_FAILED = "COMPOSE_FAILED"
        const val TIMEOUT = "COMPOSE_TIMEOUT"
        const val CALL_TIMEOUT_MS = 60_000L
        private const val MAX_SUGGESTIONS = 3
        private const val MAX_SUGGESTION_CHARS = 80

        private val SNAKE_CASE = Regex("[a-z][a-z0-9_]*")
        private const val MAX_FEEDBACK_ISSUES = 20
        private const val MAX_ECHO_CHARS = 12_000
        private const val MAX_TURNS = 10
        private const val MAX_TURN_CHARS = 800
        private val pretty = Json { prettyPrint = true }

        val SYSTEM_PROMPT = """
You write requests for Jev, a decision model. Jev judges a situation (the context) against typed questions and returns calibrated probabilities. You never answer the questions yourself; you write them.

Reply with only one JSON object, with no prose and no code fence:
{"context": <string, object, array, or null>, "questions": <object or null>, "reply": "<one short sentence>", "suggestions": ["<short next edit>", ...]}
- context: the situation Jev judges. Use a JSON object when the facts are fields and values, otherwise plain text. null keeps the current context.
- questions: the COMPLETE questions object, not a diff. null keeps the current questions.
- reply: one short plain sentence on what you did. The user sees the exact changes separately, so do not list them.
- suggestions: 0 to 3 short next edits the user might want, written as the user would ask, under 60 characters each, such as "Add a question about cost".

The questions object is keyed by question id. Ids are snake_case (lowercase letters, digits and underscores, starting with a letter), unique and short, such as "route" or "page_oncall". At most ${JevLimits().maxQuestions} questions, at least 1.
Each question is an object with exactly the fields "type", "instructions" and "criteria". No other fields.
- "type" is one of "noul", "choice", "score".
- "instructions" is a non-blank string that states the decision explicitly.
- noul: a yes/no question, answered with the probability of yes. criteria is optional: {"true": "when the answer is yes", "false": "when the answer is no"}; either key may be left out. No other keys.
- choice: pick exactly one named option. criteria is required: an object of option name to description (a string, or null for no description). 1 to ${JevValidation.MAX_OPTIONS} options. Option names are short snake_case labels.
- score: a position on an ordered rubric. criteria is required: an array of 2 to ${JevValidation.MAX_LEVELS} level descriptions, lowest first.
Instructions and criteria values may be JSON objects or arrays when structure helps, but prefer plain strings.

Example:
{"context": {"customer": "Acme", "issue": "SSO users cannot sign in", "plan": "enterprise"},
 "questions": {
  "route": {"type": "choice", "instructions": "Choose the team best equipped to own this issue.", "criteria": {"identity": "Authentication, SSO and access", "billing": "Invoices and subscriptions", "support": null}},
  "page_oncall": {"type": "noul", "instructions": "Should this page the on-call engineer now?", "criteria": {"true": "Enterprise customer fully blocked with no workaround", "false": "Partial impact or a workaround exists"}},
  "severity": {"type": "score", "instructions": "Rate the severity.", "criteria": ["Cosmetic", "Degraded", "Blocked for some users", "Blocked for everyone"]}
 },
 "reply": "Drafted routing, paging and severity for a support ticket."}

Guidance:
- One decision per question. Use choice for mutually exclusive options, score for degrees, noul for a single yes/no.
- Facts belong in the context, not the instructions. Put every fact the user gave into the context as a real value.
- Use a placeholder such as "<customer name>" only for a fact the decision needs that the user did not give. Never invent a value. Keep placeholders few; the user must fill each one before running.
- On a revision, keep every question, id and context detail the user did not ask to change.
- When a last run is given, use it to explain the result or tighten criteria. Do not invent results.
        """.trimIndent()

        internal fun userPrompt(message: String, draft: JevComposeDraft, turns: List<JevComposeTurn>, lastRun: String?): String = buildString {
            appendLine("Current draft")
            appendLine("context:")
            appendLine(draft.contextText.ifBlank { "(empty)" })
            appendLine()
            appendLine("questions:")
            appendLine(draft.questions?.let { pretty.encodeToString(JsonObject.serializer(), it) }
                ?: "(does not parse as JSON)\n${draft.questionsText}")
            val recent = turns.filterNot { it.reverted }.takeLast(MAX_TURNS)
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("Earlier in this conversation")
                recent.forEach {
                    appendLine("User: ${it.user.take(MAX_TURN_CHARS)}")
                    appendLine("You: ${it.reply.take(MAX_TURN_CHARS)}")
                }
            }
            if (lastRun != null) {
                appendLine()
                appendLine(lastRun)
            }
            appendLine()
            appendLine("Request")
            append(message.trim())
        }

        /**
         * Strict: one JSON object, optionally inside a single code fence, with only the three
         * known keys. Throws with a message the repair turn can quote back to the model.
         */
        internal fun parseReply(text: String): JevComposeResult {
            var body = text.trim()
            if (body.startsWith("```")) {
                body = body.substringAfter('\n', "").trimEnd()
                if (!body.endsWith("```")) throw IllegalArgumentException("unterminated code fence")
                body = body.removeSuffix("```").trim()
            }
            if (!body.startsWith("{")) throw IllegalArgumentException("the reply must be a single JSON object")
            val root = runCatching { Json.parseToJsonElement(body) }.getOrElse {
                throw IllegalArgumentException("the JSON does not parse (${it.message?.lineSequence()?.firstOrNull()?.take(120)})")
            } as? JsonObject ?: throw IllegalArgumentException("the reply must be a JSON object")
            // Extra keys are ignored: a repair call costs the user up to a minute, and they carry nothing.
            val reply = (root["reply"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("reply must be a non-empty string")
            val context = when (val c = root["context"]) {
                null, JsonNull -> null
                is JsonPrimitive -> c.takeIf { it.isString }?.content ?: throw IllegalArgumentException("context must be a string, object, array, or null")
                is JsonObject, is JsonArray -> pretty.encodeToString(JsonElement.serializer(), c)
            }
            val questions = when (val q = root["questions"]) {
                null, JsonNull -> null
                is JsonObject -> q
                else -> throw IllegalArgumentException("questions must be an object keyed by question id, or null")
            }
            // Lenient: suggestions are optional, and a malformed list is dropped rather than repaired.
            val suggestions = (root["suggestions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { t -> t.isNotEmpty() && t.length <= MAX_SUGGESTION_CHARS } }
                .distinct().take(MAX_SUGGESTIONS)
            return JevComposeResult(context, questions, reply, suggestions = suggestions)
        }

        /** A compact, credential-free account of one run, for the next compose turn. */
        fun summarizeRun(run: JevRunRecord): String = buildString {
            appendLine("Last run (#${run.id}) of this draft's questions returned:")
            val answers = run.decision.response["answers"] as? JsonObject ?: return@buildString
            answers.forEach { (id, value) ->
                val a = value as? JsonObject ?: return@forEach
                val line = when ((a["type"] as? JsonPrimitive)?.content) {
                    "noul" -> "yes with p=${a.num("noul")}"
                    "choice" -> {
                        val probs = (a["probabilities"] as? JsonObject)?.entries
                            ?.sortedByDescending { (it.value as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
                            ?.take(4)?.joinToString(", ") { "${it.key} ${fmt((it.value as? JsonPrimitive)?.doubleOrNull)}" }
                        "chose ${(a["choice"] as? JsonPrimitive)?.content} (confidence ${a.num("confidence")}; $probs)"
                    }
                    "score" -> {
                        val legend = a["legend"] as? JsonObject
                        val score = (a["score"] as? JsonPrimitive)?.doubleOrNull
                        val nearest = score?.let { legend?.get(Math.round(it).toString()) as? JsonPrimitive }?.content
                        "score ${fmt(score)} of 0..${(legend?.size ?: 1) - 1}" + (nearest?.let { " (nearest: \"$it\")" } ?: "") +
                            ", confidence ${a.num("confidence")}"
                    }
                    else -> return@forEach
                }
                appendLine("- $id: $line")
            }
        }.trimEnd()

        private fun JsonObject.num(key: String): String = fmt((get(key) as? JsonPrimitive)?.doubleOrNull)
        private fun fmt(value: Double?): String = value?.let { "%.2f".format(it) } ?: "?"
    }
}
