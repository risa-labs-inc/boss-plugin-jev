package ai.rever.boss.plugin.dynamic.jev

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

internal class CapturingTransport(var response: String = validResponse) : JevTransport {
    var body: ByteArray? = null
    var token: String? = null
    var cancelCount = 0

    override suspend fun post(body: ByteArray, bearerToken: String, timeoutMs: Long, maxResponseBytes: Int): ByteArray {
        this.body = body
        token = bearerToken
        return response.encodeToByteArray()
    }

    override fun cancelAll() { cancelCount++ }
}
