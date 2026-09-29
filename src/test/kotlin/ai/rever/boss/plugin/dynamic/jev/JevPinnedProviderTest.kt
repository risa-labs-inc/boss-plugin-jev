package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A preset's or draft's provider pins an explicit model: kept when it serves it, else INVALID_INPUT, never re-routed. */
@OptIn(ExperimentalCoroutinesApi::class)
class JevPinnedProviderTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Memory : JevPresetBackend {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private suspend fun repo() = JevPresetRepository(Memory()).also {
        it.save(JevPreset("p", "", requestAllTypes().questions.toString(), stateAsJson = false, model = "laya:multilingual", providerId = LOCAL))
    }

    private val localUp = localRuntime(true, "laya:en", "laya:multilingual")
    private val localEmpty = localRuntime(true).copy(models = emptyList(), detail = "No models pulled")
    private val localDown = localRuntime(false)
    private val localWithJev = localRuntime(true, "laya:en", "laya:multilingual", JEV)

    private class Outcome(val sent: List<String>, val result: McpToolResult) {
        val error: JsonObject? get() = testJson.parseToJsonElement(result.text).jsonObject["error"]?.jsonObject
        val path: String? get() = error?.get("path")?.jsonPrimitive?.content
        val code: String? get() = error?.get("code")?.jsonPrimitive?.content
    }

    /** [first] warms the catalog before the gateway switches to [now]; null leaves it cold. */
    private fun decide(first: List<AiDecisionProvider>?, now: List<AiDecisionProvider>, raw: String): Outcome = runBlocking {
        val api = FakeDecisionApi(first ?: now)
        val catalog = JevModelCatalog { api }
        if (first != null) { catalog.refresh(); api.providers = now }
        val mcp = JevMcpToolProvider("p", JevDecisionService(GatewayJevDecisionBackend { api }, catalog), repo())
        val result = mcp.call(McpToolArgs(emptyMap(), raw))
        Outcome(api.requests.map { it.providerId }, result)
    }

    private fun assertPinFailed(o: Outcome, label: String) {
        assertTrue(o.result.isError, "$label: ${o.result.text}")
        assertEquals("INVALID_INPUT", o.code, label)
        assertEquals("provider", o.path, label)
        assertTrue(o.error!!["message"]!!.jsonPrimitive.content.contains(LOCAL), label)
        assertTrue(o.error!!["message"]!!.jsonPrimitive.content.contains("pass provider"), label)
        assertEquals(emptyList(), o.sent, label)
    }

    private val presetJev = """{"preset":"p","state":"PHI","model":"$JEV"}"""

    @Test
    fun `a preset's local pin fails for an explicit model it does not serve, in every runtime state`() {
        assertPinFailed(decide(null, listOf(openRouter(), localUp), presetJev), "up, not serving, cold")
        assertPinFailed(decide(listOf(openRouter(), localUp), listOf(openRouter(), localUp), presetJev), "up, not serving, warm")
        assertPinFailed(decide(null, listOf(openRouter(), localDown), presetJev), "down, cold")
        assertPinFailed(decide(listOf(openRouter(), localUp), listOf(openRouter(), localDown), presetJev), "down, warm")
        assertPinFailed(decide(null, listOf(openRouter(), localEmpty), presetJev), "up with no models, cold")
    }

    @Test
    fun `a local pin that listed the model before stays local when it drops it`() {
        val o = decide(listOf(openRouter(), localWithJev), listOf(openRouter(), localUp), presetJev)
        assertEquals(listOf(LOCAL), o.sent)
    }

    @Test
    fun `a local pin that serves the explicit model keeps it`() {
        listOf(null, listOf(openRouter(), localWithJev)).forEach { first ->
            val o = decide(first, listOf(openRouter(), localWithJev), presetJev)
            assertFalse(o.result.isError, o.result.text)
            assertEquals(listOf(LOCAL), o.sent, "warm=${first != null}")
        }
    }

    @Test
    fun `a preset's own model on its local provider fails closed`() {
        val own = """{"preset":"p","state":"PHI"}"""
        val empty = decide(null, listOf(openRouter(), localEmpty), own)
        assertEquals("model", empty.path)
        assertEquals(emptyList(), empty.sent)
        // Down: bound to the local provider, whose error the gateway reports.
        assertEquals(listOf(LOCAL), decide(null, listOf(openRouter(), localDown), own).sent)
    }

    @Test
    fun `without a pin resolution is unchanged`() {
        val bare = """{"state":"PHI","questions":${requestAllTypes().questions},"model":"$JEV"}"""
        assertEquals(listOf("OPENROUTER"), decide(null, listOf(openRouter(), localDown), bare).sent)
        // Local listed jev-1.13 before going down, so the id is ambiguous.
        assertEquals("provider", decide(listOf(openRouter(), localWithJev), listOf(openRouter(), localDown), bare).path)
        val wrong = decide(null, listOf(openRouter(), localUp), """{"state":"PHI","questions":${requestAllTypes().questions},"model":"laya:en","provider":"OPENROUTER"}""")
        assertEquals("model", wrong.path)
        assertEquals(emptyList(), wrong.sent)
    }

