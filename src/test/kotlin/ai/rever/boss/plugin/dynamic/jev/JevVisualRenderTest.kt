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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat

/** Renders the real panel across sidebar and full-window widths for visual review. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class JevVisualRenderTest {
    private val routingResponse = """{
      "provider":"OpenRouter","model":"typesafe/jev-1.13",
      "answers":{
        "route":{"type":"choice","choice":"identity","probabilities":{"identity":0.91,"billing":0.02,"support":0.07},"confidence":0.88},
        "page_oncall":{"type":"noul","noul":0.73},
        "severity":{"type":"score","score":2.2,"legend":{"0":"Cosmetic","1":"Degraded","2":"Blocked for some users","3":"Blocked for everyone"},"probabilities":{"0":0.0,"1":0.1,"2":0.6,"3":0.3},"confidence":0.64}
      },
      "usage":{"input_tokens":412,"output_tokens":38,"cost":0.00021}
    }"""

    private val starterResponse = """{
      "provider":"OpenRouter","model":"typesafe/jev-1.13",
      "answers":{
        "route":{"type":"choice","choice":"identity","probabilities":{"identity":0.91,"billing":0.02,"support":0.07},"confidence":0.88},
        "page_oncall":{"type":"noul","noul":0.73}
      },
      "usage":{"input_tokens":412,"output_tokens":38,"cost":0.00021}
    }"""

    private val composedResponse = routingResponse.replace(""""page_oncall":{"type":"noul","noul":0.73},""", """"page_oncall":{"type":"noul","noul":0.31},"refund":{"type":"noul","noul":0.12},""")

    private val firstQuestions = """{
      "route":{"type":"choice","instructions":"Choose the team best equipped to own this ticket.","criteria":{"identity":"Authentication, SSO and access","billing":"Invoices and subscriptions","support":"General product help"}},
      "page_oncall":{"type":"noul","instructions":"Should this page the on-call engineer now?","criteria":{"true":"Enterprise customer fully blocked with no workaround","false":"Partial impact or a workaround exists"}},
      "severity":{"type":"score","instructions":"Rate the severity.","criteria":["Cosmetic","Degraded","Blocked for some users","Blocked for everyone"]}
    }"""

    private val firstReply = """{"context":{"customer":"<customer name>","plan":"enterprise","issue":"SSO users cannot sign in","affected_users":"<number of affected users>"},
      "questions":$firstQuestions,
      "reply":"Drafted routing, paging and severity for an enterprise SSO ticket.",
      "suggestions":["Add a question about refunds","Make paging stricter","Add a billing escalation"]}"""

    /** Returns [first] at once, then holds the repair call until released, so the card shows "Fixing". */
    private class GatedChat(private val first: String, private val second: String) : JevChatClient {
        val release = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        private var calls = 0
        override fun gatewayAvailable() = true
        override fun models() = listOf(JevChatModel("ANTHROPIC", "Anthropic", "claude-sonnet", "Claude Sonnet"))
        override fun defaultModel(models: List<JevChatModel>) = models.first()
        override suspend fun complete(system: String, messages: List<JevChatMessage>, model: JevChatModel?): String {
            if (calls++ == 0) return first
            waiting.complete(Unit)
            release.await()
            return second
        }
    }

    private fun context() = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @Test
    fun `renders every compose state at every width`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val broken = firstReply.replace(""""criteria":["Cosmetic","Degraded","Blocked for some users","Blocked for everyone"]""", """"criteria":["Blocked"]""")
            .replace(""""route":{"type":"choice"""", """"Route":{"type":"choice"""")
        val chat = GatedChat(broken, firstReply)
        val services = JevPluginServices(context(), CapturingTransport(composedResponse), JevKeyResolver { "test-key" }, chat)
        val vm = services.playground
        try {
            // Empty: one prominent input, examples, templates.
            listOf(280, 360, 520).forEach { render(vm, it, 900, "empty-$it") }
            render(vm, 900, 800, "empty-two-pane-900")

            // Drafting, 14 s in, on the repair step.
            vm.nowMs = { 1_000L }
            vm.setComposeInput("Route support tickets to a team, and decide whether to page on-call")
            val job = CoroutineScope(Dispatchers.Default).launch {
                runCatching { vm.compose("Route support tickets to a team, and decide whether to page on-call") }
            }
            runBlocking { withTimeout(5_000) { chat.waiting.await() } }
            vm.nowMs = { 15_000L }
            render(vm, 360, 700, "drafting-360")
            chat.release.complete(Unit)
            runBlocking { job.join() }

            // Change card, suggestions, and the placeholders the model could not know.
            listOf(280, 360, 520).forEach { render(vm, it, 1000, "turn-placeholders-$it") }
            vm.setPane(JevPane.DRAFT)
            render(vm, 360, 1000, "draft-placeholders-360")
            vm.setPane(JevPane.CHAT)

            vm.fillPlaceholders(vm.state.value.placeholders.associate { it.key to if ("customer" in it.key) "Acme" else "420" })
            runBlocking {
                services.composerForRender(
                    """{"context":null,"questions":${firstQuestions.replace("\"severity\"", "\"refund\":{\"type\":\"noul\",\"instructions\":\"Should support offer a refund?\"},\"severity\"")
                        .replace("\"Partial impact or a workaround exists\"", "\"Any workaround exists, or fewer than 50 users are affected\"")},
                    "reply":"Added a refund question and made paging stricter.","suggestions":["Only page during business hours","Rate refund size as a score"]}""",
                )
                vm.compose("Add a question about refunds, and make paging stricter")
            }
            render(vm, 360, 1000, "turn-diff-360")

            // A run joins the thread as a compact answer card.
            runBlocking { vm.runDraft() }
            listOf(280, 360).forEach { render(vm, it, 1000, "answer-card-$it") }
            render(vm, 900, 1000, "two-pane-answer-900")
            render(vm, 1440, 1000, "two-pane-answer-1440")
            vm.setPane(JevPane.DRAFT)
            render(vm, 1440, 1000, "two-pane-draft-1440")
            vm.setPane(JevPane.ANSWER)
            render(vm, 360, 1000, "answer-full-360")
            vm.setPane(JevPane.CHAT)

            // A failed turn: clear message, Retry, and the route to AI Providers when that is the fix.
            services.failNext(JevFailure(JevComposer.NO_MODEL, "No chat model is set up yet. Add a provider in Secret Manager → AI Providers."))
            runBlocking { runCatching { vm.compose("Add a question about cost") } }
            render(vm, 360, 800, "error-360")
            render(vm, 900, 800, "error-two-pane-900")
        } finally {
            services.dispose()
            Dispatchers.resetMain()
        }
    }

    /** Later turns in the render use a scripted chat; the gated one has done its job by then. */
    private var scripted: ScriptedChat? = null

    private fun JevPluginServices.composerForRender(reply: String) {
        val chat = ScriptedChat(reply, models = listOf(JevChatModel("ANTHROPIC", "Anthropic", "claude-sonnet", "Claude Sonnet")))
        scripted = chat
        swapChat(chat)
    }

    private fun JevPluginServices.failNext(failure: JevFailure) {
        val chat = ScriptedChat()
        chat.failure = failure
        swapChat(chat)
    }

    private fun JevPluginServices.swapChat(chat: JevChatClient) {
        val field = JevPluginServices::class.java.getDeclaredField("composer")
        field.isAccessible = true
        field.set(this, JevComposer(chat, service.limits))
    }

    @Test
    fun `renders the draft editor and answer at sidebar widths`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val transport = CapturingTransport(starterResponse)
        val services = JevPluginServices(context(), transport, JevKeyResolver { "test-key" })
        val viewModel = services.playground
        try {
            val request = viewModel.state.value.request!!
            runBlocking { services.service.decide(request) }
            transport.response = transport.response.replace("0.91", "0.84").replace("0.07", "0.14")
            runBlocking { services.service.decide(request, JevRunSource.MCP) }

            listOf(280, 360, 520).forEach { width ->
                viewModel.setPane(JevPane.DRAFT)
                render(viewModel, width, 1100, "draft-$width")
                viewModel.setPane(JevPane.ANSWER)
                render(viewModel, width, 1100, "answer-$width")
            }
            viewModel.useStarter(JevStarter.RUBRIC)
            viewModel.setPane(JevPane.DRAFT)
            viewModel.setLevel(0, 1, "")
            render(viewModel, 360, 900, "draft-issues-360")
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
            // A second frame lets effects such as scrolling the thread to its end apply.
            scene.render(0)
            val png = checkNotNull(scene.render(50_000_000).encodeToData(EncodedImageFormat.PNG))
            Files.createDirectories(output.parent)
            Files.write(output, png.bytes)
            assertTrue(Files.size(output) > 0)
        } finally {
            scene.close()
        }
    }
}
