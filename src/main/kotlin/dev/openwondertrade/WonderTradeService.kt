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
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

object WonderTradeService {
    private data class PendingTrade(val slot: Int, val pokemonId: UUID, val expiresAt: Long)
    private val pending = mutableMapOf<UUID, PendingTrade>()
    private lateinit var pool: PoolStore
    private lateinit var playerData: PlayerDataStore
    private lateinit var history: HistoryStore
    private lateinit var eventFile: Path
    private var eventEndsAt = 0L
    private var tickCounter = 0
    private var secondCounter = 0L

    fun initialize() {
        pending.clear()
        ConfigManager.load()
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        pool = PoolStore(directory.resolve("pool.json"))
        playerData = PlayerDataStore(directory.resolve("player-data.json"))
        history = HistoryStore(directory.resolve("history.json"))
        eventFile = directory.resolve("event.json")
        loadEvent()
        pool.load()
        playerData.load()
        history.load()
        WonderTradePlus.LOGGER.info("WonderTrade Plus {} preparado.", WonderTradePlus.MOD_VERSION)
    }

    fun reload() {
        ConfigManager.load()
        pool.load()
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

    fun helpComponent(): Component = colour(
        ConfigManager.string("messages.prefix", "") +
            ConfigManager.string("messages.help", "&7Usa &e/wt <slot> &7para iniciar un intercambio.")
    )

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

    fun poolSize(): Int = pool.size

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

    /** Especies y formas para el autocompletado de /wt admin addspecies (por ejemplo "vulpix" y "vulpix-alolan"). */
    fun speciesNames(): List<String> = speciesCache ?: PokemonSpecies.species.flatMap { species ->
        val base = species.resourceIdentifier.path
        listOf(base) + species.forms
            .map { it.name.lowercase() }
            .filter { it != "normal" }
            .distinct()
            .map { "$base-$it" }
    }.sorted().also { speciesCache = it }

    fun adminLevel(): Int = ConfigManager.int("admin.permissionLevel", 2).coerceIn(0, 4)

    fun info(text: String): Component = colour(ConfigManager.string("messages.prefix", "") + text)

    private fun addAdminEntry(pokemon: Pokemon, registryAccess: net.minecraft.core.RegistryAccess) {
        pool.add(PoolEntry.fromPokemon(pokemon, registryAccess, "Servidor"))
    }

    private fun trimAndSave() {
        pool.trim(
            ConfigManager.int("pool.maximumEntries", 5000).coerceAtLeast(1),
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

    /**
     * Crea un Pokémon de una especie concreta y lo añade al pool. Devuelve su nombre o null si la especie no existe.
     * Admite formas regionales con un guion: "vulpix-alola", "meowth-galar", "growlithe-hisui", "wooper-paldea".
     * Lanza IllegalArgumentException si la especie existe pero no tiene esa forma.
     */
    fun adminAddSpecies(player: ServerPlayer, speciesInput: String, level: Int, shiny: Boolean): String? {
        val cleaned = speciesInput.lowercase().substringAfter(':')
        val speciesName = cleaned.substringBefore('-')
        val formTag = if ('-' in cleaned) cleaned.substringAfter('-') else null

        val pokemon = runCatching {
            PokemonProperties().apply {
                this.species = speciesName
                this.level = level.coerceIn(1, 100)
                this.shiny = shiny
            }.create(player)
        }.getOrNull() ?: return null
        if (!pokemon.species.resourceIdentifier.path.equals(speciesName, ignoreCase = true)) return null

        if (formTag != null) {
            val form = pokemon.species.forms.firstOrNull {
                val name = it.name.lowercase()
                name == formTag || name.startsWith(formTag)
            } ?: throw IllegalArgumentException("${pokemon.species.resourceIdentifier.path} no tiene la forma '$formTag'.")
            pokemon.form = form
        }
        addAdminEntry(pokemon, player.server.registryAccess())
        trimAndSave()
        return pokemon.getDisplayName().string
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
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val registryAccess = player.server.registryAccess()
        val allowShiny = ConfigManager.bool("general.allowShinyPokemon", true)
        val allowSpecial = ConfigManager.bool("general.allowSpecialPokemon", true)
        val allowAlpha = ConfigManager.bool("general.allowAlphaPokemon", true)
        val chance = (chancePercent ?: autoSpecialChance(amount)).coerceIn(0.0, 100.0)

        fun allowed(candidate: Pokemon): Boolean {
            if (!allowShiny && candidate.shiny) return false
            if (!allowSpecial && WonderTradeRules.isSpecialSpecies(candidate)) return false
            if (!allowAlpha && candidate.isAlpha) return false
            return WonderTradeRules.isSpeciesAllowed(candidate.species.resourceIdentifier.toString())
        }

        var added = 0
        var special = 0
        repeat(amount) {
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

        // Bonus personal: cada especial ofrecido sube la probabilidad de recibir uno especial en un intercambio posterior.
        val bonusEnabled = ConfigManager.bool("bonus.enabled", true)
        val offeredRare = WonderTradeRules.isRare(offered)
        val bonusBefore = if (bonusEnabled) playerData.bonus(player.uuid) else 0.0
        val effectiveChance = bonusBefore + eventExtraChance()
        val forceSpecial = !offeredRare && effectiveChance > 0.0 && Random.nextDouble() * 100.0 < effectiveChance

        var chosenEntry: PoolEntry? = null
        var forcedIncoming: Pokemon? = null
        if (forceSpecial) {
            chosenEntry = choosePoolEntry(offered, true, player)
            if (chosenEntry == null) forcedIncoming = generateSpecialFallback(player, offered)
        }
        if (chosenEntry == null && forcedIncoming == null) chosenEntry = choosePoolEntry(offered, false, player)

        var fromPool = false
        val incoming: Pokemon = (forcedIncoming ?: if (chosenEntry != null) {
            val loaded = try {
                Pokemon.loadFromJSON(registryAccess, chosenEntry.pokemonJson.deepCopy())
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.error("No se pudo recuperar un Pokémon del pool ({}).", chosenEntry.id, exception)
                null
            }
            if (loaded != null) {
                fromPool = true
                loaded
            } else {
                generateFallback(player, offered)
            }
        } else {
            generateFallback(player, offered)
        }) ?: run {
            send(player, if (ConfigManager.bool("pool.randomFallback", true)) "generationFailed" else "noCandidate")
            return
        }

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

            if (chosenEntry != null) pool.remove(chosenEntry.id)
            if (ConfigManager.bool("pool.enabled", true) && ConfigManager.bool("pool.acceptDonations", true)) {
                pool.add(PoolEntry.fromPokemon(donation, registryAccess, player.gameProfile.name, now, player.uuid.toString()))
                pool.trim(
                    ConfigManager.int("pool.maximumEntries", 5000).coerceAtLeast(1),
                    ConfigManager.bool("pool.trimOldestFirst", true)
                )
            }
            pool.save()
            val incomingRare = WonderTradeRules.isRare(incoming)
            playerData.recordTrade(player.uuid, now, offeredRare, incomingRare)
            playerData.setName(player.uuid, player.gameProfile.name)
            var bonusAfter = bonusBefore
            if (bonusEnabled) {
                if (offeredRare) {
                    val gain = bonusGain(offered)
                    val maximum = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
                    bonusAfter = (bonusBefore + gain).coerceAtMost(maximum)
                } else if (incomingRare) {
                    bonusAfter = 0.0
                }
                playerData.setBonus(player.uuid, bonusAfter)
            }
            playerData.save()
            pending.remove(player.uuid)
            send(player, "success", mapOf("sent" to offered.getDisplayName().string, "received" to incoming.getDisplayName().string))
            announceDonation(player, donation)
            recordHistory(player, offered, incoming, fromPool, now)
            notifyDiscord(player, offered, incoming)
            if (bonusEnabled) {
                if (offeredRare && bonusAfter > bonusBefore) {
                    send(player, "bonusIncreased", mapOf("percent" to percentText(bonusAfter)))
                } else if (!offeredRare && incomingRare && bonusBefore > 0.0) {
                    send(player, "bonusReset", mapOf("percent" to percentText(0.0)))
                }
            }
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

    private fun choosePoolEntry(offered: Pokemon, forceSpecial: Boolean, player: ServerPlayer): PoolEntry? {
        if (!ConfigManager.bool("pool.enabled", true)) return null
        val all = pool.all().filter { entry ->
            // Anti-abuso: no recibir lo que uno mismo aportó y esperar un tiempo antes de poder recibir lo recién aportado.
            if (ConfigManager.bool("antiAbuse.excludeOwnDonations", true) &&
                (entry.donorUuid == player.uuid.toString() || entry.donorName.equals(player.gameProfile.name, ignoreCase = true))
            ) return@filter false
            val holdMinutes = ConfigManager.int("antiAbuse.donationHoldMinutes", 10)
            if (holdMinutes > 0 && entry.donorUuid.isNotEmpty() && System.currentTimeMillis() - entry.addedAt < holdMinutes * 60_000L) return@filter false
            if (!ConfigManager.bool("general.allowShinyPokemon", true) && entry.shiny) return@filter false
            if (!ConfigManager.bool("general.allowSpecialPokemon", true) && entry.special) return@filter false
            if (!ConfigManager.bool("general.allowAlphaPokemon", true) && entry.alpha) return@filter false
            if (!WonderTradeRules.isSpeciesAllowed(entry.species)) return@filter false
            if (ConfigManager.bool("trade.avoidSameSpecies", false) && entry.species.equals(offered.species.resourceIdentifier.toString(), true)) return@filter false
            if (forceSpecial && !WonderTradeRules.isRareEntry(entry)) return@filter false
            if (!forceSpecial && ConfigManager.bool("trade.matchSpecialTier", true) && entry.special != WonderTradeRules.isSpecial(offered)) return@filter false
            if (ConfigManager.bool("trade.matchLevel", false)) {
                val diff = ConfigManager.int("trade.levelDifference", 10).coerceAtLeast(0)
                if (kotlin.math.abs(entry.level - offered.level) > diff) return@filter false
            }
            true
        }
        if (all.isEmpty()) return null
        if (forceSpecial) return all.randomOrNull()
        val shinyMode = ConfigManager.string("trade.shinyMode", "MATCH_IF_AVAILABLE").uppercase()
        val shinyFiltered = when (shinyMode) {
            "REQUIRE_MATCH" -> all.filter { it.shiny == offered.shiny }
            "DISALLOW_SHINY" -> all.filter { !it.shiny }
            "MATCH_IF_AVAILABLE" -> {
                val matching = all.filter { it.shiny == offered.shiny }
                if (matching.isNotEmpty()) matching else all
            }
            else -> all
        }
        return (shinyFiltered.ifEmpty { if (shinyMode == "REQUIRE_MATCH") emptyList() else all }).randomOrNull()
    }

    private fun generateFallback(player: ServerPlayer, offered: Pokemon): Pokemon? {
        if (!ConfigManager.bool("pool.randomFallback", true)) return null
        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val shinyMode = ConfigManager.string("trade.shinyMode", "MATCH_IF_AVAILABLE").uppercase()
        val shinyBehavior = ConfigManager.string("fallback.shinyBehavior", "MATCH_INPUT").uppercase()
        val targetShiny: Boolean? = when (shinyBehavior) {
            "MATCH_INPUT" -> when (shinyMode) {
                "IGNORE" -> null
                "DISALLOW_SHINY" -> false
                else -> offered.shiny
            }
            "ALWAYS" -> true
            "NEVER" -> false
            "SERVER_RATE" -> null
            else -> offered.shiny
        }

        repeat(attempts) {
            try {
                val generated = PokemonProperties().apply {
                    this.species = "random"
                    this.level = Random.nextInt(low, high + 1)
                    this.shiny = targetShiny
                }.create(player)
                if (!ConfigManager.bool("general.allowShinyPokemon", true) && generated.shiny) return@repeat
                if (!ConfigManager.bool("general.allowSpecialPokemon", true) && WonderTradeRules.isSpecial(generated)) return@repeat
                if (!ConfigManager.bool("general.allowAlphaPokemon", true) && generated.isAlpha) return@repeat
                if (ConfigManager.bool("trade.matchSpecialTier", true) && WonderTradeRules.isSpecial(generated) != WonderTradeRules.isSpecial(offered)) return@repeat
                if (ConfigManager.bool("trade.avoidSameSpecies", false) && generated.species.resourceIdentifier == offered.species.resourceIdentifier) return@repeat
                if (shinyMode == "REQUIRE_MATCH" && generated.shiny != offered.shiny) return@repeat
                if (shinyMode == "DISALLOW_SHINY" && generated.shiny) return@repeat
                if (!WonderTradeRules.isSpeciesAllowed(generated.species.resourceIdentifier.toString())) return@repeat
                return generated
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.debug("Falló un intento de generación aleatoria.", exception)
            }
        }
        return null
    }

    // ------------------------------------------------------------ bonus por rareza

    /** Porcentaje que suma ofrecer este Pokémon, según `bonus.shiny`, `bonus.legendary`, etc. */
    fun bonusGain(pokemon: Pokemon): Double {
        val keys = WonderTradeRules.rarityKeys(pokemon)
        if (keys.isEmpty()) return 0.0
        val values = keys.map { ConfigManager.double("bonus.$it", 10.0).coerceAtLeast(0.0) }
        val base = if (ConfigManager.string("bonus.combineMode", "HIGHEST").equals("SUM", ignoreCase = true)) values.sum() else values.max()
        return base * eventBonusMultiplier()
    }

    // ------------------------------------------------------------ evento temporal

    private fun loadEvent() {
        eventEndsAt = runCatching {
            if (Files.exists(eventFile)) JsonParser.parseString(Files.readString(eventFile)).asJsonObject.get("endsAt").asLong else 0L
        }.getOrDefault(0L)
        if (eventEndsAt <= System.currentTimeMillis()) eventEndsAt = 0L
    }

    private fun saveEvent() {
        runCatching {
            Files.createDirectories(eventFile.parent)
            Files.writeString(eventFile, "{\"endsAt\": $eventEndsAt}")
        }.onFailure { WonderTradePlus.LOGGER.error("No se pudo guardar event.json.", it) }
    }

    fun eventActive(): Boolean = eventEndsAt > System.currentTimeMillis()
    private fun eventRemainingSeconds(): Long = ((eventEndsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    private fun eventCooldownMultiplier(): Double = if (eventActive()) ConfigManager.double("event.cooldownMultiplier", 0.5).coerceAtLeast(0.0) else 1.0
    private fun eventBonusMultiplier(): Double = if (eventActive()) ConfigManager.double("event.bonusGainMultiplier", 2.0).coerceAtLeast(0.0) else 1.0
    private fun eventExtraChance(): Double = if (eventActive()) ConfigManager.double("event.extraSpecialChance", 5.0).coerceIn(0.0, 100.0) else 0.0

    private fun eventValues(seconds: Long): Map<String, Any> = mapOf(
        "time" to formatDuration(seconds),
        "cooldown" to percentText(ConfigManager.double("event.cooldownMultiplier", 0.5)),
        "bonus" to percentText(ConfigManager.double("event.bonusGainMultiplier", 2.0)),
        "extra" to percentText(ConfigManager.double("event.extraSpecialChance", 5.0))
    )

    private fun broadcastTemplate(server: MinecraftServer, key: String, fallback: String, values: Map<String, Any>) {
        val template = ConfigManager.string("messages.$key", fallback)
        if (template.isBlank()) return
        server.playerList.broadcastSystemMessage(colour(ConfigManager.string("messages.prefix", "") + format(template, values)), false)
    }

    fun startEvent(server: MinecraftServer, minutes: Int) {
        eventEndsAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        saveEvent()
        broadcastTemplate(server, "eventStart", "&d✦ ¡Evento de Wonder Trade! &7Durante &f{time}&7.", eventValues(minutes * 60L))
        discordEvent("eventStartMessage", "✦ **¡Evento de Wonder Trade!** Durante {time}.", eventValues(minutes * 60L))
    }

    fun stopEvent(server: MinecraftServer): Boolean {
        if (!eventActive()) return false
        eventEndsAt = 0L
        saveEvent()
        broadcastTemplate(server, "eventEnd", "&7El evento de Wonder Trade ha terminado.", emptyMap())
        discordEvent("eventEndMessage", "El evento de Wonder Trade ha terminado.", emptyMap())
        return true
    }

    fun defaultEventMinutes(): Int = ConfigManager.int("event.defaultDurationMinutes", 60).coerceAtLeast(1)

    fun eventStatusComponent(): Component {
        if (!eventActive()) return configuredMessage("eventNone", "&7No hay ningún evento activo.")
        val template = ConfigManager.string("messages.eventStatus", "&dEvento activo: &7quedan &f{time}&7.")
        return colour(ConfigManager.string("messages.prefix", "") + format(template, eventValues(eventRemainingSeconds())))
    }

    /** Se llama cada tick del servidor; una vez por segundo revisa el evento, el mantenimiento del pool y las copias de seguridad. */
    fun tick(server: MinecraftServer) {
        tickCounter++
        if (tickCounter % 20 != 0) return
        secondCounter++

        if (eventEndsAt > 0L && System.currentTimeMillis() >= eventEndsAt) {
            eventEndsAt = 0L
            saveEvent()
            broadcastTemplate(server, "eventEnd", "&7El evento de Wonder Trade ha terminado.", emptyMap())
            discordEvent("eventEndMessage", "El evento de Wonder Trade ha terminado.", emptyMap())
        }

        val interval = ConfigManager.int("maintenance.intervalMinutes", 30).coerceAtLeast(1) * 60L
        if (secondCounter == 60L || secondCounter % interval == 0L) {
            runCatching { maintainPool(server) }.onFailure { WonderTradePlus.LOGGER.error("Falló el mantenimiento del pool.", it) }
        }
        val backupHours = ConfigManager.int("backup.intervalHours", 6)
        if (backupHours > 0 && secondCounter % (backupHours * 3600L) == 0L) backupNow()
    }

    // ------------------------------------------------------------ mantenimiento y copias

    /** Borra entradas caducadas y rellena el pool con Pokémon generados hasta el mínimo. Devuelve (caducadas, generadas). */
    fun maintainPool(server: MinecraftServer): Pair<Int, Int> {
        var expired = 0
        val days = ConfigManager.int("maintenance.expireAfterDays", 14)
        if (days > 0) {
            val limit = System.currentTimeMillis() - days * 86_400_000L
            expired = pool.removeWhere { it.addedAt > 0L && it.addedAt < limit }
            if (expired > 0) pool.save()
        }
        var generated = 0
        if (ConfigManager.bool("maintenance.autoRefill", true)) {
            val need = ConfigManager.int("maintenance.minimumEntries", 100) - pool.size
            val player = server.playerList.players.firstOrNull()
            if (need > 0 && player != null) {
                val batch = need.coerceAtMost(ConfigManager.int("maintenance.refillBatchSize", 50).coerceAtLeast(1))
                generated = adminFill(
                    player, batch, ConfigManager.double("maintenance.refillSpecialChance", 1.0),
                    ConfigManager.int("fallback.minimumLevel", 1), ConfigManager.int("fallback.maximumLevel", 100)
                ).added
            }
        }
        if (expired > 0 || generated > 0) {
            WonderTradePlus.LOGGER.info("Mantenimiento del pool: {} caducados, {} generados, {} en total.", expired, generated, pool.size)
        }
        return expired to generated
    }

    /** Copia pool.json, player-data.json, history.json y event.json a backups/<fecha>. Devuelve el nombre de la carpeta. */
    fun backupNow(): String? = runCatching {
        val directory = eventFile.parent
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val root = directory.resolve("backups")
        val target = root.resolve(stamp)
        Files.createDirectories(target)
        for (name in listOf("pool.json", "player-data.json", "history.json", "event.json")) {
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

    private fun discordEvent(key: String, fallback: String, values: Map<String, Any>) {
        if (!ConfigManager.bool("discord.enabled", false) || !ConfigManager.bool("discord.notifyEvents", true)) return
        val template = ConfigManager.string("discord.$key", fallback)
        if (template.isNotBlank()) discordPost(format(template, values))
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

    private fun notifyDiscord(player: ServerPlayer, offered: Pokemon, incoming: Pokemon) {
        if (!ConfigManager.bool("discord.enabled", false)) return
        if (ConfigManager.bool("discord.notifyDonations", true) && WonderTradeRules.isRare(offered)) {
            val template = ConfigManager.string("discord.donationMessage", "✨ **{player}** ha aportado **{pokemon}** (Nv. {level}, {category}) al pool.")
            discordPost(format(template, mapOf("player" to player.gameProfile.name, "pokemon" to plainName(offered), "level" to offered.level, "category" to plainCategory(offered))))
        }
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

    fun statsComponent(uuid: UUID, name: String): Component {
        val bonus = if (ConfigManager.bool("bonus.enabled", true)) playerData.bonus(uuid) else 0.0
        val lines = listOf(
            "&8━━ &dEstadísticas de &f$name &8━━",
            "&7Intercambios: &f${playerData.stat(uuid, "trades")}",
            "&7Especiales recibidos: &d${playerData.stat(uuid, "specialsReceived")}",
            "&7Especiales aportados: &d${playerData.stat(uuid, "specialsDonated")}",
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

    /** Genera un Pokémon especial (shiny o de especie especial) para el bonus del jugador. */
    private fun generateSpecialFallback(player: ServerPlayer, offered: Pokemon): Pokemon? {
        if (!ConfigManager.bool("pool.randomFallback", true)) return null
        val allowShiny = ConfigManager.bool("general.allowShinyPokemon", true)
        val allowSpecial = ConfigManager.bool("general.allowSpecialPokemon", true)
        val allowAlpha = ConfigManager.bool("general.allowAlphaPokemon", true)
        if (!allowShiny && !allowSpecial) return null
        val low = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
        val high = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(low, 100)
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val shinyOne = allowShiny && (!allowSpecial || Random.nextInt(3) == 0)

        repeat(attempts) {
            try {
                val generated = PokemonProperties().apply {
                    this.species = "random"
                    this.level = Random.nextInt(low, high + 1)
                    this.shiny = if (shinyOne) true else null
                }.create(player)
                if (!allowAlpha && generated.isAlpha) return@repeat
                if (!allowSpecial && WonderTradeRules.isSpecialSpecies(generated)) return@repeat
                if (!allowShiny && generated.shiny) return@repeat
                if (!shinyOne && !WonderTradeRules.isSpecialSpecies(generated)) return@repeat
                if (ConfigManager.bool("trade.avoidSameSpecies", false) && generated.species.resourceIdentifier == offered.species.resourceIdentifier) return@repeat
                if (!WonderTradeRules.isSpeciesAllowed(generated.species.resourceIdentifier.toString())) return@repeat
                return generated
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.debug("Falló un intento de generación especial.", exception)
            }
        }
        return null
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
        send(player, "bonusInfo", mapOf("percent" to percentText(value)))
    }

    private fun checkLimits(player: ServerPlayer): Boolean? {
        val bypassLevel = ConfigManager.int("trade.adminBypassPermissionLevel", 2).coerceIn(0, 4)
        val bypass = player.hasPermissions(bypassLevel)
        val cooldown = (ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0) * 60L * eventCooldownMultiplier()).toLong()
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
        "success" -> "&aIntercambio completado: {sent} → {received}."
        else -> "&cNo se ha podido completar el intercambio."
    }
}
