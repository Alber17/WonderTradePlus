package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter

/** Un evento de temporada de la configuración (seasonal.events), por ejemplo la Navidad. */
class Season(
    val id: String,
    private val rawName: String,
    val start: MonthDay,
    val end: MonthDay,
    val types: List<String>,
    val extraSpecies: List<String>,
    val restrictTrades: Boolean,
    val poolSize: Int,
    val shinyChance: Double,
    val includeSpecial: Boolean,
    /** Especies que no deben salir en la pool del evento. */
    val excluded: Set<String> = emptySet()
) {
    /** Nombre en el idioma activo (el texto de la config es la clave de traducción). */
    val name: String get() = Lang.tr(rawName)

    /** ¿Está [date] dentro del periodo? Admite periodos que cruzan el año (15-12 → 06-01). */
    fun contains(date: LocalDate): Boolean {
        val day = MonthDay.from(date)
        return if (!start.isAfter(end)) !day.isBefore(start) && !day.isAfter(end) else !day.isBefore(start) || !day.isAfter(end)
    }

    /** Próxima fecha de fin desde [today] (para los mensajes). */
    fun endDate(today: LocalDate): LocalDate {
        var date = end.atYear(today.year)
        if (date.isBefore(today)) date = end.atYear(today.year + 1)
        return date
    }

    fun startDate(today: LocalDate): LocalDate {
        var date = start.atYear(today.year)
        if (date.isAfter(today)) date = start.atYear(today.year - 1)
        return date
    }

    /** ¿Cumple el Pokémon el tema del evento (tipo o especie extra)? */
    fun matches(pokemon: Pokemon): Boolean {
        val species = pokemon.species.resourceIdentifier.path.lowercase()
        if (species in extraSpecies) return true
        return runCatching { pokemon.types.any { it.name.lowercase() in types } }.getOrDefault(false)
    }
}

/**
 * Eventos de temporada: durante unas fechas la pool se sustituye por otra temática (por ejemplo Pokémon de tipo hielo en
 * Navidad) y, si se configura, solo se pueden intercambiar Pokémon de ese tipo. La pool original se guarda en un archivo
 * aparte y se recupera al terminar. Todo se controla desde la sección `seasonal` de la configuración; no hay comandos.
 */
object WonderTradeSeasons {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var directory: Path? = null

    // Estado persistente (seasonal-state.json): sobrevive a reinicios y caídas del servidor.
    private var activeId: String? = null
    private var startedAt = 0L
    private var phase = ""            // "saved" = pool original guardada; "active" = pool de temporada puesta
    private var originalCount = 0

    private var lastCheck = 0L
    private var gaveUpRestoring = false
    private var warnedNoPokemon = false

    private fun stateFile(): Path? = directory?.resolve("seasonal-state.json")
    private fun originalFile(id: String): Path = directory!!.resolve("seasonal").resolve("pool-original-$id.json")