    @Test
    fun `an explicit provider overrides a preset's pin`() {
        val o = decide(null, listOf(openRouter(), localDown), """{"preset":"p","state":"PHI","model":"$JEV","provider":"OPENROUTER"}""")
        assertEquals(listOf("OPENROUTER"), o.sent)
    }

    @Test
    fun `a local pin never reaches OpenRouter, whatever the runtime and model`() {
        val states = listOf(localUp, localEmpty, localDown, localWithJev)
        val calls = listOf(presetJev, """{"preset":"p","state":"PHI"}""", """{"preset":"p","state":"PHI","model":"laya:en"}""")
        for (first in states.map { listOf(openRouter(), it) } + listOf(null)) for (now in states) for (raw in calls) {
            val o = decide(first, listOf(openRouter(), now), raw)
            assertFalse("OPENROUTER" in o.sent, "first=$first now=$now raw=$raw")
        }
    }

    @Test
    fun `jev_validate reports the pin as jev_decide does`() = runBlocking {
        val api = FakeDecisionApi(listOf(openRouter(), localUp))
        val mcp = JevMcpToolProvider("p", JevDecisionService(GatewayJevDecisionBackend { api }, JevModelCatalog { api }), repo())
        val body = testJson.parseToJsonElement(mcp.validate(McpToolArgs(emptyMap(), presetJev)).text).jsonObject
        assertEquals(listOf("provider"), body["issues"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content })
    }

    // ---- the panel draft ----

