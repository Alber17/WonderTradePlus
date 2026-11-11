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

/** Un evento de temporada de la configuración (seasonal.events). */
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
    val excluded: Set<String> = emptySet(),
    val sessionsPerDay: Int = 0,
    val sessionDurationMinutes: Int = 60,
    /** Avisos de apertura (00:00 del primer día) y cierre (23:59 del último día) de la temporada. Solo Navidad. */
    val announceWindow: Boolean = false
) {
    val name: String get() = Lang.tr(rawName)

    /** Tema visual y de textos: «halloween» tiene los suyos; cualquier otra temporada usa los de Navidad. */
    val theme: String get() = if (id == "halloween") "halloween" else "christmas"

    fun contains(date: LocalDate): Boolean {
        val day = MonthDay.from(date)
        return if (!start.isAfter(end)) !day.isBefore(start) && !day.isAfter(end) else !day.isBefore(start) || !day.isAfter(end)
    }

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

    fun matches(pokemon: Pokemon): Boolean {
        val species = pokemon.species.resourceIdentifier.path.lowercase()
        if (species in extraSpecies) return true
        return runCatching { pokemon.types.any { it.name.lowercase() in types } }.getOrDefault(false)
    }
}

/**
 * Eventos de temporada. Las sesiones aleatorias se guardan por fecha para que no cambien al reiniciar.
 * Cada sesión sustituye temporalmente la pool y restaura exactamente la original al terminar.
 */
object WonderTradeSeasons {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var directory: Path? = null
    private var activeId: String? = null
    private var startedAt = 0L
    private var endAt = 0L
    private var pausedAt = 0L
    private var phase = "" // saved, active, paused
    private var originalCount = 0
    private var skipAutomaticDate = ""
    private val dailySlots = linkedMapOf<String, List<Long>>()
    private var lastCheck = 0L
    private var gaveUpRestoring = false
    private var warnedNoPokemon = false
    private val announced = linkedSetOf<String>()

    private fun stateFile(): Path? = directory?.resolve("seasonal-state.json")
    private fun originalFile(id: String): Path = directory!!.resolve("seasonal").resolve("pool-original-$id.json")

