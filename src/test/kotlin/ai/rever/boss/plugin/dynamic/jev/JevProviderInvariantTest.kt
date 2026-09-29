package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One rule decides every provider: an explicit provider wins, a bound provider is kept, and
 * nothing is resolved on a cold catalog. A call never moves between OpenRouter and local unnamed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JevProviderInvariantTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Memory : JevPresetBackend {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private class Panel(providers: List<AiDecisionProvider>) {
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
        fun call(block: suspend JevMcpToolProvider.() -> McpToolResult) = runBlocking { mcp.block() }
        val sent get() = api.requests.map { it.providerId }
    }

    private fun McpToolResult.json() = testJson.parseToJsonElement(text).jsonObject
    private fun McpToolResult.error(): JsonObject? = json()["error"]?.jsonObject
    private fun McpToolResult.code() = error()?.get("code")?.jsonPrimitive?.content
    private fun McpToolResult.path() = error()?.get("path")?.jsonPrimitive?.content

    private val q get() = requestAllTypes().questions

    // ---- review scenarios ----

    @Test
    fun `a model change while the catalog cannot load is refused, and nothing reaches OpenRouter`() {
        for (warm in listOf(true, false)) {
            val d = Panel(listOf(openRouter(), localRuntime(true, "laya:en")))
            if (warm) runBlocking { d.services.catalog.refresh() } else d.api.gate = CompletableDeferred()
            val first = d.call { draftSet(McpToolArgs(emptyMap(), """{"state":"PHI patient","questions":$q,"model":"laya:en","timeout_ms":1000}""")) }
            val second = d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV","timeout_ms":1000}""")) }
            assertTrue(second.isError, "warm=$warm: ${second.text}")
            assertEquals("INVALID_INPUT", second.code())
            if (warm) {
                assertFalse(first.isError, first.text)
                assertEquals("provider", second.path())
                assertEquals("laya:en" to LOCAL, d.vm.state.value.model to d.vm.state.value.providerId)
            } else {
                assertEquals("INVALID_INPUT" to "model", first.code() to first.path(), first.text)
                assertTrue(first.error()!!["message"]!!.jsonPrimitive.content.contains("pass provider or retry"))
                // Refused whole: no PHI context, no model, no provider was stored.
                assertEquals(null, d.vm.state.value.providerId)
                assertFalse(d.vm.state.value.contextText.contains("PHI"))
            }
            d.api.gate?.complete(Unit)
            d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
            if (warm) assertEquals(listOf(LOCAL), d.sent) else assertTrue(d.api.requests.none { it.body.contains("PHI") })
            d.services.dispose()
        }
    }

    @Test
    fun `a preset saved without a provider resolves it on use and pins it`() {
        val d = Panel(listOf(openRouter(), localRuntime(false)))
        // Down: laya:en resolves nowhere, so the draft refuses it rather than store no provider.
        val set = d.call { draftSet(McpToolArgs(emptyMap(), """{"state":"PHI patient","questions":$q,"model":"laya:en"}""")) }
        assertEquals("INVALID_INPUT" to "model", set.code() to set.path(), set.text)
        // Saving the untouched default stores the provider it resolves to.
        val saved = d.call { presetSave(McpToolArgs(emptyMap(), """{"name":"default"}""")) }
        assertEquals("OPENROUTER", saved.json()["provider"]!!.jsonPrimitive.content, saved.text)

        // A preset from before providers were stored.
        runBlocking {
            d.services.presets!!.save(JevPreset("legacy", "PHI patient", q.toString(), stateAsJson = false, timeoutMs = 1_000, model = "laya:en"))
        }
        val down = d.call { call(McpToolArgs(emptyMap(), """{"preset":"legacy","state":"PHI patient"}""")) }
        assertEquals("model", down.path(), down.text)
        assertEquals(emptyList(), d.sent)

        d.api.providers = listOf(openRouter(), localRuntime(true, "laya:en"))
        val own = d.call { call(McpToolArgs(emptyMap(), """{"preset":"legacy","state":"PHI patient"}""")) }
        assertFalse(own.isError, own.text)
        val other = d.call { call(McpToolArgs(emptyMap(), """{"preset":"legacy","state":"PHI patient","model":"$JEV"}""")) }
        assertEquals("INVALID_INPUT" to "provider", other.code() to other.path(), other.text)
        assertEquals(listOf(LOCAL), d.sent)

        // Opened in the panel, it stores the resolved provider, which pins the next model.
        runBlocking { d.vm.loadPreset("legacy")!!.join() }
        assertEquals("laya:en" to LOCAL, d.vm.state.value.model to d.vm.state.value.providerId)
        val moved = d.call { draftSet(McpToolArgs(emptyMap(), """{"model":"$JEV"}""")) }
        assertEquals("provider", moved.path(), moved.text)
        d.call { draftRun(McpToolArgs(emptyMap(), "{}")) }
        assertEquals(listOf(LOCAL, LOCAL), d.sent)
        d.services.dispose()
    }

    @Test
    fun `a preset saved without a provider does not open while the catalog cannot load`() {
        val d = Panel(listOf(openRouter(), localRuntime(true, "laya:en")))
        runBlocking {
            d.services.presets!!.save(JevPreset("legacy", "PHI patient", q.toString(), stateAsJson = false, timeoutMs = 1_000, model = "laya:en"))
        }
        d.api.gate = CompletableDeferred()
        runBlocking { d.vm.loadPreset("legacy")!!.join() }
        assertEquals(null, d.vm.state.value.presetName)
        assertEquals(null, d.vm.state.value.providerId)
        assertTrue(d.vm.state.value.notice!!.contains("pass provider or retry"), d.vm.state.value.notice)
        d.api.gate!!.complete(Unit)
        d.services.dispose()
    }

    @Test
    fun `jev_models with a hung gateway returns within its timeout, and BUSY when full`() = runBlocking {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime(true, "laya:en"))).apply { gate = CompletableDeferred() }
        val service = JevDecisionService(GatewayJevDecisionBackend { api }, JevModelCatalog { api })
        val mcp = JevMcpToolProvider("p", service)
        val start = System.nanoTime()
        val listed = withTimeoutOrNull(4_000) { mcp.listModels(McpToolArgs(emptyMap(), """{"timeout_ms":1000}""")) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertNotNull(listed, "jev_models still hanging")
        assertTrue(ms < 3_000, "took ${ms}ms")
        assertFalse(listed.isError, listed.text)
        assertTrue("note" in listed.json(), listed.text)
        assertEquals(JEV, listed.json()["models"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("timeout_ms", mcp.listModels(McpToolArgs(emptyMap(), """{"timeout_ms":5}""")).path())
        api.gate!!.complete(Unit)
        val warm = mcp.listModels(McpToolArgs(emptyMap(), "{}")).json()
        assertNull(warm["note"])
        service.close()

        val full = FakeDecisionApi(listOf(openRouter())).apply { gate = CompletableDeferred() }
        val oneSlot = JevDecisionService(CapturingBackend(), JevModelCatalog { full }, JevLimits(maxConcurrentRequests = 1, maxWaitingRequests = 0))
        val held = async { runCatching { oneSlot.loadCatalog(2_000) } }
        while (full.listings == 0) kotlinx.coroutines.yield()
        assertEquals("BUSY", JevMcpToolProvider("p", oneSlot).listModels(McpToolArgs(emptyMap(), "{}")).code())
        full.gate!!.complete(Unit)
        held.await()
        Unit
    }

    // ---- every combination ----

    private enum class Local { UP, EMPTY, DOWN }

    /** OpenRouter and local each serve their own models and one they share. */
    private fun providers(local: Local): List<AiDecisionProvider> = listOf(
        openRouter().copy(models = listOf(AiDecisionModel(JEV), AiDecisionModel(OR_ALT), AiDecisionModel(SHARED))),
        when (local) {
            Local.UP -> localRuntime(true, "laya:en", LOCAL_ALT, SHARED)
            Local.EMPTY -> localRuntime(true).copy(models = emptyList())
            Local.DOWN -> localRuntime(false)
        },
    )

    private fun listed(local: Local): Map<String, Set<String>> = mapOf(
        OPENROUTER to setOf(JEV, OR_ALT, SHARED),
        LOCAL to if (local == Local.UP) setOf("laya:en", LOCAL_ALT, SHARED) else emptySet(),
    )

    /** How the call starts: no binding, a stored one, or a preset's that resolves on use. */
    private enum class Current(val model: String?, val provider: String?, val stored: Boolean) {
        NONE(null, null, false),
        OPENROUTER_STORED(JEV, OPENROUTER, true),
        LOCAL_STORED("laya:en", LOCAL, true),
        OPENROUTER_RESOLVED(JEV, null, false),
        LOCAL_RESOLVED("laya:en", null, false),
    }

    private data class Case(val current: Current, val model: String, val explicit: String?, val warm: Boolean, val local: Local) {
        override fun toString() = "current=$current model=$model explicit=$explicit warm=$warm local=$local"
    }

    private val requested = listOf(OR_ALT, LOCAL_ALT, SHARED, "nope:1")

    private fun cases(currents: List<Current>) = currents.flatMap { c ->
        requested.flatMap { m ->
            listOf(null, OPENROUTER, LOCAL).flatMap { e ->
                listOf(true, false).flatMap { w -> Local.entries.map { l -> Case(c, m, e, w, l) } }
            }
        }
    }

    /** The provider the rule allows, or null when the change must be refused. Never another. */
    private fun expected(case: Case, coldExplicitAccepted: Boolean): String? {
        val listed = listed(case.local)
        if (!case.warm) return case.explicit.takeIf { coldExplicitAccepted }
        case.explicit?.let { e ->
            // A provider that is down cannot list models; the gateway reports it when called.
            val down = e == LOCAL && case.local == Local.DOWN
            return e.takeIf { case.model in listed.getValue(e) || down }
        }
        fun unique(model: String) = listed.filterValues { model in it }.keys.singleOrNull()
        val pin = when {
            case.current == Current.NONE -> return unique(case.model)
            case.current.stored -> case.current.provider!!
            else -> unique(case.current.model!!) ?: return null
        }
        return pin.takeIf { case.model in listed.getValue(pin) }
    }

    @Test
    fun `a panel draft's model change stays on its provider, takes an explicit one, or is refused`() = runBlocking {
        val all = cases(listOf(Current.NONE, Current.OPENROUTER_STORED, Current.LOCAL_STORED))
        val failures = all.map { case -> async { draftCase(case) } }.awaitAll().filterNotNull()
        assertTrue(failures.isEmpty(), "${failures.size} of ${all.size} cases:\n" + failures.joinToString("\n"))
    }

    private suspend fun draftCase(case: Case): String? {
        val d = Panel(providers(case.local))
        try {
            d.vm.applyDraft(JevDraftChange(contextText = "PHI patient", questions = q))
            // Bound while cold, so every runtime state starts from the same draft.
            if (case.current != Current.NONE) d.vm.setModel(case.current.model!!, case.current.provider)
            if (case.warm) d.services.catalog.refresh() else d.api.gate = CompletableDeferred()
            val before = d.vm.state.value.providerId
            val provider = case.explicit?.let { ""","provider":"$it"""" }.orEmpty()
            val result = d.mcp.draftSet(McpToolArgs(emptyMap(), """{"model":"${case.model}"$provider,"timeout_ms":1000}"""))
            val after = d.vm.state.value.providerId
            val want = expected(case, coldExplicitAccepted = true)
            val problem = when {
                want == null && !result.isError -> "accepted on $after, expected refusal"
                want == null && result.code() != "INVALID_INPUT" -> "refused with ${result.code()}"
                want == null && after != before -> "refused but moved $before -> $after"
                want != null && result.isError -> "refused (${result.path()}: ${result.error()?.get("message")}), expected $want"
                want != null && after != want -> "on $after, expected $want"
                else -> null
            }
            if (problem != null) return "$case: $problem"
            d.api.gate?.complete(Unit)
            d.mcp.draftRun(McpToolArgs(emptyMap(), "{}"))
            // The untouched default is the only unpinned run, and it defaults to OpenRouter.
            val allowed = after ?: OPENROUTER
            if (d.sent.any { it != allowed }) return "$case: ran on ${d.sent}, bound to $allowed"
            return null
        } finally {
            d.api.gate?.complete(Unit)
            d.services.dispose()
        }
    }

    @Test
    fun `jev_decide with a preset stays on its provider, takes an explicit one, or is refused`() = runBlocking {
        val all = cases(Current.entries)
        val failures = all.map { case -> async { decideCase(case) } }.awaitAll().filterNotNull()
        assertTrue(failures.isEmpty(), "${failures.size} of ${all.size} cases:\n" + failures.joinToString("\n"))
    }

    private suspend fun decideCase(case: Case): String? {
        val api = FakeDecisionApi(providers(case.local))
        val catalog = JevModelCatalog { api }
        if (case.warm) catalog.refresh() else api.gate = CompletableDeferred()
        val repo = JevPresetRepository(Memory())
        val service = JevDecisionService(GatewayJevDecisionBackend { api }, catalog)
        try {
            val mcp = JevMcpToolProvider("p", service, repo)
            val body = if (case.current == Current.NONE) {
                """"questions":$q"""
            } else {
                repo.save(JevPreset("p", "", q.toString(), stateAsJson = false, model = case.current.model!!, providerId = case.current.provider))
                """"preset":"p""""
            }
            val provider = case.explicit?.let { ""","provider":"$it"""" }.orEmpty()
            val result = mcp.call(McpToolArgs(emptyMap(), """{$body,"state":"PHI patient","model":"${case.model}"$provider,"timeout_ms":1000}"""))
            val sent = api.requests.map { it.providerId }
            // A cold load times out inside the call, so nothing is decided.
            val want = expected(case, coldExplicitAccepted = false)
            return when {
                want == null && !result.isError -> "$case: accepted on ${result.json()["provider"]}, expected refusal"
                want == null && sent.isNotEmpty() -> "$case: refused but sent to $sent"
                want == null && result.code() !in setOf("INVALID_INPUT", "TIMEOUT") -> "$case: refused with ${result.code()}"
                want != null && result.isError -> "$case: refused (${result.path()}: ${result.error()?.get("message")}), expected $want"
                want != null && sent != listOf(want) -> "$case: sent to $sent, expected $want"
                else -> null
            }
        } finally {
            api.gate?.complete(Unit)
            service.close()
        }
    }

    @Test
    fun `local-pinned state never reaches OpenRouter, and OpenRouter-pinned state never reaches local`() = runBlocking {
        for ((pinned, other) in listOf(LOCAL to OPENROUTER, OPENROUTER to LOCAL)) {
            val model = if (pinned == LOCAL) "laya:en" else JEV
            for (local in Local.entries) for (target in requested + listOf(JEV, "laya:en")) {
                val d = Panel(providers(local))
                d.vm.applyDraft(JevDraftChange(contextText = "PHI patient", questions = q))
                d.vm.setModel(model, pinned)
                d.mcp.draftSet(McpToolArgs(emptyMap(), """{"model":"$target"}"""))
                d.vm.setModel(target, null)
                runCatching { d.vm.applyDraft(JevDraftChange(model = target)) }
                d.mcp.presetSave(McpToolArgs(emptyMap(), """{"name":"x","model":"$target"}"""))
                d.mcp.draftRun(McpToolArgs(emptyMap(), "{}"))
                d.mcp.call(McpToolArgs(emptyMap(), """{"preset":"x","state":"PHI patient","model":"$target"}"""))
                d.mcp.call(McpToolArgs(emptyMap(), """{"preset":"x","state":"PHI patient"}"""))
                assertFalse(other in d.sent, "pinned=$pinned local=$local target=$target sent=${d.sent}")
                assertEquals(pinned, d.vm.state.value.providerId)
                d.services.dispose()
            }
        }
    }

    @Test
    fun `resolveChange is the only rule, and it refuses on a cold catalog`() {
        val catalog = JevModelCatalog { FakeDecisionApi(providers(Local.UP)) }
        val bound = JevBinding("laya:en", LOCAL)
        fun refused(block: () -> String) = runCatching(block).exceptionOrNull() as JevFailure
        // Cold: an explicit provider is taken as named; nothing else resolves.
        assertEquals(LOCAL, catalog.resolveChange(null, LOCAL_ALT, LOCAL))
        assertEquals("model", refused { catalog.resolveChange(null, JEV, null) }.path)
        assertEquals("provider", refused { catalog.resolveChange(bound, LOCAL_ALT, null) }.path)
        assertEquals(LOCAL, catalog.resolveChange(bound, "laya:en", null))
        runBlocking { catalog.refresh() }
        assertEquals(LOCAL, catalog.resolveChange(bound, LOCAL_ALT, null))
        assertEquals(LOCAL, catalog.resolveChange(bound, SHARED, null))
        assertEquals("provider", refused { catalog.resolveChange(bound, OR_ALT, null) }.path)
        assertEquals(OPENROUTER, catalog.resolveChange(bound, OR_ALT, OPENROUTER))
        assertEquals("provider", refused { catalog.resolveChange(null, SHARED, null) }.path)
        assertEquals("model", refused { catalog.resolveChange(JevBinding("nope:1", null), "laya:en", null) }.path)
        assertEquals(LOCAL, catalog.resolveChange(JevBinding(LOCAL_ALT, null), SHARED, null))
    }

    private companion object {
        const val JEV = "typesafe/jev-1.13"
        const val OPENROUTER = "OPENROUTER"
        const val LOCAL = "LOCAL_SYSTEMONE"
        const val OR_ALT = "or/alt-1"
        const val LOCAL_ALT = "laya:multilingual"
        const val SHARED = "shared:1"
    }
}
