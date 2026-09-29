package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionModel
import ai.rever.boss.plugin.api.AiDecisionProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Catalog refresh: coalescing, admission and close, and how the panel's readiness follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class JevCatalogRefreshTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun context() = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob())
    }

    private fun JevPlaygroundViewModel.awaitCatalog() = runBlocking { catalogRefresh?.join() }

    /** A draft whose questions match [validResponse]. */
    private fun JevPlaygroundViewModel.useAllTypes() =
        applyDraft(JevDraftChange(contextText = "login outage", questions = requestAllTypes().questions))

    // ---- catalog ----

    @Test
    fun `concurrent ensures of an unknown model share one refresh`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime())).apply { gate = CompletableDeferred() }
        val catalog = JevModelCatalog { api }
        val callers = List(5) { async { catalog.ensure("laya:en", null) } }
        runCurrent()
        assertEquals(1, api.listings)
        api.gate!!.complete(Unit)
        callers.awaitAll()
        assertEquals(1, api.listings)
        assertEquals("LOCAL_SYSTEMONE", catalog.find("laya:en", null)!!.providerId)
    }

    @Test
    fun `the first panel open probes once, shared by the screen and the chat pane`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime())).apply { gate = CompletableDeferred() }
        val catalog = JevModelCatalog { api }
        val screen = async { catalog.refresh() }
        val chat = async { catalog.ensureLoaded() }
        runCurrent()
        api.gate!!.complete(Unit)
        screen.await(); chat.await()
        assertEquals(1, api.listings)
        catalog.ensureLoaded()
        assertEquals(1, api.listings)
    }

    @Test
    fun `a waiter takes over when the refresh it joined is cancelled`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime())).apply { gate = CompletableDeferred() }
        val catalog = JevModelCatalog { api }
        val first = async { catalog.refresh() }
        runCurrent()
        val second = async { catalog.refresh() }
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(2, api.listings)
        api.gate!!.complete(Unit)
        assertTrue(second.await().any { it.id == "laya:en" })
    }

    @Test
    fun `no refresh starts after close, from the catalog or from a decision`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val catalog = JevModelCatalog { api }
        val service = JevDecisionService(CapturingBackend(), catalog)
        service.close()
        catalog.refresh()
        catalog.ensure("laya:en", null)
        catalog.ensureLoaded()
        val failure = runCatching { service.decide(requestAllTypes().copy(model = "laya:en")) }.exceptionOrNull()
        assertEquals("SERVICE_UNAVAILABLE", (failure as JevFailure).code)
        assertEquals(0, api.listings)
    }

    @Test
    fun `the unknown-model refresh is admitted first and bounded by the request deadline`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime())).apply { gate = CompletableDeferred() }
        val service = JevDecisionService(
            CapturingBackend(), JevModelCatalog { api },
            JevLimits(maxConcurrentRequests = 1, maxWaitingRequests = 0, minTimeoutMs = 1, maxTimeoutMs = 2_000),
        )
        val hung = async { runCatching { service.decide(requestAllTypes(1_000).copy(model = "laya:en")) }.exceptionOrNull() }
        runCurrent()
        assertEquals(1, api.listings)
        // The refresh holds the only admission slot.
        val busy = runCatching { service.decide(requestAllTypes(1_000)) }.exceptionOrNull()
        assertEquals("BUSY", (busy as JevFailure).code)
        assertEquals("TIMEOUT", (hung.await() as JevFailure).code)
        assertEquals(1, api.listings)
    }

    // ---- panel readiness ----

    @Test
    fun `NeedsCredential clears when the window regains focus after the key is added`() {
        val api = FakeDecisionApi(listOf(openRouter(reachable = false), localRuntime()))
        val services = JevPluginServices(context(), CapturingBackend(), decisionApiOverride = { api })
        val vm = services.playground
        runBlocking { services.catalog.refresh() }
        assertEquals(JevReadiness.NeedsCredential, vm.state.value.readiness)

        api.providers = listOf(openRouter(), localRuntime())
        vm.onWindowFocused()
        vm.awaitCatalog()
        assertEquals(JevReadiness.Ready, vm.state.value.readiness)

        // Ready and nothing opened: focus alone does not probe again.
        val listings = api.listings
        vm.onWindowFocused()
        assertEquals(listings, api.listings)
        services.dispose()
    }

    @Test
    fun `returning to the panel after opening AI Providers refreshes once`() {
        val api = FakeDecisionApi(listOf(openRouter(reachable = false), localRuntime()))
        val services = JevPluginServices(context(), CapturingBackend(), decisionApiOverride = { api })
        val vm = services.playground
        runBlocking { services.catalog.refresh() }
        val before = api.listings
        vm.onPointerReturned()
        assertEquals(before, api.listings)

        vm.openSettings()
        api.providers = listOf(openRouter(), localRuntime())
        vm.onPointerReturned()
        vm.awaitCatalog()
        assertEquals(before + 1, api.listings)
        assertEquals(JevReadiness.Ready, vm.state.value.readiness)
        vm.onPointerReturned()
        assertEquals(before + 1, api.listings)
        services.dispose()
    }

    @Test
    fun `a successful run of a model shown as not ready refreshes the catalog`() {
        val api = FakeDecisionApi(listOf(openRouter(reachable = false), localRuntime()))
        val services = JevPluginServices(context(), CapturingBackend(), decisionApiOverride = { api })
        val vm = services.playground
        runBlocking { services.catalog.refresh() }
        assertEquals(JevReadiness.NeedsCredential, vm.state.value.readiness)

        // The key was added, but nothing told the catalog.
        api.providers = listOf(openRouter(), localRuntime())
        vm.useAllTypes()
        runBlocking { vm.runDraft() }
        vm.awaitCatalog()
        assertEquals(JevReadiness.Ready, vm.state.value.readiness)
        services.dispose()
    }

    @Test
    fun `a provider-only change updates the draft's hint`() {
        val down = localRuntime(reachable = false)
        val api = FakeDecisionApi(listOf(openRouter(), down))
        val services = JevPluginServices(context(), CapturingBackend(), decisionApiOverride = { api })
        val vm = services.playground
        runBlocking { services.catalog.refresh() }
        vm.setModel("laya:en", "LOCAL_SYSTEMONE")
        val readiness = vm.state.value.readiness
        assertIs<JevReadiness.Unavailable>(readiness)
        assertTrue(readiness.detail.contains("ollaya serve"), readiness.detail)
        val options = services.catalog.options.value

        // Runtime up, model not pulled: the options are the same, only the provider changed.
        api.providers = listOf(openRouter(), down.copy(reachable = true, detail = "127.0.0.1:11435"))
        runBlocking { services.catalog.refresh() }
        assertEquals(options, services.catalog.options.value)
        val issue = vm.state.value.issue(JevField.Model)
        assertTrue(issue?.contains("does not serve 'laya:en'") == true, issue)
        services.dispose()
    }

    @Test
    fun `a local model with no provider goes only to the local provider`() {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val services = JevPluginServices(context(), decisionApiOverride = { api })
        val vm = services.playground
        runBlocking { services.catalog.refresh() }
        vm.useAllTypes()
        vm.setModel("laya:en", null)
        assertNull(vm.state.value.providerId)
        val record = runBlocking { vm.runDraft() }
        assertEquals(listOf("LOCAL_SYSTEMONE"), api.requests.map { it.providerId })
        assertTrue(record.decision.model.local)
        services.dispose()
    }

    // ---- run bar ----

    @Test
    fun `Cancel wins over every readiness while running`() {
        val credential = JevPlaygroundState(readiness = JevReadiness.NeedsCredential)
        val down = JevPlaygroundState(readiness = JevReadiness.Unavailable("down"))
        assertEquals(RunBarAction.CANCEL, runBarAction(credential.copy(running = true)))
        assertEquals(RunBarAction.CANCEL, runBarAction(down.copy(running = true)))
        assertEquals(RunBarAction.CONNECT, runBarAction(credential))
        assertEquals(RunBarAction.REFRESH, runBarAction(down))
        assertEquals(RunBarAction.RUN, runBarAction(JevPlaygroundState()))
    }

    // ---- MCP ----

    @Test
    fun `an explicit model keeps the preset's provider only when that provider serves it`() = runTest {
        val twin = AiDecisionProvider("OTHER", "Other", local = false, reachable = true, models = listOf(AiDecisionModel("laya:en")))
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime(), twin))
        val catalog = JevModelCatalog { api }.also { it.refresh() }
        val values = mutableMapOf<String, String>()
        val repo = JevPresetRepository(object : JevPresetBackend {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        })
        repo.save(JevPreset("p", "", requestAllTypes().questions.toString(), stateAsJson = false, model = "laya:multilingual", providerId = "LOCAL_SYSTEMONE"))
        val sent = CapturingBackend()
        val mcp = JevMcpToolProvider("p", JevDecisionService(sent, catalog), repo)
        suspend fun call(raw: String) = testJson.parseToJsonElement(mcp.call(McpToolArgs(emptyMap(), raw)).text).jsonObject

        // laya:en is on LOCAL_SYSTEMONE and OTHER; the preset's provider settles it.
        assertEquals("LOCAL_SYSTEMONE", call("""{"preset":"p","state":"x","model":"laya:en"}""")["provider"]!!.jsonPrimitive.content)
        // The preset's provider does not serve jev-1.13, so the model resolves on its own.
        assertEquals("OPENROUTER", call("""{"preset":"p","state":"x","model":"typesafe/jev-1.13"}""")["provider"]!!.jsonPrimitive.content)
        // An explicit provider still wins.
        assertEquals("OTHER", call("""{"preset":"p","state":"x","model":"laya:en","provider":"OTHER"}""")["provider"]!!.jsonPrimitive.content)
        assertEquals(listOf("LOCAL_SYSTEMONE", "OPENROUTER", "OTHER"), sent.providers)
    }
}
