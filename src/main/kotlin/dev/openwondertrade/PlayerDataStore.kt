package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

class PlayerDataStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var root = JsonObject().apply { add("players", JsonObject()) }

    data class TradeCheck(val cooldownSeconds: Long = 0L, val dailyLimitReached: Boolean = false)

    fun load() {
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            if (!root.has("players") || !root.get("players").isJsonObject) root.add("players", JsonObject())
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo leer player-data.json; se usará un archivo nuevo.", exception)
            runCatching { Files.copy(file, file.resolveSibling("player-data.json.corrupt-${System.currentTimeMillis()}")) }
            root = JsonObject().apply { add("players", JsonObject()) }
            save()
        }
    }

    fun snapshot(): JsonObject = root.deepCopy()
    fun restore(snapshot: JsonObject) { root = snapshot.deepCopy() }

    fun check(uuid: UUID, now: Long, cooldownSeconds: Long, dailyLimit: Int, bypass: Boolean): TradeCheck {
        if (bypass) return TradeCheck()
        val data = playerNode(uuid, create = false) ?: return TradeCheck()
        val last = data.get("lastTradeAt")?.asLong ?: 0L
        val cooldownLeft = if (cooldownSeconds > 0 && last > 0L) {
            ((last + cooldownSeconds * 1000L - now).coerceAtLeast(0L) + 999L) / 1000L
        } else 0L

        val today = LocalDate.now().toString()
        val used = if (data.get("dailyDate")?.asString == today) data.get("dailyCount")?.asInt ?: 0 else 0
        return TradeCheck(cooldownLeft, dailyLimit > 0 && used >= dailyLimit)
    }

    fun recordTrade(uuid: UUID, now: Long, offeredSpecial: Boolean = false, receivedSpecial: Boolean = false) {
        val data = playerNode(uuid, create = true)!!
        val today = LocalDate.now().toString()
        val oldCount = if (data.get("dailyDate")?.asString == today) data.get("dailyCount")?.asInt ?: 0 else 0
        data.addProperty("lastTradeAt", now)
        data.addProperty("dailyDate", today)
        data.addProperty("dailyCount", oldCount + 1)
        data.addProperty("trades", (data.get("trades")?.asInt ?: 0) + 1)
        if (offeredSpecial) data.addProperty("specialsDonated", (data.get("specialsDonated")?.asInt ?: 0) + 1)
        if (receivedSpecial) data.addProperty("specialsReceived", (data.get("specialsReceived")?.asInt ?: 0) + 1)
    }

    /** Registra una donación directa. No altera cooldown, límite diario ni número de intercambios. */
    fun donationsInLastWeek(uuid: UUID, now: Long = System.currentTimeMillis()): Int {
        val data = playerNode(uuid, create = false) ?: return 0
        val cutoff = now - 7L * 24L * 60L * 60L * 1000L
        val timestamps = data.getAsJsonArray("donationTimestamps") ?: return 0
        return timestamps.count { node -> runCatching { node.asLong >= cutoff }.getOrDefault(false) }
    }

    fun recordDonation(uuid: UUID, special: Boolean, now: Long = System.currentTimeMillis()) {
        val data = playerNode(uuid, create = true)!!
        data.addProperty("donations", (data.get("donations")?.asInt ?: 0) + 1)
        if (special) data.addProperty("specialsDonated", (data.get("specialsDonated")?.asInt ?: 0) + 1)
        val cutoff = now - 7L * 24L * 60L * 60L * 1000L
        val previous = data.getAsJsonArray("donationTimestamps") ?: JsonArray()
        val timestamps = JsonArray()
        previous.mapNotNull { runCatching { it.asLong }.getOrNull() }
            .filter { it >= cutoff }
            .forEach { timestamps.add(it) }
        timestamps.add(now)
        data.add("donationTimestamps", timestamps)
    }

    fun cooldownRemainingSeconds(uuid: UUID, now: Long, cooldownSeconds: Long, bypass: Boolean): Long {
        if (bypass || cooldownSeconds <= 0L) return 0L
        val data = playerNode(uuid, create = false) ?: return 0L
        val last = data.get("lastTradeAt")?.asLong ?: 0L
        if (last <= 0L) return 0L
        return ((last + cooldownSeconds * 1000L - now).coerceAtLeast(0L) + 999L) / 1000L
    }

    fun stat(uuid: UUID, key: String): Int = playerNode(uuid, create = false)?.get(key)?.asInt ?: 0

    /** Reinicia los contadores visibles en /wt stats y usados por /wt top para un jugador. */
    fun resetStats(uuid: UUID): Boolean {
        val data = playerNode(uuid, create = false) ?: return false
        resetStatCounters(data)
        return true
    }

    /** Reinicia esos contadores para todos los jugadores guardados. Devuelve cuántos perfiles hay. */
    fun resetAllStats(): Int {
        val players = root.getAsJsonObject("players") ?: return 0
        var affected = 0
        for ((_, node) in players.entrySet()) {
            val data = node as? JsonObject ?: continue
            resetStatCounters(data)
            affected++
        }
        return affected
    }

    private fun resetStatCounters(data: JsonObject) {
        data.addProperty("trades", 0)
        data.addProperty("specialsReceived", 0)
        data.addProperty("specialsDonated", 0)
        data.addProperty("donations", 0)
    }

    /** Ranking descendente (uuid, nombre, valor) por un contador; ignora a quienes tienen 0. */
    fun ranking(key: String): List<Triple<UUID, String, Int>> {
        val players = root.getAsJsonObject("players") ?: return emptyList()
        val result = mutableListOf<Triple<UUID, String, Int>>()
        for ((id, node) in players.entrySet()) {
            val obj = node as? JsonObject ?: continue
            val value = obj.get(key)?.asInt ?: 0
            if (value <= 0) continue
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
            result.add(Triple(uuid, obj.get("name")?.asString ?: id.take(8), value))
        }
        return result.sortedByDescending { it.third }
    }

    /** Porcentaje acumulado (0–100) de que el próximo intercambio dé un Pokémon especial. */
    fun bonus(uuid: UUID): Double = playerNode(uuid, create = false)?.get("specialBonus")?.asDouble ?: 0.0

    fun setBonus(uuid: UUID, value: Double) {
        playerNode(uuid, create = true)!!.addProperty("specialBonus", value)
    }

    /** Preferencias de sonido individuales; por defecto todos los sonidos están activados. */
    fun soundEnabled(uuid: UUID, key: String): Boolean {
        val data = playerNode(uuid, create = false) ?: return true
        val preferences = data.getAsJsonObject("soundPreferences") ?: return true
        return preferences.get(key)?.let { runCatching { it.asBoolean }.getOrDefault(true) } ?: true
    }

    fun setSoundEnabled(uuid: UUID, key: String, enabled: Boolean) {
        val data = playerNode(uuid, create = true)!!
        val preferences = data.getAsJsonObject("soundPreferences") ?: JsonObject().also {
            data.add("soundPreferences", it)
        }
        preferences.addProperty(key, enabled)
    }

    fun setName(uuid: UUID, name: String) {
        playerNode(uuid, create = true)!!.addProperty("name", name)
    }

    /** Busca el UUID de un jugador por el último nombre guardado en un intercambio. */
    fun findByName(name: String): UUID? {
        val players = root.getAsJsonObject("players") ?: return null
        for ((key, value) in players.entrySet()) {
            if (value.isJsonObject && value.asJsonObject.get("name")?.asString.equals(name, ignoreCase = true)) {
                return runCatching { UUID.fromString(key) }.getOrNull()
            }
        }
        return null
    }

    fun names(): List<String> {
        val players = root.getAsJsonObject("players") ?: return emptyList()
        return players.entrySet().mapNotNull { (it.value as? JsonObject)?.get("name")?.asString }
    }

    fun save() {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun playerNode(uuid: UUID, create: Boolean): JsonObject? {
        val players = root.getAsJsonObject("players") ?: JsonObject().also { root.add("players", it) }
        val key = uuid.toString()
        val current = players.get(key)
        if (current != null && current.isJsonObject) return current.asJsonObject
        if (!create) return null
        return JsonObject().also { players.add(key, it) }
    }
}
