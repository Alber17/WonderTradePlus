package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Idiomas del mod. El idioma principal es es_es (español de España); también vienen en_us, fr_fr (francés),
 * it_it (italiano), pt_pt (portugués de Portugal) y de_de (alemán).
 *
 * Cada idioma es un archivo `config/wondertradeplus/lang/<código>.json` con dos tipos de entradas:
 *  - rutas de configuración (`messages.success`, `discord.donationMessage`, `events.names.risk`...): el texto de ese ajuste;
 *  - textos del propio mod (menús, ayudas, avisos): la clave es el texto original en español y el valor su traducción.
 * Para añadir un idioma basta con copiar es_es.json o en_us.json con otro nombre (por ejemplo nl_nl.json), traducir los
 * valores y poner `general.language` a ese código. Lo que falte en un idioma se muestra en español.
 * Los textos que pongas a mano en config.json (`messages.*`...) tienen prioridad sobre cualquier idioma.
 */
object Lang {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val bundled = listOf("es_es", "en_us", "fr_fr", "it_it", "pt_pt", "de_de")
    const val DEFAULT = "es_es"

    private var directory: Path? = null
    private var base: Map<String, String> = emptyMap()      // es_es (idioma principal)
    private var code: String = DEFAULT                      // idioma por defecto del servidor (general.language)
    private val cache = HashMap<String, Map<String, String>>()

    /** Idioma con el que se redactan los textos en este momento (null = el del servidor). Solo se usa en el hilo del servidor. */
    private var context: String? = null

    /** Idioma por defecto del servidor (consola, Discord y jugadores sin idioma propio). */
    fun current(): String = code

    private fun effective(): String = context ?: code

    /** Locale de Java del idioma activo (para nombres de días y meses): es_es → es-ES, en_us → en-US. */
    fun locale(): java.util.Locale {
        val parts = effective().split('_')
        return if (parts.size >= 2) java.util.Locale(parts[0], parts[1].uppercase()) else java.util.Locale(parts[0])
    }

    /** Textos de ajustes del idioma principal (para limpiar config.json de textos sin personalizar). */
    fun baseEntries(): Map<String, String> = base.filterKeys { it.contains('.') && !it.contains(' ') && it.first().isLowerCase() }

    private fun readBundled(name: String): JsonObject? =
        Lang::class.java.getResourceAsStream("/lang/$name.json")?.bufferedReader(Charsets.UTF_8)?.use { JsonParser.parseReader(it).asJsonObject }

