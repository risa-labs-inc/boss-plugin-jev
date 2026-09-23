package ai.rever.boss.plugin.dynamic.jev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.http.HttpTimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class JevDecisionServiceTest {
    @Test
    fun `sends exact contract body and validates all answer types`() = runTest {
        val transport = CapturingTransport()
        var resolves = 0
        val service = JevDecisionService(JevKeyResolver { resolves++; "secret-key" }, transport)

        val result = service.decide(requestAllTypes())

        assertEquals(1, resolves)
        assertEquals("secret-key", transport.token)
        val sent = testJson.parseToJsonElement(transport.body!!.decodeToString()).jsonObject
        assertEquals(setOf("model", "state", "questions"), sent.keys)
        assertEquals(JevDecisionService.DEFAULT_MODEL, sent["model"]!!.jsonPrimitive.content)
        assertEquals("identity", result.response["answers"]!!.jsonObject["route"]!!.jsonObject["choice"]!!.jsonPrimitive.content)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `resolves key lazily for each call`() = runTest {
        val transport = CapturingTransport()
        var key: String? = null
        val service = JevDecisionService(JevKeyResolver { key }, transport)
        assertEquals("MISSING_OPENROUTER_KEY", assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code)
        key = "later"
        service.decide(requestAllTypes())
        assertEquals("later", transport.token)
    }

    @Test
    fun `rejects invalid nested question shapes`() = runTest {
        val invalidQuestions = listOf(
            """{"x":{"type":"choice","instructions":"pick","criteria":{}}}""",
            """{"x":{"type":"score","instructions":"score","criteria":["only"]}}""",
            """{"x":{"type":"noul","instructions":"","criteria":{}}}""",
            """{"x":{"type":"unknown","instructions":"decide"}}""",
            """{"x":{"type":"noul","instructions":"decide","criteria":{"maybe":"bad"}}}""",
        )
        val service = JevDecisionService(JevKeyResolver { "key" }, CapturingTransport())
        invalidQuestions.forEach { raw ->
            val request = requestAllTypes().copy(questions = testJson.parseToJsonElement(raw) as JsonObject)
            assertEquals("INVALID_INPUT", assertFailsWith<JevFailure> { service.decide(request) }.code, raw)
        }
    }

    @Test
    fun `rejects malformed and mismatched response`() = runTest {
        val cases = listOf(
            "not-json",
            validResponse.replace("\"support\":0.2", "\"support\":0.3"),
            validResponse.replace("\"identity\":0.8,\"support\":0.2", "\"identity\":1.0"),
            validResponse.replace("\"choice\":\"identity\"", "\"choice\":\"billing\""),
            validResponse.replace("\"readiness\":{", "\"different\":{"),
            validResponse.replace("\"confidence\":0.75", "\"confidence\":1.5"),
        )
        cases.forEach { response ->
            val service = JevDecisionService(JevKeyResolver { "key" }, CapturingTransport(response))
            assertEquals("MALFORMED_RESPONSE", assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code)
        }
    }

    @Test
    fun `accepts official fractional probability weighted score`() = runTest {
        val service = JevDecisionService(JevKeyResolver { "key" }, CapturingTransport(validResponse))
        assertEquals("1.05", service.decide(requestAllTypes()).response["answers"]!!.jsonObject["readiness"]!!.jsonObject["score"]!!.jsonPrimitive.content)
    }

    @Test
    fun `classifies malformed numeric shapes and quoted numbers safely`() = runTest {
        val cases = listOf(
            validResponse.replace("\"score\":1.05", "\"score\":\"1.05\""),
            validResponse.replace("\"noul\":0.9", "\"noul\":{}"),
            validResponse.replace("\"confidence\":0.75", "\"confidence\":[]"),
            validResponse.replace("\"input_tokens\":22", "\"input_tokens\":\"22\""),
            validResponse.replace("\"cost\":0.002", "\"cost\":{}"),
        )
        cases.forEach { response ->
            val service = JevDecisionService(JevKeyResolver { "key" }, CapturingTransport(response))
            assertEquals("MALFORMED_RESPONSE", assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code)
        }
    }

    @Test
    fun `timeout cancels underlying transport`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray =
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelled.complete(Unit) }
                }
        }
        val service = JevDecisionService(
            JevKeyResolver { "key" },
            transport,
            JevLimits(minTimeoutMs = 1, maxTimeoutMs = 1_000),
        )
        assertEquals("TIMEOUT", assertFailsWith<JevFailure> { service.decide(requestAllTypes(10)) }.code)
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `caller cancellation reaches transport and close performs cleanup`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var cleanup = false
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray =
                suspendCancellableCoroutine { continuation ->
                    entered.complete(Unit)
                    continuation.invokeOnCancellation { cancelled.complete(Unit) }
                }
            override fun cancelAll() { cleanup = true }
        }
        val service = JevDecisionService(JevKeyResolver { "key" }, transport)
        val job = launch { service.decide(requestAllTypes()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        service.close()
        assertTrue(cleanup)
        assertEquals("SERVICE_UNAVAILABLE", assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code)
    }

    @Test
    fun `deadline includes queue wait and admission is bounded`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val service = JevDecisionService(
            JevKeyResolver { "key" },
            transport,
            JevLimits(maxConcurrentRequests = 1, maxWaitingRequests = 1, minTimeoutMs = 1, maxTimeoutMs = 2_000),
        )
        val active = launch { runCatching { service.decide(requestAllTypes(1_000)) } }
        entered.await()
        val queued = async { assertFailsWith<JevFailure> { service.decide(requestAllTypes(10)) }.code }
        runCurrent()
        assertEquals("BUSY", assertFailsWith<JevFailure> { service.decide(requestAllTypes(500)) }.code)
        advanceTimeBy(11)
        runCurrent()
        assertEquals("TIMEOUT", queued.await())
        active.cancelAndJoin()
    }

    @Test
    fun `close cancels active and queued operations`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val service = JevDecisionService(
            JevKeyResolver { "key" },
            transport,
            JevLimits(maxConcurrentRequests = 1, maxWaitingRequests = 1),
        )
        val active = async { assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code }
        entered.await()
        val queued = async { assertFailsWith<JevFailure> { service.decide(requestAllTypes()) }.code }
        runCurrent()
        service.close()
        assertEquals("SERVICE_UNAVAILABLE", active.await())
        assertEquals("SERVICE_UNAVAILABLE", queued.await())
    }

    @Test
    fun `HTTP timeout maps safely without retaining network exception cause`() = runTest {
        val timeoutTransport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray {
                throw HttpTimeoutException("private-host.example")
            }
        }
        val failure = assertFailsWith<JevFailure> {
            JevDecisionService(JevKeyResolver { "key" }, timeoutTransport).decide(requestAllTypes())
        }
        assertEquals("TIMEOUT", failure.code)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `generic transport exception maps safely without retaining details`() = runTest {
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray {
                throw IllegalStateException("private-network.example:8443")
            }
        }
        val failure = assertFailsWith<JevFailure> {
            JevDecisionService(JevKeyResolver { "key" }, transport).decide(requestAllTypes())
        }
        assertEquals("NETWORK_ERROR", failure.code)
        assertEquals("Could not reach OpenRouter", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `shorter caller deadline remains caller cancellation`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val transport = object : JevTransport {
            override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray =
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelled.complete(Unit) }
                }
        }
        val service = JevDecisionService(
            JevKeyResolver { "key" },
            transport,
            JevLimits(minTimeoutMs = 1, maxTimeoutMs = 2_000),
        )
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { service.decide(requestAllTypes(1_000)) }
        }
        assertTrue(cancelled.isCompleted)
    }
}
