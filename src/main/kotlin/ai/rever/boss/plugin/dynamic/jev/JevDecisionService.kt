package ai.rever.boss.plugin.dynamic.jev

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
    private val backend: JevDecisionBackend,
    val catalog: JevModelCatalog = JevModelCatalog(),
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
        val resolved = request.copy(providerId = decision.model.providerId)
        val record = JevRunRecord(runIds.incrementAndGet(), Instant.now(), source, resolved, decision)
        _runs.update { (listOf(record) + it).take(limits.maxRunHistory) }
        return decision
    }

    private suspend fun execute(request: JevRequest): JevDecision = supervisorScope {
        catalog.ensure(request.model, request.providerId)
        JevValidation.request(request, limits, catalog)
        // Exactly this provider; the gateway never falls back, and neither does Jev.
        val model = catalog.find(request.model, request.providerId)
            ?: throw JevFailure("INVALID_INPUT", "Unknown model '${request.model}'", "model")
        val payload = buildJsonObject {
            put("model", request.model)
            put("state", request.state)
            put("questions", request.questions)
        }
        val body = payload.toString()
        if (body.encodeToByteArray().size > limits.maxRequestBytes) {
            throw JevFailure("INPUT_TOO_LARGE", "Request exceeds plugin limit ${limits.maxRequestBytes} bytes")
        }
        admit()

        val operation = async(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(request.timeoutMs) {
                permits.withPermit {
                    ensureOpen()
                    val started = System.nanoTime()
                    val raw = backend.decide(model, body, request.timeoutMs, limits.maxResponseBytes)
                    if (raw.encodeToByteArray().size > limits.maxResponseBytes) {
                        throw JevFailure("RESPONSE_TOO_LARGE", "Response exceeds plugin limit ${limits.maxResponseBytes} bytes")
                    }
                    val parsed = try {
                        json.parseToJsonElement(raw) as? JsonObject
                    } catch (_: Exception) {
                        null
                    } ?: throw JevFailure("MALFORMED_RESPONSE", "${model.providerLabel} returned invalid JSON")
                    JevValidation.response(parsed, request.questions)
                    JevDecision(parsed, (System.nanoTime() - started) / 1_000_000, model)
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
        } catch (_: Exception) {
            throw JevFailure(JevDecisionErrors.UPSTREAM_ERROR, "${model.providerLabel} could not complete the request")
        }
    }

    fun close() {
        val pending = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            operations.toList()
        }
        pending.forEach { it.cancel(ServiceClosedCancellation()) }
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
}