    fun load(configDirectory: Path) {
        directory = configDirectory
        activeId = null; startedAt = 0L; endAt = 0L; pausedAt = 0L
        phase = ""; originalCount = 0; skipAutomaticDate = ""
        dailySlots.clear(); announced.clear(); gaveUpRestoring = false; warnedNoPokemon = false
        val file = stateFile() ?: return
        if (!Files.exists(file)) return
        runCatching {
            val node = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            activeId = node.get("activeId")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            startedAt = node.get("startedAt")?.asLong ?: 0L
            endAt = node.get("endAt")?.asLong ?: 0L
            pausedAt = node.get("pausedAt")?.asLong ?: 0L
            phase = node.get("phase")?.asString ?: ""
            originalCount = node.get("originalCount")?.asInt ?: 0
            skipAutomaticDate = node.get("skipAutomaticDate")?.asString ?: ""
            node.getAsJsonArray("announced")?.forEach { runCatching { announced.add(it.asString) } }
            node.getAsJsonObject("dailySlots")?.entrySet()?.forEach { (date, arr) ->
                dailySlots[date] = arr.asJsonArray.mapNotNull { runCatching { it.asLong }.getOrNull() }
            }
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo leer seasonal-state.json.", it) }
    }

    private fun saveState() {
        val file = stateFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val node = JsonObject().apply {
                if (activeId != null) addProperty("activeId", activeId)
                addProperty("startedAt", startedAt)
                addProperty("endAt", endAt)
                addProperty("pausedAt", pausedAt)
                addProperty("phase", phase)
                addProperty("originalCount", originalCount)
                addProperty("skipAutomaticDate", skipAutomaticDate)
                add("announced", com.google.gson.JsonArray().also { arr -> announced.forEach { arr.add(it) } })
                add("dailySlots", JsonObject().also { obj ->
                    dailySlots.forEach { (date, slots) ->
                        obj.add(date, com.google.gson.JsonArray().also { arr -> slots.forEach { arr.add(it) } })
                    }
                })
            }
            Files.writeString(file, gson.toJson(node))
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar seasonal-state.json.", it) }
    }

    private fun parseDay(text: String): MonthDay? =
        runCatching { MonthDay.parse("--" + text.trim().removePrefix("--").replace('/', '-')) }.getOrNull()

    /** Eventos de la config más los que vienen de serie y aún no están en ella (así Halloween aparece en configs ya creadas). */
    private val defaultNodes: List<JsonObject> by lazy {
        runCatching {
            WonderTradeSeasons::class.java.getResourceAsStream("/wondertradeplus-default.json")?.bufferedReader()?.use { reader ->
                JsonParser.parseReader(reader).asJsonObject.getAsJsonObject("seasonal")?.getAsJsonArray("events")?.map { it.asJsonObject }
            }
        }.getOrNull() ?: emptyList()
    }

    private fun eventNodes(): List<JsonObject> {
        val configured = ConfigManager.objects("seasonal.events")
        val ids = configured.mapNotNull { it.get("id")?.asString?.trim()?.lowercase() }.toSet()
        return configured + defaultNodes.filter { (it.get("id")?.asString?.trim()?.lowercase() ?: "") !in ids }
    }

    fun byId(id: String): Season? = allSeasons().firstOrNull { it.id == id }
    fun isRunning(id: String): Boolean = isRunning() && activeId == id
    fun isPaused(id: String): Boolean = isPaused() && activeId == id
    fun statusText(id: String): String = if (activeId == id) statusText() else "INACTIVO"

    private fun allSeasons(): List<Season> = eventNodes().mapNotNull { node ->
        val id = node.get("id")?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val start = parseDay(node.get("startDate")?.asString ?: "") ?: return@mapNotNull null
        val end = parseDay(node.get("endDate")?.asString ?: "") ?: return@mapNotNull null
        fun list(key: String) = node.getAsJsonArray(key)?.mapNotNull { runCatching { it.asString.trim().lowercase() }.getOrNull() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (node.get("enabled")?.asBoolean == false) return@mapNotNull null
        Season(
            id = id, rawName = node.get("name")?.asString ?: id, start = start, end = end,
            types = list("types"), extraSpecies = list("extraSpecies").map { it.substringAfter(':') },
            restrictTrades = node.get("restrictTrades")?.asBoolean ?: true,
            poolSize = (node.get("poolSize")?.asInt ?: 100).coerceIn(1, 5000),
            shinyChance = (node.get("shinyChancePercent")?.asDouble ?: 2.0).coerceIn(0.0, 100.0),
            includeSpecial = node.get("includeLegendary")?.asBoolean ?: true,
            excluded = list("excludeSpecies").map { it.substringAfter(':') }.toSet(),
            sessionsPerDay = (node.get("sessionsPerDay")?.asInt ?: 0).coerceIn(0, 12),
            sessionDurationMinutes = (node.get("sessionDurationMinutes")?.asInt ?: 60).coerceIn(1, 1440),
            announceWindow = id != "halloween" && (node.get("windowAnnouncements")?.let { runCatching { it.asBoolean }.getOrNull() } ?: true)
        )
    }

    fun current(): Season? {
        val id = activeId?.takeIf { phase == "active" || phase == "paused" } ?: return null
        return allSeasons().firstOrNull { it.id == id }
    }

    fun isRunning(): Boolean = phase == "active" && activeId != null
    fun sessionEndAt(): Long = endAt
    fun isPaused(): Boolean = phase == "paused" && activeId != null
    fun statusText(): String = when {
        isRunning() -> "ACTIVO"
        isPaused() -> "PAUSADO"
        else -> "INACTIVO"
    }

    fun placeholders(season: Season? = current()): Map<String, Any> {
        season ?: return emptyMap()
        val today = LocalDate.now(WonderTradeSchedule.zone())
        val formatter = DateTimeFormatter.ofPattern("d MMMM", Lang.locale())
        val types = season.types.joinToString(", ") { WonderTradeRules.typeName(it) }
        return mapOf("name" to season.name, "event" to season.name, "types" to types,
            "start" to season.startDate(today).format(formatter), "end" to season.endDate(today).format(formatter),
            "duration" to WonderTradeEvents.formatDuration(season.sessionDurationMinutes * 60L),
            "sessions" to season.sessionsPerDay,
            "sessionsInfo" to (if (season.sessionsPerDay > 0)
                Lang.t("Cada día habrá {0} sesiones de {1} en horas aleatorias; cada una se avisa en el chat.", season.sessionsPerDay, WonderTradeEvents.formatDuration(season.sessionDurationMinutes * 60L))
            else Lang.tr("La pool temática estará activa durante todo el periodo.")),
            "rules" to (if (season.restrictTrades) Lang.tr("Mientras dure, solo se podrán ofrecer Pokémon de esa temática.") else ""))
    }

    fun poolAllows(pokemon: Pokemon): Boolean = current()?.matches(pokemon) ?: true
    fun allows(pokemon: Pokemon): Boolean {
        val season = current() ?: return true
        return !season.restrictTrades || season.matches(pokemon)
    }

    fun joinLine(): String? {
        val season = current() ?: return null
        return WonderTradeService.format(
            if (season.theme == "halloween") ConfigManager.string("messages.halloweenJoin", "&6☠ Noche de Brujas activa: quedan {duration}.")
            else ConfigManager.string("messages.seasonJoin", "&b❄ Hora navideña activa: quedan {duration}."),
            placeholders(season) + mapOf("duration" to WonderTradeEvents.formatDuration(((endAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L))
        )
    }

    private fun isInDateWindow(season: Season, date: LocalDate): Boolean = season.contains(date)

    /** Cuatro horas distintas, elegidas aleatoriamente y persistidas para que sean estables ese día. */
    private fun slotsFor(season: Season, date: LocalDate): List<Long> {
        val key = "${season.id}:$date"
        dailySlots[key]?.let { return it }
        val count = season.sessionsPerDay
        if (count <= 0) return listOf(date.atStartOfDay(WonderTradeSchedule.zone()).toInstant().toEpochMilli())
        val chosen = mutableListOf<Int>()
        for (hour in (0..23).shuffled()) {
            if (chosen.all { other -> minOf((hour - other + 24) % 24, (other - hour + 24) % 24) >= 3 }) {
                chosen += hour
                if (chosen.size == count) break
            }
        }
        val hours = if (chosen.size == count) chosen.sorted() else (0..23).shuffled().take(count).sorted()
        val slots = hours.map { hour -> date.atTime(hour, 0).atZone(WonderTradeSchedule.zone()).toInstant().toEpochMilli() }
        dailySlots[key] = slots
        // Mantener solo 30 días para que el estado no crezca indefinidamente.
        if (dailySlots.size > 30) dailySlots.keys.take(dailySlots.size - 30).forEach { dailySlots.remove(it) }
        saveState()
        return slots
    }

    private fun scheduledSession(season: Season, now: Long): Long? {
        val date = java.time.Instant.ofEpochMilli(now).atZone(WonderTradeSchedule.zone()).toLocalDate()
        if (!isInDateWindow(season, date)) return null
        if (season.sessionsPerDay <= 0) return slotsFor(season, date).firstOrNull { now >= it }
        val duration = season.sessionDurationMinutes * 60_000L
        return slotsFor(season, date).firstOrNull { now >= it && now < it + duration }
    }

    /** Control de sesiones automáticas y expiración. Se llama una vez por segundo. */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 1_000L) return
        lastCheck = now
        val today = LocalDate.now(WonderTradeSchedule.zone())
        val enabled = ConfigManager.bool("seasonal.enabled", true)
        val season = if (enabled) allSeasons().firstOrNull { it.contains(today) } else null
        if (enabled) runCatching { windowAnnouncements(server, today) }
            .onFailure { WonderTradePlus.LOGGER.error("Falló el aviso de apertura/cierre de la temporada.", it) }
        if (activeId != null) {
            if (phase == "paused") {
                val configured = ConfigManager.bool("seasonal.enabled", true) && allSeasons().any { it.id == activeId }
                if (!configured) finish(server, announceEnd = true)
                return
            }
            if (phase == "saved") {
                val savedSeason = if (ConfigManager.bool("seasonal.enabled", true)) allSeasons().firstOrNull { it.id == activeId } else null
                if (now >= endAt || savedSeason == null) finish(server, announceEnd = true)
                else activateSavedPool(server, savedSeason)
                return
            }
            val configured = ConfigManager.bool("seasonal.enabled", true) && allSeasons().any { it.id == activeId }
            if (now >= endAt || !configured) finish(server, announceEnd = true)
            return
        }
        if (season == null || skipAutomaticDate == today.toString()) return
        val sessionStart = scheduledSession(season, now) ?: return
        if (season.sessionsPerDay <= 0) {
            val endDate = season.endDate(today).plusDays(1)
            val continuousEnd = endDate.atStartOfDay(WonderTradeSchedule.zone()).toInstant().toEpochMilli()
            begin(server, season, continuousEnd)
        } else {
            val duration = season.sessionDurationMinutes * 60_000L
            begin(server, season, now + duration)
        }
    }

    /** Aviso único de apertura (00:00 del primer día) y de cierre (23:59 del último día) de la temporada. */
    private fun windowAnnouncements(server: MinecraftServer, today: LocalDate) {
        val time = java.time.LocalTime.now(WonderTradeSchedule.zone())
        for (season in allSeasons()) {
            if (!season.announceWindow || !season.contains(today)) continue
            if (today == season.startDate(today)) markAndAnnounce("open:${season.id}:$today") { announceWindow(server, season, true) }
            if (today == season.endDate(today) && time.hour == 23 && time.minute == 59)
                markAndAnnounce("close:${season.id}:$today") { announceWindow(server, season, false) }
        }
    }

    private fun markAndAnnounce(key: String, action: () -> Unit) {
        if (!announced.add(key)) return
        while (announced.size > 20) announced.remove(announced.first())
        saveState()
        action()
    }

    private fun announceWindow(server: MinecraftServer, season: Season, opening: Boolean) {
        WonderTradeEvents.broadcast(server) {
            WonderTradeService.format(
                if (opening) ConfigManager.string("messages.seasonWindowOpen", "&b&l❄ ¡Ya es Navidad en Wonder Trade!\n&7Desde ahora y hasta el &f{end} &7pueden aparecer &bhoras navideñas&7.\n&7{sessionsInfo}\n&7Durante cada una la pool se llena de Pokémon de tipo &f{types}&7. {rules}\n&7Al terminar, la pool habitual vuelve tal como estaba.")
                else ConfigManager.string("messages.seasonWindowClose", "&b&l❄ Termina la Navidad en Wonder Trade\n&7La temporada navideña acaba ahora: no volverán a aparecer horas navideñas hasta el &f{start} &7del año que viene.\n&7¡Gracias por participar y feliz año nuevo!"),
                placeholders(season)
            )
        }
        runCatching { WonderTradeDiscord.seasonWindow(season, opening) }
            .onFailure { WonderTradePlus.LOGGER.error("No se pudo enviar el aviso de Discord de la temporada «{}».", season.id, it) }
    }

    /** Inicia una sesión inmediata desde el panel admin, incluso fuera del calendario navideño. */
    fun manualStart(server: MinecraftServer, id: String): Boolean {
        if (!ConfigManager.bool("seasonal.enabled", true)) return false
        val season = allSeasons().firstOrNull { it.id == id } ?: return false
        if (phase == "paused" && activeId == season.id) {
            val now = System.currentTimeMillis()
            endAt += (now - pausedAt).coerceAtLeast(0L)
            phase = "active"; pausedAt = 0L; saveState()
            WonderTradeEvents.broadcast(server) {
                if (season.theme == "halloween") Lang.tr("&6☠ La Noche de Brujas se ha reanudado.") else Lang.tr("&b❄ La hora navideña se ha reanudado.")
            }
            return true
        }
        if (activeId != null) return false
        skipAutomaticDate = ""
        return begin(server, season, System.currentTimeMillis() + season.sessionDurationMinutes * 60_000L)
    }

    fun manualPause(server: MinecraftServer, id: String): Boolean {
        if (!isRunning(id)) return false
        val halloween = current()?.theme == "halloween"
        phase = "paused"; pausedAt = System.currentTimeMillis(); saveState()
        WonderTradeEvents.broadcast(server) {
            if (halloween) Lang.tr("&6☠ La Noche de Brujas se ha pausado.") else Lang.tr("&b❄ La hora navideña se ha pausado.")
        }
        return true
    }

    fun manualFinish(server: MinecraftServer, id: String): Boolean {
        if (activeId != id) return false
        val today = LocalDate.now(WonderTradeSchedule.zone()).toString()
        finish(server, announceEnd = true)
        skipAutomaticDate = today
        saveState()
        return true
    }

    private fun begin(server: MinecraftServer, season: Season, sessionEnd: Long): Boolean {
        val player = server.playerList.players.firstOrNull() ?: return false
        val entries = WonderTradeService.seasonalGenerate(player, season)
        if (entries.isEmpty()) {
            if (!warnedNoPokemon) {
                warnedNoPokemon = true
                WonderTradePlus.LOGGER.error("Evento de temporada «{}»: no se ha podido generar ningún Pokémon con los tipos {}. Revisa seasonal.events.", season.id, season.types)
            }
            return false
        }
        startedAt = System.currentTimeMillis()
        endAt = sessionEnd
        pausedAt = 0L
        if (activeId == null) {
            val original = WonderTradeService.poolSnapshot()
            val ok = runCatching {
                Files.createDirectories(originalFile(season.id).parent)
                PoolStore(originalFile(season.id)).also { it.restore(original) }.save()
                Files.exists(originalFile(season.id))
            }.getOrDefault(false)
            if (!ok) {
                WonderTradePlus.LOGGER.error("Evento de temporada «{}»: no se pudo guardar la pool original; no se cambia nada.", season.id)
                return false
            }
            activeId = season.id
            originalCount = original.size
            phase = "saved"
            saveState()
        }
        activatePool(server, season, entries)
        return true
    }

    private fun activateSavedPool(server: MinecraftServer, season: Season) {
        val player = server.playerList.players.firstOrNull() ?: return
        val entries = WonderTradeService.seasonalGenerate(player, season)
        if (entries.isEmpty()) return
        activatePool(server, season, entries)
    }

    private fun activatePool(server: MinecraftServer, season: Season, entries: List<PoolEntry>) {
        WonderTradeService.poolReplace(entries)
        phase = "active"
        saveState()
        WonderTradePlus.LOGGER.info("Sesión de temporada «{}» iniciada: pool original guardada ({} Pokémon), pool temporal {} Pokémon.", season.id, originalCount, entries.size)
        announce(server, season, started = true, count = entries.size)
    }

    private fun finish(server: MinecraftServer, announceEnd: Boolean) {
        val id = activeId ?: return
        val file = originalFile(id)
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
        runCatching {
            val stamp = startedAt.toString()
            PoolStore(file.resolveSibling("pool-event-$id-$stamp.json")).also { it.restore(WonderTradeService.poolSnapshot()) }.save()
        }.onFailure { WonderTradePlus.LOGGER.warn("No se pudo archivar la pool del evento: {}", it.message) }
        val shift = (now - startedAt).coerceAtLeast(0L)
        val restored = original.map { if (it.addedAt > 0L) it.copy(addedAt = minOf(it.addedAt + shift, now)) else it }
        WonderTradeService.poolReplace(restored)
        activeId = null; startedAt = 0L; endAt = 0L; pausedAt = 0L; phase = ""; originalCount = 0
        saveState()
        WonderTradePlus.LOGGER.info("Sesión de temporada «{}» terminada: pool original restaurada ({} Pokémon).", id, restored.size)
        if (announceEnd && season != null) announce(server, season, started = false, count = restored.size)
    }

    private fun announce(server: MinecraftServer, season: Season, started: Boolean, count: Int) {
        val halloween = season.theme == "halloween"
        if (started) {
            WonderTradeEvents.broadcast(server) {
                WonderTradeService.format(
                    if (halloween) ConfigManager.string("messages.halloweenStart", "&6☠ La Noche de Brujas ha comenzado. La pool de miedo estará disponible durante {duration}.")
                    else ConfigManager.string("messages.seasonStart", "&b❄ ¡Ha aparecido la hora navideña! La pool temática estará disponible durante {duration}."),
                    placeholders(season) + mapOf("duration" to WonderTradeEvents.formatDuration(season.sessionDurationMinutes * 60L))
                )
            }
        } else {
            WonderTradeEvents.broadcast(server) {
                WonderTradeService.format(
                    if (halloween) ConfigManager.string("messages.halloweenEnd", "&6☠ La Noche de Brujas ha terminado. La pool habitual vuelve a estar disponible.")
                    else ConfigManager.string("messages.seasonEnd", "&b❄ La hora navideña ha terminado. La pool habitual vuelve a estar disponible."),
                    placeholders(season)
                )
            }
        }
        runCatching { WonderTradeDiscord.season(season, started, count, season.sessionDurationMinutes * 60L) }
            .onFailure { WonderTradePlus.LOGGER.error("No se pudo enviar el aviso de Discord del evento de temporada «{}».", season.id, it) }
    }
}
