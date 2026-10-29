package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon

object WonderTradeRules {
    fun isSpecial(pokemon: Pokemon): Boolean {
        if (pokemon.isLegendary() || pokemon.isMythical() || pokemon.isUltraBeast()) return true
        return ConfigManager.strings("trade.specialLabels").any { label ->
            runCatching { pokemon.hasLabels(label) }.getOrDefault(false)
        }
    }

    fun isSpeciesAllowed(speciesId: String): Boolean {
        val normalized = speciesId.lowercase()
        val blacklist = ConfigManager.strings("general.speciesBlacklist").map { it.lowercase() }.toSet()
        if (normalized in blacklist || normalized.substringAfter(':') in blacklist) return false

        if (ConfigManager.bool("general.useSpeciesWhitelist", false)) {
            val whitelist = ConfigManager.strings("general.speciesWhitelist").map { it.lowercase() }.toSet()
            if (normalized !in whitelist && normalized.substringAfter(':') !in whitelist) return false
        }
        return true
    }

    fun validateOffer(pokemon: Pokemon): String? {
        if (!pokemon.tradeable) return "notTradeable"
        if (pokemon.currentHealth <= 0 && !ConfigManager.bool("general.allowFaintedPokemon", false)) return "faintedBlocked"
        if (pokemon.shiny && !ConfigManager.bool("general.allowShinyPokemon", true)) return "shinyBlocked"
        if (isSpecial(pokemon) && !ConfigManager.bool("general.allowSpecialPokemon", true)) return "specialBlocked"
        if (pokemon.isAlpha && !ConfigManager.bool("general.allowAlphaPokemon", true)) return "alphaBlocked"
        if (!ConfigManager.bool("general.allowHeldItems", true) && !pokemon.heldItem().isEmpty) return "heldItemBlocked"

        val minimum = ConfigManager.int("general.minimumOfferedLevel", 1)
        val maximum = ConfigManager.int("general.maximumOfferedLevel", 100)
        if (pokemon.level !in minimum..maximum) return "levelBlocked"
        if (!isSpeciesAllowed(pokemon.species.resourceIdentifier.toString())) return "speciesBlocked"
        return null
    }
}
