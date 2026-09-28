package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SettingsProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class JevProviderNavigationTest {
    private class RecordingBus : ApplicationEventBus {
        val published = mutableListOf<ApplicationEvent>()
        override fun events(): Flow<ApplicationEvent> = emptyFlow()
        override fun <T : ApplicationEvent> eventsOfType(type: Class<T>): Flow<T> = emptyFlow()
        override fun publish(event: ApplicationEvent) { published += event }
    }

    private class RecordingPanels : PanelEventProvider {
        val opened = mutableListOf<Pair<PanelId, String>>()
        override suspend fun closePanel(panelId: PanelId, windowId: String) {}
        override suspend fun openPanel(panelId: PanelId, windowId: String) { opened += panelId to windowId }
    }

    private class RecordingSettings : SettingsProvider {
        val sections = mutableListOf<String>()
        override fun openSettings(windowId: String, section: String) { sections += section }
    }

    private fun context(
        window: String? = "w1",
        bus: ApplicationEventBus? = null,
        panels: PanelEventProvider? = null,
        settings: SettingsProvider? = null,
    ) = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val windowId: String? get() = window
        override val applicationEventBus: ApplicationEventBus? get() = bus
        override val panelEventProvider: PanelEventProvider? get() = panels
        override val settingsProvider: SettingsProvider? get() = settings
    }

    @Test
    fun `AI provider actions open Secret Manager, not Settings`() {
        val bus = RecordingBus()
        val panels = RecordingPanels()
        val settings = RecordingSettings()
        val services = JevPluginServices(context(bus = bus, panels = panels, settings = settings), CapturingTransport(), JevKeyResolver { null })

        assertTrue(services.openAiProviderSettings())

        val event = bus.published.single() as CustomPluginEvent
        assertEquals("secret-manager.open-ai", event.eventName)
        assertEquals(mapOf("windowId" to "w1"), event.payload)
        assertEquals(listOf(PanelId("secret-manager", 24) to "w1"), panels.opened)
        assertTrue(settings.sections.isEmpty())
        services.dispose()
    }

    @Test
    fun `either channel alone is enough`() {
        val bus = RecordingBus()
        assertTrue(JevPluginServices(context(bus = bus), CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
        assertEquals(1, bus.published.size)

        val panels = RecordingPanels()
        assertTrue(JevPluginServices(context(panels = panels), CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
        assertEquals(1, panels.opened.size)
    }

    @Test
    fun `Settings is the fallback only when neither channel exists`() {
        val settings = RecordingSettings()
        assertTrue(JevPluginServices(context(settings = settings), CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
        assertEquals(listOf("LLM_PROVIDERS"), settings.sections)

        assertFalse(JevPluginServices(context(), CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
        assertFalse(JevPluginServices(context(window = null, bus = RecordingBus()), CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
    }

    @Test
    fun `a throwing host accessor does not break navigation`() {
        val panels = RecordingPanels()
        val ctx = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            override val windowId: String? get() = "w2"
            override val applicationEventBus: ApplicationEventBus? get() = throw NoSuchMethodError("old host")
            override val panelEventProvider: PanelEventProvider? get() = panels
        }
        assertTrue(JevPluginServices(ctx, CapturingTransport(), JevKeyResolver { null }).openAiProviderSettings())
        assertEquals("w2", panels.opened.single().second)
    }
}
