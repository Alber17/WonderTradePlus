package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
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

    fun initialize() {
        pending.clear()
        ConfigManager.load()
        val directory = FabricLoader.getInstance().configDir.resolve("wondertradeplus")
        pool = PoolStore(directory.resolve("pool.json"))
        playerData = PlayerDataStore(directory.resolve("player-data.json"))
        pool.load()
        playerData.load()
        WonderTradePlus.LOGGER.info("WonderTrade Plus {} preparado.", WonderTradePlus.MOD_VERSION)
    }

    fun reload() {
        ConfigManager.load()
        pool.load()
        playerData.load()
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
        val incoming: Pokemon = if (chosenEntry != null) {
            try {
                Pokemon.loadFromJSON(registryAccess, chosenEntry.pokemonJson.deepCopy())
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.error("No se pudo recuperar un Pokémon del pool ({}).", chosenEntry.id, exception)
                null
            } ?: generateFallback(player, offered)
        } else {
            generateFallback(player, offered)
        } ?: run {
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
        val cooldown = ConfigManager.int("trade.cooldownMinutes", 10).coerceAtLeast(0).toLong() * 60L
        val limit = ConfigManager.int("trade.dailyLimit", 0).coerceAtLeast(0)
        val check = playerData.check(player.uuid, System.currentTimeMillis(), cooldown, limit, bypass)
        if (check.cooldownSeconds > 0L) {
            val minutes = check.cooldownSeconds / 60L
            val seconds = check.cooldownSeconds % 60L
            send(player, "cooldown", mapOf("time" to if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"))
            return true
        }
        if (check.dailyLimitReached) {
            send(player, "dailyLimit", mapOf("limit" to limit))
            return true
        }
        return null
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
