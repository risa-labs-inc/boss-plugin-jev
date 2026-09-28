package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class JevThreadTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun services(chat: JevChatClient, key: String? = "k") = JevPluginServices(
        object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = CoroutineScope(SupervisorJob())
        },
        CapturingTransport(), JevKeyResolver { key }, chat,
    )

    private fun obj(raw: String) = testJson.parseToJsonElement(raw) as JsonObject

    /** A chat whose one call waits until released, to observe the in-progress state. */
    private class GatedChat(private val reply: String) : JevChatClient {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        override fun gatewayAvailable() = true
        override fun models() = listOf(JevChatModel("A", "Anthropic", "claude-x", "Claude X"))
        override fun defaultModel(models: List<JevChatModel>) = models.first()
        override suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String {
            entered.complete(Unit)
            release.await()
            return reply
        }
    }

    // ---- the stuck "Drafting…" ----

    @Test
    fun `an Error from the host never leaves the turn pending`() = runBlocking {
        val chat = object : JevChatClient {
            var calls = 0
            override fun gatewayAvailable() = true
            override fun models() = listOf(JevChatModel("A", "A", "a"))
            override fun defaultModel(models: List<JevChatModel>) = models.first()
            override suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String {
                if (calls++ == 0) throw NoSuchMethodError("AiRequest.<init>")
                return composeReply()
            }
        }
        val vm = services(chat).playground
        runCatching { vm.compose("route tickets") }
        assertNull(vm.compose.value.pending)
        assertTrue(vm.compose.value.items.single() is JevThreadItem.Failed)
        // Not BUSY: the next turn runs.
        vm.compose("route tickets")
        assertTrue(vm.compose.value.items.last() is JevThreadItem.Turn)
    }

    @Test
    fun `the drafting card has a stage and a start time, and cancel clears it`() = runBlocking {
        val chat = GatedChat(composeReply())
        val s = services(chat)
        val vm = s.playground
        vm.nowMs = { 1_000L }
        val before = vm.state.value
        val job = launch(Dispatchers.Default) { runCatching { vm.compose("route tickets") } }
        withTimeout(5_000) { chat.entered.await() }
        val pending = assertNotNull(vm.compose.value.pending)
        assertEquals("route tickets", pending.user)
        assertEquals(JevComposeStage.Drafting, pending.stage)
        assertEquals(1_000L, pending.startedAtMs)
        assertEquals("Claude X", pending.modelLabel)

        vm.cancelCompose()
        job.join()
        assertNull(vm.compose.value.pending)
        assertTrue(vm.compose.value.items.isEmpty(), "a cancel is not a failure")
        assertEquals(before.questions.map { it.id }, vm.state.value.questions.map { it.id })
    }

    @Test
    fun `a failed turn can be retried`() = runBlocking {
        val chat = ScriptedChat(composeReply())
        chat.failure = JevFailure(JevComposer.TIMEOUT, "Claude X did not answer within 60 s")
        val vm = services(chat).playground
        runCatching { vm.compose("route tickets") }
        val failed = vm.compose.value.items.single() as JevThreadItem.Failed
        chat.failure = null
        vm.retryCompose(failed.id)
        withTimeout(5_000) { while (vm.compose.value.items.lastOrNull() !is JevThreadItem.Turn) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("route tickets"), vm.compose.value.items.map { (it as JevThreadItem.Turn).user })
    }

    // ---- change cards ----

    @Test
    fun `the first turn starts fresh and its card lists what was added`() = runBlocking {
        val chat = ScriptedChat(composeReply(context = """{"customer":"Acme","plan":"enterprise"}""", reply = "Drafted routing."))
        val vm = services(chat).playground
        vm.compose("route tickets")
        val prompt = chat.calls.single().second.single().text
        assertTrue("context:\n(empty)" in prompt, prompt)
        assertFalse("page_oncall" in prompt, "the untouched starter is not sent as a draft to revise")

        val turn = vm.compose.value.items.single() as JevThreadItem.Turn
        assertEquals(
            listOf(JevChange(JevChangeKind.ADDED, "route", "Choice"), JevChange(JevChangeKind.ADDED, "urgent", "Yes/No"), JevChange(JevChangeKind.ADDED, "context", "2 fields")),
            turn.changes,
        )
        assertEquals("Untitled", vm.state.value.title)
    }

    @Test
    fun `later turns diff against the draft and carry suggestions`() = runBlocking {
        val revised = """{"route":{"type":"choice","instructions":"Pick a team","criteria":{"identity":"Login","billing":"Invoices"}},"cost":{"type":"score","instructions":"Cost","criteria":["low","mid","high"]}}"""
        val chat = ScriptedChat(
            composeReply(),
            """{"context":"A ticket, now with cost","questions":$revised,"reply":"Added cost.","suggestions":["Make cost a yes/no"]}""",
        )
        val vm = services(chat).playground
        vm.compose("route tickets")
        vm.compose("add cost, drop urgent, swap support for billing")
        val turn = vm.compose.value.latestTurn!!
        assertEquals(
            listOf(
                JevChange(JevChangeKind.ADDED, "cost", "Score"),
                JevChange(JevChangeKind.CHANGED, "route", "options +billing −support"),
                JevChange(JevChangeKind.REMOVED, "urgent"),
                JevChange(JevChangeKind.CHANGED, "context", "rewritten"),
            ),
            turn.changes,
        )
        assertEquals(listOf("Make cost a yes/no"), turn.suggestions)
    }

    @Test
    fun `diff names type, instruction and level changes`() {
        val before = obj("""{"a":{"type":"noul","instructions":"A?"},"b":{"type":"score","instructions":"B","criteria":["x","y"]}}""")
        val after = obj("""{"a":{"type":"score","instructions":"A?","criteria":["x","y"]},"b":{"type":"score","instructions":"B now","criteria":["x","y","z"]}}""")
        assertEquals(
            listOf(JevChange(JevChangeKind.CHANGED, "a", "now Score"), JevChange(JevChangeKind.CHANGED, "b", "instructions changed, 2 → 3 levels")),
            JevDraftDiff.between("{\"k\":1}", before, "{\"k\":2,\"n\":3}", after).take(2),
        )
        assertEquals(JevChange(JevChangeKind.CHANGED, "context", "1 field added, 1 field changed"), JevDraftDiff.between("{\"k\":1}", before, "{\"k\":2,\"n\":3}", after).last())
        assertTrue(JevDraftDiff.between("same", before, "same", before).isEmpty())
    }

    // ---- manual edits and runs in the thread ----

    @Test
    fun `manual and MCP edits get one line each and end the turn's undo`() = runBlocking {
        val s = services(ScriptedChat(composeReply()))
        val vm = s.playground
        vm.compose("route tickets")
        assertTrue(vm.compose.value.canRevert)
        vm.setContext("typed")
        vm.setContext("typed more")
        assertEquals(JevThreadItem.Edited::class, vm.compose.value.items.last()::class)
        assertEquals(2, vm.compose.value.items.size)
        assertFalse(vm.compose.value.canRevert)

        JevMcpToolProvider("p", s.service, s.presets) { s.playground }.draftSet(McpToolArgs(emptyMap(), """{"timeout_ms":60000}"""))
        val last = vm.compose.value.items.last() as JevThreadItem.Edited
        assertTrue(last.byAgent)
        assertEquals(3, vm.compose.value.items.size)
    }

    @Test
    fun `a run joins the thread and the next turn uses it`() = runBlocking {
        val s = services(ScriptedChat(composeReply(questions = requestAllTypes().questions.toString()), composeReply(questions = null)))
        val vm = s.playground
        vm.compose("route tickets")
        vm.setContext("""{"ticket":"login outage"}""")
        val run = vm.runDraft()
        val ran = vm.compose.value.items.last() as JevThreadItem.Ran
        assertEquals(run.id, ran.runId)
        assertEquals(JevPane.ANSWER, vm.state.value.side)
        assertEquals(JevPane.CHAT, vm.state.value.pane)
        vm.compose("why identity?")
        assertEquals(run.id, vm.compose.value.latestTurn!!.runId)
    }

    @Test
    fun `answer verdicts read as a choice, yes or no, and a score level`() {
        val answers = (testJson.parseToJsonElement(validResponse).jsonObject["answers"] as JsonObject)
        assertEquals("Yes" to "90%", verdict(answers["urgent"]!!.jsonObject))
        assertEquals("identity" to "80%", verdict(answers["route"]!!.jsonObject))
        assertEquals("risky" to "1.1 of 2", verdict(answers["readiness"]!!.jsonObject))
    }

    // ---- placeholders ----

    @Test
    fun `placeholders block the run until filled and name their paths over MCP`() = runBlocking {
        val s = services(ScriptedChat())
        val vm = s.playground
        val mcp = JevMcpToolProvider("p", s.service, s.presets) { s.playground }
        mcp.draftSet(McpToolArgs(emptyMap(), """{"state":{"patient":"<patient name>","ticket":{"issue":"Login fails for <user group>"},"plan":"enterprise"},"questions":$VALID_QUESTIONS}"""))

        val st = vm.state.value
        assertEquals(listOf("Patient", "Ticket · Issue"), st.placeholders.map { it.label })
        assertNull(st.request)
        val draft = testJson.parseToJsonElement(mcp.draftGet(McpToolArgs(emptyMap(), "{}")).text).jsonObject
        assertEquals(listOf("state.patient", "state.ticket.issue"), draft["issues"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        val refused = testJson.parseToJsonElement(mcp.draftRun(McpToolArgs(emptyMap(), "{}")).text).jsonObject
        assertEquals("INVALID_INPUT", refused["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        vm.fillPlaceholders(mapOf(st.placeholders[0].key to "Ada Lovelace"))
        assertEquals(1, vm.state.value.placeholders.size)
        vm.fillPlaceholders(mapOf(vm.state.value.placeholders[0].key to "admins"))
        val filled = vm.state.value
        assertTrue(filled.placeholders.isEmpty())
        assertNotNull(filled.request)
        val state = filled.request!!.state.jsonObject
        assertEquals("Ada Lovelace", state["patient"]!!.jsonPrimitive.content)
        assertEquals("Login fails for admins", state["ticket"]!!.jsonObject["issue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text placeholders need word-like tokens, so markup is left alone`() {
        val text = "Customer <customer name> sees <br> in the <error_message> banner"
        val found = JevPlaceholders.find(detectContext(text, false), text)
        assertEquals(listOf("<customer name>", "<error_message>"), found.map { it.token })
        val filled = JevPlaceholders.fill(text, detectContext(text, false), mapOf(found[0].key to "Acme"), found)
        assertEquals("Customer Acme sees <br> in the <error_message> banner", filled)
    }
}
