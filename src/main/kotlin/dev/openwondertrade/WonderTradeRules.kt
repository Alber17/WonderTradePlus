package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon

object WonderTradeRules {
    val pastParadox = setOf(
        "greattusk", "screamtail", "brutebonnet", "fluttermane", "slitherwing",
        "sandyshocks", "roaringmoon", "walkingwake", "gougingfire", "ragingbolt"
    )
    val futureParadox = setOf(
        "irontreads", "ironbundle", "ironhands", "ironjugulis", "ironmoth",
        "ironthorns", "ironvaliant", "ironleaves", "ironboulder", "ironcrown"
    )

    private val typeNames = mapOf(
        "normal" to ("&f" to "Normal"), "fire" to ("&c" to "Fuego"), "water" to ("&9" to "Agua"),
        "grass" to ("&a" to "Planta"), "electric" to ("&e" to "Eléctrico"), "ice" to ("&b" to "Hielo"),
        "fighting" to ("&4" to "Lucha"), "poison" to ("&5" to "Veneno"), "ground" to ("&6" to "Tierra"),
        "flying" to ("&3" to "Volador"), "psychic" to ("&d" to "Psíquico"), "bug" to ("&2" to "Bicho"),
        "rock" to ("&8" to "Roca"), "ghost" to ("&5" to "Fantasma"), "dragon" to ("&9" to "Dragón"),
        "dark" to ("&8" to "Siniestro"), "steel" to ("&7" to "Acero"), "fairy" to ("&d" to "Hada")
    )

    /** Claves de rareza del Pokémon para el bonus: shiny, ultraBeast, paradox, mythical, legendary y other. */
    fun rarityKeys(pokemon: Pokemon): List<String> {
        val keys = mutableListOf<String>()
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        var category = false
        if (pokemon.shiny) keys += "shiny"
        if (runCatching { pokemon.isUltraBeast() }.getOrDefault(false)) { keys += "ultraBeast"; category = true }
        if (id in pastParadox || id in futureParadox) { keys += "paradox"; category = true }
        if (runCatching { pokemon.isMythical() }.getOrDefault(false)) { keys += "mythical"; category = true }
        if (runCatching { pokemon.isLegendary() }.getOrDefault(false)) { keys += "legendary"; category = true }
        if (!category && isSpecial(pokemon)) keys += "other"
        return keys
    }

    /** Legendario, mítico, Ultraente o Paradoja (sin contar shiny). */
    fun isSpecialSpecies(pokemon: Pokemon): Boolean = categoryLabel(pokemon) != null || isSpecial(pokemon)

    /** "Especial" para filtros y pools: shiny, legendario, mítico, Ultraente o Paradoja. */
    fun isRare(pokemon: Pokemon): Boolean = pokemon.shiny || isSpecialSpecies(pokemon)

    fun isRareEntry(entry: PoolEntry): Boolean {
        val id = entry.species.substringAfter(':').lowercase()
        return entry.shiny || entry.special || id in pastParadox || id in futureParadox
    }

    /** Categoría real del Pokémon con color, o null si es un Pokémon normal. */
    fun categoryLabel(pokemon: Pokemon): String? {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        return when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> "&3Ultraente"
            id in pastParadox || id in futureParadox -> "&cParadoja"
            runCatching { pokemon.isMythical() }.getOrDefault(false) -> "&dMítico"
            runCatching { pokemon.isLegendary() }.getOrDefault(false) -> "&6Legendario"
            else -> null
        }
    }

    /** Tipos del Pokémon en español y con color, por ejemplo "&cFuego &7/ &3Volador". */
    fun typesLabel(pokemon: Pokemon): String? {
        val parts = runCatching {
            pokemon.types.map { type ->
                val entry = typeNames[type.name.lowercase()]
                if (entry != null) entry.first + entry.second else "&f" + type.name
            }
        }.getOrNull() ?: return null
        return if (parts.isEmpty()) null else parts.joinToString(" &7/ ")
    }

    /** Nombre de la forma (Alolan, Galarian...) o null si es la forma normal. */
    fun formLabel(pokemon: Pokemon): String? = runCatching {
        pokemon.form.name.takeUnless { it.equals("normal", ignoreCase = true) }
    }.getOrNull()

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
