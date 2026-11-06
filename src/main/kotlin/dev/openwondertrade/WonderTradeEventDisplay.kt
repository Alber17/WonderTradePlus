package dev.openwondertrade

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import java.util.UUID

/** Barra de tiempo (bossbar) de los eventos activos y aviso al entrar. Todo ocurre en el servidor. */
object WonderTradeEventDisplay {
    private val bars = HashMap<String, ServerBossEvent>()
    private val barStyle = HashMap<String, String>()
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
            Lang.bindPlayer(player)   // el aviso se redacta en el idioma de quien entra
            val active = WonderTradeEvents.active(now)
            val upcoming = WonderTradeSchedule.upcomingLines()
            val seasonLine = WonderTradeSeasons.joinLine()
            if (active.isEmpty() && upcoming.isEmpty() && seasonLine == null) continue
            val lines = mutableListOf<String>()
            if (seasonLine != null) lines += seasonLine
            if (active.isNotEmpty()) lines += ConfigManager.string("messages.eventJoinHeader", "&d★ Hay eventos de Wonder Trade activos:")
            val details = ConfigManager.bool("events.joinNotice.showDetails", false)
            active.forEach { event ->
                lines += WonderTradeService.format(
                    ConfigManager.string("messages.eventJoinLine", "&7- &f{name} &7(termina en &f{time}&7)"),
                    mapOf("name" to event.title, "time" to WonderTradeEvents.formatDuration((event.endAt - now + 999L) / 1000L))
                )
                if (details) WonderTradeEvents.describe(event).forEach { lines += "  $it" }
            }
            lines += upcoming
            lines += ConfigManager.string("messages.eventJoinFooter", "&7Usa &e/wt eventos &7para ver los detalles.")
            player.sendSystemMessage(WonderTradeService.colour(WonderTradeService.prefix() + lines.joinToString("\n")))
        }
        Lang.reset()
    }

    // ------------------------------------------------------------ barras de tiempo

    private fun parseColor(text: String): BossEvent.BossBarColor =
        BossEvent.BossBarColor.values().firstOrNull { it.name.equals(text.trim(), true) } ?: BossEvent.BossBarColor.PURPLE

    private fun parseOverlay(text: String): BossEvent.BossBarOverlay =
        BossEvent.BossBarOverlay.values().firstOrNull { it.name.equals(text.trim(), true) } ?: BossEvent.BossBarOverlay.PROGRESS

    private fun syncBars(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val active = if (ConfigManager.bool("events.bossbar.enabled", true)) WonderTradeEvents.active(now) else emptyList()
        val online = server.playerList.players
        // Una barra por evento e idioma, para que cada jugador la vea en su propio idioma.
        val groups: Map<String, List<ServerPlayer>> = online.groupBy { Lang.languageOf(it) ?: Lang.current() }
        val wanted = active.flatMap { event -> groups.keys.map { "${event.id}|$it" } }.toSet()
        // Quita las barras de eventos terminados o de idiomas que ya no usa nadie.
        bars.keys.toList().filter { it !in wanted }.forEach { key ->
            bars.remove(key)?.removeAllPlayers()
            barStyle.remove(key)
        }
        if (active.isEmpty()) return

        val overlay = parseOverlay(ConfigManager.string("events.bossbar.overlay", "PROGRESS"))
        for (event in active) {
            val color = parseColor(
                ConfigManager.string(if (event.type == EventType.RISK) "events.bossbar.risk.color" else "events.bossbar.receive.color", "PURPLE")
            )
            val total = (event.endAt - event.startAt).coerceAtLeast(1L)
            val remaining = (event.endAt - now).coerceAtLeast(0L)
            for ((language, players) in groups) {
                val key = "${event.id}|$language"
                // Si cambian el color o el estilo en la configuración, se vuelve a crear la barra.
                val style = "${color.name}/${overlay.name}"
                if (barStyle[key] != style) {
                    bars.remove(key)?.removeAllPlayers()
                    barStyle[key] = style
                }
                val bar = bars.getOrPut(key) {
                    ServerBossEvent(net.minecraft.network.chat.Component.empty(), color, overlay)
                }
                bar.progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                bar.name = Lang.withPlayer(players.first()) {
                    val format = ConfigManager.string("events.bossbar.format", "&d★ {name} &7— &f{time}")
                    WonderTradeService.colour(
                        WonderTradeService.format(format, mapOf("name" to event.title, "time" to WonderTradeEvents.formatDuration((remaining + 999L) / 1000L)))
                    )
                }
                players.forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
                bar.players.toList().forEach { if (!players.contains(it)) bar.removePlayer(it) }
            }
        }
    }

    /** Se llama una vez por segundo. */
    fun tick(server: MinecraftServer) {
        syncBars(server)
        processJoins(server)
    }
}
