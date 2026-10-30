package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

object ConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configPath: Path = FabricLoader.getInstance().configDir.resolve("wondertradeplus/config.json")
    private var root: JsonObject = JsonObject()

    fun load() {
        Files.createDirectories(configPath.parent)
        val defaults = ConfigManager::class.java.getResourceAsStream("/wondertradeplus-default.json")?.bufferedReader()?.use {
            JsonParser.parseReader(it).asJsonObject
        } ?: throw IllegalStateException("No se encuentra wondertradeplus-default.json dentro del mod")

        val loaded = if (Files.exists(configPath)) {
            try {
                Files.newBufferedReader(configPath).use { JsonParser.parseReader(it).asJsonObject }
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.error("No se pudo leer config.json. Se usarán los valores por defecto.", exception)
                JsonObject()
            }
        } else JsonObject()

        root = mergeMissing(defaults, loaded)
        save()
    }

    private fun mergeMissing(defaults: JsonObject, supplied: JsonObject): JsonObject {
        val merged = defaults.deepCopy()
        for ((key, value) in supplied.entrySet()) {
            val defaultValue = merged.get(key)
            if (defaultValue != null && defaultValue.isJsonObject && value.isJsonObject) {
                merged.add(key, mergeMissing(defaultValue.asJsonObject, value.asJsonObject))
            } else if (value != null && !value.isJsonNull) {
                merged.add(key, value.deepCopy())
            }
        }
        return merged
    }

    fun save() = atomicWrite(configPath, root)

    fun bool(path: String, fallback: Boolean = false): Boolean = element(path)?.let {
        runCatching { it.asBoolean }.getOrDefault(fallback)
    } ?: fallback

    fun int(path: String, fallback: Int = 0): Int = element(path)?.let {
        runCatching { it.asInt }.getOrDefault(fallback)
    } ?: fallback

    fun double(path: String, fallback: Double = 0.0): Double = element(path)?.let {
        runCatching { it.asDouble }.getOrDefault(fallback)
    } ?: fallback

    fun long(path: String, fallback: Long = 0L): Long = element(path)?.let {
        runCatching { it.asLong }.getOrDefault(fallback)
    } ?: fallback

    fun string(path: String, fallback: String = ""): String = element(path)?.let {
        runCatching { it.asString }.getOrDefault(fallback)
    } ?: fallback

    fun strings(path: String): List<String> = element(path)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()

    private fun element(path: String): JsonElement? {
        var current: JsonElement = root
        for (segment in path.split('.')) {
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(segment) ?: return null
        }
        return current
    }

    private fun atomicWrite(path: Path, element: JsonElement) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(element, it) }
        try {
            Files.move(
                temporary,
                path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
