package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.server.level.ServerPlayer

/** Avisos al webhook de Discord (secciones `discord.*` de la configuración). */
object WonderTradeDiscord {

    private val httpClient: java.net.http.HttpClient by lazy { java.net.http.HttpClient.newHttpClient() }

    /** Un embed de Discord (tarjeta con título, color, campos y pie). Si `discord.style.useEmbeds` es false se envía como texto. */
    class Embed(var title: String, var description: String = "", var color: Int = 0x9B59B6) {
        private val fields = mutableListOf<Triple<String, String, Boolean>>()
        var thumbnail: String? = null

        fun field(name: String, value: String, inline: Boolean = true): Embed {
            if (value.isNotBlank() && fields.size < 25) fields += Triple(name.take(256), value.take(1024), inline)
            return this
        }

        fun toJson(): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
            addProperty("title", title.take(256))
            if (description.isNotBlank()) addProperty("description", description.take(4000))
            addProperty("color", color)
            if (fields.isNotEmpty()) add("fields", com.google.gson.JsonArray().also { array ->
                fields.forEach { (name, value, inline) ->
                    array.add(com.google.gson.JsonObject().apply {
                        addProperty("name", name); addProperty("value", value); addProperty("inline", inline)
                    })
                }
            })
            thumbnail?.let { add("thumbnail", com.google.gson.JsonObject().apply { addProperty("url", it) }) }
            val footer = ConfigManager.string("discord.style.footer", "WonderTrade Plus").trim()
            if (footer.isNotEmpty()) add("footer", com.google.gson.JsonObject().apply { addProperty("text", footer) })
            if (ConfigManager.bool("discord.style.showTimestamp", true)) addProperty("timestamp", java.time.Instant.now().toString())
        }

