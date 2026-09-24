package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class JevDynamicPlugin : DynamicPlugin {
    override val pluginId = PLUGIN_ID
    override val displayName = "Jev"
    override val version = "0.2.0"
    override val description =
        "Ask a decision model structured questions from a sidebar panel or the BOSS MCP server, using the OpenRouter key already configured in BOSS."
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-jev"

    private var context: PluginContext? = null
    private var services: JevPluginServices? = null

    override fun register(context: PluginContext) {
        val services = JevPluginServices(context).also { this.services = it }
        this.context = context
        context.panelRegistry.registerPanel(JevPanelInfo) { componentContext, info ->
            JevPanelComponent(componentContext, info, services)
        }
        context.registerMcpToolProvider(JevMcpToolProvider(pluginId, services.service, services.presets) { services.playground })
    }

    override fun dispose() {
        context?.unregisterMcpToolProvider(pluginId)
        context?.panelRegistry?.unregisterPanel(JevPanelInfo.id)
        services?.dispose()
        services = null
        context = null
    }

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.jev"
    }
}
