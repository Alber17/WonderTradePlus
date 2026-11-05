package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Penalización: el jugador no puede intercambiar ni donar hasta [until] (0 = permanente). */
data class Penalty(val uuid: UUID, val name: String, val until: Long, val createdAt: Long, val by: String, val reason: String = "") {
    val permanent: Boolean get() = until <= 0L
}

/** Entrada del historial: acción «penalized», «pardoned» o «expired». */
data class PenaltyRecord(val uuid: UUID, val name: String, val action: String, val at: Long, val until: Long, val by: String, val reason: String)

/** Penalizaciones personalizadas de Wonder Trade (penalties.json), con duración o permanentes. */
object WonderTradePenalties {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null
    private val items = LinkedHashMap<UUID, Penalty>()

    /** Duración elegida por cada admin en el menú (milisegundos, 0 = permanente) para usarla después con «por nombre». */
    private val selections = HashMap<UUID, Pair<Long, Long>>()
    private val reasons = HashMap<UUID, Pair<String, Long>>()
    private val history = ArrayList<PenaltyRecord>()

    /** Jugadores cuya penalización terminó estando desconectados: se les avisa al entrar. */
    private val notify = LinkedHashSet<UUID>()

    fun load(directory: Path? = null) {
        if (directory != null) file = directory.resolve("penalties.json")
        val path = file ?: return
        items.clear(); history.clear(); notify.clear()
        if (!Files.exists(path)) return
        try {
            val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            for (node in root.getAsJsonArray("penalties") ?: JsonArray()) {
                val data = node.asJsonObject
                val uuid = runCatching { UUID.fromString(data.get("uuid").asString) }.getOrNull() ?: continue
                items[uuid] = Penalty(
                    uuid, data.get("name")?.asString ?: "?", data.get("until")?.asLong ?: 0L,
                    data.get("createdAt")?.asLong ?: 0L, data.get("by")?.asString ?: "?", data.get("reason")?.asString ?: ""
                )
            }
            for (node in root.getAsJsonArray("history") ?: JsonArray()) {
                val data = node.asJsonObject
                val uuid = runCatching { UUID.fromString(data.get("uuid").asString) }.getOrNull() ?: continue
                history.add(PenaltyRecord(
                    uuid, data.get("name")?.asString ?: "?", data.get("action")?.asString ?: "penalized", data.get("at")?.asLong ?: 0L,
                    data.get("until")?.asLong ?: 0L, data.get("by")?.asString ?: "?", data.get("reason")?.asString ?: ""
                ))
            }
            for (node in root.getAsJsonArray("notify") ?: JsonArray()) {
                runCatching { UUID.fromString(node.asString) }.getOrNull()?.let { notify.add(it) }
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo cargar penalties.json. Se empieza sin penalizaciones.", exception)
            runCatching { Files.copy(path, path.resolveSibling("penalties.json.corrupt-${System.currentTimeMillis()}")) }
            items.clear()
        }
    }

    private fun save() {
        val path = file ?: return
        Files.createDirectories(path.parent)
        val root = JsonObject().apply {
            add("penalties", JsonArray().also { array ->
                items.values.forEach { p ->
                    array.add(JsonObject().apply {
                        addProperty("uuid", p.uuid.toString()); addProperty("name", p.name); addProperty("until", p.until)
                        addProperty("createdAt", p.createdAt); addProperty("by", p.by); addProperty("reason", p.reason)
                    })
                }
            })
            add("history", JsonArray().also { array ->
                history.forEach { r ->
                    array.add(JsonObject().apply {
                        addProperty("uuid", r.uuid.toString()); addProperty("name", r.name); addProperty("action", r.action)
                        addProperty("at", r.at); addProperty("until", r.until); addProperty("by", r.by); addProperty("reason", r.reason)
                    })
                }
            })
            add("notify", JsonArray().also { array -> notify.forEach { array.add(it.toString()) } })
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun record(action: String, penalty: Penalty, by: String, reason: String) {
        history.add(PenaltyRecord(penalty.uuid, penalty.name, action, System.currentTimeMillis(), penalty.until, by, reason))
        val maximum = ConfigManager.int("penalties.historySize", 200).coerceIn(10, 5000)
        while (history.size > maximum) history.removeAt(0)
    }

    /** Elimina una penalización caducada, la apunta en el historial y deja pendiente el aviso al jugador. */
    private fun expire(penalty: Penalty) {
        items.remove(penalty.uuid)
        history.add(PenaltyRecord(penalty.uuid, penalty.name, "expired", penalty.until, penalty.until, penalty.by, penalty.reason))
        notify.add(penalty.uuid)
    }

    private fun isExpired(penalty: Penalty): Boolean = !penalty.permanent && penalty.until <= System.currentTimeMillis()

    /** Penalización vigente del jugador; si ya caducó se elimina y devuelve null. */
    fun active(uuid: UUID): Penalty? {
        val penalty = items[uuid] ?: return null
        if (isExpired(penalty)) {
            expire(penalty)
            runCatching { save() }
            return null
        }
        return penalty
    }

    /** Penaliza durante [durationMillis] milisegundos; con null o 0 es permanente. Sustituye una penalización anterior. */
    fun penalize(uuid: UUID, name: String, durationMillis: Long?, by: String, reason: String = ""): Penalty {
        val now = System.currentTimeMillis()
        val until = if (durationMillis == null || durationMillis <= 0L) 0L else now + durationMillis
        val penalty = Penalty(uuid, name, until, now, by, reason.trim())
        items[uuid] = penalty
        notify.remove(uuid)
        record("penalized", penalty, by, penalty.reason)
        save()
        return penalty
    }

    fun pardon(uuid: UUID, by: String = "?"): Penalty? {
        val removed = items.remove(uuid) ?: return null
        record("pardoned", removed, by, removed.reason)
        save()
        return removed
    }

    /** Historial (el más reciente primero), opcionalmente solo de un jugador. */
    fun history(limit: Int, uuid: UUID? = null): List<PenaltyRecord> =
        history.asReversed().filter { uuid == null || it.uuid == uuid }.take(limit)

    /** Se llama cada segundo: caduca las penalizaciones vencidas y avisa a quien está conectado. */
    fun tick(server: net.minecraft.server.MinecraftServer) {
        var changed = false
        items.values.filter { isExpired(it) }.forEach { expire(it); changed = true }
        for (uuid in notify.toList()) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            notify.remove(uuid)
            WonderTradeService.send(player, "penaltyEnded")
            changed = true
        }
        if (changed) runCatching { save() }
    }

    /** Al entrar: si su penalización terminó mientras estaba fuera, se le avisa. */
    fun onJoin(player: net.minecraft.server.level.ServerPlayer) {
        if (!notify.remove(player.uuid)) return
        runCatching { save() }
        if (active(player.uuid) == null) WonderTradeService.send(player, "penaltyEnded")
    }

    /** Línea de historial: «fecha · acción · jugador · por quién · motivo». */
    fun describeRecord(r: PenaltyRecord): String {
        val date = java.text.SimpleDateFormat("dd/MM HH:mm").format(java.util.Date(r.at))
        val action = when (r.action) {
            "pardoned" -> Lang.tr("&aperdonado")
            "expired" -> Lang.tr("&7caducada")
            else -> if (r.until <= 0L) Lang.tr("&cpenalizado (permanente)") else Lang.tr("&cpenalizado")
        }
        val by = if (r.action == "expired") "" else Lang.t(" &8por &7{0}", r.by)
        val reason = if (r.reason.isBlank()) "" else " &8· &7${r.reason}"
        return "&8$date &f${r.name} $action$by$reason"
    }

    /** « Motivo: …» listo para añadir a un mensaje, o cadena vacía si no hay motivo. */
    fun reasonSuffix(penalty: Penalty): String = if (penalty.reason.isBlank()) "" else Lang.t(" &7Motivo: &f{0}&7.", Lang.tr(penalty.reason))

    fun presetReasons(): List<String> = ConfigManager.strings("penalties.reasons").ifEmpty {
        listOf(Lang.tr("Abuso de un bug"), Lang.tr("Insultos o spam"), Lang.tr("Duplicación de Pokémon"), Lang.tr("Incumplir las normas"))
    }

    fun rememberReason(admin: UUID, text: String) {
        if (text.isBlank()) reasons.remove(admin) else reasons[admin] = text.trim() to System.currentTimeMillis() + 600_000L
    }

    /** Motivo elegido por este admin (válido 10 minutos) o cadena vacía. */
    fun reason(admin: UUID): String = reasons[admin]?.takeIf { it.second >= System.currentTimeMillis() }?.first ?: ""

    /** Penalizaciones vigentes (descarta las caducadas). */
    fun list(): List<Penalty> {
        items.keys.toList().forEach { active(it) }
        return items.values.sortedBy { it.name.lowercase() }
    }

    fun remainingText(penalty: Penalty): String =
        if (penalty.permanent) ConfigManager.string("messages.penaltyPermanent", "permanente")
        else WonderTradeEvents.formatDuration((penalty.until - System.currentTimeMillis() + 999L) / 1000L)

    fun rememberSelection(admin: UUID, durationMillis: Long) {
        selections[admin] = durationMillis to System.currentTimeMillis() + 120_000L
    }

    /** Duración elegida en el menú por este admin (válida 2 minutos) o null. */
    fun selection(admin: UUID): Long? = selections[admin]?.takeIf { it.second >= System.currentTimeMillis() }?.first

    /** «permanente», «perm», «siempre»… o una duración como 30m, 2h, 7d. Devuelve milisegundos (0 = permanente) o null si no se entiende. */
    fun parseDuration(text: String): Long? {
        val clean = text.trim().lowercase()
        if (clean in setOf("permanente", "perm", "permanent", "siempre", "forever")) return 0L
        return WonderTradeEvents.parseDuration(clean)?.let { it * 1000L }
    }
}
