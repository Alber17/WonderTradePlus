package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Penalización: el jugador no puede intercambiar ni donar hasta [until] (0 = permanente). */
data class Penalty(val uuid: UUID, val name: String, val until: Long, val createdAt: Long, val by: String) {
    val permanent: Boolean get() = until <= 0L
}

/** Penalizaciones personalizadas de Wonder Trade (penalties.json), con duración o permanentes. */
object WonderTradePenalties {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null
    private val items = LinkedHashMap<UUID, Penalty>()

    /** Duración elegida por cada admin en el menú (milisegundos, 0 = permanente) para usarla después con «por nombre». */
    private val selections = HashMap<UUID, Pair<Long, Long>>()

    fun load(directory: Path? = null) {
        if (directory != null) file = directory.resolve("penalties.json")
        val path = file ?: return
        items.clear()
        if (!Files.exists(path)) return
        try {
            val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            for (node in root.getAsJsonArray("penalties") ?: JsonArray()) {
                val data = node.asJsonObject
                val uuid = runCatching { UUID.fromString(data.get("uuid").asString) }.getOrNull() ?: continue
                items[uuid] = Penalty(
                    uuid, data.get("name")?.asString ?: "?", data.get("until")?.asLong ?: 0L,
                    data.get("createdAt")?.asLong ?: 0L, data.get("by")?.asString ?: "?"
                )
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
                        addProperty("createdAt", p.createdAt); addProperty("by", p.by)
                    })
                }
            })
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Penalización vigente del jugador; si ya caducó se elimina y devuelve null. */
    fun active(uuid: UUID): Penalty? {
        val penalty = items[uuid] ?: return null
        if (!penalty.permanent && penalty.until <= System.currentTimeMillis()) {
            items.remove(uuid)
            runCatching { save() }
            return null
        }
        return penalty
    }

    /** Penaliza durante [durationMillis] milisegundos; con null o 0 es permanente. Sustituye una penalización anterior. */
    fun penalize(uuid: UUID, name: String, durationMillis: Long?, by: String): Penalty {
        val now = System.currentTimeMillis()
        val until = if (durationMillis == null || durationMillis <= 0L) 0L else now + durationMillis
        val penalty = Penalty(uuid, name, until, now, by)
        items[uuid] = penalty
        save()
        return penalty
    }

    fun pardon(uuid: UUID): Penalty? {
        val removed = items.remove(uuid) ?: return null
        save()
        return removed
    }

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
