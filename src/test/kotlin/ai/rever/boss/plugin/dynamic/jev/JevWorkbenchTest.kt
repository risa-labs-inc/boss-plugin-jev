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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class JevWorkbenchTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun memoryBackend(values: MutableMap<String, String> = mutableMapOf()) = object : JevPresetBackend {
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private fun context() = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob())
    }

    private fun questions(raw: String) = testJson.parseToJsonElement(raw) as JsonObject

    private fun error(text: String) = testJson.parseToJsonElement(text).jsonObject["error"]!!.jsonObject

    // ---- drafts and context ----

    @Test
    fun `starters round trip through the API shape`() {
        JevStarter.entries.forEach { starter ->
            val drafts = starter.questions()
            val back = draftsFromApi(drafts.toApi())!!
            assertEquals(drafts.map { it.copy(key = 0) }, back.map { it.copy(key = 0) }, starter.name)
        }
    }

    @Test
    fun `form refuses shapes it cannot show faithfully`() {
        assertNull(draftsFromApi(questions("""{"q":{"type":"noul","instructions":{"steps":["a"]}}}""")))
        assertNull(draftsFromApi(questions("""{"q":{"type":"choice","instructions":"x","criteria":{"a":["rich"]}}}""")))
        assertEquals(JevOptionDraft("b", ""), draftsFromApi(questions("""{"q":{"type":"choice","instructions":"x","criteria":{"b":null}}}"""))!!.single().options.single())
    }

    @Test
    fun `switching type keeps what was typed`() {
        val choice = JevStarter.ROUTING.questions().first()
        val back = choice.withType(JevQuestionType.SCORE).withType(JevQuestionType.CHOICE)
        assertEquals(choice.options, back.options)
        assertEquals(3, choice.withType(JevQuestionType.SCORE).levels.size)
    }

    @Test
    fun `context format is detected`() {
        assertEquals(JevContextFormat.Empty, detectContext("  ", false))
        assertEquals("JSON object · 2 fields", (detectContext("""{"a":1,"b":2}""", false) as JevContextFormat.Json).label)
        assertEquals("JSON array · 1 item", (detectContext("[1]", false) as JevContextFormat.Json).label)
        assertTrue(detectContext("""{"a":""", false) is JevContextFormat.Invalid)
        assertEquals(JevContextFormat.Text(5), detectContext("""{"a":""", true))
        assertEquals(JevContextFormat.Text(5), detectContext("hello", false))
    }

    // ---- validation paths ----

    @Test
    fun `validation reports every issue with its path`() {
        val request = JevRequest(
            JsonPrimitive("ctx"),
            questions("""{"r":{"type":"score","instructions":"","criteria":["only",""]},"c":{"type":"choice","instructions":"x","criteria":{}}}"""),
            timeoutMs = 10,
            model = "local/unknown",
        )
        val paths = JevValidation.requestIssues(request, JevLimits()).map { it.pathText }
        assertEquals(listOf("model", "timeout_ms", "questions.r.instructions", "questions.r.criteria.1", "questions.c.criteria"), paths)
    }

    @Test
    fun `API paths map onto form fields`() {
        val drafts = JevStarter.ROUTING.questions()
        fun field(vararg path: String) = fieldFor(JevIssue(path.toList(), "m"), drafts)
        assertEquals(JevField.Instructions(0), field("questions", "route", "instructions"))
        assertEquals(JevField.OptionDescription(0, 1), field("questions", "route", "criteria", "billing"))
        assertEquals(JevField.Options(0), field("questions", "route", "criteria"))
        assertEquals(JevField.YesWhen(1), field("questions", "page_oncall", "criteria", "true"))
        assertEquals(JevField.Context, field("state"))
        assertEquals(JevField.Questions, field("questions", "missing"))
    }

    // ---- shared run log ----

    @Test
    fun `run log records both sources newest first and is capped`() = runTest {
        val service = JevDecisionService(JevKeyResolver { "k" }, CapturingTransport(), JevLimits(maxRunHistory = 3))
        service.decide(requestAllTypes())
        JevMcpToolProvider("p", service).call(McpToolArgs(emptyMap(), """{"state":"x","questions":${requestAllTypes().questions}}"""))
        assertEquals(listOf(JevRunSource.MCP, JevRunSource.PLAYGROUND), service.runs.value.map { it.source })
        repeat(3) { service.decide(requestAllTypes()) }
        assertEquals(listOf(5L, 4L, 3L), service.runs.value.map { it.id })
        service.close()
        assertTrue(service.runs.value.isEmpty())
    }

    // ---- MCP surface ----

    @Test
    fun `MCP errors name the failing field`() = runTest {
        val provider = JevMcpToolProvider("p", JevDecisionService(JevKeyResolver { "k" }, CapturingTransport()))
        val result = provider.call(McpToolArgs(emptyMap(), """{"state":"x","questions":{"q":{"type":"score","instructions":"s","criteria":["a"]}}}"""))
        assertTrue(result.isError)
        assertEquals("questions.q.criteria", error(result.text)["path"]!!.jsonPrimitive.content)
        val model = provider.call(McpToolArgs(emptyMap(), """{"state":"x","model":"nope","questions":{"q":{"type":"noul","instructions":"s"}}}"""))
        assertEquals("model", error(model.text)["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jev_validate lists every issue and never calls the provider`() = runTest {
        val transport = CapturingTransport()
        val provider = JevMcpToolProvider("p", JevDecisionService(JevKeyResolver { "k" }, transport))
        val bad = provider.validate(McpToolArgs(emptyMap(), """{"state":"x","questions":{"a":{"type":"noul","instructions":""},"b":{"type":"choice","instructions":"x","criteria":{}}}}"""))
        val body = testJson.parseToJsonElement(bad.text).jsonObject
        assertEquals(false, body["valid"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("questions.a.instructions", "questions.b.criteria"), body["issues"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        val good = provider.validate(McpToolArgs(emptyMap(), """{"state":"x","questions":${requestAllTypes().questions}}"""))
        assertEquals("true", testJson.parseToJsonElement(good.text).jsonObject["valid"]!!.jsonPrimitive.content)
        assertNull(transport.body)
    }

    @Test
    fun `presets are reusable by name over MCP`() = runTest {
        val repo = JevPresetRepository(memoryBackend())
        repo.save(JevPreset("routing", "saved context", requestAllTypes().questions.toString(), timeoutMs = 7_000))
        val transport = CapturingTransport()
        val provider = JevMcpToolProvider("p", JevDecisionService(JevKeyResolver { "k" }, transport), repo)

        val listed = testJson.parseToJsonElement(provider.listPresets(McpToolArgs(emptyMap())).text).jsonObject["presets"]!!.jsonArray.single().jsonObject
        assertEquals("routing", listed["name"]!!.jsonPrimitive.content)
        assertFalse(listed.toString().contains("saved context"))

        val result = provider.call(McpToolArgs(emptyMap(), """{"preset":"routing","state":{"ticket":"new"}}"""))
        assertFalse(result.isError, result.text)
        val sent = testJson.parseToJsonElement(transport.body!!.decodeToString()).jsonObject
        assertEquals(requestAllTypes().questions, sent["questions"])
        assertEquals("""{"ticket":"new"}""", sent["state"].toString())

        val both = provider.call(McpToolArgs(emptyMap(), """{"preset":"routing","questions":{},"state":"x"}"""))
        assertEquals("preset", error(both.text)["path"]!!.jsonPrimitive.content)
        val missing = provider.call(McpToolArgs(emptyMap(), """{"preset":"nope","state":"x"}"""))
        assertEquals("INVALID_INPUT", error(missing.text)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `presets saved before model choice load with the default model`() = runTest {
        val values = mutableMapOf("presets/v2" to """{"old":{"name":"old","state":"s","questions":"{}","state_as_json":false,"timeout_ms":30000}}""")
        assertEquals(JevModelCatalog.DEFAULT.id, JevPresetRepository(memoryBackend(values)).load("old")!!.model)
    }

    // ---- view model ----

    @Test
    fun `form issues land on the field that caused them`() {
        val services = JevPluginServices(context(), CapturingTransport(), JevKeyResolver { "k" })
        val vm = services.playground
        vm.setOption(0, 1, JevOptionDraft("", "Invoices"))
        vm.editQuestion(1) { copy(id = "route") }
        vm.editQuestion(0) { copy(instructions = " ") }
        val state = vm.state.value
        assertEquals("Option 2 needs a name", state.issue(JevField.OptionName(0, 1)))
        assertEquals("ID \"route\" is already used by another question", state.issue(JevField.QuestionId(1)))
        assertNull(state.request)
        assertTrue(state.dirty)
        services.dispose()
    }

    @Test
    fun `api issues map to fields once draft issues are fixed`() {
        val services = JevPluginServices(context(), CapturingTransport(), JevKeyResolver { "k" })
        val vm = services.playground
        vm.editQuestion(0) { copy(instructions = "") }
        assertEquals("Instructions must state the decision", vm.state.value.issue(JevField.Instructions(0)))
        vm.useStarter(JevStarter.RUBRIC)
        vm.removeLevel(0, 3); vm.removeLevel(0, 2); vm.removeLevel(0, 1)
        assertEquals("Score needs 2 to 10 levels; this has 1", vm.state.value.issue(JevField.Levels(0)))
        services.dispose()
    }

    @Test
    fun `structured JSON keeps the editor in JSON`() {
        val services = JevPluginServices(context(), CapturingTransport(), JevKeyResolver { "k" })
        val vm = services.playground
        vm.setEditorMode(JevEditorMode.JSON)
        vm.setJsonText("""{"q":{"type":"noul","instructions":{"steps":["check impact"]}}}""")
        assertTrue(vm.state.value.jsonOnly)
        assertTrue(vm.state.value.request != null)
        vm.setEditorMode(JevEditorMode.FORM)
        assertEquals(JevEditorMode.JSON, vm.state.value.editorMode)
        vm.setJsonText("{")
        assertTrue(vm.state.value.issues.any { it.field == JevField.Json })
        services.dispose()
    }

    @Test
    fun `a run selects its record and restoring brings inputs back`() = runTest {
        val urgentOnly = """{"model":"typesafe/jev-1.13","answers":{"urgent":{"type":"noul","noul":0.9}},"usage":{"input_tokens":1,"output_tokens":1}}"""
        val services = JevPluginServices(context(), CapturingTransport(urgentOnly), JevKeyResolver { "k" })
        val vm = services.playground
        vm.useStarter(JevStarter.URGENCY)
        services.service.decide(vm.state.value.request!!, JevRunSource.MCP)
        vm.setContext("something else")
        val mcpRun = services.service.runs.value.single()
        vm.restoreRun(mcpRun.id)
        assertEquals(mcpRun.request, vm.state.value.request)
        assertEquals(JevPane.ASK, vm.state.value.pane)
        services.dispose()
    }

    @Test
    fun `running without a key shows setup instead of calling`() {
        val transport = CapturingTransport()
        val services = JevPluginServices(context(), transport, JevKeyResolver { null })
        val vm = services.playground
        vm.run()
        assertEquals("MISSING_OPENROUTER_KEY", vm.state.value.error?.code)
        assertEquals(JevPane.ANSWER, vm.state.value.pane)
        assertNull(transport.body)
        services.dispose()
    }
}
