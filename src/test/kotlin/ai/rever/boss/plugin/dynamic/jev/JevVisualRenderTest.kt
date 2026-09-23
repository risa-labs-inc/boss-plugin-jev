package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.EncodedImageFormat

/** Renders the real panel across sidebar and full-window widths for visual review. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class JevVisualRenderTest {
    private val routingResponse = """{
      "provider":"OpenRouter","model":"typesafe/jev-1.13",
      "answers":{
        "route":{"type":"choice","choice":"identity","probabilities":{"identity":0.91,"billing":0.02,"support":0.07},"confidence":0.88},
        "page_oncall":{"type":"noul","noul":0.73}
      },
      "usage":{"input_tokens":412,"output_tokens":38,"cost":0.00021}
    }"""

    @Test
    fun `renders the panel at every width`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        }
        val transport = CapturingTransport(routingResponse)
        val services = JevPluginServices(context, transport, JevKeyResolver { "test-key" })
        val viewModel = services.playground
        try {
            // Two runs, so the answer view shows the history strip and a change chip.
            val request = viewModel.state.value.request!!
            runBlocking { services.service.decide(request) }
            transport.response = routingResponse.replace("0.91", "0.84").replace("0.07", "0.14")
            runBlocking { services.service.decide(request, JevRunSource.MCP) }
            transport.response = routingResponse
            runBlocking { services.service.decide(request) }

            listOf(280, 360, 520).forEach { width ->
                viewModel.setPane(JevPane.ASK)
                render(viewModel, width, 1100, "ask-$width")
                viewModel.setPane(JevPane.ANSWER)
                render(viewModel, width, 1100, "answer-$width")
            }
            render(viewModel, 800, 1000, "two-pane-800")
            render(viewModel, 1440, 1000, "two-pane-1440")

            viewModel.useStarter(JevStarter.RUBRIC)
            viewModel.setPane(JevPane.ASK)
            viewModel.setLevel(0, 1, "")
            render(viewModel, 360, 900, "ask-issues-360")
        } finally {
            services.dispose()
            Dispatchers.resetMain()
        }
    }

    private fun render(viewModel: JevPlaygroundViewModel, width: Int, height: Int, name: String) {
        val output = Path.of("build/reports/visual/jev-$name.png")
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            BossTheme { JevPlaygroundScreen(viewModel) }
        }
        try {
            val png = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
            Files.createDirectories(output.parent)
            Files.write(output, png.bytes)
            assertTrue(Files.size(output) > 0)
        } finally {
            scene.close()
        }
    }
}
