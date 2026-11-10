package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.storage.PokemonStore
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

object WonderTradeService {
    private data class PendingTrade(val slot: Int, val pokemonId: UUID, val expiresAt: Long)
    private val pending = mutableMapOf<UUID, PendingTrade>()
    private lateinit var pool: PoolStore
    private lateinit var playerData: PlayerDataStore
    private lateinit var history: HistoryStore
    private lateinit var donationHistory: DonationHistoryStore
    private var tickCounter = 0
    private var secondCounter = 0L
    private var lastHistoryCleanupAt = 0L
    private var minecraftServer: MinecraftServer? = null

    fun initialize(server: MinecraftServer) {
        minecraftServer = server
        pending.clear()
        ConfigManager.load()
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        pool = PoolStore(directory.resolve("pool.json"))
        playerData = PlayerDataStore(directory.resolve("player-data.json"))
        history = HistoryStore(directory.resolve("history.json"))
        donationHistory = DonationHistoryStore(directory.resolve("donation-history.json"))
        WonderTradeEvents.load(directory)
        WonderTradeSchedule.load(directory)
        WonderTradeTopDonations.load(directory)
        WonderTradePenalties.load(directory)
        WonderTradeSeasons.load(directory)
        WonderTradeMegaHunt.load(directory)
        pool.load()
        raiseGeneratedSpecialLevels()
        trimAndSave()
        playerData.load()
        history.load()
        donationHistory.load()
        syncHistoryIdentities()
        val historyDays = ConfigManager.int("history.autoDeleteAfterDays", 30)
        val historyRemoved = history.pruneOlderThanDays(historyDays)
        if (historyRemoved > 0) WonderTradePlus.LOGGER.info("Historial: {} entradas antiguas eliminadas automáticamente.", historyRemoved)
        val donationsRemoved = donationHistory.pruneOlderThanDays(historyDays)
        if (donationsRemoved > 0) WonderTradePlus.LOGGER.info("Historial de donaciones: {} entradas antiguas eliminadas automáticamente.", donationsRemoved)
        WonderTradePlus.LOGGER.info("WonderTrade Plus {} preparado.", WonderTradePlus.MOD_VERSION)
    }

    /** Cambia el idioma del servidor (general.language) y recarga los textos. */
    fun changeLanguage(code: String) {
        ConfigManager.setString("general.language", code)
        ConfigManager.load()
    }

    fun reload() {
        ConfigManager.load()
        entryCategoryCache.clear()
        WonderTradeEvents.load()
        WonderTradeSchedule.load()
        WonderTradeTopDonations.load()
        WonderTradePenalties.load()
        pool.load()
        raiseGeneratedSpecialLevels()
        trimAndSave()
        playerData.load()
        history.load()
        donationHistory.load()
        syncHistoryIdentities()
        val historyDays = ConfigManager.int("history.autoDeleteAfterDays", 30)
        val historyRemoved = history.pruneOlderThanDays(historyDays)
        if (historyRemoved > 0) WonderTradePlus.LOGGER.info("Historial: {} entradas antiguas eliminadas al recargar.", historyRemoved)
        val donationsRemoved = donationHistory.pruneOlderThanDays(historyDays)
        if (donationsRemoved > 0) WonderTradePlus.LOGGER.info("Historial de donaciones: {} entradas antiguas eliminadas al recargar.", donationsRemoved)
        pending.clear()
    }

    // ------------------------------------------------------------ interruptor global (/wt on | off)

    fun isEnabled(): Boolean = ConfigManager.bool("general.enabled", true)

    fun setEnabled(value: Boolean) = ConfigManager.setBool("general.enabled", value)

    /** Devuelve false (y avisa al jugador) si Wonder Trade está desactivado por un administrador. */
    fun ensureEnabled(player: ServerPlayer): Boolean {
        if (isEnabled()) return true
        send(player, "tradeDisabled")
        return false
    }

    // ------------------------------------------------------------ idioma por jugador

    fun storedLanguage(uuid: UUID): String? = playerData.language(uuid)

    /** Cambia el idioma de Wonder Trade de este jugador (null = automático, según su cliente). */
    fun setPlayerLanguage(player: ServerPlayer, language: String?) {
        playerData.setLanguage(player.uuid, language)
        playerData.save()
        Lang.bindPlayer(player)
    }

    /** Envía a cada jugador (y a la consola) un texto redactado en su propio idioma. */
    fun broadcastLocalized(server: MinecraftServer, build: () -> Component) {
        Lang.withPlayer(null) { runCatching { server.sendSystemMessage(build()) } }
        server.playerList.players.forEach { player -> Lang.withPlayer(player) { player.sendSystemMessage(build()) } }
    }

    /** Nombre del donante tal como se muestra: el «Servidor» del pool se traduce. */
    fun donorLabel(name: String): String = if (name.equals("Servidor", ignoreCase = true)) Lang.tr("Servidor") else name

    // ------------------------------------------------------------ modo infinito (pruebas de admin)

    private val infinite = HashSet<UUID>()

    fun isInfinite(player: ServerPlayer): Boolean = player.uuid in infinite

    /** Activa o desactiva el modo infinito del admin: sin cooldown de /wt ni de /wt donar, ni límites ni repeticiones. */
    fun setInfinite(player: ServerPlayer, value: Boolean) {
        if (value) infinite.add(player.uuid) else infinite.remove(player.uuid)
    }

    /** false (y avisa) si el jugador tiene una penalización vigente: no puede intercambiar ni donar. */
    fun ensureNotPenalized(player: ServerPlayer): Boolean {
        val penalty = WonderTradePenalties.active(player.uuid) ?: return true
        sendPenalty(player, "penalized", penalty)
        return false
    }

    /** Wonder Trade activo y jugador sin penalización. */
    private fun canTrade(player: ServerPlayer): Boolean = ensureEnabled(player) && ensureNotPenalized(player)

    fun request(player: ServerPlayer, slotNumber: Int) {
        if (!canTrade(player)) return
        val pokemon = getPartyPokemon(player, slotNumber) ?: run {
            send(player, "emptySlot")
            return
        }
        WonderTradeRules.validateOffer(pokemon)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return
        }
        checkLimits(player)?.let { return }