    fun load(configDirectory: Path) {
        directory = configDirectory
        activeId = null; startedAt = 0L; phase = ""; originalCount = 0
        gaveUpRestoring = false; warnedNoPokemon = false
        val file = stateFile() ?: return
        if (!Files.exists(file)) return
        runCatching {
            val node = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            activeId = node.get("activeId")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            startedAt = node.get("startedAt")?.asLong ?: 0L
            phase = node.get("phase")?.asString ?: ""
            originalCount = node.get("originalCount")?.asInt ?: 0
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo leer seasonal-state.json.", it) }
    }

    private fun saveState() {
        val file = stateFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val node = JsonObject().apply {
                if (activeId != null) addProperty("activeId", activeId)
                addProperty("startedAt", startedAt)
                addProperty("phase", phase)
                addProperty("originalCount", originalCount)
            }
            Files.writeString(file, gson.toJson(node))
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar seasonal-state.json.", it) }
    }

    // ------------------------------------------------------------ configuración

    private fun parseDay(text: String): MonthDay? =
        runCatching { MonthDay.parse("--" + text.trim().removePrefix("--").replace('/', '-')) }.getOrNull()

    private fun allSeasons(): List<Season> = ConfigManager.objects("seasonal.events").mapNotNull { node ->
        val id = node.get("id")?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val start = parseDay(node.get("startDate")?.asString ?: "") ?: return@mapNotNull null
        val end = parseDay(node.get("endDate")?.asString ?: "") ?: return@mapNotNull null
        fun list(key: String) = node.getAsJsonArray(key)?.mapNotNull { runCatching { it.asString.trim().lowercase() }.getOrNull() }?.filter { it.isNotEmpty() } ?: emptyList()
        val enabled = node.get("enabled")?.asBoolean ?: true
        if (!enabled) return@mapNotNull null
        Season(
            id = id,
            rawName = node.get("name")?.asString ?: id,
            start = start, end = end,
            types = list("types"),
            extraSpecies = list("extraSpecies").map { it.substringAfter(':') },
            restrictTrades = node.get("restrictTrades")?.asBoolean ?: true,
            poolSize = (node.get("poolSize")?.asInt ?: 100).coerceIn(1, 5000),
            shinyChance = (node.get("shinyChancePercent")?.asDouble ?: 2.0).coerceIn(0.0, 100.0),
            includeSpecial = node.get("includeLegendary")?.asBoolean ?: true,
            excluded = list("excludeSpecies").map { it.substringAfter(':') }.toSet()
        )
    }

    /** Evento de temporada con la pool ya sustituida (null si no hay ninguno en curso). */
    fun current(): Season? {
        val id = activeId?.takeIf { phase == "active" } ?: return null
        return allSeasons().firstOrNull { it.id == id }
    }

    /** Valores para los mensajes: {name}, {event}, {types}, {start}, {end}. */
    fun placeholders(season: Season? = current()): Map<String, Any> {
        season ?: return emptyMap()
        val today = LocalDate.now(WonderTradeSchedule.zone())
        val formatter = DateTimeFormatter.ofPattern("d MMMM", Lang.locale())
        val types = season.types.joinToString(", ") { WonderTradeRules.typeName(it) }
        return mapOf(
            "name" to season.name, "event" to season.name, "types" to types,
            "start" to season.startDate(today).format(formatter), "end" to season.endDate(today).format(formatter)
        )
    }

    /** Pokémon que puede haber en la pool durante el evento (se usa al generar y al rellenar). */
    fun poolAllows(pokemon: Pokemon): Boolean = current()?.matches(pokemon) ?: true

    /** ¿Se puede ofrecer este Pokémon ahora? Solo restringe durante un evento con restrictTrades. */
    fun allows(pokemon: Pokemon): Boolean {
        val season = current() ?: return true
        return !season.restrictTrades || season.matches(pokemon)
    }

    /** Línea de aviso al entrar al servidor (null si no hay evento de temporada). */
    fun joinLine(): String? {
        val season = current() ?: return null
        return WonderTradeService.format(
            ConfigManager.string("messages.seasonJoin", "&b❄ Evento de {name} activo hasta el {end}: solo se pueden intercambiar Pokémon de tipo {types}."),
            placeholders(season)
        )
    }

    // ------------------------------------------------------------ transiciones

    /** Se llama una vez por segundo; comprueba cada 30 s si hay que empezar o terminar un evento. */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 30_000L) return
        lastCheck = now
        val wanted = if (ConfigManager.bool("seasonal.enabled", true)) {
            val today = LocalDate.now(WonderTradeSchedule.zone())
            allSeasons().firstOrNull { it.contains(today) }
        } else null
        val running = activeId
        when {
            running != null && (wanted == null || wanted.id != running) -> if (!gaveUpRestoring) finish(server)
            running == null && wanted != null -> begin(server, wanted)
            running != null && phase != "active" -> allSeasons().firstOrNull { it.id == running }?.let { begin(server, it) }
        }
    }

    private fun begin(server: MinecraftServer, season: Season) {
        // Generar Pokémon necesita un jugador conectado; si no hay ninguno se espera a la siguiente comprobación.
        val player = server.playerList.players.firstOrNull() ?: return
        val entries = WonderTradeService.seasonalGenerate(player, season)
        if (entries.isEmpty()) {
            if (!warnedNoPokemon) {
                warnedNoPokemon = true
                WonderTradePlus.LOGGER.error("Evento de temporada «{}»: no se ha podido generar ningún Pokémon con los tipos {}. Revisa seasonal.events.", season.id, season.types)
            }
            return
        }
        // 1) Guardar la pool original (solo la primera vez: si ya hay estado, la original ya está a salvo).
        if (activeId == null) {
            val original = WonderTradeService.poolSnapshot()
            val ok = runCatching {
                Files.createDirectories(originalFile(season.id).parent)
                PoolStore(originalFile(season.id)).also { it.restore(original) }.save()
                Files.exists(originalFile(season.id))
            }.getOrDefault(false)
            if (!ok) {
                WonderTradePlus.LOGGER.error("Evento de temporada «{}»: no se pudo guardar la pool original; no se cambia nada.", season.id)
                return
            }
            activeId = season.id
            startedAt = System.currentTimeMillis()
            originalCount = original.size
            phase = "saved"
            saveState()
        }
        // 2) Poner la pool de temporada.
        WonderTradeService.poolReplace(entries)
        phase = "active"
        saveState()
        WonderTradePlus.LOGGER.info("Evento de temporada «{}» iniciado: pool original guardada ({} Pokémon) y {} Pokémon de temporada.", season.id, originalCount, entries.size)
        announce(server, season, started = true, count = entries.size)
    }

    private fun finish(server: MinecraftServer) {
        val id = activeId ?: return
        val file = originalFile(id)
        // PoolStore.load() crea un archivo vacío si no existe, así que se comprueba antes.
        if (!Files.exists(file)) {
            WonderTradePlus.LOGGER.error("Evento de temporada «{}»: falta {}; no se puede restaurar la pool original. La pool actual se conserva.", id, file)
            gaveUpRestoring = true
            return
        }
        val original = PoolStore(file).also { it.load() }.snapshot()
        if (original.isEmpty() && originalCount > 0) {
            WonderTradePlus.LOGGER.error("Evento de temporada «{}»: la pool original guardada está vacía o dañada (se esperaban {}). La pool actual se conserva; revisa {}.", id, originalCount, file)
            gaveUpRestoring = true
            return
        }
        val season = allSeasons().firstOrNull { it.id == id }
        val now = System.currentTimeMillis()
        // Se archiva la pool del evento y se recupera la original.
        runCatching {
            PoolStore(file.resolveSibling("pool-event-$id-${LocalDate.now(WonderTradeSchedule.zone()).year}.json"))
                .also { it.restore(WonderTradeService.poolSnapshot()) }.save()
        }.onFailure { WonderTradePlus.LOGGER.warn("No se pudo archivar la pool del evento: {}", it.message) }
        // Durante el evento la pool original estuvo «congelada»: se desplaza su antigüedad para que la caducidad
        // y la purga automática no la traten como si hubiera envejecido.
        val shift = (now - startedAt).coerceAtLeast(0L)
        val restored = original.map { if (it.addedAt > 0L) it.copy(addedAt = minOf(it.addedAt + shift, now)) else it }
        WonderTradeService.poolReplace(restored)
        activeId = null; startedAt = 0L; phase = ""; originalCount = 0
        saveState()
        WonderTradePlus.LOGGER.info("Evento de temporada «{}» terminado: pool original restaurada ({} Pokémon).", id, restored.size)
        if (season != null) announce(server, season, started = false, count = restored.size)
        else WonderTradeEvents.broadcast(server) { Lang.tr("&d★ Ha terminado el evento de temporada. La pool vuelve a la normalidad.") }
    }

    private fun announce(server: MinecraftServer, season: Season, started: Boolean, count: Int) {
        val key = if (started) "messages.seasonStart" else "messages.seasonEnd"
        val fallback = if (started)
            "&b❄ ¡Empieza el evento de {name}! Hasta el {end} la pool es temática y solo se pueden intercambiar Pokémon de tipo {types}. La pool original se guarda y volverá al terminar el evento."
        else
            "&b❄ Ha terminado el evento de {name}. ¡La pool original vuelve a estar disponible!"
        WonderTradeEvents.broadcast(server) {
            WonderTradeService.format(ConfigManager.string(key, fallback), placeholders(season))
        }
        runCatching { WonderTradeDiscord.season(season, started, count) }
    }
}
