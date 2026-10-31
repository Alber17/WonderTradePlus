package dev.openwondertrade

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.server.level.ServerPlayer

/** Avisos al webhook de Discord (secciones `discord.*` de la configuración). */
object WonderTradeDiscord {

    private val httpClient: java.net.http.HttpClient by lazy { java.net.http.HttpClient.newHttpClient() }

    /** Envía un mensaje al webhook de Discord sin bloquear el servidor. `onResult` se llama desde otro hilo. */
    private fun discordPost(content: String, force: Boolean = false, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!force && !ConfigManager.bool("discord.enabled", false)) return
        val url = ConfigManager.string("discord.webhookUrl", "").trim()
        if (url.isEmpty()) {
            onResult?.invoke(false, "No hay webhookUrl configurada en la sección discord.")
            return
        }
        try {
            val body = com.google.gson.JsonObject().apply {
                addProperty("content", content)
                addProperty("username", ConfigManager.string("discord.username", "WonderTrade"))
                // Evita que un apodo con @everyone o @here mencione a nadie.
                add("allowed_mentions", com.google.gson.JsonObject().apply { add("parse", com.google.gson.JsonArray()) })
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

    fun test(callback: (Boolean, String) -> Unit) {
        discordPost("✅ Prueba de conexión de WonderTrade Plus.", force = true, onResult = callback)
    }


    private fun plainName(pokemon: Pokemon): String {
        val name = pokemon.getDisplayName().string
        // Si el servidor no tiene las traducciones, el nombre sale como una clave ("cobblemon.species...").
        return if (name.contains('.')) pokemon.species.resourceIdentifier.path.replaceFirstChar { it.uppercase() } else name
    }

    private fun plainCategory(pokemon: Pokemon): String {
        val parts = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { parts += it.replace(Regex("&."), "") }
        if (pokemon.shiny) parts += "Shiny"
        return if (parts.isEmpty()) "Especial" else parts.joinToString(" · ")
    }

    fun notifyDonation(player: ServerPlayer, offered: Pokemon) {
        if (!ConfigManager.bool("discord.enabled", false) || !ConfigManager.bool("discord.notifyDonations", true)) return
        if (!WonderTradeRules.isRare(offered)) return
        val template = ConfigManager.string("discord.donationMessage", "**{player}** ha aportado **{pokemon}** (Nv. {level}, {category}) al pool.")
        if (template.isNotBlank()) {
            discordPost(WonderTradeService.format(template, mapOf("player" to player.gameProfile.name, "pokemon" to plainName(offered), "level" to offered.level, "category" to plainCategory(offered))))
        }
    }

    fun notify(player: ServerPlayer, offered: Pokemon, incoming: Pokemon) {
        if (!ConfigManager.bool("discord.enabled", false)) return
        notifyDonation(player, offered)
        if (ConfigManager.bool("discord.notifyReceived", true) && WonderTradeRules.isRare(incoming)) {
            val template = ConfigManager.string("discord.receivedMessage", "🎉 **{player}** ha recibido **{pokemon}** (Nv. {level}, {category}) en un Wonder Trade.")
            discordPost(WonderTradeService.format(template, mapOf("player" to player.gameProfile.name, "pokemon" to plainName(incoming), "level" to incoming.level, "category" to plainCategory(incoming))))
        }
    }
}