        if (!ConfigManager.bool("general.requireConfirmation", true)) {
            execute(player, slotNumber, pokemon.uuid)
            return
        }
        val expiry = System.currentTimeMillis() + ConfigManager.int("general.confirmationTimeoutSeconds", 45).coerceAtLeast(1) * 1000L
        pending[player.uuid] = PendingTrade(slotNumber, pokemon.uuid, expiry)
        send(player, "confirmation", mapOf(
            "sent" to pokemon.getDisplayName().string,
            "seconds" to ConfigManager.int("general.confirmationTimeoutSeconds", 45).coerceAtLeast(1)
        ))
    }

    fun confirm(player: ServerPlayer) {
        if (!canTrade(player)) return
        val request = pending.remove(player.uuid)
        if (request == null) {
            send(player, "nothingToConfirm")
            return
        }
        if (System.currentTimeMillis() > request.expiresAt) {
            send(player, "confirmationExpired")
            return
        }
        execute(player, request.slot, request.pokemonId)
    }

    fun tradeNow(player: ServerPlayer, slotNumber: Int) {
        if (!canTrade(player)) return
        val pokemon = getPartyPokemon(player, slotNumber) ?: run {
            send(player, "emptySlot")
            return
        }
        pending.remove(player.uuid)
        execute(player, slotNumber, pokemon.uuid)
    }

    fun cancel(player: ServerPlayer) {
        pending.remove(player.uuid)
        send(player, "cancelled")
    }

    fun helpComponent(player: ServerPlayer? = null): Component {
        // Orden pensado para jugadores: abrir, intercambiar, confirmar/cancelar y después consultas.
        val lines = mutableListOf(
            ConfigManager.string("messages.help", "&d&lWonder Trade &7- Comandos disponibles:"),
            ConfigManager.string("messages.helpMenuCommand", "&e/wt &7Abrir el menú principal"),
            ConfigManager.string("messages.helpSlotCommand", "&e/wt <slot> &7Solicitar un intercambio (1-6)"),
            ConfigManager.string("messages.helpSlotConfirmCommand", "&e/wt <slot> confirm &7Intercambiar ese slot directamente"),
            ConfigManager.string("messages.helpConfirmCommand", "&e/wt confirm &7Confirmar un intercambio pendiente"),
            ConfigManager.string("messages.helpCancelCommand", "&e/wt cancel &7Cancelar un intercambio pendiente"),
            ConfigManager.string("messages.helpDonateCommand", "&e/wt donar &7Donar un Pokémon a la pool"),
            ConfigManager.string("messages.helpStatsCommand", "&e/wt stats [jugador] &7Consultar estadísticas"),
            ConfigManager.string("messages.helpTopCommand", "&e/wt top &7Abrir el menú de rankings"),
            ConfigManager.string("messages.helpTopDonationsCommand", "&e/wt donaciones &7Ver el Top Donaciones del mes"),
            ConfigManager.string("messages.helpRewardsCommand", "&e/wt recompensas &7Reclamar tus recompensas del Top Donaciones"),
            ConfigManager.string("messages.helpEventsCommand", "&e/wt eventos &7Ver los eventos activos"),
            ConfigManager.string("messages.helpSpecialsCommand", "&e/wt especiales &7Ver qué Pokémon son míticos, legendarios, Ultraentes y Paradoja"),
            ConfigManager.string("messages.helpRankCommand", "&e/wt rango &7Ver tu rango y sus ventajas"),
            ConfigManager.string("messages.helpRanksCommand", "&e/wt rangos &7Ver todos los rangos y sus ventajas"),
            ConfigManager.string("messages.helpSoundsCommand", "&e/wt sonidos &7Configurar tus sonidos"),
            ConfigManager.string("messages.helpLanguageCommand", "&e/wt idioma [código] &7Elegir tu idioma de Wonder Trade"),
            ConfigManager.string("messages.helpHistoryCommand", "&e/wt historial &7Ver tus últimos intercambios")
        )
        if (player != null && (WonderTradeGui.canViewPool(player) || player.hasPermissions(adminLevel()))) {
            lines += ConfigManager.string("messages.helpPoolCommand", "&e/wt pool &7Ver la pool")
            lines += ConfigManager.string("messages.helpPoolSearchCommand", "&e/wt pool search <texto> &7Buscar en la pool por nombre o especie")
        }
        lines += ConfigManager.string("messages.helpHelpCommand", "&e/wt help &7Mostrar esta ayuda")
        if (player?.hasPermissions(adminLevel()) == true) {
            lines += ConfigManager.string("messages.helpAdminHeader", "&cComandos de administración:")
            lines += ConfigManager.string("messages.helpAdminPanelCommand", "&e/wt admin &7Abrir el panel de administración")
            lines += ConfigManager.string("messages.helpAdminFillCommand", "&e/wt admin fill <cantidad> [probabilidad] [nivelMin nivelMax] &7Rellenar la pool")
            lines += ConfigManager.string("messages.helpAdminAddSpeciesCommand", "&e/wt admin addspecies <especie> [forma] [shiny] &7Añadir una especie")
            lines += ConfigManager.string("messages.helpAdminAddCommand", "&e/wt admin add <slot> &7Añadir una copia desde tu equipo")
            lines += ConfigManager.string("messages.helpAdminRemoveCommand", "&e/wt admin remove &7Eliminar Pokémon de la pool")
            lines += ConfigManager.string("messages.helpAdminRemovePlayerCommand", "&e/wt admin removeplayer <jugador> &7Eliminar sus aportaciones")
            lines += ConfigManager.string("messages.helpAdminRemoveGeneratedCommand", "&e/wt admin removegenerated &7Eliminar solo Pokémon generados por el servidor")
            lines += ConfigManager.string("messages.helpAdminRemovePlayersCommand", "&e/wt admin removeplayers &7Eliminar solo Pokémon aportados por jugadores")
            lines += ConfigManager.string("messages.helpAdminHistoryCommand", "&e/wt admin history [jugador] &7Consultar historial")
            lines += ConfigManager.string("messages.helpAdminResetStatsCommand", "&e/wt admin resetstats <jugador|all> &7Reiniciar estadísticas")
            lines += ConfigManager.string("messages.helpAdminEventsCommand", "&e/wt admin eventos &7Ver eventos activos y programados")
            lines += ConfigManager.string("messages.helpAdminEventCreateCommand", "&e/wt admin eventos crear <arriesgar|recibir> <categorías> <duración> [valor] [espera] &7Crear un evento (varias categorías con +)")
            lines += ConfigManager.string("messages.helpAdminEventStopCommand", "&e/wt admin eventos parar <id> &7Finalizar un evento")
            lines += ConfigManager.string("messages.helpAdminScheduleCommand", "&e/wt admin eventos programar <tipo> <categorías> <duración> <cuándo> &7Programar un evento (también desde Gestionar eventos)")
            lines += ConfigManager.string("messages.helpAdminPenalizeCommand", "&e/wt admin penalizar <jugador> [duración|permanente] &7Penalizar a un jugador (también desde el panel)")
            lines += ConfigManager.string("messages.helpAdminPardonCommand", "&e/wt admin perdonar <jugador> &7Retirar una penalización; &e/wt admin penalizaciones &7lista las vigentes")
            lines += ConfigManager.string("messages.helpAdminStatusCommand", "&e/wt admin estado &7Diagnóstico: versión, pool, copias, permisos y archivos")
            lines += ConfigManager.string("messages.helpAdminPauseCommand", "&e/wt admin eventos pausar|reanudar <id> &7Pausar o reanudar el tiempo de un evento")
            lines += ConfigManager.string("messages.helpAdminEventHistoryCommand", "&e/wt admin eventos historial &7Ver eventos terminados con sus WT")
            lines += ConfigManager.string("messages.helpAdminUnscheduleCommand", "&e/wt admin eventos desprogramar <id> &7Cancelar un evento programado")
            lines += ConfigManager.string("messages.helpAdminToggleCommand", "&e/wt on | off &7Activar o desactivar Wonder Trade")
            lines += ConfigManager.string("messages.helpAdminReloadCommand", "&e/wt reload &7Recargar configuración y datos")
            lines += ConfigManager.string("messages.helpAdminMaintenanceCommand", "&e/wt admin maintenance &7Ejecutar mantenimiento")
            lines += ConfigManager.string("messages.helpAdminBackupCommand", "&e/wt admin backup &7Crear una copia de seguridad")
            lines += ConfigManager.string("messages.helpAdminDiscordCommand", "&e/wt admin discord test &7Probar webhook de Discord")
            lines += ConfigManager.string("messages.helpAdminClearCommand", "&e/wt admin clear &7Vaciar la pool")
        }
        return colour(WonderTradeService.prefix() + lines.joinToString("\n"))
    }

    fun poolSummaryComponent(): Component {
        val entries = pool.all()
        val summary = format(
            ConfigManager.string("messages.poolInfo", "&7Pool de Wonder Trade: &e{size} &7Pokémon."),
            mapOf(
                "size" to entries.size,
                "shiny" to entries.count { it.shiny },
                "special" to entries.count { it.special }
            )
        )
        return colour(WonderTradeService.prefix() + summary)
    }

    fun configuredMessage(key: String, fallback: String): Component =
        colour(WonderTradeService.prefix() + ConfigManager.string("messages.$key", fallback))

    fun soundPreference(player: ServerPlayer, key: String): Boolean = playerData.soundEnabled(player.uuid, key)

    fun playerSoundsEnabled(player: ServerPlayer, key: String): Boolean =
        playerData.soundEnabled(player.uuid, "all") && playerData.soundEnabled(player.uuid, key)

    fun toggleSoundPreference(player: ServerPlayer, key: String): Boolean {
        val enabled = !playerData.soundEnabled(player.uuid, key)
        playerData.setSoundEnabled(player.uuid, key, enabled)
        playerData.save()
        return enabled
    }

    fun poolSize(): Int = pool.size

    fun isPoolFull(): Boolean = pool.size >= ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)

    fun poolRemainingCapacity(): Int = (ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1) - pool.size).coerceAtLeast(0)

    private fun playerDonationsEnabled(): Boolean =
        ConfigManager.bool("pool.playerDonationsEnabled", true) && ConfigManager.bool("pool.enabled", true)

    /** Comprueba la disponibilidad al abrir el menú y de nuevo al confirmar una donación. */
    fun canOpenDonation(player: ServerPlayer): Boolean {
        if (!canTrade(player)) return false
        if (!playerDonationsEnabled()) {
            send(player, "donationDisabled")
            return false
        }
        if (isPoolFull()) {
            send(player, "donationPoolFull", mapOf(
                "size" to pool.size,
                "maximum" to ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)
            ))
            return false
        }
        // Límite de donaciones: por mes natural (se reinicia el día 1) o por ventana móvil de 7 días.
        if (isInfinite(player)) return true
        if (ConfigManager.string("donation.limitMode", "monthly").equals("weekly", ignoreCase = true)) {
            val weeklyLimit = ConfigManager.int("donation.weeklyLimit", 5).coerceAtLeast(0)
            if (weeklyLimit > 0 && playerData.donationsInLastWeek(player.uuid) >= weeklyLimit) {
                send(player, "donationWeeklyLimit", mapOf("limit" to weeklyLimit))
                return false
            }
        } else {
            val monthlyLimit = ConfigManager.int("donation.monthlyLimit", 20).coerceAtLeast(0)
            if (monthlyLimit > 0 && playerData.donationsThisMonth(player.uuid, WonderTradeSchedule.zone()) >= monthlyLimit) {
                send(player, "donationMonthlyLimit", mapOf("limit" to monthlyLimit))
                return false
            }
        }
        return true
    }

    /** No se puede donar dos veces la misma especie (ni el mismo ejemplar) dentro del periodo configurado. */
    private fun donationRepeated(player: ServerPlayer, pokemon: Pokemon): Boolean {
        val period = ConfigManager.string("donation.sameSpecies.period", "month").lowercase()
        if (period == "off" || isInfinite(player)) return false
        val now = System.currentTimeMillis()
        val zone = WonderTradeSchedule.zone()
        fun within(moment: Long): Boolean = if (period == "week") now - moment < 7L * 24L * 3_600_000L
        else java.time.YearMonth.from(java.time.Instant.ofEpochMilli(moment).atZone(zone)) == java.time.YearMonth.now(zone)
        val species = pokemon.species.resourceIdentifier.path.lowercase()
        playerData.lastDonatedSpecies(player.uuid, species)?.takeIf { within(it) }?.let {
            send(player, "donationSameSpecies", mapOf("period" to if (period == "week") Lang.tr("esta semana") else Lang.tr("este mes")))
            return true
        }
        if (ConfigManager.bool("donation.blockSameIndividual", true)) {
            playerData.lastDonatedIndividual(player.uuid, pokemon.uuid.toString())?.takeIf { within(it) }?.let {
                send(player, "donationSameIndividual", mapOf("period" to if (period == "week") Lang.tr("esta semana") else Lang.tr("este mes")))
                return true
            }
        }
        return false
    }

    fun clearPool() {
        backupNow()
        pool.clear()
        pool.save()
    }

    private fun execute(player: ServerPlayer, slotNumber: Int, expectedPokemonId: UUID) {
        if (slotNumber !in 1..6) {
            send(player, "invalidSlot")
            return
        }
        val party = Cobblemon.storage.getParty(player)
        val offered = party.get(slotNumber - 1)
        if (offered == null) {
            send(player, "emptySlot")
            return
        }
        if (offered.uuid != expectedPokemonId) {
            send(player, "confirmationExpired")
            return
        }
        performTrade(player, party, offered)
    }

    /** Usado por la GUI: busca el Pokémon por UUID en el equipo o en el PC y lo intercambia. */
    fun tradeFromStore(player: ServerPlayer, fromPc: Boolean, pokemonId: UUID) {
        val store: PokemonStore<*> = if (fromPc) Cobblemon.storage.getPC(player) else Cobblemon.storage.getParty(player)
        val offered = store.firstOrNull { it.uuid == pokemonId }
        if (offered == null) {
            send(player, "confirmationExpired")
            return
        }
        pending.remove(player.uuid)
        performTrade(player, store, offered)
    }

    /** Comprueba que la pool admita donaciones y que el Pokémon se pueda aportar. */
    fun precheckDonation(player: ServerPlayer, pokemon: Pokemon): Boolean {
        if (!canOpenDonation(player)) return false
        if (!ConfigManager.bool("general.allowInBattle", false) && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null) {
            send(player, "donationInBattle")
            return false
        }
        WonderTradeRules.validateOffer(pokemon)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return false
        }
        if (repeatedSpecies(player, pokemon)) return false
        if (donationRepeated(player, pokemon)) return false
        val speciesPath = pokemon.species.resourceIdentifier.path.lowercase()
        val blocked = ConfigManager.strings("donation.speciesBlacklist").map { it.substringAfter(':').lowercase() }
        if (speciesPath in blocked) {
            send(player, "donationSpeciesBlocked")
            return false
        }
        return true
    }

    /** Dona el Pokémon seleccionado desde el equipo o PC; no entrega nada a cambio ni consume cooldown. */
    fun donateFromStore(player: ServerPlayer, fromPc: Boolean, pokemonId: UUID) {
        if (!canTrade(player)) return
        val store: PokemonStore<*> = if (fromPc) Cobblemon.storage.getPC(player) else Cobblemon.storage.getParty(player)
        val offered = store.firstOrNull { it.uuid == pokemonId }
        if (offered == null) {
            send(player, "confirmationExpired")
            return
        }
        pending.remove(player.uuid)
        performDonation(player, store, offered)
    }

    /** Comprueba reglas, cooldown y límite diario antes de abrir la confirmación de la GUI. */
    fun precheck(player: ServerPlayer, pokemon: Pokemon): Boolean {
        if (!canTrade(player)) return false
        WonderTradeRules.validateOffer(pokemon)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return false
        }
        if (checkLimits(player) != null) return false
        return true
    }

    fun poolEntries(): List<PoolEntry> = pool.all()

    /** El historial se identifica por UUID: asigna UUID a las entradas antiguas y actualiza los nicks cambiados. */
    private fun syncHistoryIdentities() {
        runCatching {
            val known = playerData.knownPlayers()
            if (history.syncIdentities(known)) history.save()
            if (donationHistory.syncIdentities(known)) donationHistory.save()
        }
            .onFailure { WonderTradePlus.LOGGER.warn("No se pudo actualizar la identidad del historial: {}", it.message) }
    }

    // ------------------------------------------------------------ administración

    private fun recordHistory(player: ServerPlayer, sent: Pokemon, received: Pokemon, fromPool: Boolean, now: Long) {
        if (!ConfigManager.bool("history.enabled", true)) return
        runCatching {
            history.add(
                HistoryEntry(
                    time = now,
                    player = player.gameProfile.name,
                    sentSpecies = sent.species.resourceIdentifier.path,
                    sentName = WonderTradeDiscord.plainName(sent),
                    sentLevel = sent.level,
                    sentShiny = sent.shiny,
                    receivedSpecies = received.species.resourceIdentifier.path,
                    receivedName = WonderTradeDiscord.plainName(received),
                    receivedLevel = received.level,
                    receivedShiny = received.shiny,
                    fromPool = fromPool,
                    uuid = player.uuid.toString(),
                    sentForm = formKey(sent),
                    receivedForm = formKey(received)
                ),
                ConfigManager.int("history.maxEntries", 1000).coerceAtLeast(1)
            )
            history.save()
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar el historial de intercambios.", it) }
    }

    private fun recordDonationHistory(player: ServerPlayer, pokemon: Pokemon, now: Long) {
        if (!ConfigManager.bool("history.enabled", true)) return
        runCatching {
            donationHistory.add(
                DonationEntry(
                    time = now,
                    player = player.gameProfile.name,
                    uuid = player.uuid.toString(),
                    species = pokemon.species.resourceIdentifier.path,
                    name = WonderTradeDiscord.plainName(pokemon),
                    level = pokemon.level,
                    shiny = pokemon.shiny,
                    form = formKey(pokemon)
                ),
                ConfigManager.int("history.maxEntries", 1000).coerceAtLeast(1)
            )
            donationHistory.save()
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar el historial de donaciones.", it) }
    }

    fun donationDonors(): List<Triple<String, String, Pair<Int, Long>>> = donationHistory.donors()

    fun donationEntries(uuid: String?, name: String): List<DonationEntry> = donationHistory.recent(name, uuid)

    fun historyEntries(player: String? = null): List<HistoryEntry> {
        val uuid = player?.let { name -> playerData.knownPlayers().firstOrNull { it.second.equals(name, ignoreCase = true) }?.first?.toString() }
        return history.recent(player, uuid)
    }

    /** Historial propio: solo se puede pedir el del jugador que lo consulta, nunca el de otro. */
    fun ownHistory(player: ServerPlayer): List<HistoryEntry> = history.recent(player.gameProfile.name, player.uuid.toString())

    /** Diagnóstico para admins (/wt admin estado): versión, datos cargados, copias de seguridad, permisos, etc. */
    fun statusComponent(server: MinecraftServer): Component {
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        fun fileSize(name: String): String =
            runCatching { Files.size(directory.resolve(name)) / 1024L }.getOrNull()?.let { "$it KB" } ?: "-"
        val backups = runCatching {
            Files.list(directory.resolve("backups")).use { stream -> stream.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList() }
        }.getOrDefault(emptyList())
        val maximum = ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)
        val fromPlayers = pool.all().count { !it.donorName.equals("Servidor", ignoreCase = true) }
        val on = Lang.tr("&aSí")
        val off = Lang.tr("&cNo")
        val discordReady = ConfigManager.bool("discord.enabled", false) && ConfigManager.string("discord.webhookUrl", "").isNotBlank()
        val lines = listOf(
            Lang.tr("&8━━ &dEstado de WonderTrade Plus &8━━"),
            Lang.t("&7Versión: &f{0} &8· &7Cobblemon: &f{1} especies", WonderTradePlus.MOD_VERSION, PokemonSpecies.species.count()),
            Lang.t("&7Wonder Trade activado: {0} &8· &7Jugadores conectados: &f{1}", if (isEnabled()) on else off, server.playerList.playerCount),
            Lang.t("&7Pool: &f{0}&7/&f{1} &8(jugadores: {2}, servidor: {3})", pool.size, maximum, fromPlayers, pool.size - fromPlayers),
            Lang.t("&7Jugadores guardados: &f{0} &8· &7Historial: &f{1} entradas", playerData.names().size, history.size),
            Lang.t("&7Penalizados: &f{0} &8· &7Eventos activos: &f{1} &8· &7Programados: &f{2}", WonderTradePenalties.list().size, WonderTradeEvents.active().size, WonderTradeSchedule.all().size),
            Lang.t("&7Top Donaciones: {0}", if (WonderTradeTopDonations.enabled()) on else off),
            Lang.t("&7Idioma por defecto: &f{0} &8· &7Idiomas: &f{1}", Lang.current(), Lang.available().joinToString(", ") { it.first }),
            Lang.t("&7Permisos: &f{0}", WonderTradeRanks.backendName()),
            Lang.t("&7Discord: {0}", if (discordReady) on else off),
            Lang.t("&7Copias de seguridad: &f{0} &8(última: {1})", backups.size, backups.lastOrNull() ?: "-"),
            Lang.t("&7Archivos: &fpool {0} &8· &fplayer-data {1} &8· &fhistory {2}", fileSize("pool.json"), fileSize("player-data.json"), fileSize("history.json"))
        )
        return colour(WonderTradeService.prefix() + lines.joinToString("\n"))
    }
    fun historyPlayers(): List<String> = history.players()

    /** Jugadores del historial: UUID, nick, número de intercambios y fecha del último. */
    fun historyTraders(): List<Triple<String, String, Pair<Int, Long>>> = history.traders()
    fun poolDonors(): List<String> = pool.all().map { it.donorName }.distinct()

    /** Donantes distintos de la pool con su UUID (si la entrada lo guarda), para mostrar sus cabezas aunque estén desconectados. */
    fun poolDonorProfiles(): List<Pair<String, UUID?>> =
        pool.all().groupBy { it.donorName.lowercase() }.values.map { group ->
            group.first().donorName to group.firstNotNullOfOrNull { runCatching { UUID.fromString(it.donorUuid) }.getOrNull() }
        }

    /** Textura de skin guardada de un jugador (valor, firma). */
    fun donorSkin(uuid: UUID): Pair<String, String?>? = playerData.skin(uuid)

    /** Textura de skin del perfil de un jugador conectado, si su perfil la incluye. */
    fun profileSkin(player: ServerPlayer): Pair<String, String?>? =
        runCatching { player.gameProfile.properties.get("textures").firstOrNull() }.getOrNull()?.let { it.value() to it.signature() }

    /** true (y avisa) si el jugador intenta ofrecer la misma especie que ofreció la última vez. */
    private fun repeatedSpecies(player: ServerPlayer, pokemon: Pokemon): Boolean {
        if (!ConfigManager.bool("trade.blockRepeatSpecies", true) || isInfinite(player)) return false
        val species = pokemon.species.resourceIdentifier.path.lowercase()
        if (playerData.lastOfferedSpecies(player.uuid) != species) return false
        send(player, "repeatSpecies")
        return true
    }

    /** Guarda nombre y skin del jugador para poder dibujar su cabeza cuando no esté conectado. */
    fun rememberPlayer(player: ServerPlayer) {
        var changed = false
        playerData.setName(player.uuid, player.gameProfile.name)
        val uuid = player.uuid.toString()
        val name = player.gameProfile.name
        runCatching { if (history.rename(uuid, name)) history.save() }
        runCatching { if (donationHistory.rename(uuid, name)) donationHistory.save() }
        profileSkin(player)?.let { (value, signature) -> changed = playerData.setSkin(player.uuid, value, signature) }
        if (changed) playerData.save()
    }

    private var speciesCache: List<String>? = null

    /** Solo especies base. Las formas regionales se eligen después como opciones (alolan, galarian…). */
    fun speciesNames(): List<String> = speciesCache ?: PokemonSpecies.species
        .map { it.resourceIdentifier.path }
        .distinct()
        .sorted()
        .also { speciesCache = it }

    fun adminLevel(): Int = ConfigManager.int("admin.permissionLevel", 2).coerceIn(0, 4)

    fun info(text: String): Component = colour(WonderTradeService.prefix() + text)

    private fun addAdminEntry(pokemon: Pokemon, registryAccess: net.minecraft.core.RegistryAccess) {
        WonderTradeRules.enforceGeneratedSpecialLevel(pokemon)
        pool.add(PoolEntry.fromPokemon(pokemon, registryAccess, "Servidor"))
    }

    // ------------------------------------------------------------ eventos de temporada (ver WonderTradeSeasons)

    fun poolSnapshot(): List<PoolEntry> = pool.snapshot()

    /** Sustituye la pool por [entries] y la guarda (la original ya está guardada aparte por WonderTradeSeasons). */
    fun poolReplace(entries: List<PoolEntry>) {
        pool.restore(entries)
        entryCategoryCache.clear()
        raiseGeneratedSpecialLevels()
        trimAndSave()
    }

    /** Sube al nivel 50 los especiales generados por el servidor. Las aportaciones de jugadores se quedan como están. */
    private fun raiseGeneratedSpecialLevels() {
        val registry = minecraftServer?.registryAccess() ?: return
        val raised = pool.raiseGeneratedSpecialLevels(registry)
        if (raised > 0) {
            WonderTradePlus.LOGGER.info(
                "Pool: {} legendarios, míticos, Ultraentes o Paradoja generados por el servidor pasan al nivel {}.",
                raised,
                WonderTradeRules.SPECIAL_MINIMUM_LEVEL
            )
        }
    }

    /**
     * Genera la pool temática de un evento de temporada: una vuelta por todas las especies que encajan (los legendarios,
     * míticos, Ultraentes y Paradoja solo una vez) y después se repiten las normales hasta llegar a `poolSize`.
     */
    fun seasonalGenerate(player: ServerPlayer, season: Season): List<PoolEntry> {
        val registryAccess = player.server.registryAccess()
        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val allowShiny = ConfigManager.bool("general.allowShinyPokemon", true)
        val maximum = ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)
        val target = minOf(season.poolSize, maximum)

        // Especies excluidas de la pool del evento (excludeSpecies).
        val excluded = season.excluded
        val candidates = PokemonSpecies.species.filter { species ->
            val id = species.resourceIdentifier.path.lowercase()
            id !in excluded &&
            (id in season.extraSpecies || runCatching {
                species.primaryType.name.lowercase() in season.types || ((species.secondaryType?.name?.lowercase() ?: "") in season.types)
            }.getOrDefault(false))
        }.shuffled()

        fun create(speciesName: String, shiny: Boolean): Pokemon? = try {
            PokemonProperties().apply {
                this.species = speciesName
                this.level = Random.nextInt(low, high + 1)
                this.shiny = shiny
            }.create(player)
        } catch (exception: Exception) {
            null
        }

        val result = mutableListOf<PoolEntry>()
        val normals = mutableListOf<String>()
        fun accept(pokemon: Pokemon): Boolean =
            season.matches(pokemon) && WonderTradeRules.isSpeciesAllowed(pokemon.species.resourceIdentifier.toString()) &&
                WonderTradeRules.isVariantAllowed(pokemon) &&
                (ConfigManager.bool("general.allowSpecialPokemon", true) || !WonderTradeRules.isSpecialSpecies(pokemon))

        // Primera vuelta: una de cada especie.
        for (species in candidates) {
            if (result.size >= target) break
            val name = species.resourceIdentifier.path
            val shiny = allowShiny && Random.nextDouble() * 100.0 < season.shinyChance
            val pokemon = create(name, shiny) ?: continue
            if (!accept(pokemon)) continue
            val special = WonderTradeRules.isSpecialSpecies(pokemon)
            if (special && !season.includeSpecial) continue
            WonderTradeRules.enforceGeneratedSpecialLevel(pokemon)
            result += PoolEntry.fromPokemon(pokemon, registryAccess, "Servidor")
            if (!special) normals += name
        }
        // Siguientes vueltas: solo especies normales, con otro nivel y otro Pokémon cada vez.
        var guard = target * 4
        while (result.size < target && normals.isNotEmpty() && guard-- > 0) {
            val name = normals[Random.nextInt(normals.size)]
            val shiny = allowShiny && Random.nextDouble() * 100.0 < season.shinyChance
            val pokemon = create(name, shiny) ?: continue
            if (accept(pokemon)) {
                WonderTradeRules.enforceGeneratedSpecialLevel(pokemon)
                result += PoolEntry.fromPokemon(pokemon, registryAccess, "Servidor")
            }
        }
        return result
    }

    private fun trimAndSave() {
        pool.trim(
            ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1),
            ConfigManager.bool("pool.trimOldestFirst", true)
        )
        pool.save()
    }

    /** Copia el Pokémon del slot del admin al pool (el admin conserva el suyo). Devuelve su nombre o null. */
    fun adminAddFromParty(player: ServerPlayer, slot: Int): String? {
        if (slot !in 1..6) return null
        val pokemon = Cobblemon.storage.getParty(player).get(slot - 1) ?: return null
        val registryAccess = player.server.registryAccess()
        val copy = try {
            pokemon.clone(newUUID = true, registryAccess = registryAccess)
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo clonar el Pokémon para el pool.", exception)
            return null
        }
        addAdminEntry(copy, registryAccess)
        trimAndSave()
        return pokemon.getDisplayName().string
    }

    data class AddedSpeciesResult(val name: String, val level: Int)

    /**
     * Crea una especie o una forma regional usando exactamente el atributo de /pokegive
     * (por ejemplo, "vulpix alolan shiny").
     */
    fun adminAddSpecies(
        player: ServerPlayer,
        speciesInput: String,
        shiny: Boolean,
        regionalFormInput: String? = null
    ): AddedSpeciesResult? {
        val cleaned = speciesInput.trim().lowercase()
        if (cleaned.isBlank()) return null

        val species = PokemonSpecies.species.firstOrNull { candidate ->
            val identifier = candidate.resourceIdentifier.toString().lowercase()
            val path = candidate.resourceIdentifier.path.lowercase()
            cleaned == identifier || (':' !in cleaned && cleaned == path)
        } ?: return null

        val regionalToken = regionalFormInput?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val regionalOptions = setOf("alolan", "galarian", "hisuian", "paldean")
        if (regionalToken != null) {
            if (regionalToken !in regionalOptions) {
                throw IllegalArgumentException("Forma regional no reconocida: '$regionalToken'. Opciones: ${regionalOptions.joinToString()}.")
            }
            if (!ConfigManager.bool("admin.regionalForms.$regionalToken", true)) {
                throw IllegalArgumentException("La opción regional '$regionalToken' está desactivada en config.json.")
            }
        }

        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val randomLevel = Random.nextInt(low, high + 1)

        // El comando del jugador conserva la sintaxis de /pokegive (por ejemplo, "vulpix alolan").
        // Para no depender del parser de alias de propiedades en cada versión de Cobblemon,
        // la forma regional se resuelve directamente desde las FormData registradas para esa especie.
        val propertySpecies = if (species.resourceIdentifier.namespace == Cobblemon.MODID) {
            species.resourceIdentifier.path
        } else {
            species.resourceIdentifier.toString()
        }
        val propertyText = buildList {
            add(propertySpecies)
            regionalToken?.let(::add) // Primero intenta el mismo atributo que /pokegive ("alolan").
            add("level=$randomLevel")
            add("shiny=$shiny")
        }.joinToString(" ")
        val properties = try {
            PokemonProperties.parse(propertyText)
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudieron interpretar las propiedades de addspecies '{}'.", propertyText, exception)
            return null
        }
        val pokemon = runCatching { properties.create(player) }.getOrElse { exception ->
            WonderTradePlus.LOGGER.error("No se pudo crear el Pokémon con las propiedades '{}'.", propertyText, exception)
            return null
        }
        if (pokemon.species.resourceIdentifier != species.resourceIdentifier) {
            WonderTradePlus.LOGGER.error("addspecies creó una especie distinta. Solicitada: {}; obtenida: {}", species.resourceIdentifier, pokemon.species.resourceIdentifier)
            return null
        }

        if (regionalToken != null) {
            val acceptedFormIds = when (regionalToken) {
                "alolan" -> setOf("alolan", "alola")
                "galarian" -> setOf("galarian", "galar")
                "hisuian" -> setOf("hisuian", "hisui")
                "paldean" -> setOf("paldean", "paldea")
                else -> emptySet()
            }
            fun normalizeFormId(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

            // En Cobblemon con formas basadas en aspectos, el atributo /pokegive añade
            // directamente el aspecto (p. ej. "alolan"). En versiones con FormData
            // regional, usamos la forma registrada como respaldo sin cambiar la sintaxis del comando.
            val aspectIds = pokemon.aspects.map(::normalizeFormId).toSet()
            val aspectApplied = aspectIds.any { it in acceptedFormIds }

            if (!aspectApplied) {
                val regionalForm = species.forms.firstOrNull { form ->
                    normalizeFormId(form.name) in acceptedFormIds ||
                        normalizeFormId(form.formOnlyShowdownId()) in acceptedFormIds
                } ?: throw IllegalArgumentException(
                    "${species.resourceIdentifier.path} no tiene registrada la forma regional '$regionalToken' en Cobblemon. No se ha añadido ningún Pokémon."
                )

                // La asignación se realiza después de initialize(), para que no se pierda
                // durante la creación del Pokémon.
                pokemon.form = regionalForm
                pokemon.updateAspects()
            }

            val actualFormIds = buildSet {
                addAll(pokemon.aspects.map(::normalizeFormId))
                add(normalizeFormId(pokemon.form.name))
                add(normalizeFormId(pokemon.form.formOnlyShowdownId()))
            }
            if (actualFormIds.intersect(acceptedFormIds).isEmpty()) {
                throw IllegalArgumentException(
                    "No se pudo aplicar la forma '$regionalToken' a ${species.resourceIdentifier.path}. No se ha añadido el Pokémon."
                )
            }
        }

        addAdminEntry(pokemon, player.server.registryAccess())
        trimAndSave()
        return AddedSpeciesResult(pokemon.getDisplayName().string, pokemon.level)
    }

    data class FillResult(val added: Int, val special: Int, val chancePercent: Double)

    /** Probabilidad (en %) de que cada Pokémon generado sea especial, calculada según el tamaño de la pool. */
    fun autoSpecialChance(amount: Int): Double = (0.25 + amount * 0.005).coerceAtMost(2.0)

    private const val SPECIAL_ATTEMPT_FACTOR = 50

    /** Filtro de generación automática: respeta shiny/especial/alfa permitidos y las listas de especies. */
    private fun fillAllowed(candidate: Pokemon): Boolean {
        if (!ConfigManager.bool("general.allowShinyPokemon", true) && candidate.shiny) return false
        if (!ConfigManager.bool("general.allowSpecialPokemon", true) && WonderTradeRules.isSpecialSpecies(candidate)) return false
        if (!ConfigManager.bool("general.allowAlphaPokemon", true) && candidate.isAlpha) return false
        return WonderTradeRules.isSpeciesAllowed(candidate.species.resourceIdentifier.toString()) && WonderTradeRules.isVariantAllowed(candidate) &&
            WonderTradeSeasons.poolAllows(candidate)
    }

    private fun generateRandom(player: ServerPlayer, low: Int, high: Int, attempts: Int, shiny: Boolean?, accept: (Pokemon) -> Boolean): Pokemon? {
        repeat(attempts) {
            val candidate = try {
                PokemonProperties().apply {
                    this.species = "random"
                    this.level = Random.nextInt(low, high + 1)
                    this.shiny = shiny
                }.create(player)
            } catch (exception: Exception) {
                null
            }
            if (candidate != null && accept(candidate)) return candidate
        }
        return null
    }

    /**
     * Genera Pokémon aleatorios para el pool respetando las listas y permisos de la configuración.
     * Cada Pokémon tiene una probabilidad baja de ser especial (shiny, legendario, mítico, Ultraente o Paradoja);
     * los demás son siempre normales. Si no se indica probabilidad, se calcula sola con [autoSpecialChance].
     */
    fun adminFill(player: ServerPlayer, amount: Int, chancePercent: Double?, minimumLevel: Int, maximumLevel: Int): FillResult {
        val low = minimumLevel.coerceIn(1, 100)
        val high = maximumLevel.coerceIn(low, 100)
        val targetAmount = minOf(amount.coerceAtLeast(0), poolRemainingCapacity())
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val registryAccess = player.server.registryAccess()
        val allowShiny = ConfigManager.bool("general.allowShinyPokemon", true)
        val allowSpecial = ConfigManager.bool("general.allowSpecialPokemon", true)
        val allowAlpha = ConfigManager.bool("general.allowAlphaPokemon", true)
        val chance = (chancePercent ?: autoSpecialChance(targetAmount)).coerceIn(0.0, 100.0)

        fun allowed(candidate: Pokemon): Boolean = fillAllowed(candidate)

        var added = 0
        var special = 0
        repeat(targetAmount) {
            var pokemon: Pokemon? = null
            var isSpecialOne = false

            val wantsSpecial = (allowShiny || allowSpecial) && Random.nextDouble() * 100.0 < chance
            if (wantsSpecial) {
                // Un tercio de los especiales son shiny; el resto, legendarios/míticos/Ultraentes/Paradoja.
                val shinyOne = allowShiny && (!allowSpecial || Random.nextInt(3) == 0)
                // Los especiales de especie son poco frecuentes entre las especies aleatorias, así que se
                // buscan con muchos más intentos; antes se rendía y dejaba un Pokémon normal.
                val specialAttempts = if (shinyOne) attempts else attempts * SPECIAL_ATTEMPT_FACTOR
                pokemon = generateRandom(player, low, high, specialAttempts, if (shinyOne) true else null) { candidate ->
                    allowed(candidate) && (shinyOne || WonderTradeRules.isSpecialSpecies(candidate))
                }
                // Si aun así no apareció, se genera un shiny (siempre posible) para respetar la probabilidad.
                if (pokemon == null && allowShiny) {
                    pokemon = generateRandom(player, low, high, attempts, true) { allowed(it) }
                }
                isSpecialOne = pokemon != null
            }
            // Si se pidió especial, nunca se rellena con un normal en su lugar.
            if (pokemon == null && !wantsSpecial) {
                // Pokémon normal: se descartan los especiales para que la probabilidad sea la indicada.
                pokemon = generateRandom(player, low, high, attempts, false) { allowed(it) && !WonderTradeRules.isRare(it) }
            }
            if (pokemon != null) {
                addAdminEntry(pokemon, registryAccess)
                added++
                if (isSpecialOne) special++
            }
        }
        if (added > 0) trimAndSave()
        return FillResult(added, special, chance)
    }

    data class CategoryAddResult(
        val requested: Int,
        val added: Int,
        val failed: Int,
        val counts: Map<EventCategory, Int>,
        val insufficientSpace: Boolean = false,
        val availableSpace: Int = 0
    )

    /**
     * Añade Pokémon generados por el servidor de las categorías elegidas en el menú de administración.
     * La operación se rechaza completa antes de generar nada si la pool no tiene huecos para toda la cantidad.
     */
    fun adminAddRandomCategories(
        player: ServerPlayer,
        amount: Int,
        categories: List<EventCategory>,
        minimumLevel: Int,
        maximumLevel: Int
    ): CategoryAddResult {
        val available = poolRemainingCapacity()
        if (amount <= 0 || amount > available) {
            return CategoryAddResult(amount, 0, 0, emptyMap(), insufficientSpace = amount > available, availableSpace = available)
        }

        val chosen = normalizeCategories(categories)
        if (chosen.isEmpty()) return CategoryAddResult(amount, 0, amount, emptyMap(), availableSpace = available)

        val low = minimumLevel.coerceIn(1, 100)
        val high = maximumLevel.coerceIn(low, 100)
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val counts = linkedMapOf<EventCategory, Int>()
        var failed = 0

        repeat(amount) {
            val category = chosen.random()
            val pokemon = generateForCategory(player, category, low, high, attempts)
            if (pokemon == null) {
                failed++
            } else {
                // Los especiales generados por el servidor siempre respetan el nivel mínimo global.
                WonderTradeRules.enforceGeneratedSpecialLevel(pokemon)
                addAdminEntry(pokemon, player.server.registryAccess())
                counts[category] = (counts[category] ?: 0) + 1
            }
        }

        val added = counts.values.sum()
        if (added > 0) trimAndSave()
        return CategoryAddResult(amount, added, failed, counts, availableSpace = available)
    }

    fun removeFromPool(id: String): Boolean {
        val removed = pool.remove(id)
        if (removed) pool.save()
        return removed
    }

    /** Elimina únicamente entradas generadas por el servidor (sin UUID de donante). */
    fun removeGeneratedPoolEntries(): Int {
        val removed = pool.removeWhere { it.donorName.equals("Servidor", ignoreCase = true) }
        if (removed > 0) pool.save()
        return removed
    }

    /** Elimina únicamente entradas aportadas por jugadores (con UUID de donante). */
    fun removePlayerPoolEntries(): Int {
        val removed = pool.removeWhere { !it.donorName.equals("Servidor", ignoreCase = true) }
        if (removed > 0) pool.save()
        return removed
    }

    fun removeDonorEntries(donor: String): Int {
        val removed = pool.removeWhere { it.donorName.equals(donor, ignoreCase = true) }
        if (removed > 0) pool.save()
        return removed
    }

    private fun performDonation(player: ServerPlayer, store: PokemonStore<*>, offered: Pokemon) {
        if (!precheckDonation(player, offered)) return
        val now = System.currentTimeMillis()
        val previousPool = pool.snapshot()
        val previousPlayerData = playerData.snapshot()
        val registryAccess = player.server.registryAccess()
        val donation = try {
            offered.clone(newUUID = false, registryAccess = registryAccess)
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo clonar el Pokémon donado por {}.", player.gameProfile.name, exception)
            send(player, "generationFailed")
            return
        }
        if (ConfigManager.bool("pool.clampDonatedLevel", false)) {
            val minimum = ConfigManager.int("pool.donatedMinimumLevel", 1).coerceIn(1, 100)
            val maximum = ConfigManager.int("pool.donatedMaximumLevel", 100).coerceIn(minimum, 100)
            donation.level = donation.level.coerceIn(minimum, maximum)
        }

        try {
            // Revalidar capacidad justo antes de escribir evita aceptar donaciones cuando se llena la pool.
            if (!canOpenDonation(player)) return
            if (!store.remove(offered)) throw IllegalStateException("No se pudo retirar el Pokémon donado del equipo/PC")
            pool.add(PoolEntry.fromPokemon(donation, registryAccess, player.gameProfile.name, now, player.uuid.toString()))
            pool.save()
            rememberPlayer(player)
            playerData.setLastOfferedSpecies(player.uuid, offered.species.resourceIdentifier.path.lowercase())
            playerData.recordDonation(player.uuid, WonderTradeRules.isRare(donation), now)
            playerData.recordDonatedPokemon(player.uuid, offered.species.resourceIdentifier.path.lowercase(), offered.uuid.toString(), now)
            playerData.save()
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("Ha fallado la donación de Wonder Trade para {}. Se intentará revertirla.", player.gameProfile.name, exception)
            pool.restore(previousPool)
            playerData.restore(previousPlayerData)
            runCatching { if (store.none { it.uuid == offered.uuid }) store.add(offered) }
            runCatching { pool.save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo restaurar pool.json tras una donación fallida.", it) }
            runCatching { playerData.save() }.onFailure { WonderTradePlus.LOGGER.error("No se pudo restaurar player-data.json tras una donación fallida.", it) }
            send(player, "saveFailed")
            return
        }
        recordDonationHistory(player, donation, now)
        send(player, "donationSuccess", mapOf("pokemon" to donation.getDisplayName().string, "size" to pool.size))
        announceDonation(player, donation)
        WonderTradeDiscord.notifyDonation(player, donation)
        runCatching { WonderTradeTopDonations.record(player, donation) }.onFailure { WonderTradePlus.LOGGER.error("No se pudo registrar la donación en el Top Donaciones.", it) }
    }

    private fun performTrade(player: ServerPlayer, party: PokemonStore<*>, offered: Pokemon) {
        if (!canTrade(player)) return
        if (!ConfigManager.bool("general.allowInBattle", false) && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.sendSystemMessage(colour(Lang.t("{0} &cNo puedes intercambiar Pokémon durante un combate.", WonderTradeService.prefix())))
            return
        }
        WonderTradeRules.validateOffer(offered)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return
        }
        if (repeatedSpecies(player, offered)) return
        checkLimits(player)?.let { return }
        // Si hay un evento activo con la espera cumplida, este intercambio usa el evento y no gasta tu tiempo normal.
        val eventBypass = cooldownBypass(player)
        val eventUse = if (eventBypass) null else WonderTradeEvents.readyEvent(player)

        val now = System.currentTimeMillis()
        val previousPool = pool.snapshot()
        val previousPlayerData = playerData.snapshot()
        val registryAccess = player.server.registryAccess()
        val donation = try {
            offered.clone(newUUID = false, registryAccess = registryAccess)
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo clonar el Pokémon ofrecido por {}.", player.gameProfile.name, exception)
            send(player, "generationFailed")
            return
        }
        if (ConfigManager.bool("pool.clampDonatedLevel", false)) {
            val minimum = ConfigManager.int("pool.donatedMinimumLevel", 1).coerceIn(1, 100)
            val maximum = ConfigManager.int("pool.donatedMaximumLevel", 100).coerceIn(minimum, 100)
            donation.level = donation.level.coerceIn(minimum, maximum)
        }

        // Wonder Trade: la rareza, especie, nivel y estado shiny del Pokémon ofrecido
        // no limitan el Pokémon recibido. Cada especial ofrecido aumenta el bonus personal.
        val bonusEnabled = ConfigManager.bool("bonus.enabled", true)
        val offeredRare = WonderTradeRules.isRare(offered)
        val bonusBefore = if (bonusEnabled) effectiveBonus(player) else 0.0
        // Mínimo del rango: al recibir un especial el bonus vuelve a este valor en lugar de a 0.
        val rankBase = if (bonusEnabled) WonderTradeRanks.baseBonus(player) else 0.0
        val maximumBonus = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
        // Los intercambios normales suman +0,1% antes de tirar la probabilidad de ESTE intercambio.
        // Las aportaciones especiales suman su bonus al terminar y se aplican al siguiente WT normal.
        val normalGain = if (bonusEnabled && !offeredRare) bonusGain(offered, player) else 0.0
        val bonusForRoll = if (!offeredRare) (bonusBefore + normalGain).coerceAtMost(maximumBonus) else bonusBefore
        // La mejora fija del rango se suma a la probabilidad de este intercambio (no se consume ni se acumula).
        val effectiveChance = if (bonusEnabled) (bonusForRoll + WonderTradeRanks.improvementPercent(player)).coerceAtMost(100.0) else bonusForRoll
        val forceSpecial = !offeredRare && effectiveChance > 0.0 && Random.nextDouble() * 100.0 < effectiveChance

        // Si se activa el bonus, se prioriza un especial que ya exista en el pool.
        // Si no hay especiales disponibles, se entrega cualquier entrada válida del pool.
        // Nunca se inventan Pokémon ni se exige compatibilidad con el Pokémon ofrecido.
        var chosenEntry: PoolEntry? = if (forceSpecial) choosePoolEntry(forceSpecial = true, player = player) else null
        if (chosenEntry == null) chosenEntry = choosePoolEntry(forceSpecial = false, player = player)

        // Eventos «Recibir …»: probabilidad extra de que la entrega sea de la categoría del evento.
        for (event in WonderTradeEvents.receiveEvents()) {
            val current = chosenEntry ?: break
            if (entryMatchesEvent(current, event, registryAccess)) continue
            if (Random.nextDouble() * 100.0 >= event.value) continue
            val pick = eligibleEntries(player).filter { entryMatchesEvent(it, event, registryAccess) }.randomOrNull()
            if (pick != null) chosenEntry = pick
        }

        val selectedEntry = chosenEntry ?: run {
            send(player, "noCandidate")
            return
        }
        val incoming = try {
            Pokemon.loadFromJSON(registryAccess, selectedEntry.pokemonJson.deepCopy())
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("No se pudo recuperar un Pokémon del pool ({}).", selectedEntry.id, exception)
            null
        } ?: run {
            send(player, "generationFailed")
            return
        }
        val fromPool = true

        if (incoming.uuid == offered.uuid) {
            send(player, "generationFailed")
            return
        }

        try {
            if (!party.remove(offered)) throw IllegalStateException("No se pudo retirar el Pokémon ofrecido del equipo")
            if (!party.add(incoming)) {
                party.add(offered)
                throw IllegalStateException("No se pudo insertar el Pokémon recibido en el equipo")
            }

            pool.remove(selectedEntry.id)
            if (ConfigManager.bool("pool.enabled", true) && ConfigManager.bool("pool.acceptDonations", true)) {
                pool.add(PoolEntry.fromPokemon(donation, registryAccess, player.gameProfile.name, now, player.uuid.toString()))
                pool.trim(
                    ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1),
                    ConfigManager.bool("pool.trimOldestFirst", true)
                )
            }
            pool.save()
            val incomingRare = WonderTradeRules.isRare(incoming)
            playerData.recordTrade(player.uuid, now, offeredRare, incomingRare, affectCooldown = eventUse == null)
            if (eventUse != null) WonderTradeEvents.markUsed(player, eventUse)
            rememberPlayer(player)
            playerData.setLastOfferedSpecies(player.uuid, offered.species.resourceIdentifier.path.lowercase())
            var bonusAfter = bonusBefore
            if (bonusEnabled) {
                bonusAfter = when {
                    // Si un normal recibe un especial, el bonus se consume y vuelve a su mínimo (0 sin rango).
                    !offeredRare && incomingRare -> rankBase
                    // Un intercambio normal añade +0,1% (configurable), ya usado en la tirada actual.
                    !offeredRare -> bonusForRoll
                    // Ofrecer un especial añade +0,5% (configurable) para el siguiente WT normal.
                    else -> (bonusBefore + bonusGain(offered, player)).coerceAtMost(maximumBonus)
                }
                playerData.setBonus(player.uuid, bonusAfter)
            }
            playerData.save()
            pending.remove(player.uuid)
            // El tótem es solo para un especial (legendario, mítico, Ultraente o Paradoja) que además sea shiny.
            if (incomingRare) {
                WonderTradeGui.playSpecialSound(player, incoming.shiny && WonderTradeRules.isSpecialSpecies(incoming))
            }
            var bonusText = when {
                !bonusEnabled -> ""
                !offeredRare && incomingRare && bonusForRoll > 0.0 -> format(
                    ConfigManager.string("messages.successBonusReset", "&7Has recibido un especial y tu probabilidad se ha reiniciado a &d0%&7."),
                    mapOf("percent" to percentText(rankBase))
                )
                bonusAfter > bonusBefore -> format(
                    ConfigManager.string("messages.successBonusIncreased", "&7Tu probabilidad ha aumentado &a+{gain}%&7. Ahora tienes &d{percent}%&7."),
                    mapOf("gain" to percentText(bonusAfter - bonusBefore), "percent" to percentText(bonusAfter))
                )
                else -> format(
                    ConfigManager.string("messages.successBonusUnchanged", "&7Tu probabilidad acumulada sigue en &d{percent}%&7."),
                    mapOf("percent" to percentText(bonusAfter))
                )
            }
            val fixed = if (bonusEnabled) WonderTradeRanks.improvementPercent(player) else 0.0
            if (!offeredRare && fixed > 0.0) {
                val fixedText = format(
                    ConfigManager.string("messages.successRankImprovement", "&7Mejora fija del rango: &a+{fixed}%&7. Tirada de este intercambio: &d{roll}%&7."),
                    mapOf("fixed" to percentText(fixed), "roll" to percentText(effectiveChance))
                )
                bonusText = if (bonusText.isBlank()) fixedText else "$bonusText $fixedText"
            }
            sendTradeSuccess(player, mapOf(
                "sent" to offered.getDisplayName().string,
                "received" to incoming.getDisplayName().string,
                "bonusText" to bonusText
            ), bonusText)
            announceReceivedRare(player, incoming)
            announceDonation(player, donation)
            recordHistory(player, offered, incoming, fromPool, now)
            WonderTradeDiscord.notify(player, offered, incoming)
            runCatching { WonderTradeEvents.recordOutcome(player, offered, incoming) }
            // Con un evento activo, repone enseguida el Pokémon de la categoría que se acaba de llevar el jugador.
            runCatching { maintainEventPools(player.server) }.onFailure { WonderTradePlus.LOGGER.error("No se pudo reponer la pool del evento.", it) }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.error("Ha fallado el intercambio de Wonder Trade para {}. Se intentará revertirlo.", player.gameProfile.name, exception)
            runCatching { party.remove(incoming) }
            runCatching { if (party.none { it.uuid == offered.uuid }) party.add(offered) }
            pool.restore(previousPool)
            playerData.restore(previousPlayerData)
            runCatching { pool.save() }.onFailure { WonderTradePlus.LOGGER.error("Tampoco se pudo restaurar pool.json.", it) }
            runCatching { playerData.save() }.onFailure { WonderTradePlus.LOGGER.error("Tampoco se pudo restaurar player-data.json.", it) }
            send(player, "saveFailed")
        }
    }

    private fun choosePoolEntry(forceSpecial: Boolean, player: ServerPlayer): PoolEntry? {
        val eligible = eligibleEntries(player)
        val candidates = if (forceSpecial) eligible.filter { WonderTradeRules.isRareEntry(it) } else eligible
        return candidates.randomOrNull()
    }

    /** Entradas de la pool que este jugador podría recibir ahora mismo. */
    private fun eligibleEntries(player: ServerPlayer): List<PoolEntry> {
        if (!ConfigManager.bool("pool.enabled", true)) return emptyList()
        return pool.all().filter { entry ->
            // Protección antiabuso: no se pueden recibir las propias aportaciones ni las recién añadidas.
            if (ConfigManager.bool("antiAbuse.excludeOwnDonations", true) &&
                (entry.donorUuid == player.uuid.toString() || entry.donorName.equals(player.gameProfile.name, ignoreCase = true))
            ) return@filter false
            val holdMinutes = ConfigManager.int("antiAbuse.donationHoldMinutes", 10)
            if (holdMinutes > 0 && entry.donorUuid.isNotEmpty() && System.currentTimeMillis() - entry.addedAt < holdMinutes * 60_000L) return@filter false
            if (!ConfigManager.bool("general.allowShinyPokemon", true) && entry.shiny) return@filter false
            if (!ConfigManager.bool("general.allowSpecialPokemon", true) && entry.special) return@filter false
            if (!ConfigManager.bool("general.allowAlphaPokemon", true) && entry.alpha) return@filter false
            if (!WonderTradeRules.isSpeciesAllowed(entry.species)) return@filter false
            if (!entryVariantAllowed(entry, player.server.registryAccess())) return@filter false
            true
        }
    }

    private val entryVariantCache = HashMap<String, Boolean>()
    private var variantCacheKey = ""

    /** Comprueba las listas de formas y aspectos bloqueados contra una entrada de la pool (con caché por entrada). */
    private fun entryVariantAllowed(entry: PoolEntry, registryAccess: net.minecraft.core.RegistryAccess): Boolean {
        val key = ConfigManager.strings("general.formBlacklist").joinToString(",") + "|" + ConfigManager.strings("general.aspectBlacklist").joinToString(",")
        if (key == "|") return true
        if (key != variantCacheKey) { entryVariantCache.clear(); variantCacheKey = key }
        return entryVariantCache.getOrPut(entry.id) {
            runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull()
                ?.let { WonderTradeRules.isVariantAllowed(it) } ?: true
        }
    }

    // ------------------------------------------------------------ preparación de eventos

    /** Genera un Pokémon de la categoría indicada (shiny o de especie), o null si no se encuentra. */
    /**
     * Genera un Pokémon de la categoría de un evento eligiendo la especie nosotros (no con la especie «random» de
     * Cobblemon, que repetía casi siempre las mismas). Se sortean primero las especies que todavía no están en la
     * pool, para que cada reposición traiga uno distinto; si ya están todas, se repite alguna.
     */
    private fun generateForCategory(player: ServerPlayer, category: EventCategory, low: Int, high: Int, attempts: Int): Pokemon? {
        val inPool = pool.all().map { it.species.lowercase() }.toHashSet()
        data class Template(val speciesId: String, val form: com.cobblemon.mod.common.pokemon.FormData?)
        val templates: List<Template> = if (category == EventCategory.SHINY) {
            PokemonSpecies.species.map { Template(it.resourceIdentifier.toString(), null) }
        } else {
            speciesByCategory(player)[category.speciesCategory()].orEmpty().map { Template(it.species.resourceIdentifier.toString(), it.form) }
        }
        val (unused, used) = templates.shuffled().partition { it.speciesId.lowercase() !in inPool }
        for (template in (unused + used.shuffled()).take(attempts)) {
            val candidate = runCatching {
                PokemonProperties().apply {
                    this.species = template.speciesId
                    this.level = Random.nextInt(low, high + 1)
                    if (category == EventCategory.SHINY) this.shiny = true
                }.create(player).also { created ->
                    val form = template.form
                    if (form != null && created.form.name != form.name) {
                        created.form = form
                        created.updateAspects()
                    }
                }
            }.getOrNull() ?: continue
            if (!fillAllowed(candidate)) continue
            if (category != EventCategory.SHINY && !category.matches(candidate)) continue
            return candidate
        }
        return null
    }

    /**
     * Prepara un evento recién iniciado: asegura que la pool tenga Pokémon de su categoría (retirando los normales
     * más antiguos para generar otros nuevos) y anuncia en el chat cuáles hay. Devuelve false si todavía no se puede
     * hacer porque no hay ningún jugador conectado (se reintentará).
     */
    fun prepareEvent(server: MinecraftServer, event: WtEvent): Boolean {
        val player = server.playerList.players.firstOrNull() ?: return false
        val types = ConfigManager.strings("events.ensurePool.types").map { it.lowercase() }
        if (ConfigManager.bool("events.ensurePool.enabled", true) && event.type.key in types) {
            runCatching { ensureEventPool(server, player, event) }
                .onFailure { WonderTradePlus.LOGGER.error("No se pudo preparar la pool para el evento {}.", event.title, it) }
        }
        if (ConfigManager.bool("events.announcePokemon.enabled", true)) {
            runCatching { announceEventPokemon(server, event) }
                .onFailure { WonderTradePlus.LOGGER.error("No se pudo anunciar los Pokémon del evento {}.", event.title, it) }
        }
        return true
    }

    /**
     * Mientras hay un evento activo, repone la pool si se quedan sin Pokémon de su categoría (por ejemplo,
     * si un admin los borra o alguien los recibe). Se ejecuta cada pocos segundos; necesita un jugador conectado.
     */
    fun maintainEventPools(server: MinecraftServer) {
        if (!ConfigManager.bool("events.ensurePool.enabled", true)) return
        if (!ConfigManager.bool("events.ensurePool.keepDuringEvent", true)) return
        val active = WonderTradeEvents.active()
        if (active.isEmpty()) return
        val player = server.playerList.players.firstOrNull() ?: return
        val types = ConfigManager.strings("events.ensurePool.types").map { it.lowercase() }
        // Olvida las categorías cacheadas de entradas que ya no están en la pool.
        val ids = pool.all().map { it.id }.toHashSet()
        entryCategoryCache.keys.retainAll(ids)
        val activeIds = active.map { "${it.id}:" }
        eventTargets.keys.removeAll { key -> activeIds.none { key.startsWith(it) } }
        for (event in active.filter { it.type.key in types }) {
            ensureEventPool(server, player, event)
        }
    }

    private val warnedEvents = mutableSetOf<Int>()

    /** Cantidad de Pokémon de cada categoría que se mantiene durante un evento: «idEvento:categoría» -> objetivo. */
    private val eventTargets = HashMap<String, Int>()

    private fun ensureEventPool(server: MinecraftServer, player: ServerPlayer, event: WtEvent) {
        val registryAccess = server.registryAccess()
        val minimum = ConfigManager.int("events.ensurePool.minimum", 3).coerceAtLeast(0)
        val maxGenerate = ConfigManager.int("events.ensurePool.maxGenerate", 10).coerceAtLeast(0)
        val configuredTarget = ConfigManager.int("events.ensurePool.target", 0).coerceAtLeast(0)
        // El objetivo de cada categoría es lo que había al empezar el evento (mínimo `minimum`): si había 10
        // y alguien se lleva uno, se repone hasta 10. Con `target` > 0 se fija una cantidad concreta.
        for (category in event.categories) {
            val existing = pool.all().count { entryMatches(it, category, registryAccess) }
            val target = eventTargets.getOrPut("${event.id}:${category.key}") {
                if (configuredTarget > 0) configuredTarget else maxOf(minimum, existing)
            }
            ensureCategory(server, player, event, category, target, maxGenerate)
        }
    }

    private fun ensureCategory(server: MinecraftServer, player: ServerPlayer, event: WtEvent, category: EventCategory, minimum: Int, maxGenerate: Int) {
        val registryAccess = server.registryAccess()
        val existing = pool.all().count { entryMatches(it, category, registryAccess) }
        val needed = minOf((minimum - existing).coerceAtLeast(0), maxGenerate)
        if (needed <= 0) return
        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val attempts = ConfigManager.int("events.ensurePool.attempts", 3000).coerceIn(1, 20000)
        val replaceOldest = ConfigManager.bool("events.ensurePool.replaceOldest", true)
        var generated = 0
        repeat(needed) {
            val fresh = generateForCategory(player, category, low, high, attempts) ?: return@repeat
            if (replaceOldest) {
                // Se retira el normal más antiguo; los especiales nunca se sacrifican.
                val oldest = pool.all().filter { !WonderTradeRules.isRareEntry(it) }.minByOrNull { it.addedAt }
                if (oldest != null) pool.remove(oldest.id)
            }
            addAdminEntry(fresh, registryAccess)
            generated++
        }
        if (generated > 0) {
            trimAndSave()
            WonderTradePlus.LOGGER.info("Evento {}: se han generado {} Pokémon de la categoría {}.", event.title, generated, category.label)
        } else if (warnedEvents.add(event.id)) {
            WonderTradePlus.LOGGER.warn("Evento {}: faltan Pokémon de la categoría {} pero no se ha podido generar ninguno (¿listas de especies o permisos de especiales?).", event.title, category.label)
        }
    }

    private fun announceEventPokemon(server: MinecraftServer, event: WtEvent) {
        val registryAccess = server.registryAccess()
        val matching = pool.all().filter { entryMatchesEvent(it, event, registryAccess) }
        val build: () -> String = {
        val lines = mutableListOf<String>()
        if (matching.isEmpty()) {
            lines += format(
                ConfigManager.string("messages.eventPoolNone", "&7Ahora mismo no hay ningún Pokémon {category}&7 en la pool."),
                categoryPlaceholders(event.categories)
            )
        } else {
            val maxListed = ConfigManager.int("events.announcePokemon.maxListed", 10).coerceAtLeast(1)
            val showLevel = ConfigManager.bool("events.announcePokemon.showLevel", true)
            lines += format(
                ConfigManager.string("messages.eventPoolHeader", "{color}★ Pokémon {category}&7 en la pool ({count}):"),
                categoryPlaceholders(event.categories, mapOf("count" to matching.size))
            )
            val names = matching.take(maxListed).map { entry ->
                val loaded = runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull()
                val name = loaded?.getDisplayName()?.string ?: entry.species.substringAfter(':')
                val star = if (entry.shiny && EventCategory.SHINY !in event.categories) "&e★" else ""
                val level = if (showLevel) Lang.t(" &7Nv.{0}", entry.level) else ""
                Lang.t("{0}&f{1}{2}", star, name, level)
            }
            lines += names.joinToString("&7, ")
            if (matching.size > maxListed) {
                lines += format(
                    ConfigManager.string("messages.eventPoolMore", "&7…y &f{more}&7 más. Míralos todos en &e/wt pool&7."),
                    mapOf("more" to matching.size - maxListed)
                )
            }
        }
        lines.joinToString("\n")
        }
        WonderTradeEvents.broadcast(server, build)
    }

    // ------------------------------------------------------------ categorías de entradas (eventos)

    private val entryCategoryCache = HashMap<String, String>()

    /** Categoría de especie de una entrada de la pool (legendary, mythical, ultraBeast, paradox) o null. */
    fun entryCategoryKey(entry: PoolEntry, registryAccess: net.minecraft.core.RegistryAccess): String? {
        val id = entry.species.substringAfter(':').lowercase()
        if (id in WonderTradeRules.pastParadox || id in WonderTradeRules.futureParadox) return "paradox"
        val key = entryCategoryCache.getOrPut(entry.id) {
            runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull()
                ?.let { WonderTradeRules.speciesCategoryKey(it) } ?: ""
        }
        return key.ifEmpty { null }
    }

    private fun entryMatchesEvent(entry: PoolEntry, event: WtEvent, registryAccess: net.minecraft.core.RegistryAccess): Boolean =
        event.categories.any { entryMatches(entry, it, registryAccess) }

    private fun entryMatches(entry: PoolEntry, category: EventCategory, registryAccess: net.minecraft.core.RegistryAccess): Boolean =
        if (category == EventCategory.SHINY) entry.shiny else entryCategoryKey(entry, registryAccess) == category.speciesCategory()

    // ------------------------------------------------------------ vista previa de probabilidades

    /** Líneas para el menú de confirmación: qué probabilidad real hay de recibir un especial. */
    fun tradePreviewLines(player: ServerPlayer, offered: Pokemon): List<String> {
        val eligible = eligibleEntries(player)
        val total = eligible.size
        val specials = eligible.count { WonderTradeRules.isRareEntry(it) }
        val lines = mutableListOf<String>()
        if (total == 0) {
            lines += ConfigManager.string("messages.previewEmptyPool", "&cNo hay Pokémon disponibles para ti en la pool.")
            return lines
        }
        val bonusEnabled = ConfigManager.bool("bonus.enabled", true)
        val offeredRare = WonderTradeRules.isRare(offered)
        val maximum = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
        val before = if (bonusEnabled) effectiveBonus(player) else 0.0
        val gain = if (bonusEnabled) bonusGain(offered, player) else 0.0
        val fixed = if (bonusEnabled && !offeredRare) WonderTradeRanks.improvementPercent(player) else 0.0
        // Igual que en el intercambio real: un normal suma antes de tirar; la mejora fija entra en la tirada y no se guarda.
        val accumulated = (before + gain).coerceAtMost(maximum)
        val forced = if (offeredRare || specials == 0 || !bonusEnabled) 0.0
        else (accumulated + fixed).coerceAtMost(100.0)
        val natural = specials * 100.0 / total
        val registryAccess = player.server.registryAccess()
        // Solo cuentan los eventos de recepción para los que existe algún Pokémon de su categoría en la pool.
        val usefulEvents = WonderTradeEvents.receiveEvents().filter { event -> eligible.any { entryMatchesEvent(it, event, registryAccess) } }
        var noSpecial = (1.0 - forced / 100.0) * (1.0 - natural / 100.0)
        usefulEvents.forEach { noSpecial *= (1.0 - it.value / 100.0) }
        val finalChance = (1.0 - noSpecial) * 100.0
        lines += format(ConfigManager.string("messages.previewPool", "&7Especiales disponibles en la pool: &f{specials}&7/&f{total}"),
            mapOf("specials" to specials, "total" to total))
        if (bonusEnabled) {
            lines += format(ConfigManager.string("messages.previewBonusNow", "&7Tu bonus actual: &d{percent}%"),
                mapOf("percent" to percentText(before)))
            if (offeredRare) {
                lines += format(ConfigManager.string("messages.previewBonusOfferedSpecial", "&7Ofrecer un especial no usa el bonus, pero lo subirá a &d{percent}%&7."),
                    mapOf("percent" to percentText((before + gain).coerceAtMost(maximum))))
            } else {
                lines += format(ConfigManager.string("messages.previewBonusUsed", "&7Bonus aplicado en este intercambio: &d{percent}% &8(+{gain}%)"),
                    mapOf("percent" to percentText(accumulated), "gain" to percentText(gain)))
                if (fixed > 0.0) {
                    lines += format(
                        ConfigManager.string("messages.previewRankImprovement", "&7Mejora fija del rango: &a+{fixed}%&7. Tirada: &d{roll}%&7."),
                        mapOf("fixed" to percentText(fixed), "roll" to percentText(forced))
                    )
                }
            }
        }
        lines += format(ConfigManager.string("messages.previewChance", "&7Probabilidad de recibir un especial: &d{percent}%"),
            mapOf("percent" to String.format("%.1f", finalChance)))
        usefulEvents.forEach { event ->
            lines += format(
                ConfigManager.string("messages.previewEventShiny", "{color}★ Evento: {color}+{percent}% &7de recibir un Pokémon {category}"),
                categoryPlaceholders(event.categories, mapOf("percent" to percentText(event.value)))
            )
        }
        if (bonusEnabled && !offeredRare) {
            lines += format(
                ConfigManager.string("messages.previewReset", "&8Si te toca un especial, tu bonus se reinicia a &d{percent}%&8."),
                mapOf("percent" to percentText(WonderTradeRanks.baseBonus(player)))
            )
        }
        return lines
    }

    // ------------------------------------------------------------ bonus por rareza

    /**
     * Bonus por Pokémon ofrecido. Los Pokémon especiales suman el incremento base y,
     * si además son shiny, el extra configurable. El extra solo aplica a legendarios,
     * míticos, Ultraentes y Paradoja; un shiny de especie normal no lo recibe.
     */
    /** Bonus del jugador teniendo en cuenta el mínimo de su rango. */
    fun effectiveBonus(player: ServerPlayer): Double {
        if (!ConfigManager.bool("bonus.enabled", true)) return 0.0
        val maximum = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
        return maxOf(playerData.bonus(player.uuid), WonderTradeRanks.baseBonus(player)).coerceAtMost(maximum)
    }

    fun bonusGain(pokemon: Pokemon, player: ServerPlayer? = null): Double {
        val rare = WonderTradeRules.isRare(pokemon)
        val key = if (rare) "bonus.specialTradeGain" else "bonus.normalTradeGain"
        val fallback = if (rare) 0.5 else 0.1
        val base = ConfigManager.double(key, fallback).coerceAtLeast(0.0)
        val specialShinyExtra = if (pokemon.shiny && WonderTradeRules.categoryLabel(pokemon) != null) {
            ConfigManager.double("bonus.specialShinyExtraGain", 0.2).coerceAtLeast(0.0)
        } else 0.0
        // Evento «Arriesgar Shiny»: cada shiny ofrecido suma un % extra.
        val eventExtra = WonderTradeEvents.riskExtraGain(pokemon)
        val multiplier = if (player != null) WonderTradeRanks.gainMultiplier(player) else 1.0
        return (base + specialShinyExtra + eventExtra) * multiplier
    }

    fun normalBonusGainText(): String = percentText(ConfigManager.double("bonus.normalTradeGain", 0.1).coerceAtLeast(0.0))
    fun specialBonusGainText(): String = percentText(ConfigManager.double("bonus.specialTradeGain", 0.5).coerceAtLeast(0.0))
    fun specialShinyExtraGainText(): String = percentText(ConfigManager.double("bonus.specialShinyExtraGain", 0.2).coerceAtLeast(0.0))
    fun specialShinyTotalGainText(): String = percentText(
        ConfigManager.double("bonus.specialTradeGain", 0.5).coerceAtLeast(0.0) +
            ConfigManager.double("bonus.specialShinyExtraGain", 0.2).coerceAtLeast(0.0)
    )

    /** Se llama cada tick del servidor; una vez por segundo actualiza los menús y ejecuta mantenimiento y copias de seguridad. */
    fun tick(server: MinecraftServer) {
        minecraftServer = server
        Lang.reset()
        runCatching { WonderTradeEventDisplay.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló la barra de eventos.", it) }
        tickCounter++
        if (tickCounter % 20 != 0) return
        secondCounter++
        runCatching { WonderTradeEvents.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el control de eventos.", it) }
        runCatching { WonderTradePenalties.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el control de penalizaciones.", it) }
        runCatching { WonderTradeSeasons.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el evento de temporada.", it) }
        runCatching { WonderTradeTopDonations.tick(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el Top Donaciones.", it) }
        server.playerList.players.forEach { player -> Lang.withPlayer(player) { WonderTradeGui.refreshCooldown(player) } }
        runCatching { checkCooldownNotices(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el aviso de disponibilidad.", it) }
        val now = System.currentTimeMillis()
        if (now - lastHistoryCleanupAt >= 60_000L) {
            lastHistoryCleanupAt = now
            val removed = history.pruneOlderThanDays(ConfigManager.int("history.autoDeleteAfterDays", 30), now)
            if (removed > 0) WonderTradePlus.LOGGER.info("Historial: {} entradas antiguas eliminadas automáticamente.", removed)
        }

        val interval = ConfigManager.int("maintenance.intervalMinutes", 30).coerceAtLeast(1) * 60L
        if (secondCounter == 60L || secondCounter % interval == 0L) {
            runCatching { maintainPool(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el mantenimiento del pool.", it) }
        }
        val backupHours = ConfigManager.int("backup.intervalHours", 6)
        if (backupHours > 0 && secondCounter % (backupHours * 3600L) == 0L) backupNow()
    }

    // ------------------------------------------------------------ mantenimiento y copias

    /** El mantenimiento solo elimina entradas caducadas; no crea Pokémon automáticamente. */
    @Suppress("UNUSED_PARAMETER")
    fun maintainPool(server: MinecraftServer): Pair<Int, Int> {
        val days = ConfigManager.int("maintenance.expireAfterDays", 14)
        val expired = if (days > 0) {
            val limit = System.currentTimeMillis() - days * 86_400_000L
            pool.removeWhere { it.addedAt > 0L && it.addedAt < limit }
        } else 0
        if (expired > 0) {
            pool.save()
            WonderTradePlus.LOGGER.info("Mantenimiento del pool: {} entradas caducadas eliminadas; {} Pokémon permanecen.", expired, pool.size)
        }
        val (purged, generated) = purgeStale(server)
        val monthly = runCatching { monthlyReset(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el reinicio mensual de la pool.", it) }.getOrDefault(0)
        return (expired + purged + monthly) to (generated + monthly)
    }

    /**
     * Reinicio mensual: el día configurado de cada mes sustituye los Pokémon normales de la pool por otros nuevos
     * (los especiales se conservan). La primera vez que se ejecuta solo anota el mes actual, para no vaciar nada
     * justo al actualizar. Si no hay jugadores conectados para generar, se aplaza.
     */
    private fun monthlyReset(server: MinecraftServer): Int {
        if (!ConfigManager.bool("maintenance.monthlyReset.enabled", true)) return 0
        val today = java.time.LocalDate.now(WonderTradeSchedule.zone())
        val day = ConfigManager.int("maintenance.monthlyReset.dayOfMonth", 1).coerceIn(1, 28)
        val key = "%04d-%02d".format(today.year, today.monthValue)
        val file = FabricLoader.getInstance().configDir.resolve("wondertradeplus").resolve("maintenance-state.json")
        val state = runCatching { Files.newBufferedReader(file).use { com.google.gson.JsonParser.parseReader(it).asJsonObject } }.getOrNull()
        fun mark() {
            val node = com.google.gson.JsonObject().apply { addProperty("lastMonthlyReset", key) }
            Files.createDirectories(file.parent)
            Files.writeString(file, node.toString())
        }
        if (state == null) { mark(); return 0 }
        if (state.get("lastMonthlyReset")?.asString == key || today.dayOfMonth < day) return 0
        if (server.playerList.players.isEmpty()) return 0
        val (replaced, _) = purgeStale(server, forceAll = true, forceIncludeDonations = ConfigManager.bool("maintenance.monthlyReset.includePlayerDonations", true))
        mark()
        WonderTradePlus.LOGGER.info("Reinicio mensual de la pool: {} Pokémon normales renovados.", replaced)
        WonderTradeEvents.broadcast(server) { ConfigManager.string("messages.poolMonthlyReset", "&d★ Reinicio mensual de la pool.") }
        return replaced
    }

    /**
     * Purga automática: un Pokémon normal que lleva demasiado tiempo en la pool sin que nadie lo reciba se
     * elimina y se sustituye por uno nuevo (normal). Los especiales (shiny, legendario, mítico, Ultraente
     * y Paradoja) nunca se purgan. Solo se retira una entrada si se ha podido generar su sustituto.
     */
    private fun purgeStale(server: MinecraftServer, forceAll: Boolean = false, forceIncludeDonations: Boolean = true): Pair<Int, Int> {
        if (!forceAll && !ConfigManager.bool("maintenance.purge.enabled", true)) return 0 to 0
        val hours = ConfigManager.int("maintenance.purge.afterHours", 168)
        if (!forceAll && hours <= 0) return 0 to 0
        // Generar Pokémon necesita un jugador conectado; si no hay ninguno se aplaza a la siguiente pasada.
        val player = server.playerList.players.firstOrNull() ?: return 0 to 0
        val limit = if (forceAll) Long.MAX_VALUE else System.currentTimeMillis() - hours * 3_600_000L
        val includeDonations = if (forceAll) forceIncludeDonations else ConfigManager.bool("maintenance.purge.includePlayerDonations", true)
        val stale = pool.all().filter {
            it.addedAt > 0L && it.addedAt < limit && !WonderTradeRules.isRareEntry(it) && (includeDonations || it.donorUuid.isEmpty())
        }
        if (stale.isEmpty()) return 0 to 0
        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val registryAccess = server.registryAccess()
        var replaced = 0
        for (entry in stale) {
            val fresh = generateRandom(player, low, high, attempts, false) { fillAllowed(it) && !WonderTradeRules.isRare(it) } ?: continue
            if (pool.remove(entry.id)) {
                addAdminEntry(fresh, registryAccess)
                replaced++
            }
        }
        if (replaced > 0) {
            pool.save()
            WonderTradePlus.LOGGER.info("Purga de la pool: {} Pokémon con más de {} h sustituidos por otros nuevos.", replaced, hours)
        }
        return replaced to replaced
    }

    /** Copia pool.json, player-data.json y history.json a backups/<fecha>. Devuelve el nombre de la carpeta. */
    fun backupNow(): String? = runCatching {
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val root = directory.resolve("backups")
        val target = root.resolve(stamp)
        Files.createDirectories(target)
        for (name in listOf("pool.json", "player-data.json", "history.json")) {
            val source = directory.resolve(name)
            if (Files.exists(source)) Files.copy(source, target.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val keep = ConfigManager.int("backup.keep", 10).coerceAtLeast(1)
        val folders = Files.list(root).use { stream -> stream.filter { Files.isDirectory(it) }.sorted().toList() }
        folders.dropLast(keep).forEach { folder ->
            Files.list(folder).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(folder)
        }
        stamp
    }.onFailure { WonderTradePlus.LOGGER.error("No se pudo crear la copia de seguridad.", it) }.getOrNull()

    /** Fija el porcentaje acumulado de un jugador (conectado o conocido). Devuelve (nombre, valor) o null. */
    fun adminSetBonus(server: MinecraftServer, name: String, value: Double): Pair<String, Double>? {
        val found = resolvePlayer(server, name) ?: return null
        val clamped = value.coerceIn(0.0, 100.0)
        playerData.setBonus(found.first, clamped)
        playerData.setName(found.first, found.second)
        playerData.save()
        return found.second to clamped
    }

    // ------------------------------------------------------------ estadísticas

    fun resolvePlayer(server: MinecraftServer, name: String): Pair<UUID, String>? {
        val online = server.playerList.getPlayerByName(name)
        if (online != null) return online.uuid to online.gameProfile.name
        val uuid = playerData.findByName(name) ?: return null
        return uuid to name
    }

    /** Reinicia los contadores que se muestran en /wt stats y alimentan /wt top. */
    fun adminResetStats(server: MinecraftServer, name: String): String? {
        val online = server.playerList.getPlayerByName(name)
        val uuid = online?.uuid ?: playerData.findByName(name) ?: return null
        val shownName = online?.gameProfile?.name ?: name
        playerData.setName(uuid, shownName)
        playerData.resetStats(uuid)
        playerData.save()
        return shownName
    }

    /** Reinicia todos los contadores de estadísticas y rankings, sin tocar bonus ni límites. */
    /** Reinicia las estadísticas de un solo jugador (bonus y límites no se tocan). */
    fun adminResetPlayerStats(uuid: UUID): Boolean {
        val done = playerData.resetStats(uuid)
        if (done) playerData.save()
        return done
    }

    fun knownPlayers(): List<Pair<UUID, String>> = playerData.knownPlayers()

    fun statOf(uuid: UUID, key: String): Int = playerData.stat(uuid, key)

    fun savedSkin(uuid: UUID): Pair<String, String?>? = playerData.skin(uuid)

    fun adminResetAllStats(): Int {
        val affected = playerData.resetAllStats()
        playerData.save()
        return affected
    }

    fun statsComponent(uuid: UUID, name: String): Component {
        val bonus = if (ConfigManager.bool("bonus.enabled", true)) playerData.bonus(uuid) else 0.0
        val lines = listOf(
            Lang.t("&8━━ &dEstadísticas de &f{0} &8━━", name),
            Lang.t("&7Intercambios: &f{0}", playerData.stat(uuid, "trades")),
            Lang.t("&7Especiales recibidos: &d{0}", playerData.stat(uuid, "specialsReceived")),
            Lang.t("&7Especiales aportados: &d{0}", playerData.stat(uuid, "specialsDonated")),
            Lang.t("&7Donaciones directas: &f{0}", playerData.stat(uuid, "donations")),
            Lang.t("&7Probabilidad acumulada: &d{0}%", percentText(bonus))
        )
        return colour(lines.joinToString("\n"))
    }

    fun topComponent(category: String, viewer: UUID?): Component {
        val (key, title) = when (category) {
            "received" -> "specialsReceived" to Lang.tr("Especiales recibidos")
            "donated" -> "specialsDonated" to Lang.tr("Especiales aportados")
            else -> "trades" to "Intercambios"
        }
        val ranking = playerData.ranking(key)
        val lines = mutableListOf(Lang.t("&8━━ &dTop Wonder Trade &8— &f{0} &8━━", title))
        if (ranking.isEmpty()) {
            lines += Lang.tr("&7Todavía no hay datos.")
        } else {
            val colours = listOf("&6", "&7", "&c")
            // Los empatados comparten puesto (1, 1, 3...) y se ordenan por nombre.
            fun placeOf(index: Int): Int = ranking.indexOfFirst { it.third == ranking[index].third } + 1
            ranking.take(10).forEachIndexed { index, (_, name, value) ->
                val place = placeOf(index)
                lines += "${colours.getOrElse(place - 1) { "&8" }}$place. &f$name &8— &d$value"
            }
            if (viewer != null) {
                val position = ranking.indexOfFirst { it.first == viewer }
                if (position >= 10) lines += Lang.t("&7Tu posición: &f#{0} &8— &d{1}", placeOf(position), ranking[position].third)
            }
        }
        return colour(lines.joinToString("\n"))
    }

    fun percentText(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else String.format("%.1f", value)

    /** Para admins: busca la probabilidad acumulada de un jugador (conectado o que ya haya intercambiado). */
    fun lookupBonus(server: MinecraftServer, name: String): Pair<String, Double>? {
        val online = server.playerList.getPlayerByName(name)
        val uuid = online?.uuid ?: playerData.findByName(name) ?: return null
        val shownName = online?.gameProfile?.name ?: name
        val value = if (!ConfigManager.bool("bonus.enabled", true)) 0.0
        else if (online != null) effectiveBonus(online) else playerData.bonus(uuid)
        return shownName to value
    }

    fun bonusMaximum(): String = percentText(ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0))
    fun bonusPercentText(value: Double): String = percentText(value)
    fun knownPlayerNames(): List<String> = playerData.names()

    /** Muestra al jugador su probabilidad acumulada de recibir un especial. */
    fun showBonus(player: ServerPlayer) {
        val value = effectiveBonus(player)
        val shinyExtra = specialShinyExtraGainText()
        send(player, "bonusInfo", mapOf(
            "percent" to percentText(value),
            "normalGain" to normalBonusGainText(),
            "specialGain" to specialBonusGainText(),
            "specialShinyExtraGain" to shinyExtra
        ))
        // Configuraciones antiguas pueden tener un mensaje bonusInfo personalizado
        // que todavía no contiene el nuevo marcador; mostramos el extra en otra línea.
        if (!ConfigManager.string("messages.bonusInfo", "").contains("{specialShinyExtraGain}")) {
            send(player, "bonusShinyExtraInfo", mapOf("specialShinyExtraGain" to shinyExtra))
        }
        rankInfoLine(player)?.let { player.sendSystemMessage(info(it)) }
    }

    /** Línea con el rango del jugador y sus ventajas, o null si no tiene rango (o los rangos están desactivados). */
    fun rankInfoLine(player: ServerPlayer): String? {
        val rank = WonderTradeRanks.rankOf(player) ?: return null
        return format(
            ConfigManager.string("messages.rankInfo", "&7Tu rango: {rank} &8— &7mínimo de probabilidad &d{base}%&7 y ganancias &ax{multiplier}&7."),
            mapOf("rank" to rank.name, "base" to percentText(rank.baseBonus), "improvement" to percentText(rank.improvementPercent), "multiplier" to percentText(rank.gainMultiplier))
        )
    }

    /** Prefijo de los mensajes. Con general.boldPrefix (por defecto) el nombre del mod sale siempre en negrita. */
    fun prefix(): String {
        val configured = ConfigManager.string("messages.prefix", "")
        if (!ConfigManager.bool("general.boldPrefix", true)) return configured
        if (configured.contains("&l") || configured.contains("§l") || !configured.contains("WonderTrade")) return configured
        return Regex("((?:[&§][0-9a-fk-or])*)WonderTrade").replace(configured) { m -> m.groupValues[1] + "&lWonderTrade&r" + m.groupValues[1] }
    }

    /** Respuesta de /wt rango: solo lo que tiene el jugador. La lista de todos los rangos está en /wt rangos. */
    fun rankComponent(player: ServerPlayer): Component {
        val lines = mutableListOf<String>()
        lines += Lang.tr("&8━━ &d&l✦ Tu rango &8━━")
        if (!WonderTradeRanks.enabled()) {
            lines += ConfigManager.string("messages.rankDisabled", "&7Los rangos están desactivados.")
        } else {
            val rank = WonderTradeRanks.rankOf(player)
            if (rank == null) {
                lines += ConfigManager.string("messages.rankNone", "&7No tienes ningún rango con ventajas en Wonder Trade.")
            } else {
                lines += Lang.t(" &7Rango: &f&l{0}", rank.name)
                lines += Lang.t(" &7Probabilidad mínima de especial: &d{0}%", percentText(rank.baseBonus))
                lines += Lang.t(" &7Mejora fija en cada intercambio: &a+{0}%", percentText(rank.improvementPercent))
                lines += Lang.t(" &7Ganancia por Pokémon ofrecido: &ax{0}", percentText(rank.gainMultiplier))
            }
            if (ConfigManager.bool("bonus.enabled", true)) {
                lines += Lang.t(" &7Tu probabilidad actual: &d{0}%", percentText(effectiveBonus(player)))
            }
            if (player.hasPermissions(adminLevel())) {
                lines += Lang.t("&8Detección de permisos: {0}", WonderTradeRanks.backendName())
            }
        }
        return colour(lines.joinToString("\n"))
    }

    // ------------------------------------------------------------ Pokémon por categoría (para /wt especiales)

    private var categoryCache: Map<String, List<Pokemon>>? = null

    private val regionalFormIds = mapOf(
        "alolan" to setOf("alolan", "alola"),
        "galarian" to setOf("galarian", "galar"),
        "hisuian" to setOf("hisuian", "hisui"),
        "paldean" to setOf("paldean", "paldea")
    )

    private fun normalizeFormId(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    /** «alolan|», «galarian|zen» o «paldean|Paldea-Combat». Vacío si es la forma base. */
    fun formKey(pokemon: Pokemon): String {
        val labels = runCatching { pokemon.form.labels }.getOrNull().orEmpty().map { it.lowercase() }
        val aspects = runCatching { pokemon.aspects }.getOrNull().orEmpty().map { it.lowercase() }
        val form = runCatching { pokemon.form.name }.getOrDefault("")
        val region = when {
            "alolan_form" in labels || "alolan" in aspects || form.equals("Alola", true) || form.equals("Alolan", true) -> "alolan"
            "galarian_form" in labels || "galarian" in aspects || form.contains("galar", true) -> "galarian"
            "hisuian_form" in labels || "hisuian" in aspects || form.equals("Hisui", true) || form.equals("Hisuian", true) -> "hisuian"
            "paldean_form" in labels || "paldean" in aspects || form.startsWith("Paldea", true) -> "paldean"
            else -> ""
        }
        val detail = when {
            form.startsWith("Paldea-", true) -> form
            form.contains("zen", true) && region == "galarian" -> form
            else -> ""
        }
        return if (region.isEmpty() && detail.isEmpty()) "" else "$region|$detail"
    }

    /** Recrea un Pokémon para un icono de menú, con su forma regional si se guardó. */
    fun previewPokemon(player: ServerPlayer, species: String, level: Int, shiny: Boolean, formKey: String = ""): Pokemon? {
        if (species.isBlank()) return null
        val region = formKey.substringBefore('|')
        val detail = formKey.substringAfter('|', "")
        val text = buildList {
            add(species)
            if (region.isNotBlank()) add(region)
            add("level=${level.coerceIn(1, 100)}")
            add("shiny=$shiny")
        }.joinToString(" ")
        val pokemon = runCatching { PokemonProperties.parse(text).create(player) }.getOrNull() ?: return null
        if (region.isNotBlank()) applyRegionalForm(pokemon, region)
        if (detail.isNotBlank()) {
            val match = pokemon.species.forms.firstOrNull { it.name.equals(detail, true) }
            if (match != null && pokemon.form.name != match.name) {
                pokemon.form = match
                runCatching { pokemon.updateAspects() }
            }
        }
        return pokemon
    }

    private fun applyRegionalForm(pokemon: Pokemon, regionalToken: String) {
        val accepted = regionalFormIds[regionalToken] ?: return
        val aspectApplied = pokemon.aspects.map(::normalizeFormId).any { it in accepted }
        if (aspectApplied && normalizeFormId(pokemon.form.name) in accepted) return
        val regionalForm = pokemon.species.forms.firstOrNull { form ->
            normalizeFormId(form.name) in accepted || normalizeFormId(form.formOnlyShowdownId()) in accepted
        } ?: return
        if (pokemon.form.name != regionalForm.name) {
            pokemon.form = regionalForm
            runCatching { pokemon.updateAspects() }
        }
    }

    /**
     * Pokémon de cada categoría de especie (ultraBeast, paradox, mythical, legendary). Se calcula una vez creando
     * un ejemplar de cada especie; es solo para mostrarlo en el menú.
     */
    fun speciesByCategory(player: ServerPlayer): Map<String, List<Pokemon>> {
        categoryCache?.let { return it }
        val result = LinkedHashMap<String, MutableList<Pokemon>>()
        listOf("ultraBeast", "paradox", "mythical", "legendary").forEach { result[it] = mutableListOf() }
        for (species in PokemonSpecies.species.sortedBy { it.resourceIdentifier.path }) {
            val path = species.resourceIdentifier.path
            val pokemon = previewPokemon(player, path, 50, false) ?: continue
            val key = WonderTradeRules.speciesCategoryKey(pokemon) ?: continue
            result[key]?.add(pokemon)
            for (token in regionalFormIds.keys) {
                val regional = previewPokemon(player, path, 50, false, "$token|") ?: continue
                if (formKey(regional).isBlank() || regional.form.name == pokemon.form.name) continue
                result[key]?.add(regional)
            }
        }
        return result.also { categoryCache = it }
    }

    /** Estado de cooldown y último aviso de disponibilidad por jugador. */
    private val cooldownWatch = HashMap<UUID, Boolean>()
    private val lastAvailableNoticeAt = HashMap<UUID, Long>()

    /** Avisa a todos los jugadores cuando termina su espera y repite el recordatorio cada cierto tiempo. */
    private fun checkCooldownNotices(server: MinecraftServer) {
        val online = server.playerList.players
        val onlineIds = online.map { it.uuid }.toSet()
        cooldownWatch.keys.retainAll(onlineIds)
        lastAvailableNoticeAt.keys.retainAll(onlineIds)
        if (!ConfigManager.bool("cooldownNotice.enabled", true) || !isEnabled()) return

        val now = System.currentTimeMillis()
        val eventInterval = ConfigManager.int("cooldownNotice.eventReminderMinutes", 2).coerceAtLeast(1) * 60_000L
        val normalInterval = ConfigManager.int("cooldownNotice.normalReminderMinutes", 10).coerceAtLeast(1) * 60_000L
        val reminderInterval = if (WonderTradeEvents.active(now).isNotEmpty()) eventInterval else normalInterval

        for (player in online) {
            // Un jugador puede intercambiar si acabó el cooldown normal o si tiene disponible un uso de evento.
            val available = cooldownRemainingSeconds(player) <= 0L || WonderTradeEvents.readyEvent(player) != null
            if (!available) {
                cooldownWatch[player.uuid] = true
                lastAvailableNoticeAt.remove(player.uuid)
                continue
            }

            val wasOnCooldown = cooldownWatch.put(player.uuid, false) == true
            val lastNotice = lastAvailableNoticeAt[player.uuid]
            val shouldNotify = wasOnCooldown || lastNotice == null || now - lastNotice >= reminderInterval
            if (shouldNotify) {
                send(player, "cooldownFinished")
                WonderTradeGui.playSound(player, "cooldownReady")
                lastAvailableNoticeAt[player.uuid] = now
            }
        }
    }

    /**
     * Quién se salta la espera: jugadores con nivel de permiso >= trade.cooldownBypassPermissionLevel.
     * Por defecto 5 (nadie: owners, admins y moderadores también esperan). 2 devuelve el comportamiento antiguo.
     */
    private fun cooldownBypass(player: ServerPlayer): Boolean = isInfinite(player) ||
        player.hasPermissions(ConfigManager.int("trade.cooldownBypassPermissionLevel", 5).coerceIn(0, 5))

    fun cooldownRemainingSeconds(player: ServerPlayer): Long {
        val bypass = cooldownBypass(player)
        val cooldown = (ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0) * 60L).toLong()
        return playerData.cooldownRemainingSeconds(player.uuid, System.currentTimeMillis(), cooldown, bypass)
    }

    fun cooldownDisplayText(player: ServerPlayer): String {
        val remaining = cooldownRemainingSeconds(player)
        return if (remaining <= 0L) ConfigManager.string("messages.cooldownReady", "&aDisponible") else formatDuration(remaining)
    }

    private fun checkLimits(player: ServerPlayer): Boolean? {
        val bypass = cooldownBypass(player) || WonderTradeEvents.readyEvent(player) != null
        val cooldown = (ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0) * 60L).toLong()
        val limit = ConfigManager.int("trade.dailyLimit", 0).coerceAtLeast(0)
        val check = playerData.check(player.uuid, System.currentTimeMillis(), cooldown, limit, bypass)
        if (check.cooldownSeconds > 0L) {
            // Con un evento activo se muestra la espera del evento, no la normal.
            val eventLeft = WonderTradeEvents.cooldownRemaining(player)
            if (eventLeft != null && eventLeft > 0L) {
                player.sendSystemMessage(info(format(
                    ConfigManager.string("messages.eventCooldown", "&cTu próximo intercambio de evento estará disponible en &f{time}&c."),
                    mapOf("time" to formatDuration(eventLeft))
                )))
            } else {
                send(player, "cooldown", mapOf("time" to formatDuration(check.cooldownSeconds)))
            }
            return true
        }
        if (check.dailyLimitReached) {
            send(player, "dailyLimit", mapOf("limit" to limit))
            return true
        }
        return null
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun getPartyPokemon(player: ServerPlayer, slotNumber: Int): Pokemon? {
        if (slotNumber !in 1..6) {
            send(player, "invalidSlot")
            return null
        }
        return Cobblemon.storage.getParty(player).get(slotNumber - 1)
    }

    /** Devuelve "UltraBeast", "PastParadox", "FutureParadox", "Special" o "" si es un Pokémon normal. */
    private fun donationCategory(pokemon: Pokemon): String {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        return when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> "UltraBeast"
            id in WonderTradeRules.pastParadox -> "PastParadox"
            id in WonderTradeRules.futureParadox -> "FutureParadox"
            WonderTradeRules.isSpecial(pokemon) -> "Special"
            else -> ""
        }
    }

    /** Anuncia en el chat global cuando alguien recibe un shiny o una categoría especial. */
    private fun announceReceivedRare(player: ServerPlayer, pokemon: Pokemon) {
        if (!ConfigManager.bool("broadcast.enabled", true)) return
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        val category = when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> "ultraBeast"
            id in WonderTradeRules.pastParadox || id in WonderTradeRules.futureParadox -> "paradox"
            runCatching { pokemon.isMythical() }.getOrDefault(false) -> "mythical"
            runCatching { pokemon.isLegendary() }.getOrDefault(false) -> "legendary"
            pokemon.shiny -> "shiny"
            else -> return
        }
        if (!ConfigManager.bool("broadcast.received${category.replaceFirstChar { it.uppercase() }}", true)) return
        val key = when (category) {
            "ultraBeast" -> "broadcastReceivedUltraBeast"
            "paradox" -> "broadcastReceivedParadox"
            "mythical" -> "broadcastReceivedMythical"
            "legendary" -> "broadcastReceivedLegendary"
            else -> "broadcastReceivedShiny"
        }
        fun fallback() = when (category) {
            "ultraBeast" -> Lang.tr("&3{player} ha conseguido un Pokémon Ultraente{shinySuffix} en el WonderTrade: &f{pokemon}&3!")
            "paradox" -> Lang.tr("&c{player} ha conseguido un Pokémon Paradoja{shinySuffix} en el WonderTrade: &f{pokemon}&c!")
            "mythical" -> Lang.tr("&d{player} ha conseguido un Pokémon Mítico{shinySuffix} en el WonderTrade: &f{pokemon}&d!")
            "legendary" -> Lang.tr("&6{player} ha conseguido un Pokémon Legendario{shinySuffix} en el WonderTrade: &f{pokemon}&6!")
            else -> Lang.tr("&e{player} ha conseguido un shiny en el WonderTrade: &f{pokemon}&e!")
        }
        val pokemonName = pokemon.getDisplayName().string
        broadcastLocalized(player.server) {
            val categoryText = when (category) {
                "ultraBeast" -> Lang.tr("Ultraente")
                "paradox" -> Lang.tr("Paradoja")
                "mythical" -> Lang.tr("Mítico")
                "legendary" -> Lang.tr("Legendario")
                else -> "shiny"
            }
            val suffix = if (pokemon.shiny && category != "shiny") " shiny" else ""
            val message = format(ConfigManager.string("messages.$key", fallback()), mapOf(
                "player" to player.gameProfile.name,
                "pokemon" to pokemonName,
                "category" to categoryText,
                "shinySuffix" to suffix
            ))
            colour(WonderTradeService.prefix() + message)
        }
    }

    private fun announceDonation(player: ServerPlayer, pokemon: Pokemon) {
        if (!ConfigManager.bool("broadcast.enabled", true)) return
        val category = donationCategory(pokemon)
        val toggle: String
        val key: String
        when {
            category.isEmpty() && pokemon.shiny -> { toggle = "shinyDonations"; key = "broadcastShiny" }
            category.isEmpty() -> { toggle = "normalDonations"; key = "broadcastNormal" }
            pokemon.shiny -> { toggle = "shiny${category}Donations"; key = "broadcastShiny$category" }
            else -> { toggle = category.replaceFirstChar { it.lowercase() } + "Donations"; key = "broadcast$category" }
        }
        val defaultEnabled = toggle != "normalDonations"
        if (!ConfigManager.bool("broadcast.$toggle", defaultEnabled)) return
        if (ConfigManager.string("messages.$key", "").isBlank()) return
        val pokemonName = pokemon.getDisplayName().string
        broadcastLocalized(player.server) {
            val content = format(ConfigManager.string("messages.$key", ""), mapOf("player" to player.gameProfile.name, "pokemon" to pokemonName))
            colour(WonderTradeService.prefix() + content)
        }
    }

    /** Envía el éxito y el cambio del bonus en una sola línea; mantiene compatibles mensajes personalizados antiguos. */
    private fun sendTradeSuccess(player: ServerPlayer, values: Map<String, Any>, bonusText: String) {
        Lang.withPlayer(player) {
            val fallback = Lang.tr("&aIntercambio completado: has recibido &f{received}&a. {bonusText}")
            val template = ConfigManager.string("messages.success", fallback)
            var message = format(template, values)
            if (!template.contains("{bonusText}") && bonusText.isNotBlank()) message += " $bonusText"
            player.sendSystemMessage(colour(WonderTradeService.prefix() + message))
        }
    }

    fun send(player: ServerPlayer, key: String, values: Map<String, Any> = emptyMap()) {
        Lang.withPlayer(player) {
            val fallback = defaultMessage(key)
            val template = ConfigManager.string("messages.$key", fallback)
            val extra = if (key.startsWith("season")) WonderTradeSeasons.placeholders() else if (key == "megaHuntBlocked") WonderTradeMegaHunt.placeholders() else emptyMap()
            player.sendSystemMessage(colour(WonderTradeService.prefix() + format(template, extra + values)))
        }
    }

    /** Aviso de penalización: el tiempo y el motivo se redactan en el idioma del jugador penalizado, no en el del admin. */
    fun sendPenalty(player: ServerPlayer, key: String, penalty: Penalty) {
        Lang.withPlayer(player) {
            val values = mapOf("time" to WonderTradePenalties.remainingText(penalty), "reason" to WonderTradePenalties.reasonSuffix(penalty))
            val template = ConfigManager.string("messages.$key", defaultMessage(key))
            player.sendSystemMessage(colour(prefix() + format(template, values)))
        }
    }

    fun format(value: String, replacements: Map<String, Any>): String {
        var result = value
        replacements.forEach { (key, replacement) -> result = result.replace("{$key}", replacement.toString()) }
        return result
    }

    fun colour(value: String): Component = Component.literal(value.replace('&', '§'))

    private fun defaultMessage(key: String): String = when (key) {
        "tradeDisabled" -> Lang.tr("&cWonder Trade está desactivado temporalmente.")
        "megaHuntBlocked" -> Lang.tr("&cDurante el MegaHunt no se pueden intercambiar ni donar Pokémon de la línea de {species}.")
        "seasonTypeBlocked" -> Lang.tr("&cDurante el evento de {event} solo se pueden intercambiar Pokémon de tipo {types}.")
        "cooldownFinished" -> Lang.tr("&a¡Ya puedes hacer Wonder Trade otra vez! Usa &e/wt&a.")
        "emptySlot" -> Lang.tr("&cNo tienes ningún Pokémon en ese slot.")
        "invalidSlot" -> Lang.tr("&cEl slot debe estar entre 1 y 6.")
        "confirmationExpired" -> Lang.tr("&cLa confirmación ha caducado.")
        "nothingToConfirm" -> Lang.tr("&cNo tienes ningún intercambio pendiente.")
        "success" -> Lang.tr("&aIntercambio completado: has recibido &f{received}&a. {bonusText}")
        "donationDisabled" -> Lang.tr("&cLas donaciones a la pool están desactivadas.")
        "donationPoolFull" -> Lang.tr("&cNo puedes donar porque la pool ya está completa ({size}/{maximum}).")
        "donationInBattle" -> Lang.tr("&cNo puedes donar Pokémon durante un combate.")
        "donationSuccess" -> Lang.tr("&aDonación completada: &f{pokemon} &ase ha añadido a la pool. ¡Gracias!")
        else -> Lang.tr("&cNo se ha podido completar el intercambio.")
    }
}
