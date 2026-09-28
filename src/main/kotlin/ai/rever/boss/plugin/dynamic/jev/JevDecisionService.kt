package ai.rever.boss.plugin.dynamic.jev

import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JevDecisionService(
    private val keyResolver: JevKeyResolver,
    private val transport: JevTransport = JdkJevTransport(),
    val limits: JevLimits = JevLimits(),
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val permits = Semaphore(limits.maxConcurrentRequests)
    private val lifecycleLock = Any()
    private val operations = mutableSetOf<Job>()
    private var admitted = 0
    private var closed = false
    private val runIds = AtomicLong()
    private val _runs = MutableStateFlow<List<JevRunRecord>>(emptyList())

    /** Newest first. Shared by the panel and MCP; never persisted. */
    val runs: StateFlow<List<JevRunRecord>> = _runs.asStateFlow()

    suspend fun decide(request: JevRequest, source: JevRunSource = JevRunSource.PLAYGROUND): JevDecision {
        val decision = execute(request)
        val record = JevRunRecord(runIds.incrementAndGet(), Instant.now(), source, request, decision)
        _runs.update { (listOf(record) + it).take(limits.maxRunHistory) }
        return decision
    }

    private suspend fun execute(request: JevRequest): JevDecision = supervisorScope {
        JevValidation.request(request, limits)
        val payload = buildJsonObject {
            put("model", request.model)
            put("state", request.state)
            put("questions", request.questions)
        }
        val bytes = payload.toString().encodeToByteArray()
        if (bytes.size > limits.maxRequestBytes) {
            throw JevFailure("INPUT_TOO_LARGE", "Request exceeds plugin limit ${limits.maxRequestBytes} bytes")
        }
        admit()

        val operation = async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(request.timeoutMs) {
                permits.withPermit {
                    ensureOpen()
                    val key = keyResolver.resolveOpenRouterKey()?.takeIf { it.isNotBlank() }
                        ?: throw JevFailure(
                            "MISSING_OPENROUTER_KEY",
                            "Configure an OpenRouter key and select any model in Secret Manager → AI Providers",
                        )
                    val started = System.nanoTime()
                    transport.post(bytes, key, request.timeoutMs, limits.maxResponseBytes)
                        .let { raw ->
                            val parsed = try {
                                json.parseToJsonElement(raw.decodeToString()) as? JsonObject
                            } catch (_: Exception) {
                                null
                            } ?: throw JevFailure("MALFORMED_RESPONSE", "OpenRouter returned invalid JSON")
                            JevValidation.response(parsed, request.questions)
                            JevDecision(parsed, (System.nanoTime() - started) / 1_000_000)
                        }
                }
            } ?: throw JevFailure("TIMEOUT", "Jev request timed out")
        }
        synchronized(lifecycleLock) {
            if (closed) {
                admitted--
                operation.cancel()
                throw JevFailure("SERVICE_UNAVAILABLE", "Jev is unloading")
            }
            operations += operation
        }
        operation.invokeOnCompletion {
            synchronized(lifecycleLock) {
                operations -= operation
                admitted--
            }
        }
        operation.start()

        try {
            operation.await()
        } catch (_: ServiceClosedCancellation) {
            throw JevFailure("SERVICE_UNAVAILABLE", "Jev is unloading")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: JevFailure) {
            throw failure
        } catch (_: HttpTimeoutException) {
            throw JevFailure("TIMEOUT", "Jev request timed out")
        } catch (_: Exception) {
            throw JevFailure("NETWORK_ERROR", "Could not reach OpenRouter")
        }
    }

    fun close() {
        val pending = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            operations.toList()
        }
        pending.forEach { it.cancel(ServiceClosedCancellation()) }
        transport.cancelAll()
        _runs.value = emptyList()
    }

    private fun admit() = synchronized(lifecycleLock) {
        if (closed) throw JevFailure("SERVICE_UNAVAILABLE", "Jev is unloading")
        if (admitted >= limits.maxConcurrentRequests + limits.maxWaitingRequests) {
            throw JevFailure("BUSY", "Jev has too many requests in progress; try again later")
        }
        admitted++
    }

    private fun ensureOpen() = synchronized(lifecycleLock) {
        if (closed) throw JevFailure("SERVICE_UNAVAILABLE", "Jev is unloading")
    }

    private class ServiceClosedCancellation : CancellationException("Jev is unloading")

    companion object {
        val DEFAULT_MODEL: String get() = JevModelCatalog.DEFAULT.id
    }
}
