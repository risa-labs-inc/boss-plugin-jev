package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionAPI
import ai.rever.boss.plugin.api.AiDecisionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A gateway provider as the last refresh saw it. */
data class JevProviderStatus(
    val providerId: String,
    val label: String,
    val local: Boolean,
    val reachable: Boolean,
    val detail: String?,
)

sealed interface JevModelLookup {
    data class Found(val option: JevModelOption) : JevModelLookup
    data class Unknown(val message: String, val path: String) : JevModelLookup
    data class Ambiguous(val model: String, val providers: List<String>) : JevModelLookup
}

/** Whether the selected model can be asked, and what the panel shows when it cannot. */
sealed interface JevReadiness {
    data object Ready : JevReadiness
    /** The selected model is on OpenRouter, and OpenRouter has no key. */
    data object NeedsOpenRouterKey : JevReadiness
    data class Unavailable(val detail: String) : JevReadiness
}

/**
 * Decision models the AI Gateway serves. [DEFAULT] is always listed, before the first refresh
 * and without a gateway, so presets and MCP defaults stay stable. Reachability is for display:
 * a call to an unreachable model still goes to the gateway, whose error is the truth.
 */
class JevModelCatalog(private val api: () -> AiDecisionAPI? = { null }) {
    private val lock = Mutex()
    private val _options = MutableStateFlow(listOf(DEFAULT))
    private val _providers = MutableStateFlow(emptyList<JevProviderStatus>())
    private val _gatewayAvailable = MutableStateFlow(true)
    @Volatile var refreshed: Boolean = false
        private set
    /** The gateway itself listed OpenRouter as unreachable, which means no key is configured. */
    @Volatile private var openRouterKeyMissing = false

    /** OpenRouter first, then providers in gateway order. */
    val options: StateFlow<List<JevModelOption>> = _options.asStateFlow()
    val providers: StateFlow<List<JevProviderStatus>> = _providers.asStateFlow()
    /** False once a refresh finds no AI Gateway decision API. */
    val gatewayAvailable: StateFlow<Boolean> = _gatewayAvailable.asStateFlow()

    suspend fun refresh(): List<JevModelOption> = lock.withLock {
        val gateway = runCatching { api() }.getOrNull()
        val listed: List<AiDecisionProvider>? = gateway?.let {
            try {
                it.decisionProviders()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Throwable: a gateway built against another api can fail with a linkage error.
                null
            }
        }
        openRouterKeyMissing = listed.orEmpty().any { it.providerId == OPENROUTER && !it.reachable }
        when {
            gateway == null -> publish(listOf(DEFAULT.copy(reachable = false, detail = GATEWAY_HINT)), emptyList(), gateway = false)
            listed == null -> publish(listOf(DEFAULT.copy(reachable = false, detail = LIST_FAILED)), emptyList(), gateway = true)
            else -> publish(merge(listed, _options.value), listed.map(::status), gateway = true)
        }
        refreshed = true
        _options.value
    }

    /** Refreshes when [model] is not known yet, so a model pulled since the last refresh is accepted. */
    suspend fun ensure(model: String, providerId: String?) {
        val found = lookup(model, providerId)
        if (found is JevModelLookup.Unknown) refresh()
    }

    /** Refreshes once per activation; later refreshes are explicit. */
    suspend fun ensureLoaded() {
        if (!refreshed) refresh()
    }

    /** A unique id infers its provider; an id several providers serve needs [providerId]. */
    fun lookup(model: String, providerId: String?): JevModelLookup {
        val all = _options.value
        val byId = all.filter { it.id == model }
        if (providerId != null) {
            byId.firstOrNull { it.providerId.equals(providerId, ignoreCase = true) }?.let { return JevModelLookup.Found(it) }
            val provider = providerStatus(providerId)
                ?: return JevModelLookup.Unknown("Unknown provider '$providerId'; available: ${providerIds(all)}", "provider")
            // A provider that is down lists no models; the gateway's error says why when called.
            if (!provider.reachable) {
                return JevModelLookup.Found(
                    JevModelOption(model, model, provider.providerId, provider.label, provider.local, reachable = false, detail = provider.detail),
                )
            }
            val served = all.filter { it.providerId == provider.providerId }.joinToString { it.id }.ifEmpty { "no models" }
            return JevModelLookup.Unknown("${provider.label} does not serve '$model'; it serves: $served", "model")
        }
        return when (byId.size) {
            0 -> JevModelLookup.Unknown("Unknown model '$model'; available: ${describe(all)}${downHint()}", "model")
            1 -> JevModelLookup.Found(byId.single())
            else -> JevModelLookup.Ambiguous(model, byId.map { it.providerId })
        }
    }

