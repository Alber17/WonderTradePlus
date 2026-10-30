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
    private var tickCounter = 0
    private var secondCounter = 0L

    fun initialize() {
        pending.clear()
        ConfigManager.load()
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        pool = PoolStore(directory.resolve("pool.json"))
        playerData = PlayerDataStore(directory.resolve("player-data.json"))
        history = HistoryStore(directory.resolve("history.json"))
        pool.load()
        trimAndSave()
        playerData.load()
        history.load()
        WonderTradePlus.LOGGER.info("WonderTrade Plus {} preparado.", WonderTradePlus.MOD_VERSION)
    }

    fun reload() {
        ConfigManager.load()
        pool.load()
        trimAndSave()
        playerData.load()
        history.load()
        pending.clear()
    }

    fun request(player: ServerPlayer, slotNumber: Int) {
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
            ConfigManager.string("messages.helpChanceCommand", "&e/wt chance &7Consultar tu probabilidad de especial"),
            ConfigManager.string("messages.helpSoundsCommand", "&e/wt sonidos &7Configurar tus sonidos")
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
            lines += ConfigManager.string("messages.helpAdminHistoryCommand", "&e/wt admin history [jugador] &7Consultar historial")
            lines += ConfigManager.string("messages.helpAdminResetStatsCommand", "&e/wt admin resetstats <jugador|all> &7Reiniciar estadísticas")
            lines += ConfigManager.string("messages.helpAdminChanceCommand", "&e/wt chance <jugador> set <porcentaje> | reset &7Editar el bonus")
            lines += ConfigManager.string("messages.helpAdminReloadCommand", "&e/wt reload &7Recargar configuración y datos")
            lines += ConfigManager.string("messages.helpAdminMaintenanceCommand", "&e/wt admin maintenance &7Ejecutar mantenimiento")
            lines += ConfigManager.string("messages.helpAdminBackupCommand", "&e/wt admin backup &7Crear una copia de seguridad")
            lines += ConfigManager.string("messages.helpAdminDiscordCommand", "&e/wt admin discord test &7Probar webhook de Discord")
            lines += ConfigManager.string("messages.helpAdminClearCommand", "&e/wt admin clear &7Vaciar la pool")
        }
        return colour(ConfigManager.string("messages.prefix", "") + lines.joinToString("\n"))
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
        return colour(ConfigManager.string("messages.prefix", "") + summary)
    }

    fun configuredMessage(key: String, fallback: String): Component =
        colour(ConfigManager.string("messages.prefix", "") + ConfigManager.string("messages.$key", fallback))

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
        val weeklyLimit = ConfigManager.int("donation.weeklyLimit", 5).coerceAtLeast(0)
        if (weeklyLimit > 0 && playerData.donationsInLastWeek(player.uuid) >= weeklyLimit) {
            send(player, "donationWeeklyLimit", mapOf("limit" to weeklyLimit))
            return false
        }
        return true
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
        WonderTradeRules.validateOffer(pokemon)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return false
        }
        if (checkLimits(player) != null) return false
        return true
    }

    fun poolEntries(): List<PoolEntry> = pool.all()

    // ------------------------------------------------------------ administración

    private fun recordHistory(player: ServerPlayer, sent: Pokemon, received: Pokemon, fromPool: Boolean, now: Long) {
        if (!ConfigManager.bool("history.enabled", true)) return
        runCatching {
            history.add(
                HistoryEntry(
                    time = now,
                    player = player.gameProfile.name,
                    sentSpecies = sent.species.resourceIdentifier.path,
                    sentName = sent.getDisplayName().string,
                    sentLevel = sent.level,
                    sentShiny = sent.shiny,
                    receivedSpecies = received.species.resourceIdentifier.path,
                    receivedName = received.getDisplayName().string,
                    receivedLevel = received.level,
                    receivedShiny = received.shiny,
                    fromPool = fromPool
                ),
                ConfigManager.int("history.maxEntries", 1000).coerceAtLeast(1)
            )
            history.save()
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar el historial de intercambios.", it) }
    }

    fun historyEntries(player: String? = null): List<HistoryEntry> = history.recent(player)
    fun historyPlayers(): List<String> = history.players()
    fun poolDonors(): List<String> = pool.all().map { it.donorName }.distinct()

    private var speciesCache: List<String>? = null

    /** Solo especies base. Las formas regionales se eligen después como opciones (alolan, galarian…). */
    fun speciesNames(): List<String> = speciesCache ?: PokemonSpecies.species
        .map { it.resourceIdentifier.path }
        .distinct()
        .sorted()
        .also { speciesCache = it }

    fun adminLevel(): Int = ConfigManager.int("admin.permissionLevel", 2).coerceIn(0, 4)

    fun info(text: String): Component = colour(ConfigManager.string("messages.prefix", "") + text)

    private fun addAdminEntry(pokemon: Pokemon, registryAccess: net.minecraft.core.RegistryAccess) {
        pool.add(PoolEntry.fromPokemon(pokemon, registryAccess, "Servidor"))
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

        fun allowed(candidate: Pokemon): Boolean {
            if (!allowShiny && candidate.shiny) return false
            if (!allowSpecial && WonderTradeRules.isSpecialSpecies(candidate)) return false
            if (!allowAlpha && candidate.isAlpha) return false
            return WonderTradeRules.isSpeciesAllowed(candidate.species.resourceIdentifier.toString())
        }

        var added = 0
        var special = 0
        repeat(targetAmount) {
            var pokemon: Pokemon? = null
            var isSpecialOne = false

            if ((allowShiny || allowSpecial) && Random.nextDouble() * 100.0 < chance) {
                // Un tercio de los especiales son shiny; el resto, legendarios/míticos/Ultraentes/Paradoja.
                val shinyOne = allowShiny && (!allowSpecial || Random.nextInt(3) == 0)
                pokemon = generateRandom(player, low, high, attempts, if (shinyOne) true else null) { candidate ->
                    allowed(candidate) && (shinyOne || WonderTradeRules.isSpecialSpecies(candidate))
                }
                isSpecialOne = pokemon != null
            }
            if (pokemon == null) {
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

    fun removeFromPool(id: String): Boolean {
        val removed = pool.remove(id)
        if (removed) pool.save()
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
            playerData.setName(player.uuid, player.gameProfile.name)
            playerData.recordDonation(player.uuid, WonderTradeRules.isRare(donation), now)
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
        send(player, "donationSuccess", mapOf("pokemon" to donation.getDisplayName().string, "size" to pool.size))
        announceDonation(player, donation)
        notifyDiscordDonation(player, donation)
    }

    private fun performTrade(player: ServerPlayer, party: PokemonStore<*>, offered: Pokemon) {
        if (!ConfigManager.bool("general.allowInBattle", false) && Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.sendSystemMessage(colour("${ConfigManager.string("messages.prefix")} &cNo puedes intercambiar Pokémon durante un combate."))
            return
        }
        WonderTradeRules.validateOffer(offered)?.let { key ->
            send(player, key, mapOf("min" to ConfigManager.int("general.minimumOfferedLevel", 1), "max" to ConfigManager.int("general.maximumOfferedLevel", 100)))
            return
        }
        checkLimits(player)?.let { return }

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
        val bonusBefore = if (bonusEnabled) playerData.bonus(player.uuid) else 0.0
        val maximumBonus = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
        // Los intercambios normales suman +0,1% antes de tirar la probabilidad de ESTE intercambio.
        // Las aportaciones especiales suman su bonus al terminar y se aplican al siguiente WT normal.
        val normalGain = if (bonusEnabled && !offeredRare) bonusGain(offered) else 0.0
        val bonusForRoll = if (!offeredRare) (bonusBefore + normalGain).coerceAtMost(maximumBonus) else bonusBefore
        val effectiveChance = bonusForRoll
        val forceSpecial = !offeredRare && effectiveChance > 0.0 && Random.nextDouble() * 100.0 < effectiveChance

        // Si se activa el bonus, se prioriza un especial que ya exista en el pool.
        // Si no hay especiales disponibles, se entrega cualquier entrada válida del pool.
        // Nunca se inventan Pokémon ni se exige compatibilidad con el Pokémon ofrecido.
        var chosenEntry: PoolEntry? = if (forceSpecial) choosePoolEntry(forceSpecial = true, player = player) else null
        if (chosenEntry == null) chosenEntry = choosePoolEntry(forceSpecial = false, player = player)

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
            playerData.recordTrade(player.uuid, now, offeredRare, incomingRare)
            playerData.setName(player.uuid, player.gameProfile.name)
            var bonusAfter = bonusBefore
            if (bonusEnabled) {
                bonusAfter = when {
                    // Si un normal recibe un especial, el bonus se consume y vuelve a cero.
                    !offeredRare && incomingRare -> 0.0
                    // Un intercambio normal añade +0,1% (configurable), ya usado en la tirada actual.
                    !offeredRare -> bonusForRoll
                    // Ofrecer un especial añade +0,5% (configurable) para el siguiente WT normal.
                    else -> (bonusBefore + bonusGain(offered)).coerceAtMost(maximumBonus)
                }
                playerData.setBonus(player.uuid, bonusAfter)
            }
            playerData.save()
            pending.remove(player.uuid)
            if (incomingRare) WonderTradeGui.playSpecialSound(player, incoming.shiny)
            val bonusText = when {
                !bonusEnabled -> ""
                !offeredRare && incomingRare && bonusForRoll > 0.0 -> format(
                    ConfigManager.string("messages.successBonusReset", "&7Has recibido un especial y tu probabilidad se ha reiniciado a &d0%&7."),
                    mapOf("percent" to percentText(0.0))
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
            sendTradeSuccess(player, mapOf(
                "sent" to offered.getDisplayName().string,
                "received" to incoming.getDisplayName().string,
                "bonusText" to bonusText
            ), bonusText)
            announceReceivedRare(player, incoming)
            announceDonation(player, donation)
            recordHistory(player, offered, incoming, fromPool, now)
            notifyDiscord(player, offered, incoming)
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
        if (!ConfigManager.bool("pool.enabled", true)) return null
        val candidates = pool.all().filter { entry ->
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
            // Solo se aplica este filtro cuando el bonus ha activado un intento de especial.
            if (forceSpecial && !WonderTradeRules.isRareEntry(entry)) return@filter false
            true
        }
        return candidates.randomOrNull()
    }

    // ------------------------------------------------------------ bonus por rareza

    /**
     * Bonus por Pokémon ofrecido. Los Pokémon especiales suman el incremento base y,
     * si además son shiny, el extra configurable. El extra solo aplica a legendarios,
     * míticos, Ultraentes y Paradoja; un shiny de especie normal no lo recibe.
     */
    fun bonusGain(pokemon: Pokemon): Double {
        val rare = WonderTradeRules.isRare(pokemon)
        val key = if (rare) "bonus.specialTradeGain" else "bonus.normalTradeGain"
        val fallback = if (rare) 0.5 else 0.1
        val base = ConfigManager.double(key, fallback).coerceAtLeast(0.0)
        val specialShinyExtra = if (pokemon.shiny && WonderTradeRules.categoryLabel(pokemon) != null) {
            ConfigManager.double("bonus.specialShinyExtraGain", 0.2).coerceAtLeast(0.0)
        } else 0.0
        return base + specialShinyExtra
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
        tickCounter++
        if (tickCounter % 20 != 0) return
        secondCounter++
        server.playerList.players.forEach { WonderTradeGui.refreshCooldown(it) }


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
        // El segundo valor se conserva por compatibilidad con el comando de mantenimiento.
        return expired to 0
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

    // ------------------------------------------------------------ Discord

    private val httpClient: java.net.http.HttpClient by lazy { java.net.http.HttpClient.newHttpClient() }

    /** Envía un mensaje al webhook de Discord sin bloquear el servidor. `onResult` se llama desde otro hilo. */
    private fun discordPost(content: String, force: Boolean = false, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!force && !ConfigManager.bool("discord.enabled", false)) return
        val url = ConfigManager.string("discord.webhookUrl", "").trim()
        if (url.isEmpty()) {
            onResult?.invoke(false, "No hay webhookUrl configurada en la sección discord.")
            return
        }
        try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("content", content)
                addProperty("username", ConfigManager.string("discord.username", "WonderTrade"))
                // Evita que un apodo con @everyone o @here mencione a nadie.
                add("allowed_mentions", com.google.gson.JsonObject().apply { add("parse", com.google.gson.JsonArray()) })
            }
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding()).whenComplete { response, error ->
                if (error != null) {
                    WonderTradePlus.LOGGER.warn("No se pudo enviar el aviso a Discord: {}", error.message)
                    onResult?.invoke(false, "No se pudo conectar con Discord: ${error.message}")
                } else if (response.statusCode() >= 300) {
                    WonderTradePlus.LOGGER.warn("Discord respondió con el código {}.", response.statusCode())
                    onResult?.invoke(false, "Discord respondió con el código ${response.statusCode()}. Revisa la URL del webhook.")
                } else {
                    onResult?.invoke(true, "Mensaje enviado a Discord.")
                }
            }
        } catch (exception: Exception) {
            WonderTradePlus.LOGGER.warn("Webhook de Discord inválido: {}", exception.message)
            onResult?.invoke(false, "La URL del webhook no es válida.")
        }
    }

    fun discordTest(callback: (Boolean, String) -> Unit) {
        discordPost("✅ Prueba de conexión de WonderTrade Plus.", force = true, onResult = callback)
    }


    private fun plainName(pokemon: Pokemon): String {
        val name = pokemon.getDisplayName().string
        // Si el servidor no tiene las traducciones, el nombre sale como una clave ("cobblemon.species...").
        return if (name.contains('.')) pokemon.species.resourceIdentifier.path.replaceFirstChar { it.uppercase() } else name
    }

    private fun plainCategory(pokemon: Pokemon): String {
        val parts = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { parts += it.replace(Regex("&."), "") }
        if (pokemon.shiny) parts += "Shiny"
        return if (parts.isEmpty()) "Especial" else parts.joinToString(" · ")
    }

    private fun notifyDiscordDonation(player: ServerPlayer, offered: Pokemon) {
        if (!ConfigManager.bool("discord.enabled", false) || !ConfigManager.bool("discord.notifyDonations", true)) return
        if (!WonderTradeRules.isRare(offered)) return
        val template = ConfigManager.string("discord.donationMessage", "**{player}** ha aportado **{pokemon}** (Nv. {level}, {category}) al pool.")
        if (template.isNotBlank()) {
            discordPost(format(template, mapOf("player" to player.gameProfile.name, "pokemon" to plainName(offered), "level" to offered.level, "category" to plainCategory(offered))))
        }
    }

    private fun notifyDiscord(player: ServerPlayer, offered: Pokemon, incoming: Pokemon) {
        if (!ConfigManager.bool("discord.enabled", false)) return
        notifyDiscordDonation(player, offered)
        if (ConfigManager.bool("discord.notifyReceived", true) && WonderTradeRules.isRare(incoming)) {
            val template = ConfigManager.string("discord.receivedMessage", "🎉 **{player}** ha recibido **{pokemon}** (Nv. {level}, {category}) en un Wonder Trade.")
            discordPost(format(template, mapOf("player" to player.gameProfile.name, "pokemon" to plainName(incoming), "level" to incoming.level, "category" to plainCategory(incoming))))
        }
    }

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
    fun adminResetAllStats(): Int {
        val affected = playerData.resetAllStats()
        playerData.save()
        return affected
    }

    fun statsComponent(uuid: UUID, name: String): Component {
        val bonus = if (ConfigManager.bool("bonus.enabled", true)) playerData.bonus(uuid) else 0.0
        val lines = listOf(
            "&8━━ &dEstadísticas de &f$name &8━━",
            "&7Intercambios: &f${playerData.stat(uuid, "trades")}",
            "&7Especiales recibidos: &d${playerData.stat(uuid, "specialsReceived")}",
            "&7Especiales aportados: &d${playerData.stat(uuid, "specialsDonated")}",
            "&7Donaciones directas: &f${playerData.stat(uuid, "donations")}",
            "&7Probabilidad acumulada: &d${percentText(bonus)}%"
        )
        return colour(lines.joinToString("\n"))
    }

    fun topComponent(category: String, viewer: UUID?): Component {
        val (key, title) = when (category) {
            "received" -> "specialsReceived" to "Especiales recibidos"
            "donated" -> "specialsDonated" to "Especiales aportados"
            else -> "trades" to "Intercambios"
        }
        val ranking = playerData.ranking(key)
        val lines = mutableListOf("&8━━ &dTop Wonder Trade &8— &f$title &8━━")
        if (ranking.isEmpty()) {
            lines += "&7Todavía no hay datos."
        } else {
            val colours = listOf("&6", "&7", "&c")
            ranking.take(10).forEachIndexed { index, (_, name, value) ->
                lines += "${colours.getOrElse(index) { "&8" }}${index + 1}. &f$name &8— &d$value"
            }
            if (viewer != null) {
                val position = ranking.indexOfFirst { it.first == viewer }
                if (position >= 10) lines += "&7Tu posición: &f#${position + 1} &8— &d${ranking[position].third}"
            }
        }
        return colour(lines.joinToString("\n"))
    }

    private fun percentText(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else String.format("%.1f", value)

    /** Para admins: busca la probabilidad acumulada de un jugador (conectado o que ya haya intercambiado). */
    fun lookupBonus(server: MinecraftServer, name: String): Pair<String, Double>? {
        val online = server.playerList.getPlayerByName(name)
        val uuid = online?.uuid ?: playerData.findByName(name) ?: return null
        val shownName = online?.gameProfile?.name ?: name
        val value = if (ConfigManager.bool("bonus.enabled", true)) playerData.bonus(uuid) else 0.0
        return shownName to value
    }

    fun bonusMaximum(): String = percentText(ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0))
    fun bonusPercentText(value: Double): String = percentText(value)
    fun knownPlayerNames(): List<String> = playerData.names()

    /** Muestra al jugador su probabilidad acumulada de recibir un especial. */
    fun showBonus(player: ServerPlayer) {
        val value = if (ConfigManager.bool("bonus.enabled", true)) playerData.bonus(player.uuid) else 0.0
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
    }

    fun cooldownRemainingSeconds(player: ServerPlayer): Long {
        val bypassLevel = ConfigManager.int("trade.adminBypassPermissionLevel", 2).coerceIn(0, 4)
        val bypass = player.hasPermissions(bypassLevel)
        val cooldown = (ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0) * 60L).toLong()
        return playerData.cooldownRemainingSeconds(player.uuid, System.currentTimeMillis(), cooldown, bypass)
    }

    fun cooldownDisplayText(player: ServerPlayer): String {
        val remaining = cooldownRemainingSeconds(player)
        return if (remaining <= 0L) ConfigManager.string("messages.cooldownReady", "&aDisponible") else formatDuration(remaining)
    }

    private fun checkLimits(player: ServerPlayer): Boolean? {
        val bypassLevel = ConfigManager.int("trade.adminBypassPermissionLevel", 2).coerceIn(0, 4)
        val bypass = player.hasPermissions(bypassLevel)
        val cooldown = (ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0) * 60L).toLong()
        val limit = ConfigManager.int("trade.dailyLimit", 0).coerceAtLeast(0)
        val check = playerData.check(player.uuid, System.currentTimeMillis(), cooldown, limit, bypass)
        if (check.cooldownSeconds > 0L) {
            send(player, "cooldown", mapOf("time" to formatDuration(check.cooldownSeconds)))
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
        val categoryText = when (category) {
            "ultraBeast" -> "Ultraente"
            "paradox" -> "Paradoja"
            "mythical" -> "Mítico"
            "legendary" -> "Legendario"
            else -> "shiny"
        }
        val key = when (category) {
            "ultraBeast" -> "broadcastReceivedUltraBeast"
            "paradox" -> "broadcastReceivedParadox"
            "mythical" -> "broadcastReceivedMythical"
            "legendary" -> "broadcastReceivedLegendary"
            else -> "broadcastReceivedShiny"
        }
        val fallback = when (category) {
            "ultraBeast" -> "&3{player} ha conseguido un Pokémon Ultraente{shinySuffix} en el WonderTrade: &f{pokemon}&3!"
            "paradox" -> "&c{player} ha conseguido un Pokémon Paradoja{shinySuffix} en el WonderTrade: &f{pokemon}&c!"
            "mythical" -> "&d{player} ha conseguido un Pokémon Mítico{shinySuffix} en el WonderTrade: &f{pokemon}&d!"
            "legendary" -> "&6{player} ha conseguido un Pokémon Legendario{shinySuffix} en el WonderTrade: &f{pokemon}&6!"
            else -> "&d{player} ha conseguido un shiny en el WonderTrade: &f{pokemon}&d!"
        }
        val suffix = if (pokemon.shiny && category != "shiny") " shiny" else ""
        val message = format(ConfigManager.string("messages.$key", fallback), mapOf(
            "player" to player.gameProfile.name,
            "pokemon" to pokemon.getDisplayName().string,
            "category" to categoryText,
            "shinySuffix" to suffix
        ))
        player.server.playerList.broadcastSystemMessage(colour(ConfigManager.string("messages.prefix", "") + message), false)
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
        val template = ConfigManager.string("messages.$key", "")
        if (template.isBlank()) return
        val content = format(template, mapOf("player" to player.gameProfile.name, "pokemon" to pokemon.getDisplayName().string))
        player.server.playerList.broadcastSystemMessage(colour(ConfigManager.string("messages.prefix") + content), false)
    }

    /** Envía el éxito y el cambio del bonus en una sola línea; mantiene compatibles mensajes personalizados antiguos. */
    private fun sendTradeSuccess(player: ServerPlayer, values: Map<String, Any>, bonusText: String) {
        val fallback = "&aIntercambio completado: has recibido &f{received}&a. {bonusText}"
        val template = ConfigManager.string("messages.success", fallback)
        var message = format(template, values)
        if (!template.contains("{bonusText}") && bonusText.isNotBlank()) message += " $bonusText"
        player.sendSystemMessage(colour(ConfigManager.string("messages.prefix", "") + message))
    }

    fun send(player: ServerPlayer, key: String, values: Map<String, Any> = emptyMap()) {
        val fallback = defaultMessage(key)
        val template = ConfigManager.string("messages.$key", fallback)
        player.sendSystemMessage(colour(ConfigManager.string("messages.prefix", "") + format(template, values)))
    }

    fun format(value: String, replacements: Map<String, Any>): String {
        var result = value
        replacements.forEach { (key, replacement) -> result = result.replace("{$key}", replacement.toString()) }
        return result
    }

    fun colour(value: String): Component = Component.literal(value.replace('&', '§'))

    private fun defaultMessage(key: String): String = when (key) {
        "emptySlot" -> "&cNo tienes ningún Pokémon en ese slot."
        "invalidSlot" -> "&cEl slot debe estar entre 1 y 6."
        "confirmationExpired" -> "&cLa confirmación ha caducado."
        "nothingToConfirm" -> "&cNo tienes ningún intercambio pendiente."
        "success" -> "&aIntercambio completado: has recibido &f{received}&a. {bonusText}"
        "donationDisabled" -> "&cLas donaciones a la pool están desactivadas."
        "donationPoolFull" -> "&cNo puedes donar porque la pool ya está completa ({size}/{maximum})."
        "donationInBattle" -> "&cNo puedes donar Pokémon durante un combate."
        "donationSuccess" -> "&aDonación completada: &f{pokemon} &ase ha añadido a la pool. ¡Gracias!"
        else -> "&cNo se ha podido completar el intercambio."
    }
}
