package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cada cuánto se repite un evento programado. */
enum class ScheduleRepeat(val key: String, val label: String) {
    NONE("none", "una vez"),
    DAILY("diario", "cada día"),
    WEEKLY("semanal", "cada semana"),
    MONTHLY("mensual", "cada mes"),
    YEARLY("anual", "cada año");

    companion object {
        fun parse(text: String): ScheduleRepeat = when (text.lowercase()) {
            "daily" -> DAILY
            "weekly" -> WEEKLY
            "monthly" -> MONTHLY
            "yearly", "annually" -> YEARLY
            else -> values().firstOrNull { it.key.equals(text, true) } ?: NONE
        }
    }
}

/** Evento que se iniciará solo en [nextRunAt] (milisegundos) y, si se repite, volverá a programarse. */
data class ScheduledEvent(
    val id: Int,
    val type: EventType,
    val categories: List<EventCategory>,
    val durationSeconds: Long,
    val value: Double?,
    val cooldownSeconds: Long?,
    var nextRunAt: Long,
    val repeat: ScheduleRepeat,
    val createdBy: String,
    val anchorDayOfMonth: Int = 0,
    val anchorMonth: Int = 0
) {
    val title: String get() = "${type.title} ${categoriesTitle(categories)}"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type.key)
        add("categories", JsonArray().also { array -> categories.forEach { array.add(it.key) } })
        addProperty("durationSeconds", durationSeconds)
        if (value != null) addProperty("value", value)
        if (cooldownSeconds != null) addProperty("cooldownSeconds", cooldownSeconds)
        addProperty("nextRunAt", nextRunAt)
        addProperty("repeat", repeat.key)
        addProperty("createdBy", createdBy)
        addProperty("anchorDayOfMonth", anchorDayOfMonth)
        addProperty("anchorMonth", anchorMonth)
    }

    companion object {
        fun fromJson(json: JsonObject): ScheduledEvent? {
            val type = EventType.parse(json.get("type")?.asString ?: return null) ?: return null
            val categories = WtEvent.readCategories(json)
            return ScheduledEvent(
                id = json.get("id")?.asInt ?: return null,
                type = type,
                categories = categories,
                durationSeconds = json.get("durationSeconds")?.asLong ?: return null,
                value = json.get("value")?.takeIf { !it.isJsonNull }?.asDouble,
                cooldownSeconds = json.get("cooldownSeconds")?.takeIf { !it.isJsonNull }?.asLong,
                nextRunAt = json.get("nextRunAt")?.asLong ?: return null,
                repeat = ScheduleRepeat.parse(json.get("repeat")?.asString ?: "none"),
                createdBy = json.get("createdBy")?.asString ?: "?",
                anchorDayOfMonth = json.get("anchorDayOfMonth")?.asInt ?: 0,
                anchorMonth = json.get("anchorMonth")?.asInt ?: 0
            )
        }
    }
}

/** Resultado de interpretar el texto de «cuándo»: o hay datos válidos o hay un error que mostrar. */
data class ScheduleSpec(
    val firstRunAt: Long,
    val repeat: ScheduleRepeat,
    val value: Double?,
    val cooldownSeconds: Long?
)

data class ScheduleParseResult(val spec: ScheduleSpec?, val error: String?)

/** Eventos programados (event-schedule.json): inicio a una hora/día concreto y repeticiones. */
object WonderTradeSchedule {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null
    private val items = mutableListOf<ScheduledEvent>()
    private var nextId = 1
    private val announced = HashSet<String>()

    // ------------------------------------------------------------ carga y guardado