    fun find(model: String, providerId: String?): JevModelOption? =
        (lookup(model, providerId) as? JevModelLookup.Found)?.option

    fun isDecisionModel(modelId: String): Boolean = _options.value.any { it.id == modelId }

    fun readiness(option: JevModelOption?): JevReadiness = when {
        option == null || option.reachable -> JevReadiness.Ready
        option.providerId == OPENROUTER && openRouterKeyMissing -> JevReadiness.NeedsOpenRouterKey
        else -> JevReadiness.Unavailable(option.detail ?: "${option.providerLabel} is not reachable")
    }

    private fun publish(options: List<JevModelOption>, providers: List<JevProviderStatus>, gateway: Boolean) {
        val listed = providers.map { it.providerId }.toSet()
        // Every option's provider is listed, so the picker can group and explain all of them.
        val synthesized = options.filter { it.providerId !in listed }.distinctBy { it.providerId }
            .map { JevProviderStatus(it.providerId, it.providerLabel, it.local, it.reachable, it.detail) }
        _providers.value = (synthesized + providers).sortedBy { it.providerId != OPENROUTER }
        _gatewayAvailable.value = gateway
        _options.value = options
    }

    private fun providerStatus(id: String) = _providers.value.firstOrNull { it.providerId.equals(id, ignoreCase = true) }

    private fun providerIds(all: List<JevModelOption>) =
        (_providers.value.map { it.providerId } + all.map { it.providerId }).distinct().joinToString()

    private fun downHint(): String = _providers.value.filter { !it.reachable }
        .joinToString("") { "; ${it.label} is not reachable${it.detail?.let { d -> ": $d" }.orEmpty()}" }

    companion object {
        const val OPENROUTER = "OPENROUTER"
        const val GATEWAY_HINT = "Install or update the AI Gateway plugin"
        private const val LIST_FAILED = "The AI Gateway could not list decision models"

        val DEFAULT = JevModelOption("typesafe/jev-1.13", "jev-1.13", OPENROUTER, "OpenRouter")

        private fun status(p: AiDecisionProvider) = JevProviderStatus(p.providerId, p.providerName, p.local, p.reachable, p.detail)

        private fun describe(all: List<JevModelOption>) = all.joinToString { "${it.id} (${it.providerLabel})" }

        /**
         * The gateway's models, OpenRouter first, with [DEFAULT] always present. An unreachable
         * provider lists no models, so the ones it served before stay, disabled.
         */
        internal fun merge(providers: List<AiDecisionProvider>, previous: List<JevModelOption>): List<JevModelOption> {
            val out = providers.flatMap { p ->
                val models = p.models.map { m ->
                    JevModelOption(m.id, m.displayName.ifBlank { m.id }, p.providerId, p.providerName, p.local, p.reachable, p.detail)
                }
                val kept = if (!p.reachable && models.isEmpty()) {
                    previous.filter { it.providerId == p.providerId }
                        .map { it.copy(providerLabel = p.providerName, local = p.local, reachable = false, detail = p.detail) }
                } else {
                    emptyList()
                }
                models + kept
            }.toMutableList()
            if (out.none { it.id == DEFAULT.id && it.providerId == OPENROUTER }) {
                val openRouter = providers.firstOrNull { it.providerId == OPENROUTER }
                out.add(0, DEFAULT.copy(
                    providerLabel = openRouter?.providerName ?: DEFAULT.providerLabel,
                    reachable = openRouter?.reachable ?: true,
                    detail = openRouter?.detail,
                ))
            }
            return out.distinctBy { it.providerId to it.id }.sortedBy { it.providerId != OPENROUTER }
        }
    }
}
