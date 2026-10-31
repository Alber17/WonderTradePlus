package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path

/** Tipos de evento disponibles. */
enum class EventType(val key: String, val title: String) {
    /** Ofrecer un Pokémon de la categoría del evento suma un % extra a tu probabilidad de especial. */
    RISK("arriesgar", "Arriesgar"),

    /** Aumenta la probabilidad de recibir un Pokémon de la categoría del evento en cada intercambio. */
    RECEIVE("recibir", "Recibir");

    companion object {
        fun parse(text: String): EventType? =
            values().firstOrNull { it.key.equals(text, true) || it.name.equals(text, true) }
    }
}

/** Categoría a la que afecta un evento. */
enum class EventCategory(val key: String, val label: String, private val speciesKey: String?) {
    // El orden de esta lista es el orden en que aparecen en el menú.
    SHINY("shiny", "Shiny", null),
    ULTRABEAST("ultraente", "Ultraente", "ultraBeast"),
    PARADOX("paradoja", "Paradoja", "paradox"),
    MYTHICAL("mitico", "Mítico", "mythical"),
    LEGENDARY("legendario", "Legendario", "legendary");

    fun matches(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Boolean =
        if (this == SHINY) pokemon.shiny else WonderTradeRules.speciesCategoryKey(pokemon) == speciesKey

    /** Clave de categoría de especie (null para shiny). */
    fun speciesCategory(): String? = speciesKey

    companion object {
        fun parse(text: String): EventCategory? {
            val clean = text.lowercase().replace('í', 'i').replace('é', 'e')
            return values().firstOrNull {
                it.key == clean || it.name.lowercase() == clean || it.label.lowercase().replace('í', 'i') == clean
            }
        }
    }
}

/** Ordena las categorías (orden del menú) y quita repetidas. */
fun normalizeCategories(list: Collection<EventCategory>): List<EventCategory> =
    EventCategory.values().filter { it in list }

/** Texto corto de categorías, por ejemplo «Mítico + Legendario». */
fun categoriesTitle(categories: List<EventCategory>): String = categories.joinToString(" + ") { it.label }

/** Texto para frases, por ejemplo «Mítico o Legendario». */
fun categoriesText(categories: List<EventCategory>): String = when (categories.size) {
    0 -> ""
    1 -> categories[0].label
    else -> categories.dropLast(1).joinToString(", ") { it.label } + " o " + categories.last().label
}

/** Lee «mitico+legendario» (también con comas). Devuelve null si alguna no es válida o no hay ninguna. */
fun parseCategories(text: String): List<EventCategory>? {
    val parts = text.split('+', ',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val parsed = parts.map { EventCategory.parse(it) ?: return null }
    return normalizeCategories(parsed)
}

/**
 * Un evento temporal.
 * [value] es el % extra por shiny ofrecido (arriesgar) o el % extra de recibir un shiny (recibir).
 * [cooldownSeconds] es la espera entre intercambios "de evento" de cada jugador.
 */
data class WtEvent(
    val id: Int,
    val type: EventType,
    val categories: List<EventCategory>,
    val startAt: Long,
    val endAt: Long,
    val value: Double,
    val cooldownSeconds: Long,
    val createdBy: String
) {
    /** Nombre completo, por ejemplo «Arriesgar Legendario». */
    val title: String get() = "${type.title} ${categoriesTitle(categories)}"

    /** Categorías para frases («Mítico o Legendario»). */
    val categoryText: String get() = categoriesText(categories)

    fun matches(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Boolean = categories.any { it.matches(pokemon) }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type.key)
        add("categories", JsonArray().also { array -> categories.forEach { array.add(it.key) } })
        addProperty("startAt", startAt)
        addProperty("endAt", endAt)
        addProperty("value", value)
        addProperty("cooldownSeconds", cooldownSeconds)
        addProperty("createdBy", createdBy)
    }

    companion object {
        /** Lee «categories» (lista) o, en eventos antiguos, «category» (una sola). */
        fun readCategories(json: JsonObject): List<EventCategory> {
            val list = json.getAsJsonArray("categories")?.mapNotNull { node -> runCatching { EventCategory.parse(node.asString) }.getOrNull() }
            if (!list.isNullOrEmpty()) return normalizeCategories(list)
            return listOf(EventCategory.parse(json.get("category")?.asString ?: "shiny") ?: EventCategory.SHINY)
        }

        fun fromJson(json: JsonObject): WtEvent? {
            val type = EventType.parse(json.get("type")?.asString ?: return null) ?: return null
            return WtEvent(
                id = json.get("id")?.asInt ?: return null,
                type = type,
                categories = readCategories(json),
                startAt = json.get("startAt")?.asLong ?: 0L,
                endAt = json.get("endAt")?.asLong ?: return null,
                value = json.get("value")?.asDouble ?: 0.0,
                cooldownSeconds = json.get("cooldownSeconds")?.asLong ?: 60L,
                createdBy = json.get("createdBy")?.asString ?: "?"
            )
        }
    }
}

/** Gestión de eventos: almacenamiento (event.json), esperas por jugador y mensajes. */
object WonderTradeEvents {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null
    private val events = mutableListOf<WtEvent>()
    private var nextId = 1

