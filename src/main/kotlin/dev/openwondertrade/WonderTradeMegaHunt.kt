package dev.openwondertrade

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime

/**
 * Bloqueo del MegaHunt: mientras dura, no se pueden ofrecer ni donar los Pokémon de la línea evolutiva ganadora
 * (si gana Pikachu, también Pichu y Raichu), para que no se llene la pool de los mismos Pokémon.
 * Lo activa un admin con /wt megahunt <especie> (o un comando de consola del sistema de votación) y termina solo
 * a las 00:00 del día siguiente. Se guarda en megahunt.json para sobrevivir a un reinicio.
 */
object WonderTradeMegaHunt {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var file: Path? = null
    private var root = ""
    private var line: Set<String> = emptySet()
    private var until = 0L

    fun enabled(): Boolean = ConfigManager.bool("megaHunt.enabled", true)

    fun load(directory: Path) {
        file = directory.resolve("megahunt.json")
        root = ""; line = emptySet(); until = 0L
        val path = file ?: return
        if (!Files.exists(path)) return
        runCatching {
            val node = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            root = node.get("root")?.asString ?: ""
            until = node.get("until")?.asLong ?: 0L
            line = node.getAsJsonArray("line")?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet() ?: emptySet()
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo leer megahunt.json.", it) }
    }

    private fun save() {
        val path = file ?: return
        runCatching {
            val node = JsonObject().apply {
                addProperty("root", root)
                addProperty("until", until)
                add("line", JsonArray().also { array -> line.forEach { array.add(it) } })
            }
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(node))
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar megahunt.json.", it) }
    }

    fun isActive(): Boolean = enabled() && root.isNotEmpty() && line.isNotEmpty() && System.currentTimeMillis() < until

    /** true si el Pokémon pertenece a la línea evolutiva bloqueada ahora mismo. */
    fun blocks(pokemon: Pokemon): Boolean =
        isActive() && pokemon.species.resourceIdentifier.path.lowercase() in line

    fun placeholders(): Map<String, Any> = mapOf("species" to root.replaceFirstChar { it.uppercase() })

    fun rootName(): String = root.replaceFirstChar { it.uppercase() }
    fun lineNames(): String = line.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** Línea evolutiva completa de una especie: sube hasta la primera etapa y baja por todas las evoluciones. */
    fun lineOf(start: Species): Set<String> {
        var base = start
        var guard = 0
        while (guard++ < 10) {
            val previous = base.preEvolution?.species ?: break
            base = previous
        }
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<Species>()
        queue.add(base)
        while (queue.isNotEmpty() && result.size < 40) {
            val current = queue.removeFirst()
            if (!result.add(current.resourceIdentifier.path.lowercase())) continue
            for (evolution in current.evolutions) {
                val target = evolution.result.species?.substringAfter(':')?.lowercase() ?: continue
                PokemonSpecies.getByName(target)?.let { queue.add(it) }
            }
        }
        result.add(start.resourceIdentifier.path.lowercase())
        return result
    }

    fun speciesNames(): List<String> = runCatching {
        PokemonSpecies.species.map { it.resourceIdentifier.path.lowercase() }.sorted()
    }.getOrDefault(emptyList())

    /** Activa el bloqueo hasta las 00:00 de mañana. Devuelve false si la especie no existe. */
    fun set(name: String): Boolean {
        val species = PokemonSpecies.getByName(name.substringAfter(':').lowercase()) ?: return false
        root = species.resourceIdentifier.path.lowercase()
        line = lineOf(species)
        val zone = WonderTradeSchedule.zone()
        until = ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        save()
        WonderTradePlus.LOGGER.info("MegaHunt: bloqueada la línea de {} ({}) hasta {}.", root, line, until)
        return true
    }

    fun clear() {
        root = ""; line = emptySet(); until = 0L
        save()
    }
}
