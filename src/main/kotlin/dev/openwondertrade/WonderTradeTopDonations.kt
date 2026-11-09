package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Top Donaciones: en los meses activos cada donación hecha con /wt donar suma puntos; el último día del mes se anuncia
 * el Top 3 y los ganadores reclaman las recompensas configuradas (comandos de consola) desde /wt recompensas. Guarda su estado en
 * top-donations.json. Todo se configura en la sección `topDonations`.
 */
object WonderTradeTopDonations {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null

    private class Entry(var name: String, var points: Int, var donations: Int, var reachedAt: Long)
    /** rank 0 = recompensa de participación (sin puesto en el Top 3). */
    private class Pending(val uuid: String, val name: String, val rank: Int, val period: String, val points: Int, val donations: Int = 0, val createdAt: Long = System.currentTimeMillis())

    /** «2026-11» -> uuid -> puntos. */
    private val periods = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
    private val closed = LinkedHashSet<String>()
    private val pending = mutableListOf<Pending>()
    private var lastCheck = 0L

    // ------------------------------------------------------------ carga y guardado

    fun load(directory: Path? = null) {
        if (directory != null) file = directory.resolve("top-donations.json")
        val path = file ?: return
        periods.clear()
        closed.clear()
        pending.clear()
        if (!Files.exists(path)) return
        try {
            val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            val periodsNode = root.getAsJsonObject("periods") ?: JsonObject()
            for ((key, node) in periodsNode.entrySet()) {
                val players = LinkedHashMap<String, Entry>()
                for ((uuid, value) in node.asJsonObject.entrySet()) {
                    val data = value.asJsonObject
                    players[uuid] = Entry(
                        data.get("name")?.asString ?: "?", data.get("points")?.asInt ?: 0,
                        data.get("donations")?.asInt ?: 0, data.get("reachedAt")?.asLong ?: 0L
                    )
                }
                periods[key] = players
            }
            root.getAsJsonArray("closed")?.forEach { closed.add(it.asString) }
            root.getAsJsonArray("pending")?.forEach { node ->
                val data = node.asJsonObject
                pending.add(Pending(
                    data.get("uuid").asString, data.get("name")?.asString ?: "?", data.get("rank").asInt,
                    data.get("period").asString, data.get("points")?.asInt ?: 0, data.get("donations")?.asInt ?: 0,
                    data.get("createdAt")?.asLong ?: System.currentTimeMillis()
                ))
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo cargar top-donations.json. Se empieza sin datos del Top Donaciones.", exception)
            runCatching { Files.copy(path, path.resolveSibling("top-donations.json.corrupt-${System.currentTimeMillis()}")) }
            periods.clear(); closed.clear(); pending.clear()
        }
    }

    private fun save() {
        val path = file ?: return
        Files.createDirectories(path.parent)
        val root = JsonObject().apply {
            add("periods", JsonObject().also { node ->
                periods.forEach { (key, players) ->
                    node.add(key, JsonObject().also { playersNode ->
                        players.forEach { (uuid, entry) ->
                            playersNode.add(uuid, JsonObject().apply {
                                addProperty("name", entry.name)
                                addProperty("points", entry.points)
                                addProperty("donations", entry.donations)
                                addProperty("reachedAt", entry.reachedAt)
                            })
                        }
                    })
                }
            })
            add("closed", JsonArray().also { array -> closed.forEach { array.add(it) } })
            add("pending", JsonArray().also { array ->
                pending.forEach { p ->
                    array.add(JsonObject().apply {
                        addProperty("uuid", p.uuid); addProperty("name", p.name); addProperty("rank", p.rank)
                        addProperty("period", p.period); addProperty("points", p.points); addProperty("donations", p.donations); addProperty("createdAt", p.createdAt)
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

    // ------------------------------------------------------------ reglas

    fun enabled(): Boolean = ConfigManager.bool("topDonations.enabled", true)

    private fun zone() = WonderTradeSchedule.zone()

    private fun keyOf(period: YearMonth): String = "%04d-%02d".format(period.year, period.monthValue)

    /**
     * Meses con Top Donaciones: por defecto los impares (1, 3, 5, 7, 9, 11) en el primer año
     * (`topDonations.firstYear`) y los pares al año siguiente, alternando cada año.
     */
    fun isActiveMonth(period: YearMonth): Boolean {
        if (ConfigManager.bool("topDonations.everyMonth", false)) return true
        val firstYear = ConfigManager.int("topDonations.firstYear", 2026)
        val oddInFirstYear = ConfigManager.bool("topDonations.oddMonthsInFirstYear", true)
        val alternate = ConfigManager.bool("topDonations.alternateYears", true)
        val flipped = alternate && Math.floorMod(period.year - firstYear, 2) != 0
        val oddActive = oddInFirstYear != flipped
        return (period.monthValue % 2 == 1) == oddActive
    }

    private fun monthName(period: YearMonth): String =
        period.month.getDisplayName(TextStyle.FULL, Lang.locale()).replaceFirstChar { it.uppercase() } + " " + period.year

    /** Siguiente mes activo a partir de [from] (incluido). */
    private fun nextActive(from: YearMonth): YearMonth? =
        (0..48).map { from.plusMonths(it.toLong()) }.firstOrNull { isActiveMonth(it) }

    /** Puntos de un Pokémon donado: 1 normal, 2 shiny, 3 Ultraente/Paradoja, 4 legendario/mítico (sin extra por shiny + categoría). */
    fun pointsFor(pokemon: Pokemon): Int {
        val category = WonderTradeRules.speciesCategoryKey(pokemon)
        val key = when (category) {
            "ultraBeast" -> "ultraBeast" to 3
            "paradox" -> "paradox" to 3
            "mythical" -> "mythical" to 4
            "legendary" -> "legendary" to 4
            else -> if (pokemon.shiny) "shiny" to 2 else "normal" to 1
        }
        return ConfigManager.int("topDonations.points.${key.first}", key.second).coerceAtLeast(0)
    }

    // ------------------------------------------------------------ registro

    /** Suma los puntos de una donación hecha con /wt donar (solo en meses activos y mientras el periodo no esté cerrado). */
    fun record(player: ServerPlayer, pokemon: Pokemon) {
        if (!enabled()) return
        val period = YearMonth.now(zone())
        if (!isActiveMonth(period)) return
        val key = keyOf(period)
        if (key in closed) return
        val entry = periods.getOrPut(key) { LinkedHashMap() }
            .getOrPut(player.uuid.toString()) { Entry(player.gameProfile.name, 0, 0, 0L) }
        entry.name = player.gameProfile.name
        entry.points += pointsFor(pokemon)
        entry.donations++
        entry.reachedAt = System.currentTimeMillis()
        runCatching { save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar top-donations.json.", it) }
    }

    /**
     * Desempate (`topDonations.tieBreak`): «firstToReach» (por defecto) gana quien llegó antes a esos puntos;
     * «mostDonations» gana quien hizo más donaciones (y, si siguen igualados, quien llegó antes); «share» deja a los
     * empatados con el mismo puesto y todos reciben el premio de ese puesto.
     */
    private fun tieBreak(): String = ConfigManager.string("topDonations.tieBreak", "firstToReach").lowercase()

    private fun ranking(key: String): List<Pair<String, Entry>> {
        val list = (periods[key] ?: emptyMap()).entries.map { it.key to it.value }.filter { it.second.points > 0 }
        val comparator = if (tieBreak() == "mostdonations")
            compareByDescending<Pair<String, Entry>> { it.second.points }.thenByDescending { it.second.donations }.thenBy { it.second.reachedAt }
        else compareByDescending<Pair<String, Entry>> { it.second.points }.thenBy { it.second.reachedAt }
        return list.sortedWith(comparator)
    }

    private class Placed(val rank: Int, val uuid: String, val entry: Entry)

    /** Puestos: consecutivos (1, 2, 3...) salvo en modo «share», donde los empatados a puntos comparten puesto (1, 1, 3...). */
    private fun placed(key: String): List<Placed> {
        val list = ranking(key)
        val share = tieBreak() == "share"
        return list.mapIndexed { index, (uuid, entry) ->
            val rank = if (share) list.indexOfFirst { it.second.points == entry.points } + 1 else index + 1
            Placed(rank, uuid, entry)
        }
    }

    // ------------------------------------------------------------ consulta

    /** Respuesta de /wt donaciones. */
    fun component(player: ServerPlayer?): Component {
        val lines = mutableListOf<String>()
        val zone = zone()
        val period = YearMonth.now(zone)
        if (!enabled()) {
            lines += ConfigManager.string("messages.topDonationsDisabled", "&7El Top Donaciones está desactivado.")
        } else if (!isActiveMonth(period)) {
            val next = nextActive(period)
            lines += ConfigManager.string("messages.topDonationsInactive", "&7Este mes no hay Top Donaciones. El próximo será en &f{month}&7.")
                .replace("{month}", next?.let { monthName(it) } ?: "—")
        } else {
            val key = keyOf(period)
            if (key !in closed) {
                lines += ConfigManager.string(
                    "messages.topDonationsNotFinished",
                    "&7El Top Donaciones todavía está en curso. Podrás consultar la clasificación cuando termine el evento."
                )
            } else {
                lines += WonderTradeService.format(
                    ConfigManager.string("messages.topDonationsCurrentHeader", "&d&lResultados finales del Top Donaciones de {month}:"),
                    mapOf("month" to monthName(period), "end" to "${period.atEndOfMonth().dayOfMonth}/${period.monthValue}")
                )
                val list = placed(key)
                if (list.isEmpty()) lines += ConfigManager.string("messages.topDonationsEmpty", "&7Nadie ha donado durante este evento.")
                list.take(10).forEach { item ->
                    lines += WonderTradeService.format(
                        ConfigManager.string("messages.topDonationsLine", "&6#{rank} &f{player} &7— &e{points} puntos &8({donations} donaciones)"),
                        mapOf("rank" to item.rank, "player" to item.entry.name, "points" to item.entry.points, "donations" to item.entry.donations)
                    )
                }
                if (player != null) {
                    val mine = periods[key]?.get(player.uuid.toString())
                    lines += WonderTradeService.format(
                        ConfigManager.string("messages.topDonationsYou", "&7Tus puntos: &e{points}"),
                        mapOf("points" to (mine?.points ?: 0))
                    )
                }
                lines += ConfigManager.string("messages.topDonationsPointsInfo", "&8Puntos: normal 1 · shiny 2 · Ultraente/Paradoja 3 · legendario/mítico 4.")
            }
        }
        return WonderTradeService.colour(WonderTradeService.prefix() + lines.joinToString("\n"))
    }

    // ------------------------------------------------------------ cierre y recompensas

    /** Una vez por segundo (con limitación interna): cierra los periodos terminados y entrega recompensas pendientes. */
    fun tick(server: MinecraftServer) {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < 30_000L) return
        lastCheck = now
        val zone = zone()
        val today = LocalDate.now(zone)
        val hour = LocalTime.now(zone).hour
        val announceHour = ConfigManager.int("topDonations.announceHour", 20).coerceIn(0, 23)
        // Incluye el periodo activo aunque nadie haya donado, para que también pueda cerrarse y mostrar resultados vacíos.
        val periodsToCheck = periods.keys.toMutableSet()
        val currentPeriod = YearMonth.from(today)
        if (isActiveMonth(currentPeriod)) periodsToCheck.add(keyOf(currentPeriod))
        for (key in periodsToCheck) {
            if (key in closed) continue
            val period = runCatching { YearMonth.parse(key) }.getOrNull() ?: continue
            val lastDay = period.atEndOfMonth()
            val due = today.isAfter(lastDay) || (today == lastDay && hour >= announceHour)
            if (due) runCatching { finish(server, key, period) }.onFailure { WonderTradePlus.LOGGER.error("Falló el cierre del Top Donaciones.", it) }
        }
        runCatching { remindPending(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el aviso de recompensas del Top Donaciones.", it) }
    }

    private fun finish(server: MinecraftServer, key: String, period: YearMonth) {
        val winners = placed(key).filter { it.rank <= 3 }
        closed.add(key)
        fun buildLines(): List<String> {
            val lines = mutableListOf<String>()
            if (winners.isEmpty()) {
                lines += WonderTradeService.format(
                    ConfigManager.string("messages.topDonationsNobody", "&d★ Top Donaciones de {month}: nadie ha donado este mes."),
                    mapOf("month" to monthName(period))
                )
            } else {
                lines += WonderTradeService.format(
                    ConfigManager.string("messages.topDonationsHeader", "&d&l★ ¡Top Donaciones de {month}!"),
                    mapOf("month" to monthName(period))
                )
                winners.forEach { item ->
                    lines += WonderTradeService.format(
                        ConfigManager.string("messages.topDonationsLine", "&6#{rank} &f{player} &7— &e{points} puntos &8({donations} donaciones)"),
                        mapOf("rank" to item.rank, "player" to item.entry.name, "points" to item.entry.points, "donations" to item.entry.donations)
                    )
                }
                lines += ConfigManager.string("messages.topDonationsFooter", "&7¡Gracias a todos por donar! Los ganadores reclaman su premio con /wt recompensas.")
            }
            return lines
        }
        winners.forEach { item -> pending.add(Pending(item.uuid, item.entry.name, item.rank, key, item.entry.points, item.entry.donations)) }
        // Recompensa de participación: quien haya donado al menos `participation.minDonations` veces y no esté ya en el podio.
        if (ConfigManager.bool("topDonations.participation.enabled", true)) {
            val minimum = ConfigManager.int("topDonations.participation.minDonations", 10).coerceAtLeast(1)
            val includeWinners = ConfigManager.bool("topDonations.participation.includeWinners", false)
            val winnerIds = winners.map { it.uuid }.toSet()
            (periods[key] ?: emptyMap()).forEach { (uuid, entry) ->
                if (entry.donations >= minimum && (includeWinners || uuid !in winnerIds)) {
                    pending.add(Pending(uuid, entry.name, 0, key, entry.points, entry.donations))
                }
            }
        }
        save()
        WonderTradeEvents.broadcast(server) { buildLines().joinToString("\n") }
        runCatching {
            WonderTradeDiscord.topDonations(monthName(period), winners.map { WonderTradeDiscord.TopLine(it.rank, it.entry.name, it.entry.points, it.entry.donations) })
        }
        WonderTradePlus.LOGGER.info("Top Donaciones {} cerrado con {} ganadores.", key, winners.size)
    }

    private fun rewardCommands(rank: Int): List<String> =
        if (rank == 0) ConfigManager.strings("topDonations.participation.rewards")
        else ConfigManager.strings("topDonations.rewards." + when (rank) { 1 -> "first"; 2 -> "second"; else -> "third" })

    private fun rewardDescription(rank: Int): List<String> =
        if (rank == 0) ConfigManager.strings("topDonations.participation.description")
        else ConfigManager.strings("topDonations.rewardDescriptions." + when (rank) { 1 -> "first"; 2 -> "second"; else -> "third" })

    // ------------------------------------------------------------ reclamación

    /** Recompensa pendiente de un jugador, lista para mostrarse en el menú. */
    class Claim(val period: String, val title: String, val rank: Int, val points: Int, val donations: Int, val description: List<String>)

    /** Recuerdo de cuándo se avisó por última vez a cada ganador: «uuid|periodo» -> milisegundos. */
    private val lastClaimNotice = HashMap<String, Long>()

    private fun claimKey(item: Pending) = item.uuid + "|" + item.period

    /** Recompensas sin reclamar de un jugador (más antiguas primero). Usa el idioma activo para el nombre del mes. */
    fun claimsFor(player: ServerPlayer): List<Claim> =
        pending.filter { it.uuid == player.uuid.toString() }.sortedBy { it.period }.map { item ->
            val period = runCatching { YearMonth.parse(item.period) }.getOrNull()
            val title = if (period != null) monthName(period) else item.period
            val description = rewardDescription(item.rank).ifEmpty { listOf(if (item.rank == 0) Lang.tr("&7Recompensa por participar") else Lang.t("&7Recompensa del puesto &f#{0}", item.rank)) }
            Claim(item.period, title, item.rank, item.points, item.donations, description)
        }

    enum class ClaimResult { OK, GONE, NO_SPACE }

    private fun freeSlots(player: ServerPlayer): Int = player.inventory.items.count { it.isEmpty }

    /**
     * Entrega la recompensa de un periodo al jugador, la marca como reclamada y le avisa. Si no tiene los huecos libres
     * exigidos (`topDonations.claim.requiredFreeSlots`) no se ejecuta ningún comando y la recompensa sigue pendiente.
     */
    fun claim(server: MinecraftServer, player: ServerPlayer, period: String): ClaimResult {
        val item = pending.firstOrNull { it.uuid == player.uuid.toString() && it.period == period } ?: return ClaimResult.GONE
        val needed = ConfigManager.int("topDonations.claim.requiredFreeSlots", 1).coerceIn(0, 36)
        if (needed > 0 && freeSlots(player) < needed) {
            Lang.withPlayer(player) {
                player.sendSystemMessage(WonderTradeService.info(Lang.t("&cNo tienes espacio en el inventario: libera al menos &f{0} &chueco(s) y vuelve a reclamar.", needed)))
            }
            return ClaimResult.NO_SPACE
        }
        pending.remove(item)
        lastClaimNotice.remove(claimKey(item))
        runCatching { save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar top-donations.json.", it) }
        val source = server.createCommandSourceStack().withSuppressedOutput()
        for (template in rewardCommands(item.rank)) {
            val command = WonderTradeService.format(
                template.trim().removePrefix("/"),
                mapOf("player" to player.gameProfile.name, "rank" to item.rank, "points" to item.points, "period" to item.period)
            )
            if (command.isBlank()) continue
            runCatching { server.commands.performPrefixedCommand(source, command) }
                .onFailure { WonderTradePlus.LOGGER.error("Falló el comando de recompensa '{}'.", command, it) }
        }
        if (item.rank == 0) {
            Lang.withPlayer(player) {
                player.sendSystemMessage(WonderTradeService.info(Lang.t("&d★ ¡Gracias por participar en el Top Donaciones con &f{0} &ddonaciones! Tu recompensa ya está en tu inventario.", item.donations)))
            }
        } else {
            WonderTradeService.send(player, "topDonationsReward", mapOf("rank" to item.rank, "points" to item.points))
        }
        WonderTradePlus.LOGGER.info("{} ha reclamado la recompensa del Top Donaciones {} (puesto {}).", player.gameProfile.name, item.period, if (item.rank == 0) "participación" else item.rank.toString())
        return ClaimResult.OK
    }

    /** Aviso clicable que abre el menú de recompensas. */
    private fun sendClaimNotice(player: ServerPlayer) {
        Lang.withPlayer(player) {
            val claims = claimsFor(player)
            if (claims.isEmpty()) return@withPlayer
            val text = if (claims.size == 1) Lang.t("&d★ Tienes una recompensa del Top Donaciones por reclamar: &f{0}&d. ", claims[0].title)
            else Lang.t("&d★ Tienes &f{0} &drecompensas del Top Donaciones por reclamar. ", claims.size)
            val button = Component.literal(Lang.tr("§e§n[Reclamar]"))
                .setStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/wt recompensas")))
            player.sendSystemMessage(WonderTradeService.colour(WonderTradeService.prefix() + text).copy().append(button))
            WonderTradeGui.playSound(player, "cooldownReady")
        }
    }

    /**
     * Avisa a los ganadores conectados de que tienen recompensas por reclamar: nada más cerrarse el evento (o al entrar)
     * y después cada `topDonations.claim.reminderMinutes` minutos (0 = sin recordatorios repetidos).
     */
    private fun remindPending(server: MinecraftServer) {
        if (pending.isEmpty()) { lastClaimNotice.clear(); return }
        val now = System.currentTimeMillis()
        if (ConfigManager.bool("topDonations.claim.expire.enabled", false)) {
            val limit = ConfigManager.int("topDonations.claim.expire.days", 30).coerceAtLeast(1) * 86_400_000L
            val expired = pending.filter { now - it.createdAt > limit }
            if (expired.isNotEmpty()) {
                pending.removeAll(expired.toSet())
                runCatching { save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar top-donations.json.", it) }
                expired.forEach { WonderTradePlus.LOGGER.info("Recompensa del Top Donaciones {} de {} caducada sin reclamar.", it.period, it.name) }
                if (pending.isEmpty()) { lastClaimNotice.clear(); return }
            }
        }
        val interval = ConfigManager.int("topDonations.claim.reminderMinutes", 10).coerceAtLeast(0) * 60_000L
        val keys = pending.map { claimKey(it) }.toSet()
        lastClaimNotice.keys.retainAll(keys)
        val notified = HashSet<UUID>()
        for (item in pending.toList()) {
            val uuid = runCatching { UUID.fromString(item.uuid) }.getOrNull() ?: continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (!notified.add(uuid)) continue
            val mine = pending.filter { it.uuid == item.uuid }
            val last = mine.mapNotNull { lastClaimNotice[claimKey(it)] }.maxOrNull()
            val due = last == null || (interval > 0L && now - last >= interval)
            if (!due) continue
            sendClaimNotice(player)
            mine.forEach { lastClaimNotice[claimKey(it)] = now }
        }
    }
}
