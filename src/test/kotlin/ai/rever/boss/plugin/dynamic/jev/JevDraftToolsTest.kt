package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class JevDraftToolsTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Memory : JevPresetBackend {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private fun context() = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob())
    }

    private class Fixture(val chat: ScriptedChat = ScriptedChat(), key: String? = "k") {
        val transport = CapturingTransport()
        val storage = Memory()
        val services = JevPluginServices(
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob())
            },
            transport, JevKeyResolver { key }, chat, storage,
        )
        val vm get() = services.playground
        val mcp = JevMcpToolProvider("p", services.service, services.presets) { services.playground }
    }

    private fun obj(raw: String) = testJson.parseToJsonElement(raw) as JsonObject
    private fun args(raw: String) = McpToolArgs(emptyMap(), raw)
    private fun body(text: String) = testJson.parseToJsonElement(text).jsonObject
    private fun paths(draft: JsonObject) = draft["issues"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }

    // ---- replace / merge ----

    @Test
    fun `replace swaps questions and merge upserts by id in place`() {
        val f = Fixture()
        f.vm.applyDraft(JevDraftChange(questions = obj(VALID_QUESTIONS)))
        assertEquals(listOf("route", "urgent"), f.vm.state.value.questions.map { it.id })

        f.vm.applyDraft(JevDraftChange(
            questions = obj("""{"route":{"type":"noul","instructions":"Route to identity?"},"cost":{"type":"score","instructions":"Cost","criteria":["low","high"]}}"""),
            mode = JevDraftMode.MERGE,
        ))
        val s = f.vm.state.value
        assertEquals(listOf("route", "urgent", "cost"), s.questions.map { it.id })
        assertEquals(JevQuestionType.NOUL, s.questions.first().type)
        assertTrue(s.dirty)

        f.vm.applyDraft(JevDraftChange(mode = JevDraftMode.MERGE, removeQuestions = listOf("urgent")))
        assertEquals(listOf("route", "cost"), f.vm.state.value.questions.map { it.id })

        f.vm.applyDraft(JevDraftChange(questions = obj("""{"only":{"type":"noul","instructions":"x"}}""")))
        assertEquals(listOf("only"), f.vm.state.value.questions.map { it.id })
    }

    @Test
    fun `unknown removals and unparseable JSON fail without changing the draft`() {
        val f = Fixture()
        val before = f.vm.state.value
        val missing = assertFailsWith<JevFailure> { f.vm.applyDraft(JevDraftChange(contextText = "changed", removeQuestions = listOf("nope"))) }
        assertEquals("remove_questions", missing.path)
        assertEquals(before, f.vm.state.value)

        f.vm.setEditorMode(JevEditorMode.JSON)
        f.vm.setJsonText("{broken")
        assertFailsWith<JevFailure> { f.vm.applyDraft(JevDraftChange(questions = obj(VALID_QUESTIONS), mode = JevDraftMode.MERGE)) }
        // Replace does not need the old JSON, and the open JSON editor shows the new text.
        f.vm.applyDraft(JevDraftChange(questions = obj(VALID_QUESTIONS)))
        assertEquals(JevEditorMode.JSON, f.vm.state.value.editorMode)
        assertNull(f.vm.state.value.jsonError)
        assertEquals(obj(VALID_QUESTIONS), obj(f.vm.state.value.jsonText))
    }

    @Test
    fun `concurrent changes from many threads are all kept`() = runBlocking {
        val f = Fixture()
        f.vm.applyDraft(JevDraftChange(questions = obj("""{"base":{"type":"noul","instructions":"x"}}""")))
        (1..24).map { i ->
            async(Dispatchers.Default) {
                f.vm.applyDraft(JevDraftChange(questions = obj("""{"q_$i":{"type":"noul","instructions":"Q $i"}}"""), mode = JevDraftMode.MERGE))
            }
        }.awaitAll()
        assertEquals(25, f.vm.state.value.questions.size)
        assertTrue(f.vm.state.value.issues.isEmpty())
    }

    // ---- MCP ----

    @Test
    fun `draft tools are listed only with a panel and decision tools are unchanged`() {
        val f = Fixture()
        assertEquals(
            listOf("jev_decide", "jev_validate", "jev_presets", "jev_draft_get", "jev_draft_set", "jev_draft_run", "jev_preset_save", "jev_compose"),
            f.mcp.tools().map { it.name },
        )
        assertEquals(listOf(true, false, false, false), f.mcp.tools().filter { it.name in setOf("jev_draft_get", "jev_draft_set", "jev_draft_run", "jev_compose") }.map { it.readOnly })
        assertEquals(listOf("jev_decide", "jev_validate", "jev_presets"), JevMcpToolProvider("p", f.services.service).tools().map { it.name })
    }

    @Test
    fun `draft_set shows in the panel and draft_get reports issues with paths`() = runTest {
        val f = Fixture()
        val set = f.mcp.draftSet(args("""{"state":{"ticket":"SSO down"},"questions":{"sev":{"type":"score","instructions":"Rate","criteria":["one"]}},"timeout_ms":60000}"""))
        assertFalse(set.isError, set.text)
        val draft = body(set.text)
        assertEquals(listOf("questions.sev.criteria"), paths(draft))
        assertEquals("json", draft["state_format"]!!.jsonPrimitive.content)
        assertEquals(false, draft["valid"]!!.jsonPrimitive.content.toBoolean())

        val panel = f.vm.state.value
        assertEquals(listOf("sev"), panel.questions.map { it.id })
        assertTrue(panel.contextFormat is JevContextFormat.Json)
        assertEquals(60_000, panel.timeoutMs)
        assertTrue(panel.issues.isNotEmpty())

        f.mcp.draftSet(args("""{"questions":{"sev":{"type":"score","instructions":"Rate","criteria":["low","high"]}},"mode":"merge"}"""))
        val got = body(f.mcp.draftGet(args("{}")).text)
        assertTrue(paths(got).isEmpty())
        assertEquals(true, got["valid"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SSO down", got["state"]!!.jsonObject["ticket"]!!.jsonPrimitive.content)
        assertNull(got["last_run"])
        assertNull(f.transport.body)
    }

    @Test
    fun `a JSON-looking string state stays text, and bad arguments name the field`() = runTest {
        val f = Fixture()
        f.mcp.draftSet(args("""{"context":"{not really json"}"""))
        assertTrue(f.vm.state.value.sendAsText)
        assertTrue(f.vm.state.value.contextFormat is JevContextFormat.Text)

        fun path(raw: String) = runBlocking { body(f.mcp.draftSet(args(raw)).text)["error"]!!.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals("mode", path("""{"mode":"upsert"}"""))
        assertEquals("context", path("""{"state":"a","context":"b"}"""))
        assertEquals("bogus", path("""{"bogus":1}"""))
        assertEquals("timeout_ms", path("""{"timeout_ms":"slow"}"""))
    }

    @Test
    fun `draft_run runs like the Run button and lands in the panel history`() = runTest {
        val f = Fixture()
        f.transport.response = validResponse
        f.mcp.draftSet(args("""{"state":{"ticket":"login outage"},"questions":${requestAllTypes().questions}}"""))
        val result = f.mcp.draftRun(args("{}"))
        assertFalse(result.isError, result.text)
        val runId = body(result.text)["run_id"]!!.jsonPrimitive.content.toLong()

        val panel = f.vm.state.value
        assertEquals(runId, panel.selectedRunId)
        assertEquals(JevPane.ANSWER, panel.side)
        assertEquals(runId, (f.vm.compose.value.items.last() as JevThreadItem.Ran).runId)
        assertFalse(panel.running)
        assertEquals(JevRunSource.PLAYGROUND, f.vm.runs.value.first().source)
        assertEquals(panel.request, f.vm.runs.value.first().request)

        val lastRun = body(f.mcp.draftGet(args("")).text)["last_run"]!!.jsonObject
        assertEquals(runId, lastRun["run_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(true, lastRun["matches_draft"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `draft_run refuses an invalid draft or a missing key without calling the provider`() = runTest {
        val f = Fixture()
        f.mcp.draftSet(args("""{"state":""}"""))
        assertEquals("INVALID_INPUT", body(f.mcp.draftRun(args("{}")).text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val noKey = Fixture(key = null)
        assertEquals("MISSING_OPENROUTER_KEY", body(noKey.mcp.draftRun(args("{}")).text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertNull(f.transport.body)
        assertNull(noKey.transport.body)
    }

    @Test
    fun `preset_save stores the draft or bare questions, and given questions carry no context`() = runTest {
        val f = Fixture()
        f.mcp.draftSet(args("""{"state":"secret situation","questions":$VALID_QUESTIONS}"""))
        val saved = f.mcp.presetSave(args("""{"name":"routing"}"""))
        assertFalse(saved.isError, saved.text)
        assertEquals("draft", body(saved.text)["source"]!!.jsonPrimitive.content)
        assertEquals("routing", f.vm.state.value.presetName)
        assertFalse(f.vm.state.value.dirty)
        assertEquals("secret situation", f.services.presets!!.load("routing")!!.stateText)

        val bare = f.mcp.presetSave(args("""{"name":"bare","questions":{"q":{"type":"noul","instructions":"Go?"}}}"""))
        assertFalse(bare.isError, bare.text)
        assertEquals("", f.services.presets!!.load("bare")!!.stateText)
        assertEquals("routing", f.vm.state.value.presetName)

        val bad = body(f.mcp.presetSave(args("""{"name":"bad","questions":{"q":{"type":"score","instructions":"x","criteria":["a"]}}}""")).text)
        assertEquals("questions.q.criteria", bad["issues"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
        assertNull(f.services.presets!!.load("bad"))

        val listed = body(f.mcp.listPresets(args("{}")).text).toString()
        assertFalse("secret situation" in listed)
    }

    // ---- compose through the view model ----

    @Test
    fun `compose fills the draft, records the turn, and revert restores one step`() = runTest {
        val f = Fixture(ScriptedChat(composeReply(context = """{"customer":"Acme"}""")))
        val before = f.vm.state.value
        f.vm.setComposeInput("route support tickets")
        f.vm.compose("route support tickets")

        val after = f.vm.state.value
        assertEquals(listOf("route", "urgent"), after.questions.map { it.id })
        assertTrue(after.contextFormat is JevContextFormat.Json)
        assertTrue(after.issues.isEmpty())
        val compose = f.vm.compose.value
        assertEquals("", compose.input)
        assertEquals("Drafted it.", compose.turns.single().reply)
        assertTrue(compose.canRevert)
        assertEquals("claude-test", f.chat.modelsUsed.single()?.modelId)

        f.vm.revertLastCompose()
        assertEquals(before.contextText, f.vm.state.value.contextText)
        assertEquals(before.questions.map { it.id }, f.vm.state.value.questions.map { it.id })
        assertTrue(f.vm.compose.value.turns.single().reverted)
        assertFalse(f.vm.compose.value.canRevert)
    }

    @Test
    fun `the next turn after a run summarizes it once`() = runTest {
        val f = Fixture(ScriptedChat(composeReply(), composeReply(questions = null), composeReply(questions = null)))
        f.vm.compose("route tickets")
        f.mcp.draftSet(args("""{"state":{"ticket":"login outage"},"questions":${requestAllTypes().questions}}"""))
        f.vm.runDraft()
        f.vm.compose("why identity?")
        f.vm.compose("and now?")
        val prompts = f.chat.calls.map { it.second.single().text }
        assertFalse("Last run" in prompts[0])
        assertTrue("Last run (#1)" in prompts[1])
        assertFalse("Last run" in prompts[2])
        assertEquals(listOf(null, 1L, null), f.vm.compose.value.turns.map { it.runId })
    }

    @Test
    fun `compose errors say whether AI Providers is the fix`() = runTest {
        val chat = ScriptedChat().apply { failure = JevFailure(JevComposer.NO_MODEL, "Add a chat provider") }
        val f = Fixture(chat)
        assertFailsWith<JevFailure> { f.vm.compose("x") }
        val failed = f.vm.compose.value.items.single() as JevThreadItem.Failed
        assertTrue(failed.error.needsProviders)
        assertEquals("x", failed.user)
        assertFalse(f.vm.compose.value.busy)
        assertTrue(f.vm.compose.value.turns.isEmpty())
    }

    @Test
    fun `the chosen chat model is remembered in plugin storage`() = runTest {
        val models = listOf(JevChatModel("A", "A", "a1"), JevChatModel("B", "B", "b1"))
        val f = Fixture(ScriptedChat(models = models))
        f.vm.refreshModels()
        assertEquals("a1", f.vm.compose.value.model?.modelId)
        f.vm.setComposeModel(models[1])

        val again = JevPluginServices(context(), CapturingTransport(), JevKeyResolver { "k" }, ScriptedChat(models = models), f.storage)
        again.playground.refreshModels()
        assertEquals("b1", again.playground.compose.value.model?.modelId)
    }

    @Test
    fun `jev_compose drives the composer and returns the new draft`() = runTest {
        val f = Fixture(ScriptedChat(composeReply()))
        val result = f.mcp.composeDraft(args("""{"message":"route tickets"}"""))
        assertFalse(result.isError, result.text)
        val out = body(result.text)
        assertEquals("Drafted it.", out["reply"]!!.jsonPrimitive.content)
        assertEquals(setOf("route", "urgent"), out["draft"]!!.jsonObject["questions"]!!.jsonObject.keys)
        assertEquals(1, f.vm.compose.value.turns.size)
        assertEquals("message", body(f.mcp.composeDraft(args("{}")).text)["error"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `loading a starter starts a new thread`() = runTest {
        val f = Fixture(ScriptedChat(composeReply()))
        f.vm.compose("x")
        f.vm.useStarter(JevStarter.URGENCY)
        assertTrue(f.vm.compose.value.turns.isEmpty())
        assertFalse(f.vm.compose.value.canRevert)
    }
}
