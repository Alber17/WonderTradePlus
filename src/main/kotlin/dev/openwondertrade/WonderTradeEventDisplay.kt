package dev.openwondertrade

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import java.util.UUID

/** Barra de tiempo (bossbar) de los eventos activos y aviso al entrar. Todo ocurre en el servidor. */
object WonderTradeEventDisplay {
    private val bars = HashMap<Int, ServerBossEvent>()
    private val barStyle = HashMap<Int, String>()
    private val joinQueue = HashMap<UUID, Long>()

    // ------------------------------------------------------------ aviso al entrar

    fun onJoin(player: ServerPlayer) {
        if (!ConfigManager.bool("events.joinNotice.enabled", true)) return
        val delay = ConfigManager.int("events.joinNotice.delaySeconds", 3).coerceAtLeast(0) * 1000L
        joinQueue[player.uuid] = System.currentTimeMillis() + delay
    }

    private fun processJoins(server: MinecraftServer) {
        if (joinQueue.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = joinQueue.filterValues { it <= now }.keys.toList()
        for (uuid in ready) {
            joinQueue.remove(uuid)
            val player = server.playerList.getPlayer(uuid) ?: continue
            val active = WonderTradeEvents.active(now)
            if (active.isEmpty()) continue
            val lines = mutableListOf(ConfigManager.string("messages.eventJoinHeader", "&d★ Hay eventos de Wonder Trade activos:"))
            val details = ConfigManager.bool("events.joinNotice.showDetails", false)
            active.forEach { event ->
                lines += WonderTradeService.format(
                    ConfigManager.string("messages.eventJoinLine", "&7- &f{name} &7(termina en &f{time}&7)"),
                    mapOf("name" to event.title, "time" to WonderTradeEvents.formatDuration((event.endAt - now + 999L) / 1000L))
                )
                if (details) WonderTradeEvents.describe(event).forEach { lines += "  $it" }
            }
            lines += ConfigManager.string("messages.eventJoinFooter", "&7Usa &e/wt eventos &7para ver los detalles.")
            player.sendSystemMessage(WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n")))
        }
    }

    // ------------------------------------------------------------ barras de tiempo

    private fun parseColor(text: String): BossEvent.BossBarColor =
        BossEvent.BossBarColor.values().firstOrNull { it.name.equals(text.trim(), true) } ?: BossEvent.BossBarColor.PURPLE

    private fun parseOverlay(text: String): BossEvent.BossBarOverlay =
        BossEvent.BossBarOverlay.values().firstOrNull { it.name.equals(text.trim(), true) } ?: BossEvent.BossBarOverlay.PROGRESS

    private fun syncBars(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val active = if (ConfigManager.bool("events.bossbar.enabled", true)) WonderTradeEvents.active(now) else emptyList()
        // Quita las barras de eventos terminados.
        bars.keys.toList().filter { id -> active.none { it.id == id } }.forEach { id ->
            bars.remove(id)?.removeAllPlayers()
            barStyle.remove(id)
        }
        if (active.isEmpty()) return

        val overlay = parseOverlay(ConfigManager.string("events.bossbar.overlay", "PROGRESS"))
        val format = ConfigManager.string("events.bossbar.format", "&d★ {name} &7— &f{time}")
        val online = server.playerList.players
        for (event in active) {
            val color = parseColor(
                ConfigManager.string(if (event.type == EventType.RISK) "events.bossbar.risk.color" else "events.bossbar.receive.color", "PURPLE")
            )
            // Si cambian el color o el estilo en la configuración, se vuelve a crear la barra.
            val style = "${color.name}/${overlay.name}"
            if (barStyle[event.id] != style) {
                bars.remove(event.id)?.removeAllPlayers()
                barStyle[event.id] = style
            }
            val bar = bars.getOrPut(event.id) {
                ServerBossEvent(net.minecraft.network.chat.Component.empty(), color, overlay)
            }
            val total = (event.endAt - event.startAt).coerceAtLeast(1L)
            val remaining = (event.endAt - now).coerceAtLeast(0L)
            bar.progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            bar.name = WonderTradeService.colour(
                WonderTradeService.format(format, mapOf("name" to event.title, "time" to WonderTradeEvents.formatDuration((remaining + 999L) / 1000L)))
            )
            online.forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
            bar.players.toList().forEach { if (!online.contains(it)) bar.removePlayer(it) }
        }
    }

    /** Se llama una vez por segundo. */
    fun tick(server: MinecraftServer) {
        syncBars(server)
        processJoins(server)
    }
}
