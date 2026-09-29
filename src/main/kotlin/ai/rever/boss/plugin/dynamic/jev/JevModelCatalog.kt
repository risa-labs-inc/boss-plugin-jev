package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionAPI
import ai.rever.boss.plugin.api.AiDecisionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A gateway provider as the last refresh saw it. */
data class JevProviderStatus(
    val providerId: String,
    val label: String,
    val local: Boolean,
    val reachable: Boolean,
    val detail: String?,
    val needsCredential: Boolean = false,
)

sealed interface JevModelLookup {
    data class Found(val option: JevModelOption) : JevModelLookup
    data class Unknown(val message: String, val path: String) : JevModelLookup
    data class Ambiguous(val model: String, val providers: List<String>) : JevModelLookup
}

/** Whether the selected model can be asked, and what the panel shows when it cannot. */
sealed interface JevReadiness {
    data object Ready : JevReadiness
    /** The selected model's provider reports that only a credential is missing. */
    data object NeedsCredential : JevReadiness
    data class Unavailable(val detail: String) : JevReadiness
}

/**
 * Decision models the AI Gateway serves. [DEFAULT] is always listed, before the first refresh
 * and without a gateway, so presets and MCP defaults stay stable. Reachability is for display:
 * a call to an unreachable model still goes to the gateway, whose error is the truth.
 */
class JevModelCatalog(private val api: () -> AiDecisionAPI? = { null }) {
    private val gate = Any()
    /** Completes true when the in-flight refresh published, false when its caller was cancelled first. */
    private var inFlight: CompletableDeferred<Boolean>? = null
    private var closed = false
    private val _options = MutableStateFlow(listOf(DEFAULT))
    private val _providers = MutableStateFlow(emptyList<JevProviderStatus>())
    private val _gatewayAvailable = MutableStateFlow(true)
    @Volatile var refreshed: Boolean = false
        private set

    /** OpenRouter first, then providers in gateway order. */
    val options: StateFlow<List<JevModelOption>> = _options.asStateFlow()
    val providers: StateFlow<List<JevProviderStatus>> = _providers.asStateFlow()
    /** False once a refresh finds no AI Gateway decision API. */
    val gatewayAvailable: StateFlow<Boolean> = _gatewayAvailable.asStateFlow()

    /**
     * Concurrent callers share one in-flight probe. It runs in the first caller's coroutine, so
     * that caller's timeout and cancellation bound it; after [close] nothing is probed.
     */
    suspend fun refresh(): List<JevModelOption> {
        while (true) {
            val (shared, leader) = synchronized(gate) {
                if (closed) return _options.value
                val running = inFlight
                if (running != null) running to false else CompletableDeferred<Boolean>().also { inFlight = it } to true
            }
            if (!leader) {
                if (shared.await()) return _options.value
                continue
            }
            var published = false
            try {
                probe()
                published = true
                return _options.value
            } finally {
                synchronized(gate) { if (inFlight === shared) inFlight = null }
                shared.complete(published)
            }
        }
    }

    /** No refresh starts after this; one already running finishes with its caller. */
    fun close() {
        synchronized(gate) { closed = true }
    }

    private suspend fun probe() {
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
        // Only one probe runs at a time, so publishing needs no lock.
        when {
            gateway == null -> publish(listOf(DEFAULT.copy(reachable = false, detail = GATEWAY_HINT)), emptyList(), gateway = false)
            listed == null -> publish(listOf(DEFAULT.copy(reachable = false, detail = LIST_FAILED)), emptyList(), gateway = true)
            else -> publish(merge(listed, _options.value), listed.map(::status), gateway = true)
        }
        refreshed = true
    }

    /**
     * Loads the catalog once, so resolution never depends on warmth, then refreshes when the
     * change does not resolve yet, so a model pulled since is accepted.
     */
    suspend fun ensure(current: JevBinding?, model: String, providerId: String?) {
        if (!refreshed) {
            refresh()
            return
        }
        val resolves = try {
            find(model, resolveChange(current, model, providerId)) != null
        } catch (_: JevFailure) {
            false
        }
        if (!resolves) refresh()
    }

    /** Refreshes once per activation, joining one already in flight; later refreshes are explicit. */
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
                    JevModelOption(
                        model, model, provider.providerId, provider.label, provider.local, reachable = false,
                        detail = provider.detail, needsCredential = provider.needsCredential,
                    ),
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

