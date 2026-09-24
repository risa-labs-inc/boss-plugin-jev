package ai.rever.boss.plugin.dynamic.jev

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class JevQuestionType(val wire: String, val label: String) {
    NOUL("noul", "Yes/No"),
    CHOICE("choice", "Choice"),
    SCORE("score", "Score");

    companion object {
        fun fromWire(value: String?): JevQuestionType? = entries.firstOrNull { it.wire == value }
    }
}

data class JevOptionDraft(val name: String = "", val description: String = "")

/**
 * Form-editable question. Fields for every type are kept so switching type does not
 * discard what the user typed. [key] is a stable identity for list rendering.
 */
data class JevQuestionDraft(
    val key: Long = nextDraftKey(),
    val id: String = "",
    val type: JevQuestionType = JevQuestionType.CHOICE,
    val instructions: String = "",
    val yesWhen: String = "",
    val noWhen: String = "",
    val options: List<JevOptionDraft> = emptyList(),
    val levels: List<String> = emptyList(),
) {
    fun withType(next: JevQuestionType): JevQuestionDraft = copy(
        type = next,
        options = if (next == JevQuestionType.CHOICE && options.isEmpty()) listOf(JevOptionDraft(), JevOptionDraft()) else options,
        levels = if (next == JevQuestionType.SCORE && levels.size < 2) levels + List(3 - levels.size.coerceAtMost(3)) { "" } else levels,
    )

    fun toApi(): JsonObject = buildJsonObject {
        put("type", type.wire)
        put("instructions", instructions)
        when (type) {
            JevQuestionType.NOUL -> if (yesWhen.isNotBlank() || noWhen.isNotBlank()) {
                put("criteria", buildJsonObject {
                    if (yesWhen.isNotBlank()) put("true", yesWhen)
                    if (noWhen.isNotBlank()) put("false", noWhen)
                })
            }
            JevQuestionType.CHOICE -> put("criteria", buildJsonObject {
                options.forEach { put(it.name, if (it.description.isBlank()) JsonNull else JsonPrimitive(it.description)) }
            })
            JevQuestionType.SCORE -> put("criteria", buildJsonArray { levels.forEach { add(it) } })
        }
    }

    companion object {
        fun blank(type: JevQuestionType, id: String) = JevQuestionDraft(id = id).withType(type)
    }
}

private val draftKeys = AtomicLong()
internal fun nextDraftKey(): Long = draftKeys.incrementAndGet()

internal fun List<JevQuestionDraft>.toApi(): JsonObject = buildJsonObject { forEach { put(it.id, it.toApi()) } }

/**
 * Converts API questions to form drafts. Returns null when the JSON uses shapes the form
 * cannot show faithfully (structured instructions, unknown types), so the caller stays in JSON.
 */