    /** Última vez que cada jugador usó un intercambio de evento: "uuid:idEvento" -> milisegundos. */
    private val lastUse = HashMap<String, Long>()

    // ------------------------------------------------------------ carga y guardado

    fun load(directory: Path? = null) {
        if (directory != null) {
            file = directory.resolve("event.json")
        }
        val path = file ?: return
        events.clear()
        lastUse.clear()
        nextId = 1
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) {
            save()
            return
        }
        try {
            val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            nextId = root.get("nextId")?.asInt ?: 1
            val now = System.currentTimeMillis()
            for (node in root.getAsJsonArray("events") ?: JsonArray()) {
                val event = runCatching { WtEvent.fromJson(node.asJsonObject) }.getOrNull() ?: continue
                if (event.endAt > now) events.add(event)
                if (event.id >= nextId) nextId = event.id + 1
            }
            // Esperas por jugador guardadas: solo las de eventos que siguen activos y que aún no han terminado.
            val saved = root.getAsJsonObject("lastUse")
            if (saved != null) {
                val alive = events.associateBy { it.id.toString() }
                for ((key, value) in saved.entrySet()) {
                    val event = alive[key.substringAfterLast(':')] ?: continue
                    val moment = runCatching { value.asLong }.getOrNull() ?: continue
                    if (moment + event.cooldownSeconds * 1000L > now) lastUse[key] = moment
                }
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo cargar event.json. Se empieza sin eventos.", exception)
            runCatching { Files.copy(path, path.resolveSibling("event.json.corrupt-${System.currentTimeMillis()}")) }
            events.clear()
        }
        save()
    }

