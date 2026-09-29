package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.AiDecisionAPI
import ai.rever.boss.plugin.api.AiDecisionModel
import ai.rever.boss.plugin.api.AiDecisionProvider
import ai.rever.boss.plugin.api.AiDecisionReply
import ai.rever.boss.plugin.api.AiDecisionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val testJson = Json

internal fun requestAllTypes(timeoutMs: Long = 5_000): JevRequest = JevRequest(
    state = testJson.parseToJsonElement("""{"ticket":"login outage"}"""),
    questions = testJson.parseToJsonElement(
        """{
          "urgent":{"type":"noul","instructions":"Is this urgent?","criteria":{"true":"Page now","false":"Can wait"}},
          "route":{"type":"choice","instructions":"Choose a team","criteria":{"identity":"Login","support":null}},
          "readiness":{"type":"score","instructions":"Score readiness","criteria":["blocked","risky","ready"]}
        }""",
    ) as JsonObject,
    timeoutMs = timeoutMs,
)

internal val validResponse = """{
  "id":"run-1","provider":"OpenRouter","model":"typesafe/jev-1.13",
  "answers":{
    "urgent":{"type":"noul","noul":0.9},
    "route":{"type":"choice","choice":"identity","probabilities":{"identity":0.8,"support":0.2},"confidence":0.75},
    "readiness":{"type":"score","score":1.05,"legend":{"0":"blocked","1":"risky","2":"ready"},"probabilities":{"0":0,"1":0.95,"2":0.05},"confidence":0.6}
  },
  "usage":{"input_tokens":22,"output_tokens":10,"cost":0.002}
}""".trimIndent()

/** Stands in for the AI Gateway's decide; records what it was sent. */
internal class CapturingBackend(var response: String = validResponse) : JevDecisionBackend {
    var body: String? = null
    val providers = mutableListOf<String>()
    /** When set, every call fails with it, after being recorded. */
    var failure: JevFailure? = null

    override suspend fun decide(model: JevModelOption, body: String, timeoutMs: Long, maxResponseBytes: Int): String {
        providers += model.providerId
        failure?.let { throw it }
        this.body = body
        return response
    }
}

internal fun missingCredential() = JevFailure("MISSING_CREDENTIAL", "Add an OpenRouter key in Secret Manager → AI Providers")

/** A gateway decision API with fixed providers and a scripted decide. */
internal class FakeDecisionApi(
    var providers: List<AiDecisionProvider> = listOf(openRouter()),
    var reply: (AiDecisionRequest) -> Result<AiDecisionReply> = { Result.success(AiDecisionReply(validResponse, it.providerId, 1)) },
) : AiDecisionAPI {
    val requests = mutableListOf<AiDecisionRequest>()
    @Volatile var listings = 0
    /** When set, listing waits for it, so a test can hold a refresh in flight. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    override suspend fun decisionProviders(): List<AiDecisionProvider> { listings++; gate?.await(); return providers }
    override suspend fun decide(request: AiDecisionRequest): Result<AiDecisionReply> { requests += request; return reply(request) }
}

internal fun openRouter(
    reachable: Boolean = true,
    needsCredential: Boolean = !reachable,
    detail: String? = if (needsCredential) "Add an OpenRouter key in Secret Manager → AI Providers" else null,
) = AiDecisionProvider(
    "OPENROUTER", "OpenRouter", local = false, reachable = reachable,
    models = listOf(AiDecisionModel("typesafe/jev-1.13", "jev-1.13")),
    detail = detail, needsCredential = needsCredential,
)

internal fun localRuntime(reachable: Boolean = true, vararg models: String = arrayOf("laya:en", "laya:multilingual")) = AiDecisionProvider(
    "LOCAL_SYSTEMONE", "Local", local = true, reachable = reachable,
    models = if (reachable) models.map { AiDecisionModel(it) } else emptyList(),
    detail = if (reachable) "127.0.0.1:11435" else "No local decision runtime at 127.0.0.1:11435. Start one with `ollaya serve`.",
)
