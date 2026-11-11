package dev.openwondertrade

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.time.MonthDay

/**
 * Revisa la configuración y avisa de errores típicos (fechas, especies, enlaces, colores, roles...).
 * Solo informa: nunca modifica ni borra nada. Se ejecuta al arrancar y con «/wt reload».
 * Se puede desactivar con «configCheck.enabled»: false.
 */
object WonderTradeConfigCheck {
    private val types = setOf(
        "normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying",
        "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"
    )
    private val barColors = setOf("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE")
    private val hex = Regex("^#[0-9a-fA-F]{6}$")

    /** Devuelve la lista de problemas ya traducidos (vacía si todo está bien). */
    fun run(): List<String> {
        if (!ConfigManager.bool("configCheck.enabled", true)) return emptyList()
        val problems = mutableListOf<String>()
        runCatching { check(problems) }.onFailure {
            WonderTradePlus.LOGGER.error("No se pudo completar la revisión de la configuración.", it)
        }
        return problems
    }

    /** Escribe los problemas en la consola. */
    fun log(problems: List<String>) {
        problems.forEach { WonderTradePlus.LOGGER.warn("Config: {}", it.replace(Regex("§."), "")) }
    }

    private fun check(out: MutableList<String>) {
        // 1) config.json: JSON válido (si no, el mod usa los valores por defecto).
        val file = FabricLoader.getInstance().configDir.resolve("wondertradeplus/config.json")
        if (Files.exists(file)) {
            runCatching { Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject } }
                .onFailure { out += Lang.t("config.json tiene errores de formato y se están usando los valores por defecto: {0}", (it.message ?: "").take(120)) }
        }

        // 2) Copias de seguridad.
        if (ConfigManager.int("backup.keep", 10) < 1) out += Lang.tr("backup.keep debe ser 1 o más.")
        if (ConfigManager.int("backup.intervalHours", 6) < 0) out += Lang.tr("backup.intervalHours no puede ser negativo (0 = desactivado).")

        // 3) Eventos de temporada.
        for (node in ConfigManager.objects("seasonal.events")) {
            val id = node.get("id")?.asString?.trim().orEmpty()
            if (id.isEmpty()) { out += Lang.tr("Un evento de temporada no tiene «id»."); continue }
            for (key in listOf("startDate", "endDate")) {
                val text = node.get(key)?.asString.orEmpty()
                val valid = runCatching { MonthDay.parse("--" + text.trim().removePrefix("--").replace('/', '-')) }.isSuccess
                if (!valid) out += Lang.t("Evento «{0}»: {1} «{2}» no es una fecha válida (usa MM-dd, por ejemplo 12-25).", id, key, text)
            }
            node.getAsJsonArray("types")?.forEach { element ->
                val name = runCatching { element.asString.trim().lowercase() }.getOrDefault("")
                if (name !in types) out += Lang.t("Evento «{0}»: el tipo «{1}» no existe.", id, name)
            }
            for (key in listOf("extraSpecies", "excludeSpecies")) {
                node.getAsJsonArray(key)?.forEach { element ->
                    val name = runCatching { element.asString.trim().lowercase().substringAfter(':') }.getOrDefault("")
                    if (name.isNotEmpty() && runCatching { PokemonSpecies.getByName(name) }.getOrNull() == null)
                        out += Lang.t("Evento «{0}»: la especie «{1}» ({2}) no existe.", id, name, key)
                }
            }
            val poolSize = node.get("poolSize")?.let { runCatching { it.asInt }.getOrNull() } ?: 100
            if (poolSize !in 1..5000) out += Lang.t("Evento «{0}»: poolSize debe estar entre 1 y 5000.", id)
        }

        // 4) Barra de eventos.
        for (theme in listOf("christmas", "halloween")) {
            val base = "events.bossbar.$theme"
            val bar = ConfigManager.string("$base.barColor", "").trim().uppercase()
            if (bar.isNotEmpty() && bar !in barColors) out += Lang.t("{0}.barColor «{1}» no es un color válido (PINK, BLUE, RED, GREEN, YELLOW, PURPLE o WHITE).", base, bar)
            for (key in listOf("colors", "snowflakeColors", "symbolColors")) {
                ConfigManager.strings("$base.$key").filter { !hex.matches(it.trim()) }.forEach {
                    out += Lang.t("{0}.{1}: «{2}» no es un color hexadecimal válido (#RRGGBB).", base, key, it)
                }
            }
        }

        // 5) Discord.
        if (ConfigManager.bool("discord.enabled", false)) {
            val url = ConfigManager.string("discord.webhookUrl", "").trim()
            if (url.isEmpty()) out += Lang.tr("Discord está activado pero discord.webhookUrl está vacío.")
            else if (!Regex("^https://(canary\\.|ptb\\.)?discord(app)?\\.com/api/webhooks/.+").matches(url))
                out += Lang.tr("discord.webhookUrl no parece un webhook de Discord válido.")
        }
        for (key in listOf("mentionRoleId", "mentionRoleIdChristmas", "mentionRoleIdHalloween")) {
            val value = ConfigManager.string("discord.events.$key", "").trim()
            if (value.isNotEmpty() && !value.all { it.isDigit() }) out += Lang.t("discord.events.{0} debe ser el ID numérico del rol, no el nombre.", key)
        }

        // 6) Rangos.
        val shop = ConfigManager.string("ranks.shopUrl", "").trim()
        if (shop.isNotEmpty() && !shop.startsWith("http")) out += Lang.tr("ranks.shopUrl debe empezar por http:// o https://.")
        val ranks = ConfigManager.objects("ranks.list")
        val seen = mutableSetOf<String>()
        for (rank in ranks) {
            val id = rank.get("id")?.asString?.trim().orEmpty()
            if (id.isEmpty()) out += Lang.tr("Un rango no tiene «id».")
            else if (!seen.add(id.lowercase())) out += Lang.t("El rango «{0}» está repetido.", id)
            if (rank.get("permission")?.asString.isNullOrBlank()) out += Lang.t("El rango «{0}» no tiene permiso.", id)
            val url = rank.get("shopUrl")?.asString?.trim().orEmpty()
            if (url.isNotEmpty() && !url.startsWith("http")) out += Lang.t("El enlace de compra del rango «{0}» debe empezar por http:// o https://.", id)
        }
    }
}
