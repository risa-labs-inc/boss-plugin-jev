package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionException
import ai.rever.boss.plugin.api.AiDecisionModel
import ai.rever.boss.plugin.api.AiDecisionProvider
import ai.rever.boss.plugin.api.AiDecisionReply
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JevGatewayDecisionTest {
    private val local = JevModelOption("laya:en", "laya:en", "LOCAL_SYSTEMONE", "Local", local = true)

    private fun failing(code: String, message: String = "gateway says $code") =
        FakeDecisionApi(listOf(openRouter(), localRuntime()), reply = { Result.failure(AiDecisionException(code, message)) })

    private suspend fun catalogOf(api: FakeDecisionApi) = JevModelCatalog { api }.also { it.refresh() }

    // ---- error mapping ----

    @Test
    fun `gateway codes map onto Jev codes and unknown codes become UPSTREAM_ERROR`() = runTest {
        val expected = mapOf(
            AiDecisionException.MISSING_CREDENTIAL to "MISSING_CREDENTIAL",
            AiDecisionException.INVALID_INPUT to "UPSTREAM_INVALID_INPUT",
            AiDecisionException.LOCAL_UNAVAILABLE to "LOCAL_UNAVAILABLE",
            AiDecisionException.MODEL_NOT_FOUND to "MODEL_NOT_FOUND",
            AiDecisionException.AUTH_ERROR to "AUTH_ERROR",
            AiDecisionException.RATE_LIMITED to "RATE_LIMITED",
            AiDecisionException.TIMEOUT to "TIMEOUT",
            AiDecisionException.NETWORK_ERROR to "NETWORK_ERROR",
            AiDecisionException.RESPONSE_TOO_LARGE to "RESPONSE_TOO_LARGE",
            AiDecisionException.UPSTREAM_ERROR to "UPSTREAM_ERROR",
            AiDecisionException.UNKNOWN_PROVIDER to "UNKNOWN_PROVIDER",
            "SOMETHING_NEW" to "UPSTREAM_ERROR",
        )
        expected.forEach { (gatewayCode, jevCode) ->
            val backend = GatewayJevDecisionBackend { failing(gatewayCode) }
            val failure = assertFailsWith<JevFailure> { backend.decide(JevModelCatalog.DEFAULT, "{}", 1_000, 1_000) }
            assertEquals(jevCode, failure.code, gatewayCode)
            // The gateway's message names the provider in use, so it is shown as is.
            assertEquals("gateway says $gatewayCode", failure.message)
        }
    }

    @Test
    fun `a blank gateway message falls back to one naming the provider, and other failures hide their details`() = runTest {
        val blank = GatewayJevDecisionBackend { failing(AiDecisionException.AUTH_ERROR, " ") }
        assertEquals("Local rejected the configured credential", assertFailsWith<JevFailure> { blank.decide(local, "{}", 1_000, 1_000) }.message)

        val odd = FakeDecisionApi(reply = { Result.failure(IllegalStateException("socket 10.0.0.7:443 reset")) })
        val failure = assertFailsWith<JevFailure> { GatewayJevDecisionBackend { odd }.decide(JevModelCatalog.DEFAULT, "{}", 1_000, 1_000) }
        assertEquals("UPSTREAM_ERROR", failure.code)
        assertEquals("OpenRouter could not complete the request", failure.message)
    }

    @Test
    fun `cancellation from the gateway is rethrown, never mapped to a failure`() = runTest {
        val returned = FakeDecisionApi(reply = { Result.failure(CancellationException("stop")) })
        assertFailsWith<CancellationException> { GatewayJevDecisionBackend { returned }.decide(JevModelCatalog.DEFAULT, "{}", 1_000, 1_000) }

        val thrown = FakeDecisionApi(reply = { throw CancellationException("stop") })
        assertFailsWith<CancellationException> { GatewayJevDecisionBackend { thrown }.decide(JevModelCatalog.DEFAULT, "{}", 1_000, 1_000) }
    }

    @Test
    fun `no registered gateway gives GATEWAY_UNAVAILABLE`() = runTest {
        val failure = assertFailsWith<JevFailure> { GatewayJevDecisionBackend { null }.decide(JevModelCatalog.DEFAULT, "{}", 1_000, 1_000) }
        assertEquals("GATEWAY_UNAVAILABLE", failure.code)
        assertEquals("Install or update the AI Gateway plugin", failure.message)

        val service = JevDecisionService(GatewayJevDecisionBackend { null }, JevModelCatalog { null })
        assertEquals("GATEWAY_UNAVAILABLE", assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code)
    }

    @Test
    fun `the request is the SystemOne body, sent to the resolved provider with Jev's limits`() = runTest {
        val api = FakeDecisionApi()
        val service = JevDecisionService(GatewayJevDecisionBackend { api }, JevModelCatalog { api })
        service.decide(requestAllTypes(timeoutMs = 7_000))
        val sent = api.requests.single()
        assertEquals("OPENROUTER", sent.providerId)
        assertEquals(7_000, sent.timeoutMs)
        assertEquals(JevLimits().maxResponseBytes, sent.maxResponseBytes)
        assertEquals(setOf("model", "state", "questions"), testJson.parseToJsonElement(sent.body).jsonObject.keys)
    }

    // ---- catalog ----

    @Test
    fun `the default is present before any refresh, without a gateway, and when the gateway omits it`() = runTest {
        val fresh = JevModelCatalog()
        assertEquals(listOf(JevModelCatalog.DEFAULT), fresh.options.value)
        assertEquals(JevModelCatalog.DEFAULT, fresh.find("typesafe/jev-1.13", null))

        val none = JevModelCatalog { null }.also { it.refresh() }
        val default = none.options.value.single()
        assertEquals("typesafe/jev-1.13", default.id)
        assertFalse(default.reachable)
        assertEquals("Install or update the AI Gateway plugin", default.detail)
        assertFalse(none.gatewayAvailable.value)
        // No gateway is not a missing key: the panel must not offer to connect OpenRouter.
        assertIs<JevReadiness.Unavailable>(none.readiness(default))

        val localOnly = catalogOf(FakeDecisionApi(listOf(localRuntime())))
        assertEquals(listOf("typesafe/jev-1.13", "laya:en", "laya:multilingual"), localOnly.options.value.map { it.id })
        assertEquals("OPENROUTER", localOnly.options.value.first().providerId)
    }

    @Test
    fun `an unreachable local provider is listed with its hint, and models it served before stay disabled`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val catalog = catalogOf(api)
        assertTrue(catalog.options.value.first { it.id == "laya:en" }.let { it.local && it.reachable })

        api.providers = listOf(openRouter(), localRuntime(reachable = false))
        catalog.refresh()
        val down = catalog.providers.value.single { it.providerId == "LOCAL_SYSTEMONE" }
        assertFalse(down.reachable)
        assertTrue(down.detail!!.contains("ollaya serve"))
        val kept = catalog.options.value.filter { it.providerId == "LOCAL_SYSTEMONE" }
        assertEquals(listOf("laya:en", "laya:multilingual"), kept.map { it.id })
        assertTrue(kept.none { it.reachable })
        val readiness = catalog.readiness(kept.first())
        assertIs<JevReadiness.Unavailable>(readiness)
        assertTrue(readiness.detail.contains("ollaya serve"))
        assertEquals(JevReadiness.Ready, catalog.readiness(catalog.find("typesafe/jev-1.13", null)))
    }

    @Test
    fun `needsCredential, not reachability, is what asks for a key`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(reachable = false), localRuntime()))
        val catalog = catalogOf(api)
        val jev = catalog.find("typesafe/jev-1.13", null)!!
        assertTrue(jev.needsCredential)
        assertTrue(catalog.providers.value.single { it.providerId == "OPENROUTER" }.needsCredential)
        assertEquals(JevReadiness.NeedsCredential, catalog.readiness(jev))
        assertFalse(catalog.find("laya:en", null)!!.needsCredential)

        // Unreachable for another reason: its detail and Refresh, never the key card.
        api.providers = listOf(openRouter(reachable = false, needsCredential = false, detail = "OpenRouter did not answer"), localRuntime())
        catalog.refresh()
        val down = catalog.find("typesafe/jev-1.13", null)!!
        assertFalse(down.needsCredential)
        assertEquals(JevReadiness.Unavailable("OpenRouter did not answer"), catalog.readiness(down))
        assertFalse(catalog.providers.value.single { it.providerId == "OPENROUTER" }.needsCredential)
    }

    @Test
    fun `needsCredential is carried on models kept from before, on a named down provider, and on the default`() = runTest {
        val keyed = AiDecisionProvider("KEYED", "Keyed", local = false, reachable = true, models = listOf(AiDecisionModel("k-1")))
        val api = FakeDecisionApi(listOf(openRouter(), keyed))
        val catalog = catalogOf(api)
        api.providers = listOf(keyed.copy(reachable = false, models = emptyList(), detail = "Add a key", needsCredential = true))
        catalog.refresh()
        val kept = catalog.find("k-1", null)!!
        assertTrue(kept.needsCredential)
        assertEquals(JevReadiness.NeedsCredential, catalog.readiness(kept))
        assertTrue(catalog.find("k-2", "KEYED")!!.needsCredential)

        api.providers = listOf(openRouter(reachable = false).copy(models = emptyList()))
        catalog.refresh()
        assertTrue(catalog.find("typesafe/jev-1.13", null)!!.needsCredential)
    }

    @Test
    fun `a preset naming a provider that is down still resolves, so the gateway reports why`() = runTest {
        val catalog = catalogOf(FakeDecisionApi(listOf(openRouter(), localRuntime(reachable = false))))
        val option = catalog.find("laya:en", "LOCAL_SYSTEMONE")!!
        assertFalse(option.reachable)
        assertTrue(option.local)
        val unknown = catalog.lookup("laya:en", null)
        assertIs<JevModelLookup.Unknown>(unknown)
        assertTrue(unknown.message.contains("Local is not reachable"), unknown.message)
    }

    @Test
    fun `an id served by two providers is ambiguous until a provider is named`() = runTest {
        val twin = AiDecisionProvider("OTHER", "Other", local = false, reachable = true, models = listOf(AiDecisionModel("laya:en")))
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime(), twin))
        val service = JevDecisionService(CapturingBackend(), catalogOf(api))
        val request = requestAllTypes().copy(model = "laya:en")

        val failure = assertFailsWith<JevFailure> { service.decide(request) }
        assertEquals("INVALID_INPUT", failure.code)
        assertEquals("provider", failure.path)
        assertTrue(failure.message.contains("LOCAL_SYSTEMONE") && failure.message.contains("OTHER"), failure.message)

        service.decide(request.copy(providerId = "local_systemone"))
        assertEquals("LOCAL_SYSTEMONE", service.runs.value.single().providerId)
        assertTrue(service.runs.value.single().decision.model.local)
    }

    @Test
    fun `an unknown model id refreshes the catalog before it is rejected`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val catalog = JevModelCatalog { api }
        val backend = CapturingBackend()
        val service = JevDecisionService(backend, catalog)

        service.decide(requestAllTypes().copy(model = "laya:en"))
        assertEquals(1, api.listings)
        assertEquals(listOf("LOCAL_SYSTEMONE"), backend.providers)

        val failure = assertFailsWith<JevFailure> { service.decide(requestAllTypes().copy(model = "nope:1")) }
        assertEquals("INVALID_INPUT", failure.code)
        assertEquals("model", failure.path)
        assertEquals(2, api.listings)
    }

    // ---- no fallback ----

    @Test
    fun `a selected local model that fails is never retried on OpenRouter`() = runTest {
        for (code in listOf(AiDecisionException.LOCAL_UNAVAILABLE, AiDecisionException.MODEL_NOT_FOUND, AiDecisionException.TIMEOUT)) {
            val api = failing(code)
            val service = JevDecisionService(GatewayJevDecisionBackend { api }, catalogOf(api))
            val failure = assertFailsWith<JevFailure> { service.decide(requestAllTypes().copy(model = "laya:en")) }
            assertEquals(code, failure.code)
            assertEquals(listOf("LOCAL_SYSTEMONE"), api.requests.map { it.providerId }, code)
            assertTrue(service.runs.value.isEmpty())
        }
    }

    // ---- Ollaya contract ----

    private fun resource(name: String) = javaClass.getResource("/ollaya/$name")!!.readText()

    @Test
    fun `real Ollaya responses pass response validation against their requests`() = runTest {
        for (case in listOf("noul", "choice", "score")) {
            val request = testJson.parseToJsonElement(resource("$case.req.json")).jsonObject
            val response = testJson.parseToJsonElement(resource("$case.resp.body")) as JsonObject
            JevValidation.response(response, request["questions"]!!.jsonObject)

            // The whole path too: catalog lookup, request validation, the gateway reply.
            val api = FakeDecisionApi(
                listOf(openRouter(), localRuntime()),
                reply = { Result.success(AiDecisionReply(resource("$case.resp.body"), it.providerId, 3)) },
            )
            val service = JevDecisionService(GatewayJevDecisionBackend { api }, JevModelCatalog { api })
            val decision = service.decide(JevRequest(request["state"]!!, request["questions"]!!.jsonObject, model = "laya:multilingual"))
            assertEquals(request, testJson.parseToJsonElement(api.requests.single().body))
            assertTrue(decision.model.local, case)
        }
    }

    // ---- composer, MCP, presets ----

    @Test
    fun `jev_models refreshes and lists id, provider, local, reachable and detail`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(reachable = false), localRuntime()))
        val provider = JevMcpToolProvider("p", JevDecisionService(CapturingBackend(), JevModelCatalog { api }))
        val body = testJson.parseToJsonElement(provider.listModels(McpToolArgs(emptyMap(), "{}")).text).jsonObject
        assertEquals(1, api.listings)
        val models = body["models"]!!.jsonArray.map { it.jsonObject }
        val jev = models.first()
        assertEquals("typesafe/jev-1.13", jev["id"]!!.jsonPrimitive.content)
        assertEquals("OPENROUTER", jev["provider"]!!.jsonPrimitive.content)
        assertEquals("false", jev["reachable"]!!.jsonPrimitive.content)
        assertTrue(jev["detail"]!!.jsonPrimitive.content.contains("OpenRouter key"))
        assertEquals("true", jev["needs_credential"]!!.jsonPrimitive.content)
        val openRouterStatus = body["providers"]!!.jsonArray.map { it.jsonObject }.single { it["provider"]!!.jsonPrimitive.content == "OPENROUTER" }
        assertEquals("true", openRouterStatus["needs_credential"]!!.jsonPrimitive.content)
        val laya = models.first { it["id"]!!.jsonPrimitive.content == "laya:en" }
        assertEquals("true", laya["local"]!!.jsonPrimitive.content)
        assertEquals("LOCAL_SYSTEMONE", laya["provider"]!!.jsonPrimitive.content)
        assertEquals("typesafe/jev-1.13", body["default"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jev_decide takes a provider, reports it, and the model schema is a plain string`() = runTest {
        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val backend = CapturingBackend()
        val provider = JevMcpToolProvider("p", JevDecisionService(backend, JevModelCatalog { api }))
        val raw = """{"state":"x","model":"laya:en","provider":"LOCAL_SYSTEMONE","questions":${requestAllTypes().questions}}"""
        val result = testJson.parseToJsonElement(provider.call(McpToolArgs(emptyMap(), raw)).text).jsonObject
        assertEquals("LOCAL_SYSTEMONE", result["provider"]!!.jsonPrimitive.content)
        assertEquals("true", result["local"]!!.jsonPrimitive.content)
        assertEquals(listOf("LOCAL_SYSTEMONE"), backend.providers)

        val bad = provider.call(McpToolArgs(emptyMap(), """{"state":"x","model":"laya:en","provider":"NOPE","questions":${requestAllTypes().questions}}"""))
        assertEquals("provider", testJson.parseToJsonElement(bad.text).jsonObject["error"]!!.jsonObject["path"]!!.jsonPrimitive.content)

        listOf(JevMcpToolProvider.DECIDE_SCHEMA, JevMcpToolProvider.DRAFT_SET_SCHEMA, JevMcpToolProvider.PRESET_SAVE_SCHEMA).forEach { schema ->
            val properties = testJson.parseToJsonElement(schema).jsonObject["properties"]!!.jsonObject
            assertNull(properties["model"]!!.jsonObject["enum"])
            assertEquals("string", properties["provider"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertFalse(provider.tools().first { it.name == "jev_decide" }.description.contains("paid external request"))
    }

    @Test
    fun `presets store the provider next to the model`() = runTest {
        val values = mutableMapOf<String, String>()
        val backend = object : JevPresetBackend {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        val repo = JevPresetRepository(backend)
        repo.save(JevPreset("local", "", "{}", stateAsJson = false, model = "laya:en", providerId = "LOCAL_SYSTEMONE"))
        repo.save(JevPreset("default", "", "{}", stateAsJson = false))
        assertEquals("LOCAL_SYSTEMONE", repo.load("local")!!.providerId)
        assertNull(repo.load("default")!!.providerId)

        val api = FakeDecisionApi(listOf(openRouter(), localRuntime()))
        val sent = CapturingBackend()
        val mcp = JevMcpToolProvider("p", JevDecisionService(sent, JevModelCatalog { api }), repo)
        repo.save(JevPreset("routing", "", requestAllTypes().questions.toString(), stateAsJson = false, model = "laya:en", providerId = "LOCAL_SYSTEMONE"))
        assertFalse(mcp.call(McpToolArgs(emptyMap(), """{"preset":"routing","state":"x"}""")).isError)
        assertEquals(listOf("LOCAL_SYSTEMONE"), sent.providers)
        val listed = testJson.parseToJsonElement(mcp.listPresets(McpToolArgs(emptyMap())).text).jsonObject["presets"]!!.jsonArray
        assertEquals(JsonPrimitive("LOCAL_SYSTEMONE"), listed.first { it.jsonObject["name"]!!.jsonPrimitive.content == "routing" }.jsonObject["provider"])
    }
}
