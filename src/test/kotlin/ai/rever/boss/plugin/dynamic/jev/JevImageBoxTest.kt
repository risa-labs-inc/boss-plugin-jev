package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionModel
import ai.rever.boss.plugin.api.AiDecisionProvider
import ai.rever.boss.plugin.api.AiDecisionReply
import ai.rever.boss.plugin.api.McpToolArgs
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JevImageBoxTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 0)
    private val b64 = Base64.getEncoder().encodeToString(png)

    private fun obj(text: String) = testJson.parseToJsonElement(text).jsonObject

    private fun boxQuestions() = obj("""{"target":{"type":"box","instructions":"Where is the Submit button?"}}""")
    private fun assets(ref: String = "shot1") = obj("""{"$ref":{"type":"image","format":"png","data_base64":"$b64"}}""")

    private fun request(
        state: String = """{"screenshot":"shot1"}""",
        questions: JsonObject = boxQuestions(),
        assets: JsonObject = assets(),
    ) = JevRequest(testJson.parseToJsonElement(state), questions, assets = assets)

    private fun issues(r: JevRequest) = JevValidation.bodyIssues(r, JevLimits())

    private val boxResponse = """{"model":"typesafe/jev-1.14","answers":{"target":{"type":"box","box":{"top":0.1,"bottom":0.2,"left":0.3,"right":0.4},"confidence":0.8}},"usage":{"input_tokens":1,"output_tokens":1,"cost":0.001}}"""

    @Test
    fun `box question is valid and rejects criteria`() {
        assertEquals(emptyList(), issues(request()))
        val withCriteria = obj("""{"t":{"type":"box","instructions":"where","criteria":{"a":null}}}""")
        val found = issues(request(questions = withCriteria))
        assertEquals(listOf("questions", "t"), found.single().path)
        assertTrue(found.single().message.contains("criteria") || found.single().message.contains("Unknown"))
        val noInstructions = obj("""{"t":{"type":"box"}}""")
        assertEquals(listOf("questions", "t", "instructions"), issues(request(questions = noInstructions)).single().path)
    }

    @Test
    fun `unreferenced and malformed assets are rejected with a path`() {
        val unreferenced = issues(request(state = """{"screenshot":"other"}"""))
        assertEquals(listOf("assets", "shot1"), unreferenced.single().path)
        val badFormat = issues(request(assets = obj("""{"shot1":{"format":"gif","data_base64":"$b64"}}""")))
        assertEquals(listOf("assets", "shot1", "format"), badFormat.single().path)
        val badData = issues(request(assets = obj("""{"shot1":{"format":"png","data_base64":"data:image/png;base64,xx"}}""")))
        assertEquals(listOf("assets", "shot1", "data_base64"), badData.single().path)
    }

    @Test
    fun `a ref in a question counts as referenced`() {
        val q = obj("""{"t":{"type":"box","instructions":["Find it in","shot1"]}}""")
        assertEquals(emptyList(), issues(request(state = "\"plain\"", questions = q)))
    }

    @Test
    fun `a ref inside a longer string counts as referenced`() {
        val q = obj("""{"t":{"type":"box","instructions":"Find the button in shot1 and box it"}}""")
        assertEquals(emptyList(), issues(request(state = "\"plain\"", questions = q)))
    }

    @Test
    fun `an image over 16 MiB is refused`() {
        val big = Base64.getEncoder().encodeToString(ByteArray(16 * 1024 * 1024 + 1))
        val found = issues(request(assets = obj("""{"shot1":{"format":"png","data_base64":"$big"}}""")))
        assertEquals(listOf("assets", "shot1", "data_base64"), found.single().path)
    }

    @Test
    fun `body keeps assets and gateway extras are set for assets or box only`() = runTest {
        val backend = CapturingBackend(boxResponse)
        val service = JevDecisionService(backend)
        val decision = service.decide(request())
        val sent = obj(backend.body!!)
        assertEquals(setOf("model", "state", "questions", "assets"), sent.keys)
        assertEquals(b64, sent["assets"]!!.jsonObject["shot1"]!!.jsonObject["data_base64"]!!.jsonPrimitive.content)
        assertEquals(mapOf("systemone.images" to "true"), backend.extras)
        // The answer comes back unchanged.
        assertEquals("0.3", decision.response["answers"]!!.jsonObject["target"]!!.jsonObject["box"]!!.jsonObject["left"]!!.jsonPrimitive.content)
        // Bytes are not kept in run history.
        assertTrue(service.runs.value.single().request.assets.isEmpty())

        // Box alone passes the extra too.
        service.decide(request(state = "\"x\"", assets = JsonObject(emptyMap())))
        assertEquals(mapOf("systemone.images" to "true"), backend.extras)

        // Neither: no extras, no assets key.
        val plain = CapturingBackend()
        JevDecisionService(plain).decide(requestAllTypes())
        assertEquals(emptyMap(), plain.extras)
        assertFalse(obj(plain.body!!).containsKey("assets"))
    }

    @Test
    fun `text-only keeps the 256 KB cap but image requests get 32 MiB`() = runTest {
        val service = JevDecisionService(CapturingBackend(boxResponse))
        val huge = "x".repeat(300 * 1024)
        val failure = assertFailsWith<JevFailure> {
            service.decide(request(state = """{"t":"$huge"}""", assets = JsonObject(emptyMap()), questions = obj("""{"target":{"type":"box","instructions":"w"}}""")))
        }
        assertEquals("INPUT_TOO_LARGE", failure.code)
        service.decide(request(state = """{"screenshot":"shot1","t":"$huge"}"""))
    }

    @Test
    fun `box answers are validated`() {
        val q = boxQuestions()
        fun check(answer: String) = JevValidation.response(obj("""{"model":"m","answers":{"target":$answer},"usage":{"input_tokens":1,"output_tokens":1}}"""), q)
        check("""{"type":"box","box":{"top":0,"bottom":1,"left":0,"right":1},"confidence":null}""")
        check("""{"type":"box","box":{"top":0,"bottom":1,"left":0,"right":1}}""")
        assertFailsWith<JevFailure> { check("""{"type":"box","box":{"top":0,"bottom":1.5,"left":0,"right":1},"confidence":0.5}""") }
        assertFailsWith<JevFailure> { check("""{"type":"box","box":{"top":0,"bottom":1,"left":0},"confidence":0.5}""") }
        assertFailsWith<JevFailure> { check("""{"type":"box","confidence":0.5}""") }
        assertEquals("top 0.100 bottom 0.200 left 0.300 right 0.400", boxText(obj("""{"box":{"top":0.1,"bottom":0.2,"left":0.3,"right":0.4}}""")))
    }

    @Test
    fun `MCP reads path assets, encodes them, and passes inline ones`() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"), "jev-assets-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(dir, "shot.PNG").apply { writeBytes(png) }
            val backend = CapturingBackend(boxResponse)
            val mcp = JevMcpToolProvider("p", JevDecisionService(backend))
            val args = """{"state":{"screenshot":"shot1","second":"shot2"},"questions":{"target":{"type":"box","instructions":"where"}},
                "assets":{"shot1":{"path":"${file.absolutePath}"},"shot2":{"format":"png","data_base64":"$b64"}}}"""
            val result = mcp.call(McpToolArgs(emptyMap(), args))
            assertFalse(result.isError, result.text)
            val sent = obj(backend.body!!)["assets"]!!.jsonObject
            assertEquals(b64, sent["shot1"]!!.jsonObject["data_base64"]!!.jsonPrimitive.content)
            assertEquals("png", sent["shot1"]!!.jsonObject["format"]!!.jsonPrimitive.content)
            assertEquals("image", sent["shot1"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(mapOf("systemone.images" to "true"), backend.extras)
            val response = obj(result.text)["response"]!!.jsonObject["answers"]!!.jsonObject["target"]!!.jsonObject
            assertEquals("0.8", response["confidence"]!!.jsonPrimitive.content)

            suspend fun code(asset: String): String {
                val r = mcp.call(McpToolArgs(emptyMap(), """{"state":{"s":"shot1"},"questions":{"t":{"type":"box","instructions":"w"}},"assets":{"shot1":$asset}}"""))
                assertTrue(r.isError)
                return obj(r.text)["error"]!!.jsonObject["path"]!!.jsonPrimitive.content
            }
            assertEquals("assets.shot1.path", code("""{"path":"relative.png"}"""))
            assertEquals("assets.shot1.path", code("""{"path":"${File(dir, "gone.png").absolutePath}"}"""))
            assertEquals("assets.shot1.path", code("""{"path":"${File(dir, "notes.txt").apply { writeText("x") }.absolutePath}"}"""))
            assertEquals("assets.shot1.path", code("""{"path":"${File(dir, "fake.png").apply { writeText("not a png at all") }.absolutePath}"}"""))
            assertEquals("assets.shot1", code("""{"path":"${file.absolutePath}","format":"png"}"""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreferenced path asset fails validation in jev_validate`() = runTest {
        val mcp = JevMcpToolProvider("p", JevDecisionService(CapturingBackend()))
        val result = mcp.validate(McpToolArgs(emptyMap(), """{"state":"x","questions":{"t":{"type":"box","instructions":"w"}},"assets":{"a":{"format":"png","data_base64":"$b64"}}}"""))
        val first = obj(result.text)["issues"]!!.toString()
        assertTrue(first.contains("assets.a"), first)
    }

    @Test
    fun `schema declares assets and box`() {
        val schema = obj(JevMcpToolProvider.DECIDE_SCHEMA)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("assets"))
        assertTrue(JevMcpToolProvider.DECIDE_SCHEMA.contains("\"const\":\"box\""))
    }

    @Test
    fun `jev-1_14 is selectable when the gateway lists it and the default stays 1_13`() = runTest {
        val api = FakeDecisionApi(listOf(AiDecisionProvider(
            "OPENROUTER", "OpenRouter", local = false, reachable = true,
            models = listOf(AiDecisionModel("typesafe/jev-1.13", "jev-1.13"), AiDecisionModel("typesafe/jev-1.14", "jev-1.14")),
        )), reply = { Result.success(AiDecisionReply(boxResponse, it.providerId, 1)) })
        val service = JevDecisionService(GatewayJevDecisionBackend { api }, JevModelCatalog { api })
        val decision = service.decide(request().copy(model = "typesafe/jev-1.14"))
        assertEquals("typesafe/jev-1.14", decision.model.id)
        assertEquals("true", api.requests.single().extras["systemone.images"])
        assertEquals("typesafe/jev-1.13", JevModelCatalog.DEFAULT.id)
    }
}
