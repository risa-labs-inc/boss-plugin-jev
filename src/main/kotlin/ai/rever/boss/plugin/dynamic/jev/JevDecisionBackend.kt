package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionAPI
import ai.rever.boss.plugin.api.AiDecisionException
import ai.rever.boss.plugin.api.AiDecisionRequest
import kotlinx.coroutines.CancellationException

/** Where a decision request goes. Production is the AI Gateway; a plugin never calls a model API itself. */
fun interface JevDecisionBackend {
    /** Returns the provider's response body. Throws [JevFailure]. */
    suspend fun decide(model: JevModelOption, body: String, timeoutMs: Long, maxResponseBytes: Int): String
}

/** Resolved per call: plugin load order is not guaranteed, so a cached null would stick. */
class GatewayJevDecisionBackend(private val api: () -> AiDecisionAPI?) : JevDecisionBackend {
    override suspend fun decide(model: JevModelOption, body: String, timeoutMs: Long, maxResponseBytes: Int): String {
        val gateway = runCatching { api() }.getOrNull() ?: throw gatewayUnavailable()
        val result = try {
            gateway.decide(AiDecisionRequest(model.providerId, body, timeoutMs, maxResponseBytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            throw gatewayUnavailable()
        }
        return result.getOrElse { error ->
            if (error is CancellationException) throw error
            throw JevDecisionErrors.map(error, model)
        }.body
    }

    private fun gatewayUnavailable() = JevFailure(JevDecisionErrors.GATEWAY_UNAVAILABLE, JevModelCatalog.GATEWAY_HINT)
}

/** Gateway codes to Jev's. Jev keeps INVALID_INPUT for its own validation. */
internal object JevDecisionErrors {
    const val GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE"
    const val MISSING_CREDENTIAL = "MISSING_CREDENTIAL"
    const val UPSTREAM_INVALID_INPUT = "UPSTREAM_INVALID_INPUT"
    const val UPSTREAM_ERROR = "UPSTREAM_ERROR"
    const val LOCAL_UNAVAILABLE = "LOCAL_UNAVAILABLE"

    private val KEPT = setOf(
        AiDecisionException.MISSING_CREDENTIAL,
        AiDecisionException.LOCAL_UNAVAILABLE,
        AiDecisionException.MODEL_NOT_FOUND,
        AiDecisionException.AUTH_ERROR,
        AiDecisionException.RATE_LIMITED,
        AiDecisionException.TIMEOUT,
        AiDecisionException.NETWORK_ERROR,
        AiDecisionException.RESPONSE_TOO_LARGE,
        AiDecisionException.UPSTREAM_ERROR,
    )

    fun code(gatewayCode: String?): String = when {
        gatewayCode == AiDecisionException.INVALID_INPUT -> UPSTREAM_INVALID_INPUT
        gatewayCode != null && gatewayCode in KEPT -> gatewayCode
        else -> UPSTREAM_ERROR
    }

    /**
     * An [AiDecisionException] message is written for a person and names the provider; bounded so
     * no body is echoed. Any other failure's message may carry internals, so it is not shown.
     */
    fun map(error: Throwable, model: JevModelOption): JevFailure {
        val gatewayError = error as? AiDecisionException
        val code = code(gatewayError?.code)
        val message = gatewayError?.message?.trim()?.take(MAX_MESSAGE_CHARS)?.takeIf { it.isNotEmpty() } ?: fallback(code, model)
        return JevFailure(code, message)
    }

    private fun fallback(code: String, model: JevModelOption): String {
        val who = model.providerLabel
        return when (code) {
            MISSING_CREDENTIAL -> "$who has no credential; add one in Secret Manager → AI Providers"
            UPSTREAM_INVALID_INPUT -> "$who rejected the request"
            LOCAL_UNAVAILABLE -> "No local decision runtime answered"
            AiDecisionException.MODEL_NOT_FOUND -> "$who does not have '${model.id}'"
            AiDecisionException.AUTH_ERROR -> "$who rejected the configured credential"
            AiDecisionException.RATE_LIMITED -> "$who rate limit reached; try again later"
            AiDecisionException.TIMEOUT -> "$who did not answer in time"
            AiDecisionException.NETWORK_ERROR -> "Could not reach $who"
            AiDecisionException.RESPONSE_TOO_LARGE -> "$who returned a response over the plugin limit"
            else -> "$who could not complete the request"
        }
    }

    private const val MAX_MESSAGE_CHARS = 240
}
