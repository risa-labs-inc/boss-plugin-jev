package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JevMcpAndPresetTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fixing timeout immediately revalidates run input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val context = object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob())
            }
            val services = JevPluginServices(context)
            val viewModel = JevPlaygroundViewModel(services)
            viewModel.setTimeout(500)
            assertEquals("Timeout must be between 1000 and 120000 ms", viewModel.state.value.issue(JevField.Timeout))
            assertEquals(null, viewModel.state.value.request)
            viewModel.setTimeout(1_000)
            assertEquals(null, viewModel.state.value.issue(JevField.Timeout))
            assertTrue(viewModel.state.value.request != null)
            viewModel.dispose()
            services.dispose()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `MCP and UI share the service owned by one plugin activation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = CoroutineScope(SupervisorJob())
        }
        val services = JevPluginServices(context)
        val viewModel = JevPlaygroundViewModel(services)
        val provider = JevMcpToolProvider("plugin", services.service)
        assertSame(viewModel.decisionService, provider.service)
        val tools = provider.tools().associateBy { it.name }
        assertFalse(tools.getValue("jev_decide").readOnly)
        assertTrue(tools.getValue("jev_validate").readOnly)
        assertTrue(tools.getValue("jev_presets").readOnly)
        viewModel.dispose()
        services.dispose()
        Dispatchers.resetMain()

        val service = JevDecisionService(JevKeyResolver { "key" }, CapturingTransport())
        val testProvider = JevMcpToolProvider("plugin", service)
        val raw = """{"state":{"ticket":"login outage"},"questions":${requestAllTypes().questions},"timeout_ms":5000}"""
        val result = testProvider.call(McpToolArgs(emptyMap(), raw))
        assertFalse(result.isError)
        val envelope = testJson.parseToJsonElement(result.text).jsonObject
        assertEquals("typesafe/jev-1.13", envelope["response"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MCP parses nested raw JSON and returns stable safe errors`() = runTest {
        val provider = JevMcpToolProvider(
            "plugin",
            JevDecisionService(JevKeyResolver { null }, CapturingTransport()),
        )
        val missing = provider.call(McpToolArgs(emptyMap(), """{"state":"x","questions":{"q":{"type":"noul","instructions":"decide"}}}"""))
        assertTrue(missing.isError)
        assertEquals("MISSING_OPENROUTER_KEY", testJson.parseToJsonElement(missing.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val malformed = provider.call(McpToolArgs(emptyMap(), "{"))
        assertTrue(malformed.isError)
        assertEquals("INVALID_INPUT", testJson.parseToJsonElement(malformed.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        listOf(
            """{"state":"x","questions":{"q":{"type":"noul","instructions":"decide"}},"timeout_ms":"5000"}""",
            """{"state":"x","questions":{"q":{"type":"noul","instructions":"decide"}},"extra":true}""",
        ).forEach { raw ->
            val result = provider.call(McpToolArgs(emptyMap(), raw))
            assertEquals("INVALID_INPUT", testJson.parseToJsonElement(result.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }

        val oversized = provider.call(McpToolArgs(emptyMap(), " ".repeat(256 * 1024 + 1)))
        assertEquals("INPUT_TOO_LARGE", testJson.parseToJsonElement(oversized.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preset roundtrip list and delete stores no credential`() = runTest {
        val values = mutableMapOf<String, String>()
        val backend = object : JevPresetBackend {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        val repository = JevPresetRepository(backend)
        val preset = JevPreset("Incident triage", "secretless context", "{\"q\":{}}", stateAsJson = false, timeoutMs = 42_000)
        repository.save(preset)
        assertEquals(listOf("Incident triage"), repository.names())
        assertEquals(preset, repository.load("Incident triage"))
        assertTrue(values.values.none { it.contains("apiKey", ignoreCase = true) || it.contains("Bearer ") })
        repository.delete("Incident triage")
        assertTrue(repository.names().isEmpty())
        assertEquals(null, repository.load("Incident triage"))
    }

    @Test
    fun `preset collection updates are atomic bounded and storage failures surface`() = runTest {
        val values = mutableMapOf<String, String>()
        val backend = object : JevPresetBackend {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        val repository = JevPresetRepository(backend)
        (1..32).map { index ->
            async { repository.save(JevPreset("p$index", "state", "{}", timeoutMs = 1_000)) }
        }.awaitAll()
        assertEquals(32, repository.names().size)
        assertFailsWith<IllegalArgumentException> {
            repository.save(JevPreset("overflow", "state", "{}", timeoutMs = 1_000))
        }

        values.clear()
        values["presets/v2"] = "not-json"
        assertEquals("PRESET_STORAGE_ERROR", assertFailsWith<JevFailure> { repository.names() }.code)
    }
}
