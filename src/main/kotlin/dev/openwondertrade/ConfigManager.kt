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
        // Idiomas: carga es_es (principal) y el idioma elegido en general.language.
        Lang.load(configPath.parent, string("general.language", Lang.DEFAULT))

        // Migration: 5000 was accidentally shipped as the pool default in 0.8.6.
        // The intended maximum is 500, so update that legacy default in existing configs too.
        root.getAsJsonObject("pool")?.let { poolConfig ->
            if (poolConfig.get("maximumEntries")?.let { runCatching { it.asInt }.getOrNull() } == 5000) {
                poolConfig.addProperty("maximumEntries", 500)
            }
        }
        root.getAsJsonObject("admin")?.getAsJsonObject("fillMenu")?.let { fillMenu ->
            val amounts = fillMenu.getAsJsonArray("amounts")
            val previousDefaults = listOf(250, 500, 1000, 2500, 5000)
            val configuredAmounts = amounts?.mapNotNull { runCatching { it.asInt }.getOrNull() }
            if (configuredAmounts == previousDefaults) {
                fillMenu.add("amounts", com.google.gson.JsonArray().also { array ->
                    listOf(25, 50, 100, 250, 500).forEach { array.add(it) }
                })
            }
        }
        // Migración: los rangos genéricos «Rango 1-5» de la 0.13.0 se sustituyen por los del servidor
        // solo si siguen exactamente como se entregaron (sin que hayas editado nada).
        root.getAsJsonObject("ranks")?.let { ranksConfig ->
            val previous = com.google.gson.JsonParser.parseString("[{\"id\": \"rango5\", \"name\": \"&6Rango 5\", \"permission\": \"wondertradeplus.rank.5\", \"tag\": \"wt_rank_5\", \"baseBonus\": 1.0, \"gainMultiplier\": 1.0}, {\"id\": \"rango4\", \"name\": \"&dRango 4\", \"permission\": \"wondertradeplus.rank.4\", \"tag\": \"wt_rank_4\", \"baseBonus\": 0.0, \"gainMultiplier\": 1.0}, {\"id\": \"rango3\", \"name\": \"&bRango 3\", \"permission\": \"wondertradeplus.rank.3\", \"tag\": \"wt_rank_3\", \"baseBonus\": 0.0, \"gainMultiplier\": 1.0}, {\"id\": \"rango2\", \"name\": \"&aRango 2\", \"permission\": \"wondertradeplus.rank.2\", \"tag\": \"wt_rank_2\", \"baseBonus\": 0.0, \"gainMultiplier\": 1.0}, {\"id\": \"rango1\", \"name\": \"&eRango 1\", \"permission\": \"wondertradeplus.rank.1\", \"tag\": \"wt_rank_1\", \"baseBonus\": 0.0, \"gainMultiplier\": 1.0}]")
            if (ranksConfig.get("list") == previous) {
                defaults.getAsJsonObject("ranks")?.get("list")?.let { ranksConfig.add("list", it.deepCopy()) }
            }
        }
        // Migración 0.17.6: los rangos ya guardados reciben «improvementPercent» desde los valores por defecto (por id).
        root.getAsJsonObject("ranks")?.getAsJsonArray("list")?.let { list ->
            val defaultRanks = defaults.getAsJsonObject("ranks")?.getAsJsonArray("list")
            for (node in list) {
                val rank = node as? JsonObject ?: continue
                if (rank.has("improvementPercent")) continue
                val fresh = defaultRanks?.firstOrNull { (it as? JsonObject)?.get("id") == rank.get("id") } as? JsonObject
                rank.addProperty("improvementPercent", fresh?.get("improvementPercent")?.asDouble ?: 0.0)
            }
        }
        // Migración de la ayuda de eventos: los textos de versiones anteriores se sustituyen por los actuales.
        root.getAsJsonObject("messages")?.let { messages ->
            val defaultMessages = defaults.getAsJsonObject("messages")
            fun refresh(key: String, isOld: (String) -> Boolean) {
                val current = messages.get(key)?.let { node -> runCatching { node.asString }.getOrNull() } ?: return
                val fresh = defaultMessages?.get(key) ?: return
                if (isOld(current)) messages.add(key, fresh.deepCopy())
            }
            refresh("helpAdminEventsCommand") { it.contains("[crear") }
            refresh("helpAdminScheduleCommand") {
                it == "&e/wt admin eventos programar <tipo> <categoría> <duración> <cuándo> &7Programar un evento" ||
                    it == "&e/wt admin eventos programar <tipo> <categorías> <duración> <cuándo> &7Programar un evento"
            }
        }
        // Migración de mensajes: si el texto guardado sigue siendo exactamente el antiguo por defecto,
        // se sustituye por el corregido (color de la exclamación final, espacios sobrantes).
        val messageFixes = mapOf(
            "broadcastShinySpecial" to ("&d&6{player} ha añadido un Pokémon LEGENDARIO SHINY al pool de Wonder Trade: &f{pokemon}&d!" to "&6{player} ha añadido un Pokémon LEGENDARIO SHINY al pool de Wonder Trade: &f{pokemon}&6!"),
            "broadcastShinyUltraBeast" to ("&d&3◆ {player} ha añadido un ULTRAENTE SHINY al pool de Wonder Trade: &f{pokemon}&d!" to "&3◆ {player} ha añadido un ULTRAENTE SHINY al pool de Wonder Trade: &f{pokemon}&3!"),
            "broadcastShinyPastParadox" to ("&d&c⌛ {player} ha añadido un Pokémon del PASADO SHINY al pool de Wonder Trade: &f{pokemon}&d!" to "&c⌛ {player} ha añadido un Pokémon del PASADO SHINY al pool de Wonder Trade: &f{pokemon}&c!"),
            "broadcastShinyFutureParadox" to ("&d&b⚛ {player} ha añadido un Pokémon del FUTURO SHINY al pool de Wonder Trade: &f{pokemon}&d!" to "&b⚛ {player} ha añadido un Pokémon del FUTURO SHINY al pool de Wonder Trade: &f{pokemon}&b!"),
            "broadcastShiny" to ("&d {player} ha añadido un Pokémon shiny al pool de Wonder Trade: &f{pokemon}&d!" to "&d{player} ha añadido un Pokémon shiny al pool de Wonder Trade: &f{pokemon}&d!")
        )
        root.getAsJsonObject("messages")?.let { messages ->
            for ((key, pair) in messageFixes) {
                val current = messages.get(key)?.let { node -> runCatching { node.asString }.getOrNull() }
                if (current == pair.first) messages.addProperty(key, pair.second)
            }
        }
        // Migración 0.21.0: el nombre del mod pasa a negrita; un prefijo sin personalizar se actualiza.
        root.getAsJsonObject("messages")?.let { messages ->
            if (messages.get("prefix")?.let { runCatching { it.asString }.getOrNull() } == "&8[&dWonderTrade&8] &r") messages.remove("prefix")
        }
        // Migración 0.20.0: los textos ya no se guardan en config.json, salen del idioma elegido (carpeta lang).
        // Se quitan de config.json los que siguen iguales al texto de es_es; los que hayas personalizado se conservan
        // y tienen prioridad sobre el idioma.
        for ((path, text) in Lang.baseEntries()) {
            if (string(path, "\u0000") == text) removePath(path)
        }
        // 0.26.10: la bossbar ya no usa estos colores, la talla ya no usa umbrales y la action bar desapareció.
        root.getAsJsonObject("events")?.getAsJsonObject("bossbar")?.let { bossbar ->
            bossbar.remove("risk")
            bossbar.remove("receive")
            val format = bossbar.get("format")?.takeIf { it.isJsonPrimitive }?.asString
            if (format == "&d★ {name} &7— &f{time}") {
                bossbar.addProperty("format", "★ {name} — {time}")
            }
        }
        root.getAsJsonObject("pool")?.getAsJsonObject("size")?.let { size ->
            listOf("xsMax", "sMax", "mMax", "lMax").forEach { size.remove(it) }
        }
        root.remove("animations")
        // Migración 0.26.3: Navidad pasa de una temporada continua a cuatro sesiones aleatorias de una hora.
        // Solo cambia la fecha antigua que venía de fábrica; se respetan fechas personalizadas.
        root.getAsJsonObject("seasonal")?.getAsJsonArray("events")?.firstOrNull { element ->
            runCatching { element.asJsonObject.get("id")?.asString == "navidad" }.getOrDefault(false)
        }?.asJsonObject?.let { christmas ->
            if (christmas.get("startDate")?.asString == "12-15") christmas.addProperty("startDate", "12-25")
            if (!christmas.has("sessionsPerDay")) christmas.addProperty("sessionsPerDay", 4)
            if (!christmas.has("sessionDurationMinutes")) christmas.addProperty("sessionDurationMinutes", 60)
        }
        save()
        writeGuide()
    }

    private fun removePath(path: String) {
        val segments = path.split('.')
        var current: JsonObject = root
        for (segment in segments.dropLast(1)) current = current.get(segment)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        current.remove(segments.last())
    }

    /** Cambia un texto en memoria y lo guarda en config.json (crea los apartados que falten). */
    fun setString(path: String, value: String) {
        val segments = path.split('.')
        var current = root
        for (segment in segments.dropLast(1)) {
            val next = current.get(segment)
            current = if (next != null && next.isJsonObject) next.asJsonObject
            else JsonObject().also { current.add(segment, it) }
        }
        current.addProperty(segments.last(), value)
        save()
    }

    /** Copia config-guide.txt (explicación de cada apartado) junto a config.json. Se regenera en cada arranque. */
    private fun writeGuide() {
        runCatching {
            ConfigManager::class.java.getResourceAsStream("/config-guide.txt")?.use { input ->
                Files.copy(input, configPath.resolveSibling("config-guide.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { WonderTradePlus.LOGGER.warn("No se pudo escribir config-guide.txt: {}", it.message) }
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

    /** Cambia un valor booleano en memoria y lo guarda en config.json (crea los apartados que falten). */
    fun setBool(path: String, value: Boolean) {
        val segments = path.split('.')
        var current = root
        for (segment in segments.dropLast(1)) {
            val next = current.get(segment)
            current = if (next != null && next.isJsonObject) next.asJsonObject
            else JsonObject().also { current.add(segment, it) }
        }
        current.addProperty(segments.last(), value)
        save()
    }

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

    /**
     * Texto o ajuste. Prioridad: lo que haya en config.json, el texto del idioma activo (carpeta lang) y por último
     * el valor por defecto del código (traducido si el idioma lo tiene).
     */
    fun string(path: String, fallback: String = ""): String {
        element(path)?.let { node -> runCatching { node.asString }.getOrNull()?.let { return it } }
        return Lang.text(path) ?: Lang.tr(fallback)
    }

    fun strings(path: String): List<String> = element(path)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()

    /** Lista de objetos JSON (por ejemplo ranks.list). */
    fun objects(path: String): List<JsonObject> = element(path)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null } ?: emptyList()

    fun ints(path: String): List<Int> = element(path)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asInt }.getOrNull() } ?: emptyList()

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
