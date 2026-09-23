package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.PluginContext

class JevPluginServices(
    val context: PluginContext,
    transport: JevTransport = JdkJevTransport(),
    /** Test seam; production resolves the key from BOSS AI Providers. */
    private val keyResolverOverride: JevKeyResolver? = null,
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
    val presets: JevPresetRepository? = runCatching {
        context.pluginStorageFactory
            ?.createStorage(JevDynamicPlugin.PLUGIN_ID)
            ?.let(::PluginPresetBackend)
            ?.let(::JevPresetRepository)
    }.getOrNull()

    private val playgroundDelegate = lazy { JevPlaygroundViewModel(this) }

    /** One per plugin activation, so drafts survive the sidebar panel being hidden or recreated. */
    val playground: JevPlaygroundViewModel by playgroundDelegate

    fun hasOpenRouterKey(): Boolean = keyResolverOverride?.let { !it.resolveOpenRouterKey().isNullOrBlank() }
        ?: runCatching {
            context.llmProvider?.configuredProviders().orEmpty().any {
                it.providerId.equals(OPENROUTER_PROVIDER_ID, ignoreCase = true) && it.apiKey.isNotBlank()
            }
        }.getOrDefault(false)

    fun openAiProviderSettings(): Boolean {
        val settings = context.settingsProvider ?: return false
        val windowId = context.windowId ?: return false
        return runCatching { settings.openSettings(windowId, "LLM_PROVIDERS") }.isSuccess
    }

    fun dispose() {
        if (playgroundDelegate.isInitialized()) playground.dispose()
        service.close()
    }

    companion object {
        const val OPENROUTER_PROVIDER_ID = "OPENROUTER"
    }
}
