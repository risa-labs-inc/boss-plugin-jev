package ai.rever.boss.plugin.dynamic.jev

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class JevRequest(
    val state: JsonElement,
    val questions: JsonObject,
    val timeoutMs: Long = JevLimits.DEFAULT_TIMEOUT_MS,
    val model: String = JevModelCatalog.DEFAULT.id,
)

/** A decision model the user can pick. [providerId] names the backend that serves it. */
data class JevModelOption(
    val id: String,
    val label: String,
    val providerId: String,
    val providerLabel: String,
)

/**
 * Models Jev can call. Only OpenRouter's jev-1.13 is served today; local and other
 * decision models join this list once the service can route to their backend.
 */
object JevModelCatalog {
    val DEFAULT = JevModelOption("typesafe/jev-1.13", "jev-1.13", JevPluginServices.OPENROUTER_PROVIDER_ID, "OpenRouter")
    val all: List<JevModelOption> = listOf(DEFAULT)
    fun find(id: String): JevModelOption? = all.firstOrNull { it.id == id }
}

data class JevDecision(
    val response: JsonObject,
    val latencyMs: Long,
)

enum class JevRunSource { PLAYGROUND, MCP }

/** One completed call, kept in memory for the plugin activation only. */
data class JevRunRecord(
    val id: Long,
    val at: Instant,
    val source: JevRunSource,
    val request: JevRequest,
    val decision: JevDecision,
)

data class JevLimits(
    val maxRequestBytes: Int = 256 * 1024,
    val maxResponseBytes: Int = 1024 * 1024,
    val maxQuestions: Int = 32,
    val maxConcurrentRequests: Int = 4,
    val maxWaitingRequests: Int = 16,
    val minTimeoutMs: Long = 1_000,
    val maxTimeoutMs: Long = 120_000,
    val maxRunHistory: Int = 20,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/** A validation problem at a JSON path such as `questions.route.criteria.2`. */
data class JevIssue(val path: List<String>, val message: String) {
    val pathText: String get() = path.joinToString(".")
}

class JevFailure(
    val code: String,
    override val message: String,
    val path: String? = null,
) : Exception(message)

fun interface JevKeyResolver {
    /** Return the credential only. Implementations must never retain or log it. */
    fun resolveOpenRouterKey(): String?
}

interface JevTransport {
    suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray
    fun cancelAll() {}
}
