package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every catalog load outside a decision is admitted, bounded by a deadline, and off after close. */
@OptIn(ExperimentalCoroutinesApi::class)
class JevCatalogLoadTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Memory : JevPresetBackend {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private fun hungApi() = FakeDecisionApi(listOf(openRouter(), localRuntime())).apply { gate = CompletableDeferred() }

    private fun oneSlot() = JevLimits(maxConcurrentRequests = 1, maxWaitingRequests = 0, minTimeoutMs = 1, maxTimeoutMs = 2_000)

    private fun McpToolResult.json() = testJson.parseToJsonElement(text).jsonObject
    private fun McpToolResult.code() = json()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    /** Runs [block] in real time and returns its result and how long it took. */
    private fun <T> timed(block: suspend () -> T): Pair<T?, Long> = runBlocking {
        val start = System.nanoTime()
        val result = withTimeoutOrNull(HANG_MS) { block() }
        result to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `with a hung gateway listing every tool returns within its timeout`() {
        val api = hungApi()
        val services = JevPluginServices(
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob())
            },
            storageOverride = Memory(), decisionApiOverride = { api },
        )
        val vm = services.playground
        vm.applyDraft(JevDraftChange(contextText = "PHI", questions = requestAllTypes().questions, timeoutMs = 1_000))
        val mcp = JevMcpToolProvider("p", services.service, services.presets) { vm }
        val raw = """{"state":"x","questions":${requestAllTypes().questions},"timeout_ms":1000}"""
        val calls: List<Pair<String, suspend () -> McpToolResult>> = listOf(
            "jev_decide" to { mcp.call(McpToolArgs(emptyMap(), raw)) },
            "jev_validate" to { mcp.validate(McpToolArgs(emptyMap(), raw)) },
            "jev_draft_get" to { mcp.draftGet(McpToolArgs(emptyMap(), "{}")) },
            "jev_draft_set" to { mcp.draftSet(McpToolArgs(emptyMap(), """{"state":"more PHI","timeout_ms":1000}""")) },
            "jev_draft_run" to { mcp.draftRun(McpToolArgs(emptyMap(), "{}")) },
            "jev_preset_save" to { mcp.presetSave(McpToolArgs(emptyMap(), """{"name":"x","timeout_ms":1000}""")) },
        )
        val outcomes = calls.associate { (name, call) ->
            val (result, ms) = timed(call)
            assertNotNull(result, "$name still hanging after ${HANG_MS}ms")
            assertTrue(ms < HANG_MS, "$name took ${ms}ms")
            name to result
        }
        assertEquals("TIMEOUT", outcomes.getValue("jev_decide").code())
        assertEquals("TIMEOUT", outcomes.getValue("jev_draft_run").code())
        assertEquals("TIMEOUT", outcomes.getValue("jev_preset_save").code())
        for (name in listOf("jev_validate", "jev_draft_get", "jev_draft_set")) {
            val body = outcomes.getValue(name)
            assertFalse(body.isError, "$name: ${body.text}")
            assertTrue("note" in body.json(), "$name: ${body.text}")
        }
        assertEquals("more PHI", vm.state.value.contextText)
        assertTrue(api.requests.isEmpty())
        api.gate!!.complete(Unit)
        services.dispose()
    }

    @Test
    fun `a full service answers BUSY to a load`() = runTest {
        val api = hungApi()
        val service = JevDecisionService(CapturingBackend(), JevModelCatalog { api }, oneSlot())
        val held = async { runCatching { service.decide(requestAllTypes(1_000)) } }
        runCurrent()
        val mcp = JevMcpToolProvider("p", service)
        val raw = """{"state":"x","questions":${requestAllTypes().questions},"timeout_ms":1000}"""
        assertEquals("BUSY", mcp.validate(McpToolArgs(emptyMap(), raw)).code())
        val busy = runCatching { service.loadCatalog(1_000) }.exceptionOrNull()
        assertEquals("BUSY", (busy as JevFailure).code)
        api.gate!!.complete(Unit)
        held.await()
    }

    @Test
    fun `the slot is released when a load times out or is cancelled`() = runTest {
        val api = hungApi()
        val service = JevDecisionService(CapturingBackend(), JevModelCatalog { api }, oneSlot())
        assertFalse(service.loadCatalog(500))
        // Admitted again, so the slot was released; this one is cancelled.
        val cancelled = async { service.loadCatalog(1_000) }
        runCurrent()
        assertEquals("BUSY", (runCatching { service.loadCatalog(1_000) }.exceptionOrNull() as JevFailure).code)
        cancelled.cancel()
        runCurrent()
        api.gate!!.complete(Unit)
        assertTrue(service.loadCatalog(1_000))
        assertTrue(service.catalog.refreshed)
    }

    @Test
    fun `a decision does not wait on a load's slot`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val sent = CapturingBackend()
        val service = JevDecisionService(sent, JevModelCatalog { api }, oneSlot())
        // decide loads the catalog inside its own admission, so one slot is enough.
        service.decide(requestAllTypes(1_000).copy(model = "laya:en"))
        assertEquals(listOf("LOCAL_SYSTEMONE"), sent.providers)
        assertEquals(1, api.listings)
    }

    @Test
    fun `nothing loads after close`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val service = JevDecisionService(CapturingBackend(), JevModelCatalog { api })
        service.close()
        val failure = runCatching { service.loadCatalog(1_000, "laya:en") }.exceptionOrNull()
        assertEquals("SERVICE_UNAVAILABLE", (failure as JevFailure).code)
        val mcp = JevMcpToolProvider("p", service)
        mcp.validate(McpToolArgs(emptyMap(), """{"state":"x","questions":${requestAllTypes().questions}}"""))
        assertEquals(0, api.listings)
        assertFalse(service.catalog.refreshed)
    }

    private companion object {
        const val HANG_MS = 4_000L
    }
}