    private fun readFile(path: Path): JsonObject? =
        runCatching { Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject } }
            .onFailure { WonderTradePlus.LOGGER.error("No se pudo leer el idioma {}: {}", path.fileName, it.message) }.getOrNull()

    private fun toMap(json: JsonObject?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        json?.entrySet()?.forEach { (key, value) ->
            if (!key.startsWith("_") && value.isJsonPrimitive) result[key] = value.asString
        }
        return result
    }

    /** Textos de un idioma (archivo de la carpeta lang, o el incluido en el mod). Vacío si no existe. */
    private fun map(language: String): Map<String, String> {
        if (language == DEFAULT) return base
        return cache.getOrPut(language) {
            val folder = directory
            val json = (if (folder != null) readFile(folder.resolve("$language.json")) else null) ?: readBundled(language)
            toMap(json)
        }
    }

    /**
     * Prepara la carpeta lang (copia los idiomas incluidos si faltan y les añade las claves nuevas sin tocar lo que
     * hayas editado) y carga es_es y el idioma por defecto del servidor (`general.language`).
     */
    private val legacyTexts: Map<String, List<String>> = mapOf(
        "discord.receivedMessage" to listOf(
            "🎉 **{player}** ha recibido **{pokemon}** (Nv. {level}, {category}) en un Wonder Trade.",
            "🎉 **{player}** received **{pokemon}** (Lv. {level}, {category}) in a Wonder Trade.",
            "**{player}** ha recibido **{pokemon}** (Nv. {level}, {category}) en un Wonder Trade.",
            "**{player}** received **{pokemon}** (Lv. {level}, {category}) in a Wonder Trade.",
            "**{player}** a reçu **{pokemon}** (Niv. {level}, {category}) lors d'un Wonder Trade.",
            "**{player}** ha ricevuto **{pokemon}** (Lv. {level}, {category}) in un Wonder Trade.",
            "**{player}** recebeu **{pokemon}** (Nv. {level}, {category}) numa troca do Wonder Trade.",
            "**{player}** hat **{pokemon}** (Lv. {level}, {category}) in einem Wonder Trade erhalten.",
            "{player} ha recibido un {pokemon} al pool del WonderTrade.",
            "{player} ha recibido un {pokemon} en el WonderTrade.",
            "{player} received a {pokemon} into the WonderTrade pool.",
            "{player} received a {pokemon} in WonderTrade.",
            "{player} a reçu un {pokemon} dans le pool de WonderTrade.",
            "{player} a reçu un {pokemon} dans WonderTrade.",
            "{player} ha ricevuto un {pokemon} nella pool di WonderTrade.",
            "{player} ha ricevuto un {pokemon} in WonderTrade.",
            "{player} recebeu um {pokemon} para a pool do WonderTrade.",
            "{player} recebeu um {pokemon} no WonderTrade.",
            "{player} hat ein {pokemon} in den WonderTrade-Pool erhalten.",
            "{player} hat ein {pokemon} im WonderTrade erhalten."
        ),
        "discord.donationMessage" to listOf(
            "**{player}** ha aportado **{pokemon}** (Nv. {level}, {category}) al pool de Wonder Trade.",
            "**{player}** contributed **{pokemon}** (Lv. {level}, {category}) to the Wonder Trade pool.",
            "**{player}** a contribué avec **{pokemon}** (Niv. {level}, {category}) au pool de Wonder Trade.",
            "**{player}** ha contribuito con **{pokemon}** (Lv. {level}, {category}) alla pool del Wonder Trade.",
            "**{player}** contribuiu com **{pokemon}** (Nv. {level}, {category}) para a pool do Wonder Trade.",
            "**{player}** hat **{pokemon}** (Lv. {level}, {category}) zum Wonder-Trade-Pool beigetragen."
        ),
        "discord.eventStartMessage" to listOf("🎉 **Evento iniciado: {name}**\n{details}\n⏱️ Dura {duration} · termina <t:{endEpoch}:R> (<t:{endEpoch}:t>, a tu hora)", "🎉 **Event started: {name}**\n{details}\n⏱️ Lasts {duration} · ends <t:{endEpoch}:R> (<t:{endEpoch}:t>, in your local time)"),
        "discord.eventEndMessage" to listOf("🏁 **Ha terminado el evento: {name}**", "🏁 **The event has ended: {name}**"),
        "discord.eventSoonMessage" to listOf("⏰ **{name}** empieza <t:{startEpoch}:R> (<t:{startEpoch}:F>, a tu hora)", "⏰ **{name}** starts <t:{startEpoch}:R> (<t:{startEpoch}:F>, in your local time)"),
        "discord.eventPausedMessage" to listOf("⏸️ El evento **{name}** se ha pausado temporalmente.", "⏸️ The event **{name}** has been temporarily paused."),
        "discord.eventResumedMessage" to listOf("▶️ El evento **{name}** se ha reanudado. Termina <t:{endEpoch}:R>.", "▶️ The event **{name}** has resumed. Ends <t:{endEpoch}:R>."),
        "messages.menuBookShinyGain" to listOf(
            "&7Pokémon shiny (sin categoría especial): &d+{gain}%",
            "&7Shiny Pokémon (no special category): &d+{gain}%",
            "&7Pokémon chromatique (sans catégorie spéciale) : &d+{gain}%",
            "&7Pokémon cromatico (senza categoria speciale): &d+{gain}%",
            "&ePokémon shiny&7 (sin categoría especial): &e+{gain}%",
            "&eShiny Pokémon&7 (no special category): &e+{gain}%",
            "&ePokémon chromatique&7 (sans catégorie spéciale) : &e+{gain}%",
            "&ePokémon cromatico&7 (senza categoria speciale): &e+{gain}%"
        ),
        "messages.menuBookSpecialGain" to listOf(
            "&7Legendario / Mítico / Ultraente / Paradoja: &d+{gain}%",
            "&7Legendary / Mythical / Ultra Beast / Paradox: &d+{gain}%",
            "&7Légendaire / Fabuleux / Ultra-Chimère / Paradoxe : &d+{gain}%",
            "&7Leggendario / Misterioso / Ultracreatura / Paradosso: &d+{gain}%"
        ),
        "messages.previewReset" to listOf(
            "&8Si te toca un especial, tu bonus se reinicia a 0%.",
            "&8If you get a special, your bonus resets to 0%.",
            "&8Si tu obtiens un spécial, ton bonus retombe à 0 %.",
            "&8Se ottieni uno speciale, il tuo bonus torna a 0%."
        ),
        "messages.menuBookSpecialShinyGain" to listOf(
            "&7Especial shiny: &d+{base}% + {extra}% extra = {total}%",
            "&7Shiny special: &d+{base}% + {extra}% extra = {total}%",
            "&7Spécial chromatique : &d+{base}% + {extra}% bonus = {total}%",
            "&7Speciale cromatico: &d+{base}% + {extra}% extra = {total}%",
            "&eEspecial shiny&7: &e+{base}% &7+ &e{extra}% &7extra = &e{total}%",
            "&eShiny special&7: &e+{base}% &7+ &e{extra}% &7extra = &e{total}%",
            "&eSpécial chromatique&7 : &e+{base}% &7+ &e{extra}% &7bonus = &e{total}%",
            "&eSpeciale cromatico&7: &e+{base}% &7+ &e{extra}% &7extra = &e{total}%"
        ),
        "messages.eventStarted" to listOf(
            "&d★ ¡Evento «{name}» activo durante {duration}! &7Usa &e/wt eventos &7para ver los detalles.",
            "&d★ Event «{name}» is active for {duration}! &7Use &e/wt eventos &7to see the details.",
            "&d★ L'événement «{name}» est actif pendant {duration} ! &7Utilise &e/wt eventos &7pour voir les détails.",
            "&d★ L'evento «{name}» è attivo per {duration}! &7Usa &e/wt eventos &7per vedere i dettagli."
        ),
        "messages.eventEnded" to listOf(
            "&d★ El evento «{name}» ha terminado.",
            "&d★ The event «{name}» has ended.",
            "&d★ L'événement «{name}» est terminé.",
            "&d★ L'evento «{name}» è terminato."
        ),
        "messages.eventRiskDescription" to listOf(
            "&7Cada Pokémon &f{category}&7 que ofrezcas suma &d+{value}%&7 extra a tu probabilidad de especial.",
            "&7Each &f{category}&7 Pokémon you offer adds an extra &d+{value}%&7 to your special chance.",
            "&7Chaque Pokémon &f{category}&7 que tu proposes ajoute un bonus de &d+{value}%&7 à ta chance de spécial.",
            "&7Ogni Pokémon &f{category}&7 che offri aggiunge un ulteriore &d+{value}%&7 alla tua probabilità di speciale."
        ),
        "messages.eventReceiveDescription" to listOf(
            "&7Probabilidad extra de recibir un Pokémon &f{category}&7: &d+{value}%&7 en cada intercambio.",
            "&7Extra chance to receive a &f{category}&7 Pokémon: &d+{value}%&7 on every trade.",
            "&7Chance supplémentaire de recevoir un Pokémon &f{category}&7 : &d+{value}%&7 à chaque échange.",
            "&7Probabilità extra di ricevere un Pokémon &f{category}&7: &d+{value}%&7 a ogni scambio."
        ),
        "messages.previewEventShiny" to listOf(
            "&d★ Evento: &7+{percent}% de recibir un Pokémon {category}",
            "&d★ Event: &7+{percent}% chance to receive a {category} Pokémon",
            "&d★ Événement : &7+{percent}% de chance de recevoir un Pokémon {category}",
            "&d★ Evento: &7+{percent}% di probabilità di ricevere un Pokémon {category}"
        ),
        "messages.eventScheduledSoon" to listOf(
            "&d★ El evento «{name}» empezará en &f{time}&d.",
            "&d★ The event «{name}» will start in &f{time}&d.",
            "&d★ L'événement «{name}» commencera dans &f{time}&d.",
            "&d★ L'evento «{name}» inizierà tra &f{time}&d."
        ),
        "messages.eventJoinLine" to listOf(
            "&7- &f{name} &7(termina en &f{time}&7)",
            "&7- &f{name} &7(ends in &f{time}&7)",
            "&7- &f{name} &7(se termine dans &f{time}&7)",
            "&7- &f{name} &7(termina tra &f{time}&7)"
        ),
        "messages.eventPoolHeader" to listOf(
            "&d★ Pokémon &f{category}&d en la pool ({count}):",
            "&d★ &f{category}&d Pokémon in the pool ({count}):",
            "&d★ Pokémon &f{category}&d dans le pool ({count}) :",
            "&d★ Pokémon &f{category}&d nella pool ({count}):"
        ),
        "messages.eventPoolNone" to listOf(
            "&7Ahora mismo no hay ningún Pokémon &f{category}&7 en la pool.",
            "&7There are currently no &f{category}&7 Pokémon in the pool.",
            "&7Il n'y a actuellement aucun Pokémon &f{category}&7 dans le pool.",
            "&7Al momento non ci sono Pokémon &f{category}&7 nella pool."
        ),
        "messages.eventUpcomingLine" to listOf("&7- &f{name} &7— {when} &8({duration})"),
        "messages.eventReminderLine" to listOf("&7- &f{name} &7— {when}"),
        "messages.broadcastShiny" to listOf(
            "&d{player} ha añadido un Pokémon shiny al pool de Wonder Trade: &f{pokemon}&d!",
            "&d {player} ha añadido un Pokémon shiny al pool de Wonder Trade: &f{pokemon}&d!",
            "&d{player} has added a shiny Pokémon to the Wonder Trade pool: &f{pokemon}&d!",
            "&d{player} a ajouté un Pokémon chromatique au pool de Wonder Trade : &f{pokemon}&d !",
            "&d{player} ha aggiunto un Pokémon cromatico alla pool del Wonder Trade: &f{pokemon}&d!"
        ),
        "messages.broadcastReceivedShiny" to listOf(
            "&d{player} ha conseguido un shiny en el WonderTrade: &f{pokemon}&d!",
            "&d{player} got a shiny in WonderTrade: &f{pokemon}&d!",
            "&d{player} a obtenu un chromatique dans WonderTrade : &f{pokemon}&d !",
            "&d{player} ha ottenuto un cromatico nel WonderTrade: &f{pokemon}&d!"
        )
    )

    fun load(configDirectory: Path, requested: String) {
        val folder = configDirectory.resolve("lang")
        directory = folder
        cache.clear()
        runCatching { Files.createDirectories(folder) }
        for (name in bundled) {
            val shipped = readBundled(name) ?: continue
            val target = folder.resolve("$name.json")
            try {
                if (!Files.exists(target)) {
                    Files.newBufferedWriter(target, Charsets.UTF_8).use { gson.toJson(shipped, it) }
                } else {
                    val existing = readFile(target) ?: continue
                    var changed = false
                    for ((key, value) in shipped.entrySet()) {
                        if (!existing.has(key)) { existing.add(key, value); changed = true }
                        else if (legacyTexts[key]?.contains(runCatching { existing.get(key).asString }.getOrNull()) == true && existing.get(key) != value) {
                            // Texto de una versión anterior sin personalizar (avisos de Discord de antes de 0.23): se actualiza.
                            existing.add(key, value); changed = true
                        }
                    }
                    if (changed) Files.newBufferedWriter(target, Charsets.UTF_8).use { gson.toJson(existing, it) }
                }
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.warn("No se pudo preparar el idioma {}: {}", name, exception.message)
            }
        }
        base = toMap(readFile(folder.resolve("$DEFAULT.json")) ?: readBundled(DEFAULT))
        languages = scan()
        val wanted = normalize(requested)
        code = if (available().any { it.first == wanted }) wanted else {
            WonderTradePlus.LOGGER.warn("Idioma «{}» no encontrado en {}. Se usa {}.", wanted, folder, DEFAULT)
            DEFAULT
        }
        context = null
    }

    fun normalize(text: String): String = text.trim().lowercase().replace('-', '_').ifBlank { DEFAULT }

    // ------------------------------------------------------------ idioma por jugador

    /** Redacta todo lo que ocurra dentro de [block] en el idioma del jugador (null = idioma del servidor). */
    inline fun <T> withPlayer(player: net.minecraft.server.level.ServerPlayer?, block: () -> T): T {
        val previous = swap(languageOf(player))
        try {
            return block()
        } finally {
            swap(previous)
        }
    }

    /** Fija el idioma activo y devuelve el anterior (uso interno de [withPlayer]). */
    fun swap(language: String?): String? {
        val previous = context
        context = language
        return previous
    }

    /** Usa el idioma de ese jugador hasta el siguiente cambio (se restablece al inicio de cada tick del servidor). */
    fun bindPlayer(player: net.minecraft.server.level.ServerPlayer?) {
        context = languageOf(player)
    }

    fun reset() {
        context = null
    }

    /**
     * Idioma de un jugador: el que eligió con /wt idioma; si no eligió, el de su cliente de Minecraft (si existe en
     * lang y `general.useClientLanguage` está activo); y si no, el del servidor. null = idioma del servidor.
     */
    fun languageOf(player: net.minecraft.server.level.ServerPlayer?): String? {
        if (player == null) return null
        runCatching { WonderTradeService.storedLanguage(player.uuid) }.getOrNull()?.let { stored ->
            if (available().any { it.first == stored }) return stored
        }
        if (!ConfigManager.bool("general.useClientLanguage", true)) return null
        val client = runCatching { player.clientInformation().language() }.getOrNull()?.let { normalize(it) } ?: return null
        val list = available().map { it.first }
        if (client in list) return client
        return list.firstOrNull { it.substringBefore('_') == client.substringBefore('_') }
    }

    /** Códigos de idioma disponibles (archivos de la carpeta lang) con su nombre visible. */
    fun available(): List<Pair<String, String>> = languages

    private var languages: List<Pair<String, String>> = bundled.map { it to it }

    private fun scan(): List<Pair<String, String>> {
        val folder = directory ?: return bundled.map { it to it }
        val files = runCatching { Files.list(folder).use { stream -> stream.toList() } }.getOrDefault(emptyList())
        val found = files.filter { it.fileName.toString().endsWith(".json") }.mapNotNull { path ->
            val id = path.fileName.toString().removeSuffix(".json")
            val label = readFile(path)?.get("_name")?.takeIf { it.isJsonPrimitive }?.asString ?: id
            id to label
        }
        return found.sortedWith(compareBy({ it.first != DEFAULT }, { it.first }))
    }

    fun nameOf(id: String): String = available().firstOrNull { it.first == id }?.second ?: id

    /** Texto de un ajuste (`messages.*`, `discord.*Message`, `events.names.*`) en el idioma activo; null si no existe. */
    fun text(path: String): String? = map(effective())[path] ?: base[path]

    /** Traduce un texto original en español; si el idioma no lo tiene, se queda en español. */
    fun tr(source: String): String {
        val language = effective()
        return if (language == DEFAULT) source else map(language)[source] ?: source
    }

    /** Igual que [tr] sustituyendo {0}, {1}... por los valores indicados. */
    fun t(source: String, vararg args: Any?): String {
        var result = tr(source)
        for (index in args.indices) result = result.replace("{$index}", args[index].toString())
        return result
    }
}