internal fun draftsFromApi(questions: JsonObject): List<JevQuestionDraft>? = questions.map { (id, value) ->
    val obj = value as? JsonObject ?: return null
    val type = JevQuestionType.fromWire(obj.text("type")) ?: return null
    if (obj.keys.any { it !in setOf("type", "instructions", "criteria") }) return null
    val instructions = obj.textOrBlank("instructions") ?: return null
    val criteria = obj["criteria"]
    when (type) {
        JevQuestionType.NOUL -> {
            val c = when (criteria) { null -> JsonObject(emptyMap()); is JsonObject -> criteria; else -> return null }
            if (c.keys.any { it != "true" && it != "false" }) return null
            JevQuestionDraft(
                id = id, type = type, instructions = instructions,
                yesWhen = c.textOrBlank("true") ?: return null,
                noWhen = c.textOrBlank("false") ?: return null,
            )
        }
        JevQuestionType.CHOICE -> {
            val c = criteria as? JsonObject ?: return null
            JevQuestionDraft(
                id = id, type = type, instructions = instructions,
                options = c.map { (name, d) ->
                    JevOptionDraft(name, if (d is JsonNull) "" else (d as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null)
                },
            )
        }
        JevQuestionType.SCORE -> {
            val c = criteria as? JsonArray ?: return null
            JevQuestionDraft(
                id = id, type = type, instructions = instructions,
                levels = c.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null },
            )
        }
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Missing counts as blank; a non-string value is not representable. */
private fun JsonObject.textOrBlank(key: String): String? = when (val v = get(key)) {
    null -> ""
    is JsonPrimitive -> v.takeIf { it.isString }?.content
    else -> null
}

sealed interface JevContextFormat {
    data object Empty : JevContextFormat
    data class Json(val element: JsonElement, val label: String) : JevContextFormat
    data class Text(val chars: Int) : JevContextFormat
    data class Invalid(val reason: String) : JevContextFormat
}

private val parser = Json

/** JSON is detected from a leading `{` or `[`; anything else is sent as text. */
internal fun detectContext(text: String, sendAsText: Boolean): JevContextFormat {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JevContextFormat.Empty
    if (sendAsText || !looksLikeJson(trimmed)) return JevContextFormat.Text(trimmed.length)
    val element = runCatching { parser.parseToJsonElement(trimmed) }.getOrElse {
        return JevContextFormat.Invalid(it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "Invalid JSON")
    }
    return when (element) {
        is JsonObject -> JevContextFormat.Json(element, "JSON object · ${element.size} ${if (element.size == 1) "field" else "fields"}")
        is JsonArray -> JevContextFormat.Json(element, "JSON array · ${element.size} ${if (element.size == 1) "item" else "items"}")
        else -> JevContextFormat.Text(trimmed.length)
    }
}

internal fun looksLikeJson(text: String): Boolean = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

/** Where an issue shows in the form. Indexes refer to the current draft list. */
sealed interface JevField {
    data object Context : JevField
    data object Json : JevField
    data object Questions : JevField
    data object Timeout : JevField
    data object Model : JevField
    data class Placeholder(val key: String) : JevField
    data class Question(val index: Int) : JevField
    data class QuestionId(val index: Int) : JevField
    data class Instructions(val index: Int) : JevField
    data class YesWhen(val index: Int) : JevField
    data class NoWhen(val index: Int) : JevField
    data class Options(val index: Int) : JevField
    data class OptionName(val index: Int, val option: Int) : JevField
    data class OptionDescription(val index: Int, val option: Int) : JevField
    data class Levels(val index: Int) : JevField
    data class Level(val index: Int, val level: Int) : JevField
}

data class JevFormIssue(val field: JevField, val message: String)

/** Problems the API layer cannot see, because blank or duplicate keys collapse in a JSON object. */
internal fun draftIssues(drafts: List<JevQuestionDraft>): List<JevFormIssue> {
    val out = mutableListOf<JevFormIssue>()
    val seen = mutableSetOf<String>()
    drafts.forEachIndexed { i, q ->
        when {
            q.id.isBlank() -> out += JevFormIssue(JevField.QuestionId(i), "Give this question an ID, such as route")
            !seen.add(q.id) -> out += JevFormIssue(JevField.QuestionId(i), "ID \"${q.id}\" is already used by another question")
        }
        if (q.type == JevQuestionType.CHOICE) {
            val names = mutableSetOf<String>()
            q.options.forEachIndexed { j, o ->
                when {
                    o.name.isBlank() -> out += JevFormIssue(JevField.OptionName(i, j), "Option ${j + 1} needs a name")
                    !names.add(o.name) -> out += JevFormIssue(JevField.OptionName(i, j), "Option \"${o.name}\" is listed twice")
                }
            }
        }
    }
    return out
}

/** Maps an API issue path onto a form field. Anything unrecognised lands on the question card. */
internal fun fieldFor(issue: JevIssue, drafts: List<JevQuestionDraft>): JevField {
    val path = issue.path
    return when (path.firstOrNull()) {
        "state" -> JevField.Context
        "timeout_ms" -> JevField.Timeout
        "questions" -> {
            if (path.size == 1) return JevField.Questions
            val index = drafts.indexOfFirst { it.id == path[1] }.takeIf { it >= 0 } ?: return JevField.Questions
            val draft = drafts[index]
            val rest = path.drop(2)
            when {
                rest.isEmpty() || rest == listOf("type") -> JevField.Question(index)
                rest == listOf("instructions") -> JevField.Instructions(index)
                rest.first() != "criteria" -> JevField.Question(index)
                rest.size == 1 -> when (draft.type) {
                    JevQuestionType.CHOICE -> JevField.Options(index)
                    JevQuestionType.SCORE -> JevField.Levels(index)
                    JevQuestionType.NOUL -> JevField.Question(index)
                }
                draft.type == JevQuestionType.NOUL && rest[1] == "true" -> JevField.YesWhen(index)
                draft.type == JevQuestionType.NOUL && rest[1] == "false" -> JevField.NoWhen(index)
                draft.type == JevQuestionType.SCORE -> rest[1].toIntOrNull()?.let { JevField.Level(index, it) } ?: JevField.Levels(index)
                draft.type == JevQuestionType.CHOICE -> draft.options.indexOfFirst { it.name == rest[1] }
                    .takeIf { it >= 0 }?.let { JevField.OptionDescription(index, it) } ?: JevField.Options(index)
                else -> JevField.Question(index)
            }
        }
        else -> JevField.Questions
    }
}

enum class JevStarter(val label: String, val blurb: String, val context: String, val questions: () -> List<JevQuestionDraft>) {
    ROUTING(
        "Ticket routing",
        "Choice and Yes/No · route a support ticket and decide on paging",
        "{\n  \"customer\": \"Acme\",\n  \"issue\": \"SSO users cannot sign in\",\n  \"plan\": \"enterprise\",\n  \"affected_users\": 420\n}",
        {
            listOf(
                JevQuestionDraft(
                    id = "route", type = JevQuestionType.CHOICE,
                    instructions = "Choose the team best equipped to own this issue.",
                    options = listOf(
                        JevOptionDraft("identity", "Authentication, SSO, and access"),
                        JevOptionDraft("billing", "Invoices and subscriptions"),
                        JevOptionDraft("support", "General product help"),
                    ),
                ),
                JevQuestionDraft(
                    id = "page_oncall", type = JevQuestionType.NOUL,
                    instructions = "Should this page the on-call engineer right now?",
                    yesWhen = "Enterprise customer is fully blocked with no workaround",
                    noWhen = "Partial impact or a workaround exists",
                ),
            )
        },
    ),
    URGENCY(
        "Incident urgency",
        "Yes/No · decide whether an incident needs paging",
        "{\n  \"affected_users\": 420,\n  \"workaround\": false,\n  \"started_minutes_ago\": 18\n}",
        {
            listOf(
                JevQuestionDraft(
                    id = "urgent", type = JevQuestionType.NOUL,
                    instructions = "Assess whether this incident needs immediate paging.",
                    yesWhen = "Material user impact with no practical workaround",
                    noWhen = "Limited impact or an effective workaround exists",
                ),
            )
        },
    ),
    RUBRIC(
        "Release readiness",
        "Score · rate a release candidate on a four-level rubric",
        "A release candidate passes unit tests but has not completed load testing.",
        {
            listOf(
                JevQuestionDraft(
                    id = "readiness", type = JevQuestionType.SCORE,
                    instructions = "Score production readiness using the ordered rubric.",
                    levels = listOf("Not deployable", "Major gaps remain", "Deployable with explicit safeguards", "Ready for normal rollout"),
                ),
            )
        },
    ),
}
