package dev.openwondertrade

import com.google.gson.GsonBuilder
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

    fun recordTrade(uuid: UUID, now: Long) {
        val data = playerNode(uuid, create = true)!!
        val today = LocalDate.now().toString()
        val oldCount = if (data.get("dailyDate")?.asString == today) data.get("dailyCount")?.asInt ?: 0 else 0
        data.addProperty("lastTradeAt", now)
        data.addProperty("dailyDate", today)
        data.addProperty("dailyCount", oldCount + 1)
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
