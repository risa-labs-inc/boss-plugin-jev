package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiAgentResult
import ai.rever.boss.plugin.api.AiAvailableModel
import ai.rever.boss.plugin.api.AiBudget
import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.AiProviderModels
import ai.rever.boss.plugin.api.AiReply
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/** Replies in order and records every call. */
internal class ScriptedChat(
    vararg replies: String,
    private val models: List<JevChatModel> = listOf(JevChatModel("ANTHROPIC", "Anthropic", "claude-test")),
    private val gateway: Boolean = true,
) : JevChatClient {
    private val queue = ArrayDeque(replies.toList())
    val calls = mutableListOf<Pair<String, List<JevChatMessage>>>()
    val modelsUsed = mutableListOf<JevChatModel?>()
    var failure: JevFailure? = null

    override fun gatewayAvailable() = gateway
    override fun models() = models
    override fun defaultModel(models: List<JevChatModel>) = models.firstOrNull()
    override suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String {
        failure?.let { throw it }
        calls += system to messages
        modelsUsed += model
        return queue.removeFirstOrNull() ?: error("no scripted reply left")
    }
}

internal fun composeReply(context: String? = "\"A ticket\"", questions: String? = VALID_QUESTIONS, reply: String = "Drafted it.") =
    """{"context":${context ?: "null"},"questions":${questions ?: "null"},"reply":"$reply"}"""

internal const val VALID_QUESTIONS =
    """{"route":{"type":"choice","instructions":"Pick a team","criteria":{"identity":"Login","support":null}},"urgent":{"type":"noul","instructions":"Page now?"}}"""

class JevComposerTest {
    private val draft = JevComposeDraft(
        contextText = "old context",
        sendAsText = false,
        questions = testJson.parseToJsonElement("""{"old":{"type":"noul","instructions":"Old?"}}""") as JsonObject,
        questionsText = "",
    )

    // ---- parsing ----

    @Test
    fun `parses a bare or fenced object and keeps null fields as keep`() {
        val bare = JevComposer.parseReply(composeReply())
        assertEquals("A ticket", bare.context)
        assertEquals(setOf("route", "urgent"), bare.questions!!.keys)
        assertEquals("Drafted it.", bare.reply)

        val fenced = JevComposer.parseReply("```json\n" + composeReply(context = null, questions = null) + "\n```")
        assertNull(fenced.context)
        assertNull(fenced.questions)
    }

    @Test
    fun `structured context becomes pretty JSON text the panel detects as JSON`() {
        val result = JevComposer.parseReply(composeReply(context = """{"customer":"Acme","seats":3}"""))
        assertTrue(detectContext(result.context!!, false) is JevContextFormat.Json)
    }

    @Test
    fun `rejects prose, unknown keys, and wrong shapes`() {
        val cases = listOf(
            "Sure! Here is the draft: " + composeReply(),
            """{"context":"x","questions":{},"reply":"ok","notes":"extra"}""",
            """{"context":"x","questions":[],"reply":"ok"}""",
            """{"context":3,"questions":null,"reply":"ok"}""",
            """{"context":"x","questions":null}""",
            """{"context":"x","questions":null,"reply":"  "}""",
            "```json\n{\"reply\":\"x\"}",
            "{not json",
        )
        cases.forEach { raw -> assertFailsWith<IllegalArgumentException>(raw) { JevComposer.parseReply(raw) } }
    }

    // ---- compose ----

    @Test
    fun `a valid first reply needs no repair and the prompt carries the draft and request`() = runTest {
        val chat = ScriptedChat(composeReply())
        val result = JevComposer(chat).compose("route tickets", draft)
        assertEquals(1, chat.calls.size)
        assertFalse(result.repaired)
        assertTrue(result.issues.isEmpty())
        val (system, messages) = chat.calls.single()
        val prompt = messages.single().text
        assertTrue("old context" in prompt && "\"old\"" in prompt && prompt.endsWith("route tickets"))
        listOf("noul", "choice", "score", "snake_case", "At most 32", "2 to 10", "only one JSON object").forEach {
            assertTrue(it in system, "system prompt should teach: $it")
        }
    }

    @Test
    fun `one repair turn feeds back issue paths and fixes the draft`() = runTest {
        val broken = """{"sev":{"type":"score","instructions":"Rate","criteria":["only one"]}}"""
        val chat = ScriptedChat(composeReply(questions = broken), composeReply(questions = VALID_QUESTIONS, reply = "Fixed."))
        val result = JevComposer(chat).compose("rate severity", draft)
        assertEquals(2, chat.calls.size)
        val repair = chat.calls[1].second
        assertEquals(listOf("user", "assistant", "user"), repair.map { it.role })
        assertTrue("questions.sev.criteria" in repair.last().text, repair.last().text)
        assertTrue(result.repaired)
        assertTrue(result.issues.isEmpty())
        assertEquals("Fixed.", result.reply)
    }

    @Test
    fun `issues left after the repair are surfaced, not retried again`() = runTest {
        val broken = """{"Bad ID":{"type":"score","instructions":"Rate","criteria":["one"]}}"""
        val chat = ScriptedChat(composeReply(questions = broken), composeReply(questions = broken))
        val result = JevComposer(chat).compose("rate", draft)
        assertEquals(2, chat.calls.size)
        assertEquals(setOf("questions.Bad ID.criteria", "questions.Bad ID"), result.issues.map { it.pathText }.toSet())
    }

