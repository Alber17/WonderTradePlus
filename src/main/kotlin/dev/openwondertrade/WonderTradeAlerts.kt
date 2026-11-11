package dev.openwondertrade

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Avisos para administradores: solo a la consola y al chat de los admins conectados (nunca a Discord).
 * Cubre pool casi vacía, posibles abusos y fallos importantes. Se configura en «alerts».
 */
object WonderTradeAlerts {
    private val queue = ConcurrentLinkedQueue<() -> String>()
    private val lastSent = HashMap<String, Long>()
    private val tradeTimes = HashMap<UUID, ArrayDeque<Long>>()
    private val received = HashMap<String, ArrayDeque<Long>>()
    private var lastCheck = 0L
    private var lastPoolCheck = 0L
    private const val DAY = 24L * 60L * 60L * 1000L

    private fun enabled(): Boolean = ConfigManager.bool("alerts.enabled", true)

    /** Encola un aviso (se puede llamar desde cualquier hilo). Un mismo `key` no se repite antes de `alerts.cooldownMinutes`. */
    @Synchronized
    fun alert(key: String, message: () -> String) {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        val cooldown = ConfigManager.int("alerts.cooldownMinutes", 60).coerceAtLeast(0) * 60_000L
        val last = lastSent[key]
        if (last != null && now - last < cooldown) return
        lastSent[key] = now
        if (lastSent.size > 500) lastSent.entries.removeIf { now - it.value > cooldown }
        queue.add(message)
    }

    /** Se llama una vez por segundo desde el hilo del servidor. */
    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 1_000L) return
        lastCheck = now
        if (!enabled()) { queue.clear(); return }
        if (now - lastPoolCheck >= 60_000L) {
            lastPoolCheck = now
            checkPool()
        }
        while (true) {
            val message = queue.poll() ?: break
            Lang.withPlayer(null) {
                WonderTradePlus.LOGGER.warn("Aviso: {}", message().replace(Regex("[&§]."), ""))
            }
            server.playerList.players.filter { it.hasPermissions(WonderTradeService.adminLevel()) }.forEach { admin ->
                Lang.withPlayer(admin) { admin.sendSystemMessage(WonderTradeService.info(message())) }
            }
        }
    }

    private fun checkPool() {
        if (!ConfigManager.bool("alerts.poolLow.enabled", true) || !ConfigManager.bool("pool.enabled", true)) return
        val threshold = ConfigManager.int("alerts.poolLow.threshold", 10)
        if (threshold <= 0) return
        val size = WonderTradeService.poolSize()
        if (size < threshold) alert("poolLow") { Lang.t("&c⚠ La pool tiene solo &f{0} &cPokémon (el aviso salta por debajo de {1}).", size, threshold) }
    }

    /** Detecta patrones sospechosos tras cada intercambio. Solo avisa; no sanciona a nadie. */
    fun trade(player: ServerPlayer, donorUuid: String, donorName: String, now: Long) {
        if (!enabled() || !ConfigManager.bool("alerts.abuse.enabled", true)) return
        val name = player.gameProfile.name
        val times = tradeTimes.getOrPut(player.uuid) { ArrayDeque() }
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > DAY) times.removeFirst()
        val maxDaily = ConfigManager.int("alerts.abuse.maxTradesPer24h", 50)
        if (maxDaily > 0 && times.size > maxDaily) {
            val count = times.size
            alert("daily:${player.uuid}") { Lang.t("&c⚠ Posible abuso: &f{0} &cha hecho &f{1} &cintercambios en 24 horas.", name, count) }
        }
        val donor = runCatching { UUID.fromString(donorUuid) }.getOrNull() ?: return
        if (donor == player.uuid) return
        val key = "${player.uuid}>$donor"
        val list = received.getOrPut(key) { ArrayDeque() }
        list.addLast(now)
        while (list.isNotEmpty() && now - list.first() > DAY) list.removeFirst()
        if (received.size > 2000) received.entries.removeIf { entry -> entry.value.isEmpty() || now - entry.value.last() > DAY }
        val forward = list.size
        val back = received["$donor>${player.uuid}"]?.count { now - it <= DAY } ?: 0
        val pairMin = ConfigManager.int("alerts.abuse.pairCount", 3)
        val loopMin = ConfigManager.int("alerts.abuse.reciprocalCount", 2)
        if (pairMin > 0 && forward >= pairMin) {
            alert("pair:$key") { Lang.t("&c⚠ Posible abuso: &f{0} &cha recibido &f{1} &cPokémon aportados por &f{2} &cen 24 horas.", name, forward, donorName) }
        } else if (loopMin > 0 && forward >= loopMin && back >= loopMin) {
            val pairKey = listOf(player.uuid.toString(), donor.toString()).sorted().joinToString("~")
            alert("loop:$pairKey") { Lang.t("&c⚠ Posible abuso: &f{0} &cy &f{1} &cse intercambian Pokémon entre sí (&f{2} &cy &f{3} &cveces en 24 horas).", name, donorName, forward, back) }
        }
    }
}
