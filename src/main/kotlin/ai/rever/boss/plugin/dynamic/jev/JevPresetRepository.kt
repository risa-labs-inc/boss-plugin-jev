package ai.rever.boss.plugin.dynamic.jev

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class JevPreset(
    val name: String,
    val stateText: String,
    val questionsText: String,
    val stateAsJson: Boolean = true,
    val timeoutMs: Long = JevLimits.DEFAULT_TIMEOUT_MS,
    val model: String = JevModelCatalog.DEFAULT.id,
)

interface JevPresetBackend {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class PluginPresetBackend(private val storage: PluginStorageProvider) : JevPresetBackend {
    override suspend fun get(key: String): String? = storage.getJson(key)
    override suspend fun put(key: String, value: String) = storage.putJson(key, value)
    override suspend fun remove(key: String) = storage.remove(key)
}

class JevPresetRepository(private val backend: JevPresetBackend) {
    private val json = Json
    private val lock = Mutex()

    suspend fun names(): List<String> = lock.withLock { readAll().keys.sorted() }

    suspend fun save(preset: JevPreset) = lock.withLock {
        val name = preset.name.trim()
        require(name.isNotEmpty() && name.length <= 80) { "Preset name must be 1 to 80 characters" }
        require(preset.stateText.encodeToByteArray().size <= MAX_FIELD_BYTES) { "Preset context is too large" }
        require(preset.questionsText.encodeToByteArray().size <= MAX_FIELD_BYTES) { "Preset questions are too large" }
        require(preset.timeoutMs in 1_000..120_000) { "Preset timeout must be between 1000 and 120000" }
        val all = readAll().toMutableMap()
        if (name !in all && all.size >= MAX_PRESETS) throw IllegalArgumentException("At most $MAX_PRESETS presets are allowed")
        all[name] = preset.copy(name = name)
        writeAll(all)
    }

    suspend fun load(name: String): JevPreset? = lock.withLock { readAll()[name] }

    suspend fun all(): List<JevPreset> = lock.withLock { readAll().toSortedMap().values.toList() }

    suspend fun delete(name: String) = lock.withLock {
        val all = readAll().toMutableMap()
        if (all.remove(name) != null) writeAll(all)
    }

    private suspend fun readAll(): Map<String, JevPreset> {
        val raw = storage { backend.get(STORAGE_KEY) } ?: return emptyMap()
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid")
        return root.mapValues { (_, value) ->
            val obj = value as? JsonObject
                ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid")
            JevPreset(
                name = obj.string("name")
                    ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid"),
                stateText = obj.string("state")
                    ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid"),
                questionsText = obj.string("questions")
                    ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid"),
                stateAsJson = obj.boolean("state_as_json")
                    ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid"),
                timeoutMs = obj.long("timeout_ms")
                    ?: throw JevFailure("PRESET_STORAGE_ERROR", "Saved presets are invalid"),
                // Presets saved before model choice existed have no model field.
                model = obj.string("model") ?: JevModelCatalog.DEFAULT.id,
            )
        }
    }

    private suspend fun writeAll(all: Map<String, JevPreset>) {
        val raw = buildJsonObject {
            all.toSortedMap().forEach { (name, preset) ->
                put(name, buildJsonObject {
                    put("name", name)
                    put("state", preset.stateText)
                    put("questions", preset.questionsText)
                    put("state_as_json", preset.stateAsJson)
                    put("timeout_ms", preset.timeoutMs)
                    put("model", preset.model)
                })
            }
        }.toString()
        require(raw.encodeToByteArray().size <= MAX_TOTAL_BYTES) { "Preset storage limit exceeded" }
        storage { backend.put(STORAGE_KEY, raw) }
    }

    private suspend fun <T> storage(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: JevFailure) {
        throw failure
    } catch (_: Exception) {
        throw JevFailure("PRESET_STORAGE_ERROR", "Could not access saved presets")
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    companion object {
        private const val STORAGE_KEY = "presets/v2"
        private const val MAX_PRESETS = 32
        private const val MAX_FIELD_BYTES = 128 * 1024
        private const val MAX_TOTAL_BYTES = 256 * 1024
    }
}
