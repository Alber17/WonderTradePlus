package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Idiomas del mod. El idioma principal es es_es (español de España); también vienen en_us, fr_fr (francés) e it_it (italiano).
 *
 * Cada idioma es un archivo `config/wondertradeplus/lang/<código>.json` con dos tipos de entradas:
 *  - rutas de configuración (`messages.success`, `discord.donationMessage`, `events.names.risk`...): el texto de ese ajuste;
 *  - textos del propio mod (menús, ayudas, avisos): la clave es el texto original en español y el valor su traducción.
 * Para añadir un idioma basta con copiar es_es.json o en_us.json con otro nombre (por ejemplo de_de.json), traducir los
 * valores y poner `general.language` a ese código. Lo que falte en un idioma se muestra en español.
 * Los textos que pongas a mano en config.json (`messages.*`...) tienen prioridad sobre cualquier idioma.
 */
object Lang {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val bundled = listOf("es_es", "en_us", "fr_fr", "it_it")
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
        "discord.receivedMessage" to listOf("🎉 **{player}** ha recibido **{pokemon}** (Nv. {level}, {category}) en un Wonder Trade.", "🎉 **{player}** received **{pokemon}** (Lv. {level}, {category}) in a Wonder Trade."),
        "discord.eventStartMessage" to listOf("🎉 **Evento iniciado: {name}**\n{details}\n⏱️ Dura {duration} · termina <t:{endEpoch}:R> (<t:{endEpoch}:t>, a tu hora)", "🎉 **Event started: {name}**\n{details}\n⏱️ Lasts {duration} · ends <t:{endEpoch}:R> (<t:{endEpoch}:t>, in your local time)"),
        "discord.eventEndMessage" to listOf("🏁 **Ha terminado el evento: {name}**", "🏁 **The event has ended: {name}**"),
        "discord.eventSoonMessage" to listOf("⏰ **{name}** empieza <t:{startEpoch}:R> (<t:{startEpoch}:F>, a tu hora)", "⏰ **{name}** starts <t:{startEpoch}:R> (<t:{startEpoch}:F>, in your local time)"),
        "discord.eventPausedMessage" to listOf("⏸️ El evento **{name}** se ha pausado temporalmente.", "⏸️ The event **{name}** has been temporarily paused."),
        "discord.eventResumedMessage" to listOf("▶️ El evento **{name}** se ha reanudado. Termina <t:{endEpoch}:R>.", "▶️ The event **{name}** has resumed. Ends <t:{endEpoch}:R>.")
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