    private class Draft(providers: List<AiDecisionProvider>, pinLocal: Boolean = true) {
        val api = FakeDecisionApi(providers)
        val services = JevPluginServices(
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob())
            },
            storageOverride = Memory(), decisionApiOverride = { api },
        )
        val vm get() = services.playground
        val mcp = JevMcpToolProvider("p", services.service, services.presets) { services.playground }

        init {
            if (pinLocal) {
                vm.applyDraft(JevDraftChange(contextText = "PHI", questions = requestAllTypes().questions))
                vm.setModel("laya:en", LOCAL)
            }
        }

        fun call(block: suspend JevMcpToolProvider.() -> McpToolResult) = runBlocking { mcp.block() }
    }

    private fun McpToolResult.path() = testJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("path")?.jsonPrimitive?.content

    @Test
    fun `jev_draft_set keeps a local pin down, and jev_draft_run stays local`() {
        val d = Draft(listOf(openRouter(), localDown))
        val set = d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV"}""")) }
        assertTrue(set.isError, set.text)
        assertEquals("provider", set.path())
        assertEquals("laya:en" to LOCAL, d.vm.state.value.model to d.vm.state.value.providerId)
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertEquals(listOf(LOCAL), d.api.requests.map { it.providerId })
        d.services.dispose()
    }

    @Test
    fun `jev_draft_set keeps the pin for a model it serves, and an explicit provider moves it`() {
        val d = Draft(listOf(openRouter(), localWithJev))
        assertFalse(d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV"}""")) }.isError)
        assertEquals(LOCAL, d.vm.state.value.providerId)
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertFalse(d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV","provider":"OPENROUTER"}""")) }.isError)
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertEquals(listOf(LOCAL, "OPENROUTER"), d.api.requests.map { it.providerId })
        d.services.dispose()
    }

    @Test
    fun `the view model never drops a draft's pin`() {
        val d = Draft(listOf(openRouter(), localUp))
        runBlocking { d.services.catalog.refresh() }
        val failure = assertFailsWith<JevFailure> { d.vm.applyDraft(JevDraftChange(model = JEV)) }
        assertEquals("INVALID_INPUT" to "provider", failure.code to failure.path)
        assertEquals("laya:en" to LOCAL, d.vm.state.value.model to d.vm.state.value.providerId)
        d.vm.applyDraft(JevDraftChange(model = "laya:multilingual"))
        assertEquals(LOCAL, d.vm.state.value.providerId)
        d.vm.applyDraft(JevDraftChange(model = JEV, providerId = "OPENROUTER"))
        assertEquals("OPENROUTER", d.vm.state.value.providerId)
        d.services.dispose()
    }

    @Test
    fun `jev_preset_save from a pinned draft keeps the pin or fails`() {
        val d = Draft(listOf(openRouter(), localUp))
        val refused = d.call { presetSave(McpToolArgs(emptyMap(), """{"name":"x","model":"$JEV"}""")) }
        assertTrue(refused.isError, refused.text)
        assertEquals("provider", refused.path())
        val saved = d.call { presetSave(McpToolArgs(emptyMap(), """{"name":"y","model":"laya:multilingual"}""")) }
        assertEquals(LOCAL, testJson.parseToJsonElement(saved.text).jsonObject["provider"]!!.jsonPrimitive.content)
        val same = d.call { presetSave(McpToolArgs(emptyMap(), """{"name":"z"}""")) }
        assertEquals(LOCAL, testJson.parseToJsonElement(same.text).jsonObject["provider"]!!.jsonPrimitive.content)
        d.services.dispose()
    }

    // ---- an inferred provider pins like an explicit one ----

    private val q get() = requestAllTypes().questions

    private fun McpToolResult.body() = testJson.parseToJsonElement(text).jsonObject
    private fun McpToolResult.provider() = body()["provider"]?.jsonPrimitive?.content

    @Test
    fun `an inferred local draft refuses an explicit OpenRouter model, and nothing reaches OpenRouter`() {
        val d = Draft(listOf(openRouter(), localRuntime(true, "laya:en")), pinLocal = false)
        val set = d.call { draftSet(McpToolArgs(emptyMap(), """{"state":"PHI patient","questions":$q,"model":"laya:en"}""")) }
        assertFalse(set.isError, set.text)
        assertEquals(LOCAL, set.provider())
        assertEquals(LOCAL, d.vm.state.value.providerId)
        assertEquals(LOCAL, d.call { draftGet(McpToolArgs(emptyMap(), "{}")) }.provider())
        val moved = d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV"}""")) }
        assertTrue(moved.isError, moved.text)
        assertEquals("INVALID_INPUT" to "provider", moved.body()["error"]!!.jsonObject.let { it["code"]!!.jsonPrimitive.content to it["path"]!!.jsonPrimitive.content })
        assertEquals("laya:en" to LOCAL, d.vm.state.value.model to d.vm.state.value.providerId)
        val saved = d.call { presetSave(McpToolArgs(emptyMap(), """{"name":"x","model":"$JEV"}""")) }
        assertEquals("provider", saved.path(), saved.text)
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertEquals(listOf(LOCAL), d.api.requests.map { it.providerId })
        d.services.dispose()
    }

    @Test
    fun `an inferred OpenRouter draft is not moved to local`() {
        val d = Draft(listOf(openRouter(), localUp), pinLocal = false)
        assertFalse(d.call { draftSet(McpToolArgs(emptyMap(), """{"state":"x","questions":$q,"model":"$JEV"}""")) }.isError)
        assertEquals("OPENROUTER", d.vm.state.value.providerId)
        val moved = d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"laya:en"}""")) }
        assertEquals("provider", moved.path(), moved.text)
        assertEquals("OPENROUTER", d.call { draftGet(McpToolArgs(emptyMap(), "{}")) }.provider())
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertEquals(listOf("OPENROUTER"), d.api.requests.map { it.providerId })
        d.services.dispose()
    }

    @Test
    fun `an inferred pin and an explicit pin give the same results`() {
        fun trace(first: String): List<Any?> {
            val d = Draft(listOf(openRouter(), localUp), pinLocal = false)
            val out = mutableListOf<Any?>()
            val calls = listOf(first, """{"model":"$JEV"}""", """{"model":"laya:multilingual"}""", """{"model":"$JEV","provider":"OPENROUTER"}""")
            calls.forEach { raw ->
                val r = d.call { draftSet(McpToolArgs(emptyMap(), raw)) }
                out.add(listOf(r.isError, r.path(), d.vm.state.value.model, d.vm.state.value.providerId, d.call { draftGet(McpToolArgs(emptyMap(), "{}")) }.provider()))
            }
            d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
            out.add(d.api.requests.map { it.providerId })
            d.services.dispose()
            return out
        }
        val inferred = trace("""{"state":"x","questions":$q,"model":"laya:en"}""")
        assertEquals(inferred, trace("""{"state":"x","questions":$q,"model":"laya:en","provider":"$LOCAL"}"""))
        assertEquals(listOf(true, "provider", "laya:en", LOCAL, LOCAL), inferred[1])
    }

    @Test
    fun `a model change on a cold catalog is refused, and resolves once it loads`() {
        val d = Draft(listOf(openRouter(), localUp), pinLocal = false)
        val cold = assertFailsWith<JevFailure> { d.vm.applyDraft(JevDraftChange(contextText = "x", questions = q, model = "laya:en")) }
        assertEquals("INVALID_INPUT" to "model", cold.code to cold.path)
        assertTrue(cold.message.contains("pass provider or retry"), cold.message)
        assertEquals(JevModelCatalog.DEFAULT.id to null, d.vm.state.value.model to d.vm.state.value.providerId)
        runBlocking { d.vm.loadCatalog() }
        d.vm.applyDraft(JevDraftChange(contextText = "x", questions = q, model = "laya:en"))
        assertEquals(LOCAL, d.vm.state.value.providerId)
        assertEquals("provider", assertFailsWith<JevFailure> { d.vm.applyDraft(JevDraftChange(model = JEV)) }.path)
        d.services.dispose()
    }

    private companion object {
        const val LOCAL = "LOCAL_SYSTEMONE"
        const val JEV = "typesafe/jev-1.13"
    }
}
