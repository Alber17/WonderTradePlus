package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Una donación directa ya completada. Se guarda un resumen, no el Pokémon completo. */
data class DonationEntry(
    val time: Long,
    var player: String,
    var uuid: String,
    val species: String,
    val name: String,
    val level: Int,
    val shiny: Boolean
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("time", time)
        addProperty("player", player)
        if (uuid.isNotBlank()) addProperty("uuid", uuid)
        addProperty("species", species)
        addProperty("name", name)
        addProperty("level", level)
        addProperty("shiny", shiny)
    }

    companion object {
        fun fromJson(json: JsonObject) = DonationEntry(
            time = json.get("time")?.asLong ?: 0L,
            player = json.get("player")?.asString ?: "?",
            uuid = json.get("uuid")?.takeIf { !it.isJsonNull }?.asString ?: "",
            species = json.get("species")?.asString ?: "",
            name = json.get("name")?.asString ?: "?",
            level = json.get("level")?.asInt ?: 1,
            shiny = json.get("shiny")?.asBoolean ?: false
        )
    }
}

class DonationHistoryStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val entries = mutableListOf<DonationEntry>()

    val size: Int get() = entries.size

    /** Más recientes primero; solo las de un jugador si se indica su UUID o, en su defecto, su nick. */
    fun recent(player: String? = null, uuid: String? = null): List<DonationEntry> =
        entries.asReversed().filter {
            when {
                player == null && uuid == null -> true
                uuid != null && it.uuid.isNotBlank() -> it.uuid == uuid
                else -> player != null && it.player.equals(player, ignoreCase = true)
            }
        }

    /** Un jugador por UUID, con su nick actual, cuántas donaciones tiene y la fecha de la última. */
    fun donors(): List<Triple<String, String, Pair<Int, Long>>> {
        val grouped = linkedMapOf<String, MutableList<DonationEntry>>()
        for (entry in entries) {
            val key = entry.uuid.ifBlank { entry.player.lowercase() }
            grouped.getOrPut(key) { mutableListOf() }.add(entry)
        }
        return grouped.map { (_, list) ->
            val latest = list.maxBy { it.time }
            Triple(latest.uuid, latest.player, list.size to latest.time)
        }.sortedBy { it.second.lowercase() }
    }

    fun syncIdentities(known: List<Pair<UUID, String>>): Boolean {
        val byName = known.associate { it.second.lowercase() to it.first.toString() }
        val byUuid = known.associate { it.first.toString() to it.second }
        var changed = false
        for (entry in entries) {
            if (entry.uuid.isBlank()) {
                byName[entry.player.lowercase()]?.let { entry.uuid = it; changed = true }
            }
            byUuid[entry.uuid]?.let { name -> if (entry.player != name) { entry.player = name; changed = true } }
        }
        return changed
    }

    fun rename(uuid: String, name: String): Boolean {
        var changed = false
        for (entry in entries) if (entry.uuid == uuid && entry.player != name) { entry.player = name; changed = true }
        return changed
    }

    fun pruneOlderThanDays(days: Int, now: Long = System.currentTimeMillis()): Int {
        if (days <= 0) return 0
        val cutoff = now - days.toLong() * 86_400_000L
        val before = entries.size
        entries.removeAll { it.time > 0L && it.time < cutoff }
        val removed = before - entries.size
        if (removed > 0) save()
        return removed
    }

    fun add(entry: DonationEntry, maximum: Int) {
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
                runCatching { entries.add(DonationEntry.fromJson(node.asJsonObject)) }
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo leer donation-history.json; se empieza sin historial de donaciones.", exception)
            runCatching { Files.copy(file, file.resolveSibling("donation-history.json.corrupt-${System.currentTimeMillis()}")) }
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
