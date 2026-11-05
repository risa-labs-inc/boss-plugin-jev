package ai.rever.boss.plugin.dynamic.jev

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Image assets for a decision: a ref string in state or a question becomes the image. */
internal object JevImages {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 32 * 1024 * 1024
    /** Gateway extras key; the provider adds the TypeSafe images header when it is "true". */
    const val EXTRAS_KEY = "systemone.images"
    val FORMATS = setOf("png", "jpg", "jpeg")

    fun extras(request: JevRequest): Map<String, String> =
        if (request.assets.isNotEmpty() || request.questions.values.any { (it as? JsonObject)?.get("type").text() == "box" }) {
            mapOf(EXTRAS_KEY to "true")
        } else {
            emptyMap()
        }

    /**
     * jev_decide's `assets` argument to the body form: each `path` is read and base64-encoded
     * (format from the extension); inline entries are kept. Throws [JevFailure] with a path.
     */
    fun resolve(raw: JsonElement): JsonObject {
        val assets = raw as? JsonObject ?: throw JevFailure("INVALID_INPUT", "assets must be an object keyed by ref", "assets")
        return buildJsonObject {
            assets.forEach { (ref, value) ->
                val where = "assets.$ref"
                val entry = value as? JsonObject
                    ?: throw JevFailure("INVALID_INPUT", "Each asset must be {path} or {format, data_base64}", where)
                val unknown = entry.keys - setOf("path", "format", "data_base64", "type")
                if (unknown.isNotEmpty()) throw JevFailure("INVALID_INPUT", "Unknown asset fields ${unknown.sorted().joinToString()}", where)
                if (entry["type"] != null && entry["type"].text() != "image") throw JevFailure("INVALID_INPUT", "Asset type must be image", "$where.type")
                val path = entry["path"]
                put(ref, if (path != null) {
                    if (entry.containsKey("data_base64") || entry.containsKey("format")) {
                        throw JevFailure("INVALID_INPUT", "Use path, or format with data_base64, not both", where)
                    }
                    fromPath(path.text() ?: throw JevFailure("INVALID_INPUT", "path must be a string", "$where.path"), "$where.path")
                } else {
                    entry
                })
            }
        }
    }

    internal fun fromPath(path: String, where: String): JsonObject {
        val file = File(path)
        if (!file.isAbsolute) throw JevFailure("INVALID_INPUT", "path must be absolute", where)
        val format = file.extension.lowercase()
        if (format !in FORMATS) throw JevFailure("INVALID_INPUT", "Only png, jpg, or jpeg files are accepted", where)
        if (!file.isFile) throw JevFailure("INVALID_INPUT", "No such file: $path", where)
        if (file.length() > MAX_IMAGE_BYTES) throw JevFailure("INPUT_TOO_LARGE", "Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB", where)
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            throw JevFailure("INVALID_INPUT", "Could not read $path", where)
        }
        if (bytes.size > MAX_IMAGE_BYTES) throw JevFailure("INPUT_TOO_LARGE", "Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB", where)
        if (!matchesFormat(bytes, format)) throw JevFailure("INVALID_INPUT", "File content is not a $format image", where)
        return buildJsonObject {
            put("type", "image")
            put("format", format)
            put("data_base64", Base64.getEncoder().encodeToString(bytes))
        }
    }

    private fun matchesFormat(b: ByteArray, format: String): Boolean = when (format) {
        "png" -> b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        else -> b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    }

    /** Problems in the body-form [assets] of a request: shape, size, and refs that nothing uses. */
    fun issues(request: JevRequest): List<JevIssue> {
        val out = mutableListOf<JevIssue>()
        val used = mutableSetOf<String>()
        collectStrings(request.state, used)
        collectStrings(request.questions, used)
        request.assets.forEach { (ref, value) ->
            fun add(message: String, vararg sub: String) { out += JevIssue(listOf("assets", ref) + sub, message) }
            if (ref.isBlank()) { add("Asset refs must not be blank"); return@forEach }
            val entry = value as? JsonObject
            if (entry == null) { add("Each asset must be {path} or {format, data_base64}"); return@forEach }
            val format = entry["format"].text()
            if (format == null || format !in FORMATS) { add("format must be png, jpg, or jpeg", "format"); return@forEach }
            val data = entry["data_base64"].text()
            if (data.isNullOrEmpty()) { add("data_base64 must be a base64 string", "data_base64"); return@forEach }
            // Four characters carry three bytes: reject an oversized image before decoding it.
            if (data.length / 4L * 3 > MAX_IMAGE_BYTES + 3) {
                add("Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB", "data_base64"); return@forEach
            }
            val size = try { Base64.getDecoder().decode(data).size } catch (_: IllegalArgumentException) { -1 }
            when {
                size < 0 -> add("data_base64 is not valid base64 (no data: prefix)", "data_base64")
                size > MAX_IMAGE_BYTES -> add("Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB", "data_base64")
            }
            if (ref !in used) add("Asset '$ref' is not referenced: put the ref string in state or a question's instructions or criteria")
        }
        return out
    }

    private fun collectStrings(value: JsonElement, into: MutableSet<String>) {
        when (value) {
            is JsonPrimitive -> if (value.isString) into += value.content
            is JsonArray -> value.forEach { collectStrings(it, into) }
            is JsonObject -> value.values.forEach { collectStrings(it, into) }
        }
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