    private fun save() {
        val path = file ?: return
        Files.createDirectories(path.parent)
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("nextId", nextId)
            add("events", JsonArray().also { array -> events.forEach { array.add(it.toJson()) } })
            add("lastUse", JsonObject().also { node -> lastUse.forEach { (key, moment) -> node.addProperty(key, moment) } })
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ------------------------------------------------------------ consulta

    fun enabled(): Boolean = ConfigManager.bool("events.enabled", true)

    fun active(now: Long = System.currentTimeMillis()): List<WtEvent> =
        if (!enabled()) emptyList() else events.filter { it.startAt <= now && now < it.endAt }

    /** % extra al ofrecer este Pokémon (eventos «Arriesgar …» activos cuya categoría coincide). */
    fun riskExtraGain(pokemon: com.cobblemon.mod.common.pokemon.Pokemon): Double =
        active().filter { it.type == EventType.RISK && it.matches(pokemon) }.sumOf { it.value }.coerceAtLeast(0.0)

    /** Eventos «Recibir …» activos. */
    fun receiveEvents(): List<WtEvent> = active().filter { it.type == EventType.RECEIVE && it.value > 0.0 }

    // ------------------------------------------------------------ esperas por jugador

    private fun key(player: ServerPlayer, event: WtEvent) = "${player.uuid}:${event.id}"

    private fun remainingSeconds(player: ServerPlayer, event: WtEvent, now: Long): Long {
        val last = lastUse[key(player, event)] ?: return 0L
        val left = last + event.cooldownSeconds * 1000L - now
        return if (left <= 0L) 0L else (left + 999L) / 1000L
    }

    /** Un evento activo cuya espera ya terminó para este jugador, o null. */
    fun readyEvent(player: ServerPlayer): WtEvent? {
        val now = System.currentTimeMillis()
        return active(now).firstOrNull { remainingSeconds(player, it, now) <= 0L }
    }

    /** Segundos hasta el próximo intercambio de evento (0 = disponible); null si no hay eventos activos. */
    fun cooldownRemaining(player: ServerPlayer): Long? {
        val now = System.currentTimeMillis()
        return active(now).minOfOrNull { remainingSeconds(player, it, now) }
    }

    fun markUsed(player: ServerPlayer, event: WtEvent) {
        lastUse[key(player, event)] = System.currentTimeMillis()
        runCatching { save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar la espera del evento.", it) }
    }

    fun unmark(player: ServerPlayer, event: WtEvent) {
        lastUse.remove(key(player, event))
        runCatching { save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar la espera del evento.", it) }
    }

    // ------------------------------------------------------------ creación y fin

    fun defaultValue(type: EventType): Double = when (type) {
        EventType.RISK -> ConfigManager.double("events.risk.extraGain", 0.2)
        EventType.RECEIVE -> ConfigManager.double("events.receive.chance", 10.0)
    }.coerceIn(0.0, 100.0)

    /** Porcentaje de la duración total que debe transcurrir entre dos WT de evento. */
    fun tradeIntervalPercent(): Double = ConfigManager.double("events.tradeIntervalPercent", 20.0).coerceIn(0.1, 100.0)

    /** Calcula el intervalo automáticamente a partir de la duración del evento. */
    fun defaultCooldownSeconds(durationSeconds: Long): Long {
        val interval = durationSeconds.coerceAtLeast(1L) * tradeIntervalPercent() / 100.0
        return kotlin.math.ceil(interval).toLong().coerceAtLeast(1L)
    }

    fun maximumDurationSeconds(): Long = ConfigManager.int("events.maximumDurationHours", 720).coerceAtLeast(1) * 3600L

    /** Crea e inicia un evento y lo anuncia a todos los jugadores. Devuelve null si los eventos están desactivados. */
    fun create(
        server: MinecraftServer,
        type: EventType,
        categories: List<EventCategory>,
        durationSeconds: Long,
        value: Double?,
        cooldownSeconds: Long?,
        createdBy: String
    ): WtEvent? {
        if (!enabled()) return null
        val now = System.currentTimeMillis()
        val event = WtEvent(
            id = nextId++,
            type = type,
            categories = normalizeCategories(categories).ifEmpty { listOf(EventCategory.SHINY) },
            startAt = now,
            endAt = now + durationSeconds * 1000L,
            value = (value ?: defaultValue(type)).coerceIn(0.0, 100.0),
            cooldownSeconds = (cooldownSeconds ?: defaultCooldownSeconds(durationSeconds)).coerceAtLeast(1L),
            createdBy = createdBy
        )
        events.add(event)
        save()
        broadcast(server, ConfigManager.string("messages.eventStarted", "&d★ ¡Evento «{name}» activo durante {duration}! &7Usa &e/wt eventos &7para ver los detalles."),
            mapOf("name" to event.title, "duration" to formatDuration(durationSeconds)))
        // Asegura Pokémon de la categoría en la pool y anuncia cuáles hay; si no hay jugadores, se hace al entrar el primero.
        if (!runCatching { WonderTradeService.prepareEvent(server, event) }.getOrDefault(true)) pendingSetup.add(event.id)
        return event
    }

    fun stop(server: MinecraftServer, id: Int): WtEvent? {
        val event = events.firstOrNull { it.id == id } ?: return null
        events.remove(event)
        lastUse.keys.removeAll { it.endsWith(":${event.id}") }
        pendingSetup.remove(event.id)
        save()
        announceEnd(server, event)
        return event
    }

    /** Eventos recién creados a los que aún les falta preparar la pool y anunciar sus Pokémon (no había jugadores). */
    private val pendingSetup = mutableSetOf<Int>()
    private var ensureCounter = 0

    /** Se llama una vez por segundo: termina los eventos caducados, inicia los programados y actualiza barras y avisos. */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val ended = events.filter { it.endAt <= now }
        if (ended.isNotEmpty()) {
            events.removeAll(ended.toSet())
            ended.forEach { event -> lastUse.keys.removeAll { it.endsWith(":${event.id}") } }
            save()
            ended.forEach { announceEnd(server, it) }
        }
        runCatching { WonderTradeSchedule.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el control de eventos programados.", it) }
        ensureCounter++
        val interval = ConfigManager.int("events.ensurePool.checkIntervalSeconds", 10).coerceAtLeast(1)
        if (ensureCounter % interval == 0) {
            runCatching { WonderTradeService.maintainEventPools(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el mantenimiento de la pool del evento.", it) }
        }
        runCatching { processPendingSetup(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló la preparación de un evento.", it) }
        runCatching { WonderTradeEventDisplay.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló la barra de eventos.", it) }
    }

    private fun processPendingSetup(server: MinecraftServer) {
        if (pendingSetup.isEmpty()) return
        for (id in pendingSetup.toList()) {
            val event = active().firstOrNull { it.id == id }
            if (event == null) {
                pendingSetup.remove(id)
                continue
            }
            if (WonderTradeService.prepareEvent(server, event)) pendingSetup.remove(id)
        }
    }

    private fun announceEnd(server: MinecraftServer, event: WtEvent) {
        broadcast(server, ConfigManager.string("messages.eventEnded", "&d★ El evento «{name}» ha terminado."),
            mapOf("name" to event.title))
    }

    fun broadcast(server: MinecraftServer, template: String, values: Map<String, Any>) {
        val text = WonderTradeService.format(template, values)
        server.playerList.broadcastSystemMessage(
            WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + text), false
        )
    }

    // ------------------------------------------------------------ textos

    fun describe(event: WtEvent): List<String> {
        val value = valueText(event.value)
        val cooldown = formatDuration(event.cooldownSeconds)
        val first = when (event.type) {
            EventType.RISK -> WonderTradeService.format(
                ConfigManager.string("messages.eventRiskDescription", "&7Cada Pokémon &f{category}&7 que ofrezcas suma &d+{value}%&7 extra a tu probabilidad de especial."),
                mapOf("value" to value, "category" to event.categoryText)
            )
            EventType.RECEIVE -> WonderTradeService.format(
                ConfigManager.string("messages.eventReceiveDescription", "&7Probabilidad extra de recibir un Pokémon &f{category}&7: &d+{value}%&7 en cada intercambio."),
                mapOf("value" to value, "category" to event.categoryText)
            )
        }
        val second = WonderTradeService.format(
            ConfigManager.string("messages.eventCooldownDescription", "&7Puedes intercambiar una vez cada &f{cooldown}&7 sin gastar tu tiempo normal."),
            mapOf("cooldown" to cooldown)
        )
        return listOf(first, second)
    }

    /** Descripción con los valores por defecto, para el menú de creación. */
    fun describeDefault(type: EventType, categories: List<EventCategory>): List<String> {
        val sample = WtEvent(0, type, normalizeCategories(categories), 0L, 3600_000L, defaultValue(type), defaultCooldownSeconds(3600L), "")
        val percent = tradeIntervalPercent()
        return describe(sample).dropLast(1) + "&7Intervalo automático: &f${percentText(percent)}% &7de la duración total."
    }

    private fun percentText(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    /** Respuesta de /wt eventos. */
    fun eventsComponent(player: ServerPlayer?): Component {
        val now = System.currentTimeMillis()
        val list = active(now)
        val lines = mutableListOf<String>()
        if (list.isEmpty()) {
            lines += ConfigManager.string("messages.eventNone", "&7No hay ningún evento activo ahora mismo.")
        } else {
            lines += ConfigManager.string("messages.eventsHeader", "&d&lEventos activos:")
            list.forEach { event ->
                lines += "&d★ &f${event.title} &7— termina en &f${formatDuration((event.endAt - now + 999L) / 1000L)}"
                describe(event).forEach { lines += "  $it" }
                if (player != null) {
                    val left = remainingSeconds(player, event, now)
                    val state = if (left <= 0L) "&aDisponible" else "&f${formatDuration(left)}"
                    lines += "  " + WonderTradeService.format(
                        ConfigManager.string("messages.eventYourCooldown", "&7Tu próximo intercambio de evento: {state}"),
                        mapOf("state" to state)
                    )
                }
            }
        }
        lines += WonderTradeSchedule.upcomingLines()
        return WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n"))
    }

    /** Lista para administradores, con el id de cada evento. */
    fun adminListComponent(): Component {
        val now = System.currentTimeMillis()
        val list = active(now)
        val lines = mutableListOf<String>()
        if (list.isEmpty()) lines += "&7No hay eventos activos."
        else {
            lines += "&d&lEventos activos:"
            list.forEach { event ->
                lines += "&8#${event.id} &d${event.title} &7valor &f${valueText(event.value)}% &7espera &f${formatDuration(event.cooldownSeconds)} &7termina en &f${formatDuration((event.endAt - now + 999L) / 1000L)} &8(${event.createdBy})"
            }
            lines += "&7Finalizar: &e/wt admin eventos parar <id>"
        }
        val scheduled = WonderTradeSchedule.all()
        if (scheduled.isNotEmpty()) {
            lines += "&d&lProgramados:"
            scheduled.forEach { item ->
                lines += "&8P${item.id} &d${item.title} &7dura &f${formatDuration(item.durationSeconds)} &7— &f${WonderTradeSchedule.describeWhen(item)} &8(${item.createdBy})"
            }
            lines += "&7Cancelar: &e/wt admin eventos desprogramar <id>"
        }
        return WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n"))
    }

    /** Líneas para el reloj del menú principal. */
    fun clockLines(player: ServerPlayer): List<String> {
        val now = System.currentTimeMillis()
        return active(now).map { event ->
            val left = remainingSeconds(player, event, now)
            val state = if (left <= 0L) "&aDisponible" else "&f${formatDuration(left)}"
            "&d★ ${event.title} &8— $state"
        }
    }

    // ------------------------------------------------------------ utilidades

    /** Acepta 30s, 5m, 2h, 1d y combinaciones como 1h30m. */
    fun parseDuration(text: String): Long? {
        val clean = text.trim().lowercase()
        if (!Regex("^(\\d+[smhd])+$").matches(clean)) return null
        var total = 0L
        for (match in Regex("(\\d+)([smhd])").findAll(clean)) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            total += amount * when (match.groupValues[2]) {
                "s" -> 1L
                "m" -> 60L
                "h" -> 3600L
                else -> 86400L
            }
        }
        return if (total > 0L) total else null
    }

    fun formatDuration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0L)
        val days = seconds / 86400L
        val hours = (seconds % 86400L) / 3600L
        val minutes = (seconds % 3600L) / 60L
        val rest = seconds % 60L
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> if (rest > 0) "${minutes}m ${rest}s" else "${minutes}m"
            else -> "${rest}s"
        }
    }

    fun valueText(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else value.toString()
}