    /**
     * The one provider rule: the provider a model change goes to, or INVALID_INPUT. An explicit
     * [providerId] wins. [current]'s own model keeps its provider. Any other model goes to the
     * provider that serves it in the loaded catalog, even when that leaves [current]'s provider;
     * an id several providers serve keeps [current]'s provider only when it is one of them.
     * A provider is never picked from a cold catalog.
     */
    fun resolveChange(current: JevBinding?, model: String, providerId: String?, loaded: Boolean = refreshed): String {
        if (providerId != null) {
            // Unvalidated while cold: the named provider is the one called, and the call validates.
            if (!loaded) return providerStatus(providerId)?.providerId ?: providerId
            return when (val found = lookup(model, providerId)) {
                is JevModelLookup.Found -> found.option.providerId
                is JevModelLookup.Unknown -> refuse(found.path, found.message)
                is JevModelLookup.Ambiguous -> refuse("provider", ambiguous(found))
            }
        }
        if (current != null && model == current.model) return current.providerId ?: resolveChange(null, model, null, loaded)
        if (!loaded) refuse("model", "The model catalog did not load in time, so '$model' cannot be resolved to a provider; pass provider or retry")
        return when (val found = lookup(model, null)) {
            is JevModelLookup.Found -> found.option.providerId
            is JevModelLookup.Unknown -> refuse(found.path, found.message)
            is JevModelLookup.Ambiguous -> {
                val kept = current?.let { it.providerId ?: runCatching { resolveChange(null, it.model, null, loaded) }.getOrNull() }
                found.providers.firstOrNull { it.equals(kept, ignoreCase = true) } ?: refuse("provider", ambiguous(found))
            }
        }
    }

    fun find(model: String, providerId: String?): JevModelOption? =
        (lookup(model, providerId) as? JevModelLookup.Found)?.option

    fun isDecisionModel(modelId: String): Boolean = _options.value.any { it.id == modelId }

    fun readiness(option: JevModelOption?): JevReadiness = when {
        option == null -> JevReadiness.Ready
        option.needsCredential -> JevReadiness.NeedsCredential
        option.reachable -> JevReadiness.Ready
        else -> JevReadiness.Unavailable(option.detail ?: "${option.providerLabel} is not reachable")
    }

    private fun publish(options: List<JevModelOption>, providers: List<JevProviderStatus>, gateway: Boolean) {
        val listed = providers.map { it.providerId }.toSet()
        // Every option's provider is listed, so the picker can group and explain all of them.
        val synthesized = options.filter { it.providerId !in listed }.distinctBy { it.providerId }
            .map { JevProviderStatus(it.providerId, it.providerLabel, it.local, it.reachable, it.detail, it.needsCredential) }
        _providers.value = (synthesized + providers).sortedBy { it.providerId != OPENROUTER }
        _gatewayAvailable.value = gateway
        _options.value = options
    }

    private fun refuse(path: String, message: String): Nothing = throw JevFailure("INVALID_INPUT", message, path)

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

        private fun status(p: AiDecisionProvider) =
            JevProviderStatus(p.providerId, p.providerName, p.local, p.reachable, p.detail, p.needsCredential)

        internal fun ambiguous(found: JevModelLookup.Ambiguous) =
            "Model '${found.model}' is served by ${found.providers.joinToString()}; set provider to one of them"

        private fun describe(all: List<JevModelOption>) = all.joinToString { "${it.id} (${it.providerLabel})" }

        /**
         * The gateway's models, OpenRouter first, with [DEFAULT] always present. An unreachable
         * provider lists no models, so the ones it served before stay, disabled.
         */
        internal fun merge(providers: List<AiDecisionProvider>, previous: List<JevModelOption>): List<JevModelOption> {
            val out = providers.flatMap { p ->
                val models = p.models.map { m ->
                    JevModelOption(
                        m.id, m.displayName.ifBlank { m.id }, p.providerId, p.providerName, p.local, p.reachable, p.detail,
                        p.needsCredential,
                    )
                }
                val kept = if (!p.reachable && models.isEmpty()) {
                    previous.filter { it.providerId == p.providerId }
                        .map {
                            it.copy(
                                providerLabel = p.providerName, local = p.local, reachable = false, detail = p.detail,
                                needsCredential = p.needsCredential,
                            )
                        }
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
                    needsCredential = openRouter?.needsCredential ?: false,
                ))
            }
            return out.distinctBy { it.providerId to it.id }.sortedBy { it.providerId != OPENROUTER }
        }
    }
}