        /** Versión en texto plano (cuando los embeds están desactivados). */
        fun toPlain(): String = buildString {
            append("**").append(title).append("**")
            if (description.isNotBlank()) append("\n").append(description)
            fields.forEach { (name, value, _) -> append("\n**").append(name).append(":** ").append(value) }
        }
    }

    /** Color «#RRGGBB» de `discord.style.colors.<key>`. */
    private fun colorOf(key: String, fallback: String): Int {
        val text = ConfigManager.string("discord.style.colors.$key", fallback).trim().removePrefix("#")
        return text.toIntOrNull(16) ?: fallback.removePrefix("#").toInt(16)
    }

    /** Envía un mensaje al webhook de Discord sin bloquear el servidor. `onResult` se llama desde otro hilo. */
    private fun discordPost(content: String, force: Boolean = false, onResult: ((Boolean, String) -> Unit)? = null, roleId: String? = null, embed: Embed? = null) {
        if (!force && !ConfigManager.bool("discord.enabled", false)) return
        val url = ConfigManager.string("discord.webhookUrl", "").trim()
        if (url.isEmpty()) {
            onResult?.invoke(false, "No hay webhookUrl configurada en la sección discord.")
            return
        }
        try {
            val useEmbeds = ConfigManager.bool("discord.style.useEmbeds", true)
            val text = if (embed != null && !useEmbeds) (content + "\n" + embed.toPlain()).trim() else content
            val body = com.google.gson.JsonObject().apply {
                if (text.isNotBlank()) addProperty("content", text.take(2000))
                if (embed != null && useEmbeds) add("embeds", com.google.gson.JsonArray().also { it.add(embed.toJson()) })
                addProperty("username", ConfigManager.string("discord.username", "WonderTrade"))
                val avatar = ConfigManager.string("discord.avatarUrl", "").trim()
                if (avatar.isNotEmpty()) addProperty("avatar_url", avatar)
                // Evita que un apodo con @everyone o @here mencione a nadie.
                // Solo se permite mencionar el rol configurado para eventos (si lo hay).
                add("allowed_mentions", com.google.gson.JsonObject().apply {
                    add("parse", com.google.gson.JsonArray())
                    if (roleId != null) add("roles", com.google.gson.JsonArray().also { it.add(roleId) })
                })
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

    // ------------------------------------------------------------ eventos

    private fun eventsEnabled(kind: String): Boolean =
        ConfigManager.bool("discord.enabled", false) && ConfigManager.bool("discord.events.enabled", true) &&
            ConfigManager.bool("discord.events.$kind", true)

    private fun role(): String? =
        ConfigManager.string("discord.events.mentionRoleId", "").trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** Envía el aviso de un evento; si hay rol configurado (discord.events.mentionRoleId) lo menciona fuera del embed. */
    private fun postEvent(embed: Embed) {
        val role = role()
        discordPost(if (role != null) "<@&$role>" else "", roleId = role, embed = embed)
    }

    private fun epoch(millis: Long): Long = millis / 1000L

    private fun plainLines(lines: List<String>): String = lines.joinToString("\n") { it.replace(Regex("[&§]."), "") }

    fun eventStarted(event: WtEvent) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("start")) return
            val details = plainLines(WonderTradeEvents.describe(event))
            val description = WonderTradeService.format(
                ConfigManager.string("discord.eventStartMessage", "El evento **{name}** ya está activo.\n{details}"),
                mapOf("name" to event.title, "details" to details, "duration" to WonderTradeEvents.formatDuration((event.endAt - event.startAt) / 1000L), "endEpoch" to epoch(event.endAt))
            )
            postEvent(
                Embed(ConfigManager.string("discord.titles.eventStart", "🎉 ¡Evento iniciado: {name}!").replace("{name}", event.title), description, colorOf("eventStart", "#9B59B6"))
                    .field(Lang.tr("⏱️ Duración"), WonderTradeEvents.formatDuration((event.endAt - event.startAt) / 1000L))
                    .field(Lang.tr("🏁 Termina"), "<t:${epoch(event.endAt)}:R>\n<t:${epoch(event.endAt)}:f>")
            )
        }
    }

    /** Nombres de los jugadores agrupados («Nick — Pokémon, Pokémon»), recortados al límite de un campo de Discord. */
    private fun playersField(outcomes: List<WtEvent.Outcome>): String {
        val maxPlayers = ConfigManager.int("discord.events.maxPlayersListed", 15).coerceIn(1, 40)
        val grouped = LinkedHashMap<String, MutableList<String>>()
        outcomes.forEach { grouped.getOrPut(it.name) { mutableListOf() }.add(it.pokemon) }
        val lines = grouped.entries.take(maxPlayers).map { (name, pokemon) ->
            "• **$name** — ${pokemon.distinct().joinToString(", ")}" + (if (pokemon.size > 1) " (×${pokemon.size})" else "")
        }.toMutableList()
        if (grouped.size > maxPlayers) lines += Lang.t("…y {0} más", grouped.size - maxPlayers)
        return lines.joinToString("\n")
    }

    fun eventEnded(event: WtEvent) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("end")) return
            val description = WonderTradeService.format(
                ConfigManager.string("discord.eventEndMessage", "El evento **{name}** ha finalizado. ¡Gracias por participar!"),
                mapOf("name" to event.title, "details" to "", "duration" to WonderTradeEvents.formatDuration((event.endAt - event.startAt) / 1000L), "endEpoch" to epoch(event.endAt))
            )
            val embed = Embed(
                ConfigManager.string("discord.titles.eventEnd", "🏁 Evento finalizado: {name}").replace("{name}", event.title),
                description, colorOf("eventEnd", "#2ECC71")
            )
                .field(Lang.tr("🔄 Intercambios"), event.trades.toString())
                .field(Lang.tr("👥 Jugadores"), event.playerIds.size.toString())
                .field(Lang.tr("⏱️ Duración"), WonderTradeEvents.formatDuration((event.endAt - event.startAt) / 1000L))
            if (ConfigManager.bool("discord.events.listPlayers", true)) {
                val got = event.outcomes.filter { !it.offered }
                val risked = event.outcomes.filter { it.offered }
                if (event.type == EventType.RECEIVE) {
                    embed.field(Lang.tr("🏆 Han conseguido algo gracias al evento"), if (got.isEmpty()) Lang.tr("Nadie ha conseguido nada esta vez.") else playersField(got), false)
                } else {
                    embed.field(Lang.tr("🎁 Han aportado Pokémon del evento"), if (risked.isEmpty()) Lang.tr("Nadie ha participado esta vez.") else playersField(risked), false)
                }
            }
            postEvent(embed)
        }
    }

    fun eventScheduledSoon(item: ScheduledEvent) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("scheduledSoon")) return
            val description = WonderTradeService.format(
                ConfigManager.string("discord.eventSoonMessage", "**{name}** empieza <t:{startEpoch}:R>."),
                mapOf("name" to item.title, "startEpoch" to epoch(item.nextRunAt), "endEpoch" to epoch(item.nextRunAt))
            )
            postEvent(
                Embed(ConfigManager.string("discord.titles.eventSoon", "⏰ Próximo evento: {name}").replace("{name}", item.title), description, colorOf("eventSoon", "#F1C40F"))
                    .field(Lang.tr("🕒 Empieza"), "<t:${epoch(item.nextRunAt)}:F>")
            )
        }
    }

    class TopLine(val rank: Int, val name: String, val points: Int, val donations: Int)

    /** Anuncio del Top Donaciones al cerrar el mes. */
    fun topDonations(month: String, winners: List<TopLine>) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("topDonations")) return
            val medals = mapOf(1 to "🥇", 2 to "🥈", 3 to "🥉")
            val embed = Embed(
                ConfigManager.string("discord.titles.topDonations", "★ Top Donaciones de {month}").replace("{month}", month),
                if (winners.isEmpty()) Lang.tr("Nadie ha donado este mes.") else Lang.tr("¡Gracias a todos por donar! Estos son los ganadores:"),
                colorOf("top", "#E67E22")
            )
            winners.forEach { w ->
                embed.field(
                    (medals[w.rank] ?: "#${w.rank}") + " " + w.name,
                    Lang.t("{0} puntos · {1} donaciones", w.points, w.donations), false
                )
            }
            discordPost("", embed = embed)
        }
    }

    /** Avisos de cada sesión de temporada (por ejemplo, la hora navideña). */
    fun season(season: Season, started: Boolean, count: Int, durationSeconds: Long = season.sessionDurationMinutes * 60L) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("seasonal")) return
            val values = WonderTradeSeasons.placeholders(season) + mapOf(
                "duration" to WonderTradeEvents.formatDuration(durationSeconds),
                "endEpoch" to (epoch(System.currentTimeMillis()) + durationSeconds)
            )
            val halloween = season.theme == "halloween"
            val description = WonderTradeService.format(
                if (halloween) {
                    if (started)
                        ConfigManager.string("discord.halloweenStartMessage", "Ha aparecido la hora de Halloween. La pool de miedo estará disponible durante **{duration}**. ¡Aprovecha para conseguir Pokémon de tipo **{types}**.")
                    else
                        ConfigManager.string("discord.halloweenEndMessage", "La hora de Halloween ha terminado. La pool habitual vuelve a estar disponible.")
                } else if (started)
                    ConfigManager.string("discord.seasonStartMessage", "Ha aparecido la hora navideña. La pool temática estará disponible durante **{duration}**. ¡Aprovecha para conseguir Pokémon de tipo **{types}**.")
                else
                    ConfigManager.string("discord.seasonEndMessage", "La hora navideña ha terminado. La pool habitual vuelve a estar disponible."),
                values
            )
            val title = if (halloween) {
                if (started) ConfigManager.string("discord.titles.halloweenStart", "🎃 ¡Ha aparecido la hora de Halloween!")
                else ConfigManager.string("discord.titles.halloweenEnd", "👻 La hora de Halloween ha terminado")
            } else if (started)
                ConfigManager.string("discord.titles.seasonStart", "🎄 ¡Ha aparecido la hora navideña!")
            else
                ConfigManager.string("discord.titles.seasonEnd", "🎁 La hora navideña ha terminado")
            val colorKey = (if (halloween) "halloween" else "season") + (if (started) "Start" else "End")
            val colorDefault = if (halloween) (if (started) "#FF7518" else "#6A0DAD") else (if (started) "#168BFF" else "#B2BEC3")
            val embed = Embed(WonderTradeService.format(title, values), description, colorOf(colorKey, colorDefault))
            if (started) {
                embed.field(Lang.tr("⏱️ Tiempo disponible"), WonderTradeEvents.formatDuration(durationSeconds))
                embed.field(Lang.tr("🏁 Termina"), "<t:${values["endEpoch"]}:R>")
                embed.field(if (halloween) Lang.tr("🎃 Temática") else Lang.tr("🧊 Temática"), season.types.joinToString(", ") { WonderTradeRules.typeName(it) })
            } else {
                embed.field(Lang.tr("🎴 Pokémon en la pool habitual"), count.toString())
            }
            postEvent(embed)
        }
    }

    fun eventPaused(event: WtEvent) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("pause")) return
            val description = WonderTradeService.format(
                ConfigManager.string("discord.eventPausedMessage", "El evento **{name}** se ha pausado temporalmente."), mapOf("name" to event.title, "endEpoch" to epoch(event.endAt), "details" to "")
            )
            postEvent(Embed(ConfigManager.string("discord.titles.eventPaused", "⏸️ Evento en pausa").replace("{name}", event.title), description, colorOf("paused", "#95A5A6")))
        }
    }

    fun eventResumed(event: WtEvent) {
        Lang.withPlayer(null) {
            if (!eventsEnabled("pause")) return
            val description = WonderTradeService.format(
                ConfigManager.string("discord.eventResumedMessage", "El evento **{name}** se ha reanudado."), mapOf("name" to event.title, "endEpoch" to epoch(event.endAt), "details" to "")
            )
            postEvent(
                Embed(ConfigManager.string("discord.titles.eventResumed", "▶️ Evento reanudado").replace("{name}", event.title), description, colorOf("resumed", "#3498DB"))
                    .field(Lang.tr("🏁 Termina"), "<t:${epoch(event.endAt)}:R>")
            )
        }
    }

    fun test(callback: (Boolean, String) -> Unit) {
        Lang.withPlayer(null) {
            discordPost(
                "", force = true, onResult = callback,
                embed = Embed(Lang.tr("✅ Conexión correcta"), Lang.tr("WonderTrade Plus está conectado a este canal. Aquí llegarán los avisos de especiales, eventos y Top Donaciones."), colorOf("test", "#57F287"))
            )
        }
    }

    // ------------------------------------------------------------ Pokémon especiales

    fun plainName(pokemon: Pokemon): String {
        val name = pokemon.getDisplayName().string
        // Si el servidor no tiene las traducciones, el nombre sale como una clave ("cobblemon.species...").
        val base = if (name.contains('.')) pokemon.species.resourceIdentifier.path.replaceFirstChar { it.uppercase() } else name
        val region = regionalRegion(pokemon) ?: return base
        val titled = when (region) {
            "alola" -> Lang.t("{0} de Alola", base)
            "galar" -> Lang.t("{0} de Galar", base)
            "hisui" -> Lang.t("{0} de Hisui", base)
            "paldea" -> Lang.t("{0} de Paldea", base)
            else -> base
        }
        val form = runCatching { pokemon.form.name }.getOrDefault("")
        val detail = when {
            form.startsWith("Paldea-", ignoreCase = true) -> when (form.substringAfter('-').lowercase()) {
                "combat" -> Lang.tr("Combatiente")
                "blaze" -> Lang.tr("Ardiente")
                "aqua" -> Lang.tr("Acuática")
                else -> null
            }
            form.contains("zen", ignoreCase = true) && region == "galar" -> Lang.tr("Modo Daruma")
            else -> null
        }
        return if (detail == null) titled else Lang.t("{0} ({1})", titled, detail)
    }

    private fun plainCategory(pokemon: Pokemon): String {
        val parts = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { parts += it.replace(Regex("&."), "") }
        if (pokemon.shiny) parts += Lang.tr("Shiny")
        return if (parts.isEmpty()) Lang.tr("Especial") else parts.joinToString(" · ")
    }

    /** Color de la tarjeta según la rareza del Pokémon. */
    private fun rarityColor(pokemon: Pokemon): Int = when {
        pokemon.shiny -> colorOf("shiny", "#F1C40F")
        else -> when (WonderTradeRules.speciesCategoryKey(pokemon)) {
            "legendary" -> colorOf("legendary", "#9B59B6")
            "mythical" -> colorOf("mythical", "#E91E63")
            "ultraBeast" -> colorOf("ultraBeast", "#3498DB")
            "paradox" -> colorOf("paradox", "#E67E22")
            else -> colorOf("donation", "#9B59B6")
        }
    }

    /**
     * Imagen oficial (PokéAPI). Las formas regionales tienen otro número: el de la especie
     * muestra el Pokémon base, así que se usa el de la forma.
     */
    private fun imageUrl(pokemon: Pokemon): String? {
        if (!ConfigManager.bool("discord.style.pokemonImages", true)) return null
        val id = regionalArtworkId(pokemon) ?: runCatching { pokemon.species.nationalPokedexNumber }.getOrDefault(0)
        if (id <= 0) return null
        return "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" + (if (pokemon.shiny) "shiny/" else "") + "$id.png"
    }

    /** alola, galar, hisui o paldea, o null si es la forma base. */
    private fun regionalRegion(pokemon: Pokemon): String? {
        val labels = runCatching { pokemon.form.labels }.getOrNull().orEmpty().map { it.lowercase() }
        val aspects = runCatching { pokemon.aspects }.getOrNull().orEmpty().map { it.lowercase() }
        return when {
            "alolan_form" in labels || "alolan" in aspects -> "alola"
            "galarian_form" in labels || "galarian" in aspects -> "galar"
            "hisuian_form" in labels || "hisuian" in aspects -> "hisui"
            "paldean_form" in labels || "paldean" in aspects -> "paldea"
            else -> null
        }
    }

    private fun regionalArtworkId(pokemon: Pokemon): Int? {
        val region = regionalRegion(pokemon) ?: return null
        val species = when (val path = pokemon.species.resourceIdentifier.path.lowercase()) {
            "mrmime", "mr_mime" -> "mr-mime"
            else -> path
        }
        val form = runCatching { pokemon.form.name }.getOrDefault("").lowercase()
        val key = when {
            form.startsWith("paldea-") -> "$species-paldea-${form.removePrefix("paldea-")}"
            form.contains("zen") && region == "galar" -> "$species-galar-zen"
            else -> "$species-$region"
        }
        return regionalArtwork[key]
    }

    /** Arte oficial de PokéAPI para cada forma regional (el número de la especie es el de la forma base). */
    private val regionalArtwork = mapOf(
        "rattata-alola" to 10091,
        "raticate-alola" to 10092,
        "raichu-alola" to 10100,
        "sandshrew-alola" to 10101,
        "sandslash-alola" to 10102,
        "vulpix-alola" to 10103,
        "ninetales-alola" to 10104,
        "diglett-alola" to 10105,
        "dugtrio-alola" to 10106,
        "meowth-alola" to 10107,
        "persian-alola" to 10108,
        "geodude-alola" to 10109,
        "graveler-alola" to 10110,
        "golem-alola" to 10111,
        "grimer-alola" to 10112,
        "muk-alola" to 10113,
        "exeggutor-alola" to 10114,
        "marowak-alola" to 10115,
        "meowth-galar" to 10161,
        "ponyta-galar" to 10162,
        "rapidash-galar" to 10163,
        "slowpoke-galar" to 10164,
        "slowbro-galar" to 10165,
        "farfetchd-galar" to 10166,
        "weezing-galar" to 10167,
        "mr-mime-galar" to 10168,
        "articuno-galar" to 10169,
        "zapdos-galar" to 10170,
        "moltres-galar" to 10171,
        "slowking-galar" to 10172,
        "corsola-galar" to 10173,
        "zigzagoon-galar" to 10174,
        "linoone-galar" to 10175,
        "darumaka-galar" to 10176,
        "darmanitan-galar" to 10177,
        "darmanitan-galar-zen" to 10178,
        "yamask-galar" to 10179,
        "stunfisk-galar" to 10180,
        "growlithe-hisui" to 10229,
        "arcanine-hisui" to 10230,
        "voltorb-hisui" to 10231,
        "electrode-hisui" to 10232,
        "typhlosion-hisui" to 10233,
        "qwilfish-hisui" to 10234,
        "sneasel-hisui" to 10235,
        "samurott-hisui" to 10236,
        "lilligant-hisui" to 10237,
        "zorua-hisui" to 10238,
        "zoroark-hisui" to 10239,
        "braviary-hisui" to 10240,
        "sliggoo-hisui" to 10241,
        "goodra-hisui" to 10242,
        "avalugg-hisui" to 10243,
        "decidueye-hisui" to 10244,
        "tauros-paldea-combat" to 10250,
        "tauros-paldea-blaze" to 10251,
        "tauros-paldea-aqua" to 10252,
        "wooper-paldea" to 10253
    )

    private fun pokemonEmbed(title: String, description: String, pokemon: Pokemon, player: String): Embed =
        Embed(title, description, rarityColor(pokemon)).apply {
            thumbnail = imageUrl(pokemon)
            field(Lang.tr("👤 Jugador"), player)
            field(Lang.tr("🎴 Pokémon"), plainName(pokemon))
            field(Lang.tr("📈 Nivel"), pokemon.level.toString())
            field(Lang.tr("✨ Categoría"), plainCategory(pokemon))
        }

    fun notifyDonation(player: ServerPlayer, offered: Pokemon) {
        Lang.withPlayer(null) {
            if (!ConfigManager.bool("discord.enabled", false) || !ConfigManager.bool("discord.notifyDonations", true)) return
            if (!WonderTradeRules.isRare(offered)) return
            val template = ConfigManager.string("discord.donationMessage", "Han aportado un {pokemon} al pool del WonderTrade.")
            if (template.isNotBlank()) {
                val name = player.gameProfile.name
                val text = WonderTradeService.format(template, mapOf("player" to name, "pokemon" to plainName(offered), "level" to offered.level, "category" to plainCategory(offered)))
                discordPost("", embed = pokemonEmbed(ConfigManager.string("discord.titles.donation", "🎁 ¡Nueva aportación especial!"), text, offered, name))
            }
        }
    }

    fun notify(player: ServerPlayer, offered: Pokemon, incoming: Pokemon) {
        Lang.withPlayer(null) {
            if (!ConfigManager.bool("discord.enabled", false)) return
            notifyDonation(player, offered)
            if (ConfigManager.bool("discord.notifyReceived", true) && WonderTradeRules.isRare(incoming)) {
                val template = ConfigManager.string("discord.receivedMessage", "Han recibido un {pokemon} del pool del WonderTrade.")
                val name = player.gameProfile.name
                val text = WonderTradeService.format(template, mapOf("player" to name, "pokemon" to plainName(incoming), "level" to incoming.level, "category" to plainCategory(incoming)))
                discordPost("", embed = pokemonEmbed(ConfigManager.string("discord.titles.received", "🎉 ¡Premio en un Wonder Trade!"), text, incoming, name))
            }
        }
    }
}
