package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** Un intercambio ya completado. Se guarda un resumen, no el Pokémon completo. */
data class HistoryEntry(
    val time: Long,
    val player: String,
    val sentSpecies: String,
    val sentName: String,
    val sentLevel: Int,
    val sentShiny: Boolean,
    val receivedSpecies: String,
    val receivedName: String,
    val receivedLevel: Int,
    val receivedShiny: Boolean,
    val fromPool: Boolean
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("time", time)
        addProperty("player", player)
        addProperty("sentSpecies", sentSpecies)
        addProperty("sentName", sentName)
        addProperty("sentLevel", sentLevel)
        addProperty("sentShiny", sentShiny)
        addProperty("receivedSpecies", receivedSpecies)
        addProperty("receivedName", receivedName)
        addProperty("receivedLevel", receivedLevel)
        addProperty("receivedShiny", receivedShiny)
        addProperty("fromPool", fromPool)
    }

    companion object {
        fun fromJson(json: JsonObject) = HistoryEntry(
            time = json.get("time")?.asLong ?: 0L,
            player = json.get("player")?.asString ?: "?",
            sentSpecies = json.get("sentSpecies")?.asString ?: "",
            sentName = json.get("sentName")?.asString ?: "?",
            sentLevel = json.get("sentLevel")?.asInt ?: 1,
            sentShiny = json.get("sentShiny")?.asBoolean ?: false,
            receivedSpecies = json.get("receivedSpecies")?.asString ?: "",
            receivedName = json.get("receivedName")?.asString ?: "?",
            receivedLevel = json.get("receivedLevel")?.asInt ?: 1,
            receivedShiny = json.get("receivedShiny")?.asBoolean ?: false,
            fromPool = json.get("fromPool")?.asBoolean ?: false
        )
    }
}

class HistoryStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val entries = mutableListOf<HistoryEntry>()

    val size: Int get() = entries.size

    /** Más recientes primero; opcionalmente solo los de un jugador. */
    fun recent(player: String? = null): List<HistoryEntry> =
        entries.asReversed().filter { player == null || it.player.equals(player, ignoreCase = true) }

    fun players(): List<String> = entries.map { it.player }.distinct()

    fun add(entry: HistoryEntry, maximum: Int) {
        entries.add(entry)
        while (maximum > 0 && entries.size > maximum) entries.removeAt(0)
    }

    fun load() {
        Files.createDirectories(file.parent)
        entries.clear()
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            for (node in root.getAsJsonArray("entries") ?: JsonArray()) {
                runCatching { entries.add(HistoryEntry.fromJson(node.asJsonObject)) }
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo leer history.json; se empieza con un historial vacío.", exception)
            runCatching { Files.copy(file, file.resolveSibling("history.json.corrupt-${System.currentTimeMillis()}")) }
            entries.clear()
            save()
        }
    }

    fun save() {
        Files.createDirectories(file.parent)
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("entries", JsonArray().also { array -> entries.forEach { array.add(it.toJson()) } })
        }
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
