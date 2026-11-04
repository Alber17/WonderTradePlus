package dev.openwondertrade

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Idiomas del mod. El idioma principal es es_es (español de España) y el secundario en_us.
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
    private val bundled = listOf("es_es", "en_us")
    const val DEFAULT = "es_es"

    private var directory: Path? = null
    private var base: Map<String, String> = emptyMap()      // es_es (idioma principal)
    private var active: Map<String, String> = emptyMap()    // idioma elegido
    private var code: String = DEFAULT

    fun current(): String = code

    /** Locale de Java del idioma activo (para nombres de días y meses): es_es → es-ES, en_us → en-US. */
    fun locale(): java.util.Locale {
        val parts = code.split('_')
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

    /**
     * Prepara la carpeta lang (copia los idiomas incluidos si faltan y les añade las claves nuevas sin tocar lo que
     * hayas editado) y carga el idioma elegido en `general.language`.
     */
    fun load(configDirectory: Path, requested: String) {
        val folder = configDirectory.resolve("lang")
        directory = folder
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
                    }
                    if (changed) Files.newBufferedWriter(target, Charsets.UTF_8).use { gson.toJson(existing, it) }
                }
            } catch (exception: Exception) {
                WonderTradePlus.LOGGER.warn("No se pudo preparar el idioma {}: {}", name, exception.message)
            }
        }
        base = toMap(readFile(folder.resolve("$DEFAULT.json")) ?: readBundled(DEFAULT))
        val wanted = requested.trim().lowercase().replace('-', '_').ifBlank { DEFAULT }
        val wantedJson = if (wanted == DEFAULT) null
        else readFile(folder.resolve("$wanted.json")) ?: readBundled(wanted)
        if (wanted != DEFAULT && wantedJson == null) {
            WonderTradePlus.LOGGER.warn("Idioma «{}» no encontrado en {}. Se usa {}.", wanted, folder, DEFAULT)
            code = DEFAULT
            active = base
        } else {
            code = wanted
            active = if (wantedJson == null) base else toMap(wantedJson)
        }
    }

    /** Códigos de idioma disponibles (archivos de la carpeta lang) con su nombre visible. */
    fun available(): List<Pair<String, String>> {
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
    fun text(path: String): String? = active[path] ?: base[path]

    /** Traduce un texto original en español; si el idioma no lo tiene, se queda en español. */
    fun tr(source: String): String = if (code == DEFAULT) source else active[source] ?: source

    /** Igual que [tr] sustituyendo {0}, {1}... por los valores indicados. */
    fun t(source: String, vararg args: Any?): String {
        var result = tr(source)
        for (index in args.indices) result = result.replace("{$index}", args[index].toString())
        return result
    }
}
