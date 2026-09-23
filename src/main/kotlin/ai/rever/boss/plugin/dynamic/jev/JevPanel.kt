package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

object JevPanelInfo : PanelInfo {
    override val id = PanelId("jev", 60, JevDynamicPlugin.PLUGIN_ID)
    override val displayName = "Jev"
    override val icon = Icons.Outlined.Psychology
    override val defaultSlotPosition = right.top.bottom
}

/** Thin bridge; the view model belongs to the plugin activation, not to this component. */
class JevPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val services: JevPluginServices,
) : PanelComponentWithUI, ComponentContext by ctx {
    @Composable
    override fun Content() = BossTheme { JevPlaygroundScreen(services.playground) }
}
