package dev.openwondertrade

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.BossEvent
import java.util.UUID

/** Barra de tiempo (bossbar) de los eventos activos y aviso al entrar. Todo ocurre en el servidor. */
object WonderTradeEventDisplay {
    private val bars = HashMap<String, ServerBossEvent>()
    private val seasonBars = HashMap<String, ServerBossEvent>()
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
        if (active.isEmpty()) {
            syncSeasonBars(server, groups)
            return
        }

        val overlay = parseOverlay(ConfigManager.string("events.bossbar.overlay", "PROGRESS"))
        for (event in active) {
            val looks = categoryLooks(event.categories)
            val total = (event.endAt - event.startAt).coerceAtLeast(1L)
            val remaining = (event.endAt - now).coerceAtLeast(0L)
            // La barra se queda en un color. Con varias categorías es morada, que no usa ningún nombre; el texto sigue recorriendo cada una.
            val barColor = if (looks.size == 1) looks[0].bar else BossEvent.BossBarColor.PURPLE
            for ((language, players) in groups) {
                val key = "${event.id}|$language"
                if (barStyle[key] != overlay.name) {
                    bars.remove(key)?.removeAllPlayers()
                    barStyle[key] = overlay.name
                }
                val bar = bars.getOrPut(key) {
                    ServerBossEvent(Component.empty(), barColor, overlay)
                }
                bar.color = barColor
                bar.progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                bar.name = Lang.withPlayer(players.first()) {
                    val format = ConfigManager.string("events.bossbar.format", "★ {name} — {time}")
                    val plain = WonderTradeService.format(
                        format,
                        mapOf("name" to event.title, "time" to WonderTradeEvents.formatDuration((remaining + 999L) / 1000L))
                    ).replace(Regex("[&§][0-9a-fk-orA-FK-OR]"), "")
                    movingGradient(plain, looks.map { it.rgb }, now)
                }
                players.forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
                bar.players.toList().forEach { if (!players.contains(it)) bar.removePlayer(it) }
            }
        }
        syncSeasonBars(server, groups)
    }

    /** Bossbar azul exclusiva de la hora navideña, con texto animado en degradado azul-blanco. */
    private fun syncSeasonBars(server: MinecraftServer, groups: Map<String, List<ServerPlayer>>) {
        val season = WonderTradeSeasons.current()?.takeIf { WonderTradeSeasons.isRunning() }
        val online = groups.values.flatten()
        if (season == null || online.isEmpty()) {
            seasonBars.values.forEach { it.removeAllPlayers() }
            seasonBars.clear()
            return
        }
        val now = System.currentTimeMillis()
        val total = (season.sessionDurationMinutes * 60_000L).coerceAtLeast(1L)
        val remaining = (WonderTradeSeasons.sessionEndAt() - now).coerceAtLeast(0L)
        for ((language, players) in groups) {
            val key = "${season.id}|$language"
            val bar = seasonBars.getOrPut(key) {
                ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS)
            }
            bar.progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val time = WonderTradeEvents.formatDuration((remaining + 999L) / 1000L)
            bar.name = Lang.withPlayer(players.first()) {
                movingGradient("❄ HORA NAVIDEÑA — $time", listOf(intArrayOf(35, 139, 255)), now)
            }
            players.forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
            bar.players.toList().forEach { if (!players.contains(it)) bar.removePlayer(it) }
        }
        val wanted = groups.keys.map { "${season.id}|$it" }.toSet()
        seasonBars.keys.toList().filter { it !in wanted }.forEach { seasonBars.remove(it)?.removeAllPlayers() }
    }

    /** Color de la barra y del texto, los mismos que el menú de /wt especiales. Shiny, que no sale ahí, va en amarillo. */
    private class CategoryLook(val bar: BossEvent.BossBarColor, val rgb: IntArray)

    private fun categoryLooks(categories: List<EventCategory>): List<CategoryLook> {
        val chosen = categories.ifEmpty { listOf(EventCategory.LEGENDARY) }
        return chosen.map { category ->
            when (category) {
                EventCategory.SHINY -> CategoryLook(BossEvent.BossBarColor.YELLOW, intArrayOf(255, 255, 85))
                EventCategory.ULTRABEAST -> CategoryLook(BossEvent.BossBarColor.BLUE, intArrayOf(0, 170, 170))
                EventCategory.PARADOX -> CategoryLook(BossEvent.BossBarColor.RED, intArrayOf(255, 85, 85))
                EventCategory.MYTHICAL -> CategoryLook(BossEvent.BossBarColor.PINK, intArrayOf(255, 85, 255))
                EventCategory.LEGENDARY -> CategoryLook(BossEvent.BossBarColor.YELLOW, intArrayOf(255, 170, 0))
            }
        }
    }

    /**
     * Ola de color sobre el texto. Con una sola categoría oscila entre ese color y el blanco.
     * Con varias, recorre el color de cada una.
     */
    private fun movingGradient(text: String, colors: List<IntArray>, now: Long): Component {
        val result = Component.empty()
        val phase = now / 420.0
        val palette = if (colors.size <= 1) listOf(colors.firstOrNull() ?: intArrayOf(255, 255, 255), intArrayOf(245, 252, 255)) else colors
        text.forEachIndexed { index, char ->
            val rgb = if (colors.size <= 1) {
                val wave = ((index * 0.16 + phase) % 2.0)
                val blend = if (wave <= 1.0) wave else 2.0 - wave
                mix(palette[0], palette[1], blend)
            } else {
                val pos = (index * 0.16 + phase) % palette.size
                val left = pos.toInt()
                mix(palette[left], palette[(left + 1) % palette.size], pos - left)
            }
            result.append(Component.literal(char.toString()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))))
        }
        return result
    }

    private fun mix(from: IntArray, to: IntArray, blend: Double): Int {
        val r = (from[0] + (to[0] - from[0]) * blend).toInt()
        val g = (from[1] + (to[1] - from[1]) * blend).toInt()
        val b = (from[2] + (to[2] - from[2]) * blend).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /** Se llama en cada tick del servidor para que el degradado y el progreso avancen sin saltos de un segundo. */
    fun tick(server: MinecraftServer) {
        syncBars(server)
        processJoins(server)
    }
}