    @Test
    fun `a repair that leaves a field null keeps the first reply's value`() = runTest {
        val chat = ScriptedChat(
            composeReply(context = "\"\"", questions = VALID_QUESTIONS),
            composeReply(context = "\"Filled in\"", questions = null),
        )
        val result = JevComposer(chat).compose("x", draft)
        assertEquals("Filled in", result.context)
        assertEquals(setOf("route", "urgent"), result.questions!!.keys)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `an unparseable reply gets the repair turn, then a clear failure`() = runTest {
        val recovered = ScriptedChat("I think you want routing.", composeReply())
        assertTrue(JevComposer(recovered).compose("x", draft).repaired)
        assertTrue("not usable" in recovered.calls[1].second.last().text)

        val hopeless = ScriptedChat("prose", "more prose")
        val failure = assertFailsWith<JevFailure> { JevComposer(hopeless).compose("x", draft) }
        assertEquals(JevComposer.COMPOSE_FAILED, failure.code)
    }

    @Test
    fun `earlier turns and the last run reach the prompt`() = runTest {
        val chat = ScriptedChat(composeReply())
        val run = JevRunRecord(
            7, Instant.EPOCH, JevRunSource.PLAYGROUND, requestAllTypes(),
            JevDecision(testJson.parseToJsonElement(validResponse) as JsonObject, 10),
        )
        JevComposer(chat).compose(
            "why identity? tighten it", draft,
            turns = listOf(JevComposeTurn("route tickets", "Drafted routing."), JevComposeTurn("undone", "gone", reverted = true)),
            lastRun = JevComposer.summarizeRun(run),
        )
        val prompt = chat.calls.single().second.single().text
        assertTrue("User: route tickets" in prompt && "You: Drafted routing." in prompt)
        assertFalse("undone" in prompt)
        assertTrue("Last run (#7)" in prompt)
        assertTrue("route: chose identity" in prompt, prompt)
        assertTrue("urgent: yes with p=0.90" in prompt)
        assertTrue("readiness: score 1.05 of 0..2 (nearest: \"risky\")" in prompt)
    }

    // ---- gateway client ----

    private class FakeGateway(
        val catalog: List<AiProviderModels>,
        val active: AiModelInfo?,
        val caps: Set<String> = setOf(AiGatewayAPI.CAPABILITY_PROVIDER_OVERRIDE),
    ) : AiGatewayAPI {
        val requests = mutableListOf<AiRequest>()
        override suspend fun complete(request: AiRequest): Result<AiReply> { requests += request; return Result.success(AiReply("ok")) }
        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()
        override suspend fun runAgent(request: AiRequest, tools: List<AiToolSpec>, budget: AiBudget, invoke: suspend (AiToolCall) -> AiToolOutcome): Result<AiAgentResult> =
            Result.failure(UnsupportedOperationException())
        override fun capabilities() = caps
        override fun activeModel() = active
        override fun availableModels() = catalog
    }

    private val catalog = listOf(
        AiProviderModels("OPENROUTER", "OpenRouter", listOf(
            AiAvailableModel("openrouter/free", "Free router"),
            AiAvailableModel("typesafe/jev-1.13", "Jev"),
            AiAvailableModel("anthropic/claude-x", "Claude X"),
        )),
    )

    @Test
    fun `lists chat models without decision models and never defaults to openrouter free`() {
        val gateway = FakeGateway(catalog, AiModelInfo("OPENROUTER", "OpenRouter", "openrouter/free"))
        val client = GatewayJevChatClient({ gateway }, { null })
        val models = client.models()
        assertEquals(listOf("openrouter/free", "anthropic/claude-x"), models.map { it.modelId })
        assertEquals("anthropic/claude-x", client.defaultModel(models)?.modelId)
    }

    @Test
    fun `routes the picked model through extras only when the gateway supports it`() = runTest {
        val picked = JevChatModel("OPENROUTER", "OpenRouter", "anthropic/claude-x")
        val gateway = FakeGateway(catalog, AiModelInfo("ANTHROPIC", "Anthropic", "claude"))
        GatewayJevChatClient({ gateway }, { null }).complete("sys", listOf(JevChatMessage.user("hi")), picked)
        assertEquals(mapOf("providerId" to "OPENROUTER", "modelOverride" to "anthropic/claude-x"), gateway.requests.single().extras)

        val legacy = FakeGateway(catalog, AiModelInfo("ANTHROPIC", "Anthropic", "claude"), caps = emptySet())
        val refused = assertFailsWith<JevFailure> { GatewayJevChatClient({ legacy }, { null }).complete("sys", emptyList(), picked) }
        assertEquals(JevComposer.MODEL_UNROUTABLE, refused.code)
        assertTrue(legacy.requests.isEmpty())
    }

    @Test
    fun `missing gateway or model is a typed failure`() = runTest {
        val noGateway = assertFailsWith<JevFailure> { GatewayJevChatClient({ null }, { null }).complete("s", emptyList(), null) }
        assertEquals(JevComposer.NO_GATEWAY, noGateway.code)
        val noModel = assertFailsWith<JevFailure> { GatewayJevChatClient({ FakeGateway(emptyList(), null) }, { null }).complete("s", emptyList(), null) }
        assertEquals(JevComposer.NO_MODEL, noModel.code)
    }
}
