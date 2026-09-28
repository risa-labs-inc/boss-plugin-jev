package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.launch

class JevPluginServices(
    val context: PluginContext,
    transport: JevTransport = JdkJevTransport(),
    /** Test seam; production resolves the key from Secret Manager → AI Providers. */
    private val keyResolverOverride: JevKeyResolver? = null,
    /** Test seam; production reaches chat models through the AI Gateway. */
    chatOverride: JevChatClient? = null,
    /** Test seam; production uses the host's plugin storage. */
    storageOverride: JevPresetBackend? = null,
) {
    private val keyResolver = keyResolverOverride ?: JevKeyResolver {
        runCatching {
            context.llmProvider
                ?.configuredProviders()
                .orEmpty()
                .firstOrNull { it.providerId.equals(OPENROUTER_PROVIDER_ID, ignoreCase = true) }
                ?.apiKey
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }
    val service = JevDecisionService(keyResolver = keyResolver, transport = transport)
    /** Plugin key-value storage: presets and the remembered compose model. */
    internal val storage: JevPresetBackend? = storageOverride ?: runCatching {
        context.pluginStorageFactory
            ?.createStorage(JevDynamicPlugin.PLUGIN_ID)
            ?.let(::PluginPresetBackend)
    }.getOrNull()
    val presets: JevPresetRepository? = storage?.let(::JevPresetRepository)
    val chat: JevChatClient = chatOverride ?: GatewayJevChatClient(
        gateway = { context.optionalHostValue { getPluginAPI(AiGatewayAPI::class.java) } },
        llmProvider = { context.optionalHostValue { llmProvider } },
    )
    val composer = JevComposer(chat, service.limits)

    private val playgroundDelegate = lazy { JevPlaygroundViewModel(this) }

    /** One per plugin activation, so drafts survive the sidebar panel being hidden or recreated. */
    val playground: JevPlaygroundViewModel by playgroundDelegate

    fun hasOpenRouterKey(): Boolean = keyResolverOverride?.let { !it.resolveOpenRouterKey().isNullOrBlank() }
        ?: runCatching {
            context.llmProvider?.configuredProviders().orEmpty().any {
                it.providerId.equals(OPENROUTER_PROVIDER_ID, ignoreCase = true) && it.apiKey.isNotBlank()
            }
        }.getOrDefault(false)

    /**
     * AI providers live in the Secret Manager panel. The event selects its AI tab (also for a
     * panel constructed just after); openPanel brings the panel up. Settings is the fallback
     * only for hosts that expose neither.
     */
    fun openAiProviderSettings(): Boolean {
        val windowId = context.optionalHostValue { windowId } ?: return false
        val bus = context.optionalHostValue { applicationEventBus }
        val panels = context.optionalHostValue { panelEventProvider }
        if (bus == null && panels == null) {
            val settings = context.optionalHostValue { settingsProvider } ?: return false
            return runCatching { settings.openSettings(windowId, "LLM_PROVIDERS") }.isSuccess
        }
        val published = bus != null && runCatching {
            bus.publish(CustomPluginEvent(JevDynamicPlugin.PLUGIN_ID, OPEN_AI_EVENT, mapOf("windowId" to windowId)))
        }.isSuccess
        val launched = panels != null && runCatching {
            context.pluginScope.launch { runCatching { panels.openPanel(SECRET_MANAGER_PANEL, windowId) } }
        }.isSuccess
        return published || launched
    }

    fun dispose() {
        if (playgroundDelegate.isInitialized()) playground.dispose()
        service.close()
    }

    companion object {
        const val OPENROUTER_PROVIDER_ID = "OPENROUTER"
        const val OPEN_AI_EVENT = "secret-manager.open-ai"
        val SECRET_MANAGER_PANEL = PanelId("secret-manager", 24)
    }
}

/** Optional host accessors can fail independently; lose that feature without losing the panel. */
internal inline fun <T> PluginContext.optionalHostValue(read: PluginContext.() -> T?): T? =
    runCatching { read() }.getOrNull()
