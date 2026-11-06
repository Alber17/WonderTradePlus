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

    /** Categoría de especie para eventos: ultraBeast, paradox, mythical, legendary o null. */
    fun speciesCategoryKey(pokemon: Pokemon): String? {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        return when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> "ultraBeast"
            id in pastParadox || id in futureParadox -> "paradox"
            runCatching { pokemon.isMythical() }.getOrDefault(false) -> "mythical"
            runCatching { pokemon.isLegendary() }.getOrDefault(false) -> "legendary"
            else -> null
        }
    }

    /** Legendario, mítico, Ultraente o Paradoja (sin contar shiny). */
    fun isSpecialSpecies(pokemon: Pokemon): Boolean = categoryLabel(pokemon) != null || isSpecial(pokemon)

    /** "Especial" para filtros y pools: shiny, legendario, mítico, Ultraente o Paradoja. */
    /**
     * Talla del Pokémon (XS, S, M, L, XL) según su modificador de tamaño. Los límites se configuran en
     * pool.size.xsMax / sMax / mMax / lMax (por encima del último es XL).
     */
    fun sizeLabel(pokemon: Pokemon): String {
        val scale = runCatching { pokemon.scaleModifier.toDouble() }.getOrDefault(1.0)
        return when {
            scale < ConfigManager.double("pool.size.xsMax", 0.8) -> "XS"
            scale < ConfigManager.double("pool.size.sMax", 0.95) -> "S"
            scale < ConfigManager.double("pool.size.mMax", 1.05) -> "M"
            scale < ConfigManager.double("pool.size.lMax", 1.2) -> "L"
            else -> "XL"
        }
    }

    fun isRare(pokemon: Pokemon): Boolean = pokemon.shiny || isSpecialSpecies(pokemon)

    fun isRareEntry(entry: PoolEntry): Boolean {
        val id = entry.species.substringAfter(':').lowercase()
        return entry.shiny || entry.special || id in pastParadox || id in futureParadox
    }

    /** Categoría real del Pokémon con color, o null si es un Pokémon normal. */
    fun categoryLabel(pokemon: Pokemon): String? {
        val id = pokemon.species.resourceIdentifier.path.lowercase()
        return when {
            runCatching { pokemon.isUltraBeast() }.getOrDefault(false) -> Lang.tr("&3Ultraente")
            id in pastParadox || id in futureParadox -> Lang.tr("&cParadoja")
            runCatching { pokemon.isMythical() }.getOrDefault(false) -> Lang.tr("&dMítico")
            runCatching { pokemon.isLegendary() }.getOrDefault(false) -> Lang.tr("&6Legendario")
            else -> null
        }
    }

    /** Tipos del Pokémon en español y con color, por ejemplo "&cFuego &7/ &3Volador". */
    fun typesLabel(pokemon: Pokemon): String? {
        val parts = runCatching {
            pokemon.types.map { type ->
                val entry = typeNames[type.name.lowercase()]
                if (entry != null) entry.first + Lang.tr(entry.second) else "&f" + type.name
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

    /** Nombre del tipo en el idioma activo (ice → Hielo), sin colores. */
    fun typeName(id: String): String = typeNames[id.lowercase()]?.let { Lang.tr(it.second) } ?: id

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

    /**
     * Formas y aspectos bloqueados (general.formBlacklist / general.aspectBlacklist). Sirven, por ejemplo, para texturas de un
     * resource pack: cada textura se activa con un aspecto o una forma. Se admite «nombre» (cualquier especie) o «especie:nombre».
     */
    fun isVariantAllowed(pokemon: Pokemon): Boolean {
        val forms = ConfigManager.strings("general.formBlacklist").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val aspects = ConfigManager.strings("general.aspectBlacklist").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (forms.isEmpty() && aspects.isEmpty()) return true
        val species = pokemon.species.resourceIdentifier.path.lowercase()
        fun blocked(list: List<String>, value: String): Boolean =
            list.any { it == value || it == "$species:$value" || it == "cobblemon:$species:$value" }
        if (forms.isNotEmpty()) {
            val form = runCatching { pokemon.form.name.lowercase() }.getOrDefault("")
            if (form.isNotEmpty() && blocked(forms, form)) return false
        }
        if (aspects.isNotEmpty()) {
            val own = runCatching { pokemon.aspects.map { it.lowercase() } }.getOrDefault(emptyList())
            if (own.any { blocked(aspects, it) }) return false
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
        if (!isVariantAllowed(pokemon)) return "speciesBlocked"
        if (!WonderTradeSeasons.allows(pokemon)) return "seasonTypeBlocked"
        return null
    }
}
