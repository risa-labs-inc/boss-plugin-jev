package ai.rever.boss.plugin.dynamic.jev

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class JevRequest(
    val state: JsonElement,
    val questions: JsonObject,
    val timeoutMs: Long = JevLimits.DEFAULT_TIMEOUT_MS,
    val model: String = JevModelCatalog.DEFAULT.id,
    /** Null means infer it from the catalog; the service records the resolved one. */
    val providerId: String? = null,
)

/** A decision model the user can pick. [providerId] names the gateway provider that serves it. */
data class JevModelOption(
    val id: String,
    val label: String,
    val providerId: String,
    val providerLabel: String,
    /** Served on this machine, so the request never leaves it. */
    val local: Boolean = false,
    val reachable: Boolean = true,
    /** Why the provider is unusable, or where it listens. */
    val detail: String? = null,
    /** The provider is unusable only because no credential is configured. */
    val needsCredential: Boolean = false,
)

data class JevDecision(
    val response: JsonObject,
    val latencyMs: Long,
    /** The model that answered, as the catalog had it at call time. */
    val model: JevModelOption,
)

enum class JevRunSource { PLAYGROUND, MCP }

/** One completed call, kept in memory for the plugin activation only. */
data class JevRunRecord(
    val id: Long,
    val at: Instant,
    val source: JevRunSource,
    /** Carries the resolved providerId. */
    val request: JevRequest,
    val decision: JevDecision,
) {
    val providerId: String get() = decision.model.providerId
}

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
