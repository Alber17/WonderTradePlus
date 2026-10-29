package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.storage.PokemonStore
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.random.Random

object WonderTradeService {
    private data class PendingTrade(val slot: Int, val pokemonId: UUID, val expiresAt: Long)
    private val pending = mutableMapOf<UUID, PendingTrade>()
    private lateinit var pool: PoolStore
    private lateinit var playerData: PlayerDataStore
    private lateinit var history: HistoryStore

    fun initialize() {
        pending.clear()
        ConfigManager.load()
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        pool = PoolStore(directory.resolve("pool.json"))
        playerData = PlayerDataStore(directory.resolve("player-data.json"))
        history = HistoryStore(directory.resolve("history.json"))
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

    /** Nombres de especie para el autocompletado de /wt admin addspecies. */
    fun speciesNames(): List<String> =
        PokemonSpecies.species.map { it.resourceIdentifier.path }.sorted()

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

    /** Crea un Pokémon de una especie concreta y lo añade al pool. Devuelve su nombre o null si la especie no existe. */
    fun adminAddSpecies(player: ServerPlayer, species: String, level: Int, shiny: Boolean): String? {
        val pokemon = runCatching {
            PokemonProperties().apply {
                this.species = species
                this.level = level.coerceIn(1, 100)
                this.shiny = shiny
            }.create(player)
        }.getOrNull() ?: return null
        val created = pokemon.species.resourceIdentifier.path
        if (!created.equals(species.substringAfter(':'), ignoreCase = true)) return null
        addAdminEntry(pokemon, player.server.registryAccess())
        trimAndSave()
        return pokemon.getDisplayName().string
    }

    /** Genera Pokémon aleatorios respetando las listas y permisos de la configuración. Devuelve cuántos se añadieron. */
    fun adminFill(player: ServerPlayer, amount: Int, minimumLevel: Int, maximumLevel: Int): Int {
        val low = minimumLevel.coerceIn(1, 100)
        val high = maximumLevel.coerceIn(low, 100)
        val attempts = ConfigManager.int("fallback.speciesGenerationAttempts", 128).coerceIn(1, 1000)
        val registryAccess = player.server.registryAccess()
        var added = 0
        repeat(amount) {
            var tries = 0
            while (tries < attempts) {
                tries++
                val candidate = try {
                    PokemonProperties().apply {
                        this.species = "random"
                        this.level = Random.nextInt(low, high + 1)
                    }.create(player)
                } catch (exception: Exception) {
                    continue
                }
                if (!ConfigManager.bool("general.allowShinyPokemon", true) && candidate.shiny) continue
                if (!ConfigManager.bool("general.allowSpecialPokemon", true) && WonderTradeRules.isSpecial(candidate)) continue
                if (!ConfigManager.bool("general.allowAlphaPokemon", true) && candidate.isAlpha) continue
                if (!WonderTradeRules.isSpeciesAllowed(candidate.species.resourceIdentifier.toString())) continue
                addAdminEntry(candidate, registryAccess)
                added++
                break
            }
        }
        if (added > 0) trimAndSave()
        return added
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

        val chosenEntry = choosePoolEntry(offered)
        var fromPool = false
        val incoming: Pokemon = (if (chosenEntry != null) {
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
                pool.add(PoolEntry.fromPokemon(donation, registryAccess, player.gameProfile.name, now))
                pool.trim(
                    ConfigManager.int("pool.maximumEntries", 5000).coerceAtLeast(1),
                    ConfigManager.bool("pool.trimOldestFirst", true)
                )
            }
            pool.save()
            playerData.recordTrade(player.uuid, now)
            playerData.save()
            pending.remove(player.uuid)
            send(player, "success", mapOf("sent" to offered.getDisplayName().string, "received" to incoming.getDisplayName().string))
            announceDonation(player, donation)
            recordHistory(player, offered, incoming, fromPool, now)
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

    private fun choosePoolEntry(offered: Pokemon): PoolEntry? {
        if (!ConfigManager.bool("pool.enabled", true)) return null
        val all = pool.all().filter { entry ->
            if (!ConfigManager.bool("general.allowShinyPokemon", true) && entry.shiny) return@filter false
            if (!ConfigManager.bool("general.allowSpecialPokemon", true) && entry.special) return@filter false
            if (!ConfigManager.bool("general.allowAlphaPokemon", true) && entry.alpha) return@filter false
            if (!WonderTradeRules.isSpeciesAllowed(entry.species)) return@filter false
            if (ConfigManager.bool("trade.avoidSameSpecies", false) && entry.species.equals(offered.species.resourceIdentifier.toString(), true)) return@filter false
            if (ConfigManager.bool("trade.matchSpecialTier", true) && entry.special != WonderTradeRules.isSpecial(offered)) return@filter false
            if (ConfigManager.bool("trade.matchLevel", false)) {
                val diff = ConfigManager.int("trade.levelDifference", 10).coerceAtLeast(0)
                if (kotlin.math.abs(entry.level - offered.level) > diff) return@filter false
            }
            true
        }
        if (all.isEmpty()) return null
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

    private fun checkLimits(player: ServerPlayer): Boolean? {
        val bypassLevel = ConfigManager.int("trade.adminBypassPermissionLevel", 2).coerceIn(0, 4)
        val bypass = player.hasPermissions(bypassLevel)
        val cooldown = ConfigManager.int("trade.cooldownMinutes", 720).coerceAtLeast(0).toLong() * 60L
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

    private val pastParadox = setOf(
        "greattusk", "screamtail", "brutebonnet", "fluttermane", "slitherwing",
        "sandyshocks", "roaringmoon", "walkingwake", "gougingfire", "ragingbolt"
    )
    private val futureParadox = setOf(
        "irontreads", "ironbundle", "ironhands", "ironjugulis", "ironmoth",
        "ironthorns", "ironvaliant", "ironleaves", "ironboulder", "ironcrown"
    )

    /** Devuelve "UltraBeast", "PastParadox", "FutureParadox", "Special" o "" si es un Pokémon normal. */
    private fun donationCategory(pokemon: Pokemon): String {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        return when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> "UltraBeast"
            id in pastParadox -> "PastParadox"
            id in futureParadox -> "FutureParadox"
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
