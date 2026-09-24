package ai.rever.boss.plugin.dynamic.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class JevChangeKind { ADDED, REMOVED, CHANGED }

/** One line of a compose turn's change card, such as `+ cost (Score)`. */
data class JevChange(val kind: JevChangeKind, val subject: String, val detail: String = "")

/** What one draft revision changed, computed locally so the card never depends on the model's prose. */
internal object JevDraftDiff {
    fun between(beforeContext: String, beforeQuestions: JsonObject?, afterContext: String, afterQuestions: JsonObject?): List<JevChange> =
        questionChanges(beforeQuestions, afterQuestions) + listOfNotNull(contextChange(beforeContext, afterContext))

    private fun questionChanges(before: JsonObject?, after: JsonObject?): List<JevChange> {
        if (after == null || before == after) return emptyList()
        val old = before ?: JsonObject(emptyMap())
        val out = mutableListOf<JevChange>()
        after.forEach { (id, q) -> if (id !in old) out += JevChange(JevChangeKind.ADDED, id, typeLabel(q)) }
        after.forEach { (id, q) ->
            val prev = old[id] ?: return@forEach
            if (prev != q) out += JevChange(JevChangeKind.CHANGED, id, describe(prev, q))
        }
        old.keys.filter { it !in after }.forEach { out += JevChange(JevChangeKind.REMOVED, it) }
        return out
    }

    private fun describe(before: JsonElement, after: JsonElement): String {
        val b = before as? JsonObject
        val a = after as? JsonObject
        if (b == null || a == null) return "changed"
        if (b["type"] != a["type"]) return "now ${typeLabel(a)}"
        val parts = mutableListOf<String>()
        if (b["instructions"] != a["instructions"]) parts += "instructions changed"
        val bc = b["criteria"]
        val ac = a["criteria"]
        if (bc != ac) {
            parts += when {
                bc is JsonObject && ac is JsonObject && (bc.keys != ac.keys) -> {
                    val added = ac.keys - bc.keys
                    val removed = bc.keys - ac.keys
                    (added.map { "+$it" } + removed.map { "−$it" }).joinToString(" ").let { "options $it" }
                }
                bc is JsonArray && ac is JsonArray && bc.size != ac.size -> "${bc.size} → ${ac.size} levels"
                else -> "criteria changed"
            }
        }
        return parts.joinToString(", ").ifEmpty { "changed" }
    }

    private fun contextChange(before: String, after: String): JevChange? {
        if (before.trim() == after.trim()) return null
        val b = parse(before)
        val a = parse(after)
        if (before.isBlank()) return JevChange(JevChangeKind.ADDED, "context", if (a != null) fieldCount(a.size) else "text")
        if (b != null && a != null) {
            val added = (a.keys - b.keys).size
            val removed = (b.keys - a.keys).size
            val changed = a.keys.intersect(b.keys).count { a[it] != b[it] }
            val parts = listOfNotNull(
                added.takeIf { it > 0 }?.let { "${fieldCount(it)} added" },
                removed.takeIf { it > 0 }?.let { "${fieldCount(it)} removed" },
                changed.takeIf { it > 0 }?.let { "${fieldCount(it)} changed" },
            )
            return JevChange(JevChangeKind.CHANGED, "context", parts.joinToString(", ").ifEmpty { "reformatted" })
        }
        return JevChange(JevChangeKind.CHANGED, "context", "rewritten")
    }

    private fun parse(text: String): JsonObject? =
        if (looksLikeJson(text)) runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() else null

    private fun fieldCount(n: Int) = if (n == 1) "1 field" else "$n fields"

    private fun typeLabel(q: JsonElement): String =
        JevQuestionType.fromWire(((q as? JsonObject)?.get("type") as? JsonPrimitive)?.content)?.label ?: "question"
}

/** A `<customer name>` style value the composer could not know. [path] is empty for text context. */
data class JevPlaceholder(val path: List<String>, val token: String) {
    val key: String get() = (path + token).joinToString("/")
    val hint: String get() = token.removePrefix("<").removeSuffix(">")
    val label: String get() = path.filter { it.toIntOrNull() == null }.takeLast(2).joinToString(" · ") { humanize(it) }.ifEmpty { humanize(hint) }

    private fun humanize(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

internal object JevPlaceholders {
    private val TOKEN = Regex("<[A-Za-z][A-Za-z0-9 _\\-/,.()'#]{0,60}>")

    /** A whole JSON string value counts; inside text a token must look like words, so markup does not. */
    private fun MatchResult.isPlaceholder(whole: Boolean) = whole || value.any { it == ' ' || it == '_' || it == '-' }

    fun find(format: JevContextFormat, text: String): List<JevPlaceholder> = when (format) {
        is JevContextFormat.Json -> buildList { walk(format.element, emptyList(), this) }
        is JevContextFormat.Text -> TOKEN.findAll(text).filter { it.isPlaceholder(false) }.map { JevPlaceholder(emptyList(), it.value) }
            .distinctBy { it.key }.toList()
        else -> emptyList()
    }

    private fun walk(element: JsonElement, path: List<String>, out: MutableList<JevPlaceholder>) {
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> walk(v, path + k, out) }
            is JsonArray -> element.forEachIndexed { i, v -> walk(v, path + i.toString(), out) }
            is JsonPrimitive -> if (element.isString) {
                val s = element.content
                TOKEN.findAll(s).filter { it.isPlaceholder(it.value == s.trim()) }.forEach { out += JevPlaceholder(path, it.value) }
            }
        }
    }

    /** Writes [values] (placeholder key to text) into the context, keeping its format. */
    fun fill(contextText: String, format: JevContextFormat, values: Map<String, String>, placeholders: List<JevPlaceholder>): String {
        val filled = placeholders.mapNotNull { p -> values[p.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { p to it } }
        if (filled.isEmpty()) return contextText
        return when (format) {
            is JevContextFormat.Json -> {
                var root = format.element
                filled.forEach { (p, v) -> root = replaceAt(root, p.path, p.token, v) }
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root)
            }
            else -> filled.fold(contextText) { text, (p, v) -> text.replace(p.token, v) }
        }
    }

    private fun replaceAt(element: JsonElement, path: List<String>, token: String, value: String): JsonElement {
        if (path.isEmpty()) {
            val p = element as? JsonPrimitive ?: return element
            return if (p.isString) JsonPrimitive(p.content.replace(token, value)) else p
        }
        val head = path.first()
        return when (element) {
            is JsonObject -> element[head]?.let { JsonObject(element + (head to replaceAt(it, path.drop(1), token, value))) } ?: element
            is JsonArray -> head.toIntOrNull()?.takeIf { it in element.indices }?.let { i ->
                JsonArray(element.toMutableList().also { it[i] = replaceAt(it[i], path.drop(1), token, value) })
            } ?: element
            else -> element
        }
    }
}