    fun load(directory: Path? = null) {
        if (directory != null) file = directory.resolve("event-schedule.json")
        val path = file ?: return
        items.clear()
        nextId = 1
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) {
            save()
            return
        }
        try {
            val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            nextId = root.get("nextId")?.asInt ?: 1
            for (node in root.getAsJsonArray("scheduled") ?: JsonArray()) {
                val item = runCatching { ScheduledEvent.fromJson(node.asJsonObject) }.getOrNull() ?: continue
                items.add(item)
                if (item.id >= nextId) nextId = item.id + 1
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo cargar event-schedule.json. Se empieza sin eventos programados.", exception)
            runCatching { Files.copy(path, path.resolveSibling("event-schedule.json.corrupt-${System.currentTimeMillis()}")) }
            items.clear()
        }
    }

    private fun save() {
        val path = file ?: return
        Files.createDirectories(path.parent)
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("nextId", nextId)
            add("scheduled", JsonArray().also { array -> items.forEach { array.add(it.toJson()) } })
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ------------------------------------------------------------ consulta y gestión

    fun all(): List<ScheduledEvent> = items.sortedBy { it.nextRunAt }

    fun zone(): ZoneId {
        val configured = ConfigManager.string("events.schedule.timezone", "").trim()
        return if (configured.isEmpty()) ZoneId.systemDefault()
        else runCatching { ZoneId.of(configured) }.getOrElse { ZoneId.systemDefault() }
    }

    fun add(
        type: EventType,
        categories: List<EventCategory>,
        durationSeconds: Long,
        spec: ScheduleSpec,
        createdBy: String
    ): ScheduledEvent {
        val anchorDate = Instant.ofEpochMilli(spec.firstRunAt).atZone(zone()).toLocalDate()
        val item = ScheduledEvent(
            nextId++, type, normalizeCategories(categories), durationSeconds, spec.value, spec.cooldownSeconds,
            spec.firstRunAt, spec.repeat, createdBy, anchorDate.dayOfMonth, anchorDate.monthValue
        )
        items.add(item)
        save()
        return item
    }

    fun remove(id: Int): ScheduledEvent? {
        val item = items.firstOrNull { it.id == id } ?: return null
        items.remove(item)
        announced.removeAll { it.startsWith("$id:") }
        save()
        return item
    }

    /** Texto como «sáb 07/11 20:00 (cada semana)». */
    fun describeWhen(item: ScheduledEvent): String {
        val formatter = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale("es", "ES"))
        val moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.nextRunAt), zone()).format(formatter)
        return if (item.repeat == ScheduleRepeat.NONE) moment else "$moment (${item.repeat.label})"
    }

    /** Crea la primera ejecución a partir del día y la hora elegidos en el menú. */
    fun fromMenu(day: DayOfWeek, hour: Int, minute: Int, repeat: ScheduleRepeat): ScheduleSpec {
        val zone = zone()
        val now = ZonedDateTime.now(zone)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var date = now.toLocalDate()
        while (date.dayOfWeek != day) date = date.plusDays(1)
        var first = ZonedDateTime.of(date, time, zone)
        if (!first.isAfter(now)) first = first.plusWeeks(1)
        return ScheduleSpec(first.toInstant().toEpochMilli(), repeat, null, null)
    }

    /** Líneas para jugadores con los próximos eventos; vacío si no hay o está desactivado. */
    fun upcomingLines(limit: Int = 3): List<String> {
        if (!ConfigManager.bool("events.showUpcoming", true)) return emptyList()
        val list = all().take(limit)
        if (list.isEmpty()) return emptyList()
        val lines = mutableListOf(ConfigManager.string("messages.eventUpcomingHeader", "&d&lPróximos eventos:"))
        list.forEach { item ->
            lines += WonderTradeService.format(
                ConfigManager.string("messages.eventUpcomingLine", "&7- &f{name} &7— {when} &8({duration})"),
                mapOf("name" to item.title, "when" to describeWhen(item), "duration" to WonderTradeEvents.formatDuration(item.durationSeconds))
            )
        }
        return lines
    }

    // ------------------------------------------------------------ ejecución

    /** Una vez por segundo: avisa con antelación e inicia los eventos cuya hora ha llegado. */
    fun tick(server: MinecraftServer) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        announceSoon(server, now)
        val due = items.filter { it.nextRunAt <= now }
        if (due.isEmpty()) return
        val lateLimit = ConfigManager.int("events.schedule.maxLateMinutes", 10).coerceAtLeast(0) * 60_000L
        val skipIfActive = ConfigManager.bool("events.schedule.skipIfActive", true)
        for (item in due) {
            val late = now - item.nextRunAt
            val alreadyActive = skipIfActive && WonderTradeEvents.active(now).any { it.type == item.type && it.categories == item.categories }
            when {
                late > lateLimit -> WonderTradePlus.LOGGER.info("Evento programado #{} ({}) omitido: el servidor estaba apagado a su hora.", item.id, item.title)
                alreadyActive -> WonderTradePlus.LOGGER.info("Evento programado #{} ({}) omitido: ya hay uno igual activo.", item.id, item.title)
                else -> {
                    val created = WonderTradeEvents.create(server, item.type, item.categories, item.durationSeconds, item.value, item.cooldownSeconds, "programado #${item.id}")
                    if (created == null) WonderTradePlus.LOGGER.info("Evento programado #{} omitido: los eventos están desactivados.", item.id)
                }
            }
            announced.removeAll { it.startsWith("${item.id}:") }
            if (item.repeat == ScheduleRepeat.NONE) items.remove(item)
            else item.nextRunAt = nextAfter(item, now)
        }
        save()
    }

    private fun nextAfter(item: ScheduledEvent, now: Long): Long {
        var moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(item.nextRunAt), zone())
        val anchorDay = item.anchorDayOfMonth.takeIf { it in 1..31 } ?: moment.dayOfMonth
        val anchorMonth = item.anchorMonth.takeIf { it in 1..12 } ?: moment.monthValue
        do {
            moment = when (item.repeat) {
                ScheduleRepeat.NONE -> moment
                ScheduleRepeat.DAILY -> moment.plusDays(1)
                ScheduleRepeat.WEEKLY -> moment.plusWeeks(1)
                ScheduleRepeat.MONTHLY -> {
                    val firstOfNextMonth = moment.toLocalDate().withDayOfMonth(1).plusMonths(1)
                    val date = firstOfNextMonth.withDayOfMonth(minOf(anchorDay, firstOfNextMonth.lengthOfMonth()))
                    ZonedDateTime.of(date, moment.toLocalTime(), zone())
                }
                ScheduleRepeat.YEARLY -> {
                    val firstOfTargetMonth = LocalDate.of(moment.year + 1, anchorMonth, 1)
                    val date = firstOfTargetMonth.withDayOfMonth(minOf(anchorDay, firstOfTargetMonth.lengthOfMonth()))
                    ZonedDateTime.of(date, moment.toLocalTime(), zone())
                }
            }
        } while (moment.toInstant().toEpochMilli() <= now)
        return moment.toInstant().toEpochMilli()
    }

    private fun announceSoon(server: MinecraftServer, now: Long) {
        val marks = ConfigManager.ints("events.schedule.announceBeforeMinutes").filter { it > 0 }
        if (marks.isEmpty()) return
        for (item in items) {
            for (minutes in marks) {
                val at = item.nextRunAt - minutes * 60_000L
                if (now < at || now >= item.nextRunAt || now - at > 120_000L) continue
                if (!announced.add("${item.id}:${item.nextRunAt}:$minutes")) continue
                WonderTradeEvents.broadcast(
                    server,
                    ConfigManager.string("messages.eventScheduledSoon", "&d★ El evento «{name}» empezará en &f{time}&d."),
                    mapOf("name" to item.title, "time" to WonderTradeEvents.formatDuration((item.nextRunAt - now + 999L) / 1000L))
                )
            }
        }
    }

    // ------------------------------------------------------------ interpretación del texto

    private val dayNames = mapOf(
        "lunes" to DayOfWeek.MONDAY, "lun" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY, "mar" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY, "mie" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "jue" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY, "vie" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY, "sab" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY, "dom" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun failure(message: String) = ScheduleParseResult(null, message)

    /**
     * Interpreta, por ejemplo: «20:00», «sabado 20:00», «sabado 20:00 semanal», «15/11 18:30»,
     * «20:00 diario valor=25 espera=2m». La hora es obligatoria; el resto es opcional.
     */
    fun parse(text: String): ScheduleParseResult {
        var time: LocalTime? = null
        var day: DayOfWeek? = null
        var dateParts: Triple<Int, Int, Int?>? = null
        var repeat = ScheduleRepeat.NONE
        var value: Double? = null
        var cooldown: Long? = null
        val timeRegex = Regex("^(\\d{1,2}):(\\d{2})$")
        val dateRegex = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?$")

        for (raw in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val token = normalize(raw)
            val timeMatch = timeRegex.matchEntire(token)
            val dateMatch = dateRegex.matchEntire(token)
            when {
                timeMatch != null -> {
                    time = runCatching { LocalTime.of(timeMatch.groupValues[1].toInt(), timeMatch.groupValues[2].toInt()) }.getOrNull()
                        ?: return failure("Hora no válida: $raw (usa HH:mm, por ejemplo 20:00).")
                }
                dateMatch != null -> {
                    val year = dateMatch.groupValues[3].ifEmpty { null }?.toInt()
                    dateParts = Triple(dateMatch.groupValues[1].toInt(), dateMatch.groupValues[2].toInt(), year)
                }
                token in dayNames -> day = dayNames[token]
                token == "diario" || token == "diaria" || token == "cadadia" -> repeat = ScheduleRepeat.DAILY
                token == "semanal" || token == "cadasemana" -> repeat = ScheduleRepeat.WEEKLY
                token == "mensual" || token == "cadames" -> repeat = ScheduleRepeat.MONTHLY
                token == "anual" || token == "cadaano" -> repeat = ScheduleRepeat.YEARLY
                token.startsWith("valor=") -> {
                    value = token.substringAfter('=').replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                        ?: return failure("Valor no válido: $raw (0 a 100).")
                }
                token.startsWith("espera=") -> {
                    cooldown = WonderTradeEvents.parseDuration(token.substringAfter('='))
                        ?: return failure("Espera no válida: $raw (ejemplos: 30s, 1m, 5m).")
                }
                else -> return failure("No entiendo «$raw». Usa una hora (20:00), un día (sabado o 15/11), diario, semanal, mensual o anual, valor=N y espera=1m.")
            }
        }
        val chosenTime = time ?: return failure("Falta la hora, por ejemplo 20:00.")
        val zone = zone()
        val now = ZonedDateTime.now(zone)
        val first: ZonedDateTime
        if (dateParts != null) {
            val (dayOfMonth, month, year) = dateParts
            val date = runCatching { LocalDate.of(year ?: now.year, month, dayOfMonth) }.getOrNull()
                ?: return failure("Fecha no válida (usa día/mes o día/mes/año).")
            var moment = ZonedDateTime.of(date, chosenTime, zone)
            if (!moment.isAfter(now)) {
                if (year == null) moment = moment.plusYears(1) else return failure("Esa fecha ya ha pasado.")
            }
            first = moment
        } else if (day != null) {
            var date = now.toLocalDate()
            while (date.dayOfWeek != day) date = date.plusDays(1)
            var moment = ZonedDateTime.of(date, chosenTime, zone)
            if (!moment.isAfter(now)) moment = moment.plusWeeks(1)
            first = moment
        } else {
            var moment = ZonedDateTime.of(now.toLocalDate(), chosenTime, zone)
            if (!moment.isAfter(now)) moment = moment.plusDays(1)
            first = moment
        }
        return ScheduleParseResult(ScheduleSpec(first.toInstant().toEpochMilli(), repeat, value, cooldown), null)
    }
}
