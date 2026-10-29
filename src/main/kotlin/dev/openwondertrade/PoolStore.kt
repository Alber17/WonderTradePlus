package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.RegistryAccess
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A full Cobblemon JSON snapshot plus a small index used to filter the pool quickly. */
data class PoolEntry(
    val id: String,
    val pokemonJson: JsonObject,
    val species: String,
    val level: Int,
    val shiny: Boolean,
    val special: Boolean,
    val alpha: Boolean,
    val addedAt: Long,
    val donorName: String
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        add("pokemon", pokemonJson.deepCopy())
        addProperty("species", species)
        addProperty("level", level)
        addProperty("shiny", shiny)
        addProperty("special", special)
        addProperty("alpha", alpha)
        addProperty("addedAt", addedAt)
        addProperty("donorName", donorName)
    }

    fun deepCopyEntry() = copy(pokemonJson = pokemonJson.deepCopy())

    companion object {
        fun fromPokemon(pokemon: Pokemon, registryAccess: RegistryAccess, donorName: String, timestamp: Long = System.currentTimeMillis()): PoolEntry {
            return PoolEntry(
                id = UUID.randomUUID().toString(),
                pokemonJson = pokemon.saveToJSON(registryAccess, JsonObject()).deepCopy(),
                species = pokemon.species.resourceIdentifier.toString().lowercase(),
                level = pokemon.level,
                shiny = pokemon.shiny,
                special = WonderTradeRules.isSpecial(pokemon),
                alpha = pokemon.isAlpha,
                addedAt = timestamp,
                donorName = donorName
            )
        }

        fun fromJson(json: JsonObject): PoolEntry {
            return PoolEntry(
                id = json.get("id")?.asString ?: UUID.randomUUID().toString(),
                pokemonJson = json.getAsJsonObject("pokemon").deepCopy(),
                species = json.get("species")?.asString ?: "minecraft:unknown",
                level = json.get("level")?.asInt ?: 1,
                shiny = json.get("shiny")?.asBoolean ?: false,
                special = json.get("special")?.asBoolean ?: false,
                alpha = json.get("alpha")?.asBoolean ?: false,
                addedAt = json.get("addedAt")?.asLong ?: 0L,
                donorName = json.get("donorName")?.asString ?: "unknown"
            )
        }
    }
}

class PoolStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val entries = mutableListOf<PoolEntry>()

    val size: Int get() = entries.size
    fun all(): List<PoolEntry> = entries.map { it.deepCopyEntry() }
    fun entryById(id: String): PoolEntry? = entries.firstOrNull { it.id == id }?.deepCopyEntry()
    fun add(entry: PoolEntry) { entries.add(entry.deepCopyEntry()) }
    fun remove(id: String): Boolean = entries.removeIf { it.id == id }
    fun clear() = entries.clear()

    fun snapshot(): List<PoolEntry> = entries.map { it.deepCopyEntry() }
    fun restore(snapshot: List<PoolEntry>) {
        entries.clear()
        entries.addAll(snapshot.map { it.deepCopyEntry() })
    }

    fun trim(maximum: Int, oldestFirst: Boolean) {
        if (maximum < 1 || entries.size <= maximum) return
        while (entries.size > maximum) {
            val index = if (oldestFirst) entries.indices.minByOrNull { entries[it].addedAt } ?: 0 else entries.lastIndex
            entries.removeAt(index)
        }
    }

    fun load() {
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            entries.clear()
            val array = root.getAsJsonArray("entries") ?: JsonArray()
            for (node in array) {
                try {
                    entries.add(PoolEntry.fromJson(node.asJsonObject))
                } catch (exception: Exception) {
                    WonderTradePlus.LOGGER.warn("Se ha ignorado una entrada corrupta del pool de Wonder Trade.", exception)
                }
            }
            WonderTradePlus.LOGGER.info("WonderTrade Plus ha cargado {} Pokémon en el pool.", entries.size)
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo cargar pool.json. Se conserva una copia de seguridad y se empieza con un pool vacío.", exception)
            runCatching { Files.copy(file, file.resolveSibling("pool.json.corrupt-${System.currentTimeMillis()}")) }
            entries.clear()
            save()
        }
    }

    fun save() {
        Files.createDirectories(file.parent)
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("savedAt", System.currentTimeMillis())
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
