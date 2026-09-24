package ai.rever.boss.plugin.dynamic.jev

import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object JevValidation {
    /** Throws the first issue so callers that need one failure keep a stable code. */
    fun request(request: JevRequest, limits: JevLimits) {
        requestIssues(request, limits).firstOrNull()?.let { throw JevFailure("INVALID_INPUT", it.message, it.pathText) }
    }

    /** Every request problem, each with the path of the field that caused it. */
    fun requestIssues(request: JevRequest, limits: JevLimits): List<JevIssue> {
        val issues = mutableListOf<JevIssue>()
        fun add(message: String, vararg path: String) { issues += JevIssue(path.toList(), message) }

        if (JevModelCatalog.find(request.model) == null) {
            add("Unknown model '${request.model}'; available: ${JevModelCatalog.all.joinToString { it.id }}", "model")
        }
        if (request.timeoutMs !in limits.minTimeoutMs..limits.maxTimeoutMs) {
            add("Timeout must be between ${limits.minTimeoutMs} and ${limits.maxTimeoutMs} ms", "timeout_ms")
        }
        val state = request.state
        val stateOk = state is JsonObject || state is JsonArray || (state is JsonPrimitive && state.isString)
        if (!stateOk) add("Context must be text, a JSON object, or a JSON array", "state")
        if (state is JsonPrimitive && state.isString && state.content.isBlank()) add("Context must not be blank", "state")

        if (request.questions.isEmpty()) add("Add at least one question", "questions")
        if (request.questions.size > limits.maxQuestions) {
            add("Too many questions: ${request.questions.size} of ${limits.maxQuestions} allowed", "questions")
        }
        request.questions.forEach { (id, value) ->
            if (id.isBlank()) add("Question IDs must not be blank", "questions", id)
            val question = value as? JsonObject
            if (question == null) {
                add("Each question must be an object with type, instructions, and criteria", "questions", id)
                return@forEach
            }
            val type = question.string("type")
            if (type == null) {
                add("Set type to noul, choice, or score", "questions", id, "type")
                return@forEach
            }
            if (type !in TYPES) {
                add("Unsupported type '$type'; use noul, choice, or score", "questions", id, "type")
                return@forEach
            }
            val unknown = question.keys - setOf("type", "instructions", "criteria")
            if (unknown.isNotEmpty()) {
                add("Unknown fields ${unknown.sorted().joinToString()}; allowed: type, instructions, criteria", "questions", id)
            }
            instruction(question["instructions"], "Instructions must state the decision")
                ?.let { add(it, "questions", id, "instructions") }
            when (type) {
                "noul" -> noulIssues(question["criteria"]).forEach { (sub, message) ->
                    add(message, *(listOf("questions", id, "criteria") + sub).toTypedArray())
                }
                "choice" -> choiceIssues(question["criteria"]).forEach { (sub, message) ->
                    add(message, *(listOf("questions", id, "criteria") + sub).toTypedArray())
                }
                "score" -> scoreIssues(question["criteria"]).forEach { (sub, message) ->
                    add(message, *(listOf("questions", id, "criteria") + sub).toTypedArray())
                }
            }
        }
        return issues
    }

    private fun noulIssues(criteria: JsonElement?): List<Pair<List<String>, String>> {
        if (criteria == null) return emptyList()
        val obj = criteria as? JsonObject
            ?: return listOf(emptyList<String>() to "Yes/No criteria must be an object with true and/or false")
        val out = mutableListOf<Pair<List<String>, String>>()
        val unknown = obj.keys - setOf("true", "false")
        if (unknown.isNotEmpty()) out += emptyList<String>() to "Yes/No criteria accepts only true and false"
        obj["true"]?.let { v -> instruction(v, "Describe when the answer is yes, or leave it out")?.let { out += listOf("true") to it } }
        obj["false"]?.let { v -> instruction(v, "Describe when the answer is no, or leave it out")?.let { out += listOf("false") to it } }
        return out
    }

    private fun choiceIssues(criteria: JsonElement?): List<Pair<List<String>, String>> {
        val obj = criteria as? JsonObject
            ?: return listOf(emptyList<String>() to "Choice criteria must be an object of option name to description")
        val out = mutableListOf<Pair<List<String>, String>>()
        if (obj.isEmpty()) out += emptyList<String>() to "Add at least one option"
        if (obj.size > MAX_OPTIONS) out += emptyList<String>() to "Too many options: ${obj.size} of $MAX_OPTIONS allowed"
        obj.forEach { (option, value) ->
            if (option.isBlank()) out += emptyList<String>() to "Option names must not be blank"
            if (value !is JsonNull) {
                instruction(value, "Describe option '$option', or set it to null")?.let { out += listOf(option) to it }
            }
        }
        return out
    }

    private fun scoreIssues(criteria: JsonElement?): List<Pair<List<String>, String>> {
        val array = criteria as? JsonArray
            ?: return listOf(emptyList<String>() to "Score criteria must be an array of levels, lowest first")
        val out = mutableListOf<Pair<List<String>, String>>()
        if (array.size !in 2..MAX_LEVELS) out += emptyList<String>() to "Score needs 2 to $MAX_LEVELS levels; this has ${array.size}"
        array.forEachIndexed { index, value ->
            instruction(value, "Describe level $index")?.let { out += listOf(index.toString()) to it }
        }
        return out
    }

    fun response(response: JsonObject, questions: JsonObject) {
        val model = response.string("model") ?: malformed("response.model must be a string")
        if (model.isBlank()) malformed("response.model must not be blank")
        val answers = response["answers"] as? JsonObject ?: malformed("response.answers must be an object")
        if (answers.keys != questions.keys) malformed("response answer IDs do not match request question IDs")
        val usage = response["usage"] as? JsonObject ?: malformed("response.usage must be an object")
        nonNegativeInt(usage["input_tokens"], "usage.input_tokens")
        nonNegativeInt(usage["output_tokens"], "usage.output_tokens")
        usage["cost"]?.let { nonNegativeNumber(it, "usage.cost") }

        questions.forEach { (id, questionValue) ->
            val question = questionValue.jsonObject
            val type = question.string("type")!!
            val answer = answers[id] as? JsonObject ?: malformed("answer '$id' must be an object")
            if (answer.string("type") != type) malformed("answer '$id' type does not match question")
            when (type) {
                "noul" -> probability(answer["noul"], "answer '$id'.noul")
                "choice" -> validateChoiceAnswer(id, question, answer)
                "score" -> validateScoreAnswer(id, question, answer)
            }
        }
    }

    private fun validateChoiceAnswer(id: String, question: JsonObject, answer: JsonObject) {
        val options = question["criteria"]!!.jsonObject.keys
        val choice = answer.string("choice") ?: malformed("answer '$id'.choice must be a string")
        if (choice !in options) malformed("answer '$id'.choice is not a requested option")
        val probabilities = answer["probabilities"] as? JsonObject
            ?: malformed("answer '$id'.probabilities must be an object")
        if (probabilities.keys != options) malformed("answer '$id' probability options do not match request")
        probabilityMap(probabilities, "answer '$id'.probabilities")
        probability(answer["confidence"], "answer '$id'.confidence")
    }

    private fun validateScoreAnswer(id: String, question: JsonObject, answer: JsonObject) {
        val levels = question["criteria"]!!.jsonArray.size
        val expected = (0 until levels).map(Int::toString).toSet()
        val score = number(answer["score"], "answer '$id'.score")
        if (score !in 0.0..(levels - 1).toDouble()) {
            malformed("answer '$id'.score is outside the requested rubric")
        }
        val legend = answer["legend"] as? JsonObject ?: malformed("answer '$id'.legend must be an object")
        if (legend.keys != expected || legend.values.any { it !is JsonPrimitive || !it.isString }) {
            malformed("answer '$id'.legend does not match requested score levels")
        }
        val probabilities = answer["probabilities"] as? JsonObject
            ?: malformed("answer '$id'.probabilities must be an object")
        if (probabilities.keys != expected) malformed("answer '$id' score probabilities do not match rubric")
        probabilityMap(probabilities, "answer '$id'.probabilities")
        probability(answer["confidence"], "answer '$id'.confidence")
    }

    private fun probabilityMap(values: JsonObject, path: String) {
        var sum = 0.0
        values.forEach { (key, value) -> sum += probability(value, "$path.$key") }
        if (abs(sum - 1.0) > sumTolerance(values.size)) malformed("$path must sum to 1")
    }

    /**
     * How far a probability map may drift from 1. The provider rounds each value, so the error
     * grows with the number of options: a fixed 1e-6 rejected a sound answer over ~200 choices
     * (LLM RPA offers one option per link on a page). Allows 3-decimal rounding per option.
     */
    internal fun sumTolerance(options: Int): Double = maxOf(1e-6, 5e-4 * options)

    private fun probability(value: JsonElement?, path: String): Double {
        val number = number(value, path)
        if (!number.isFinite() || number !in 0.0..1.0) malformed("$path must be between 0 and 1")
        return number
    }

    private fun nonNegativeInt(value: JsonElement?, path: String) {
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: malformed("$path must be an integer")
        if (number < 0) malformed("$path must not be negative")
    }

    private fun nonNegativeNumber(value: JsonElement, path: String) {
        val number = number(value, path)
        if (!number.isFinite() || number < 0) malformed("$path must not be negative")
    }

    private fun number(value: JsonElement?, path: String): Double =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?: malformed("$path must be a finite number")

    /** Instructions may be a non-blank string, an object, or an array. Returns the problem, if any. */
    private fun instruction(value: JsonElement?, blankMessage: String): String? = when (value) {
        is JsonPrimitive -> if (!value.isString || value.content.isBlank()) blankMessage else null
        is JsonObject, is JsonArray -> null
        else -> blankMessage
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun malformed(message: String): Nothing = throw JevFailure("MALFORMED_RESPONSE", message)

    private val TYPES = setOf("noul", "choice", "score")
    const val MAX_OPTIONS = 255
    const val MAX_LEVELS = 10
}
