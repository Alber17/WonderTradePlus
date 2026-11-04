package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
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
 * el Top 3 y los ganadores reciben las recompensas configuradas (comandos de consola). Guarda su estado en
 * top-donations.json. Todo se configura en la sección `topDonations`.
 */
object WonderTradeTopDonations {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var file: Path? = null

    private class Entry(var name: String, var points: Int, var donations: Int, var reachedAt: Long)
    private class Pending(val uuid: String, val name: String, val rank: Int, val period: String, val points: Int)

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
                    data.get("period").asString, data.get("points")?.asInt ?: 0
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
                        addProperty("period", p.period); addProperty("points", p.points)
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
            lines += WonderTradeService.format(
                ConfigManager.string("messages.topDonationsCurrentHeader", "&d&lTop Donaciones de {month} &7(termina el {end}):"),
                mapOf("month" to monthName(period), "end" to "${period.atEndOfMonth().dayOfMonth}/${period.monthValue}")
            )
            val list = placed(key)
            if (list.isEmpty()) lines += ConfigManager.string("messages.topDonationsEmpty", "&7Todavía nadie ha donado este mes. ¡Usa &e/wt donar&7!")
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
        return WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n"))
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
        for (key in periods.keys.toList()) {
            if (key in closed) continue
            val period = runCatching { YearMonth.parse(key) }.getOrNull() ?: continue
            val lastDay = period.atEndOfMonth()
            val due = today.isAfter(lastDay) || (today == lastDay && hour >= announceHour)
            if (due) runCatching { finish(server, key, period) }.onFailure { WonderTradePlus.LOGGER.error("Falló el cierre del Top Donaciones.", it) }
        }
        runCatching { deliverPending(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló la entrega de recompensas del Top Donaciones.", it) }
    }

    private fun finish(server: MinecraftServer, key: String, period: YearMonth) {
        val winners = placed(key).filter { it.rank <= 3 }
        closed.add(key)
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
                pending.add(Pending(item.uuid, item.entry.name, item.rank, key, item.entry.points))
            }
            lines += ConfigManager.string("messages.topDonationsFooter", "&7¡Gracias a todos por donar! Los premios se entregan al ganador.")
        }
        save()
        server.playerList.broadcastSystemMessage(
            WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n")), false
        )
        winners.firstOrNull()?.let { runCatching { WonderTradeAnimations.playAll(server, "topDonations", "★ Top Donaciones: ${it.entry.name} ★") } }
        runCatching { WonderTradeDiscord.topDonations(lines.map { it.replace(Regex("&."), "") }) }
        WonderTradePlus.LOGGER.info("Top Donaciones {} cerrado con {} ganadores.", key, winners.size)
    }

    private fun rewardCommands(rank: Int): List<String> =
        ConfigManager.strings("topDonations.rewards." + when (rank) { 1 -> "first"; 2 -> "second"; else -> "third" })

    /** Entrega los premios a los ganadores conectados (los que no lo estén lo recibirán al entrar). */
    private fun deliverPending(server: MinecraftServer) {
        if (pending.isEmpty()) return
        var changed = false
        for (item in pending.toList()) {
            val player = runCatching { server.playerList.getPlayer(UUID.fromString(item.uuid)) }.getOrNull() ?: continue
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
            WonderTradeService.send(player, "topDonationsReward", mapOf("rank" to item.rank, "points" to item.points))
            pending.remove(item)
            changed = true
        }
        if (changed) save()
    }
}
