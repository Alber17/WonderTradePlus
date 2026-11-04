package dev.openwondertrade

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Animaciones discretas en la barra de acción (encima de la barra de objetos): una frase con una ola de brillo que
 * dura unos segundos. Solo se usa en tres momentos (recibir un especial, empezar un evento y el Top Donaciones) y
 * cada uso se puede desactivar en `animations`.
 */
object WonderTradeAnimations {
    private class Animation(val uuid: UUID, val text: String, var frame: Int, val frames: Int)

    private val running = ArrayList<Animation>()
    private var tickCounter = 0

    // Ola dorada: oro, amarillo claro, blanco, amarillo claro.
    private val palette = listOf(0xFFAA00, 0xFFD24D, 0xFFFFFF, 0xFFD24D, 0xFFAA00, 0xE08A00)

    fun enabled(key: String): Boolean =
        ConfigManager.bool("animations.enabled", true) && ConfigManager.bool("animations.$key", true)

    private fun clean(text: String): String = text.replace(Regex("[&§]."), "")

    fun play(player: ServerPlayer, key: String, text: String) {
        if (!enabled(key)) return
        val seconds = ConfigManager.int("animations.durationSeconds", 3).coerceIn(1, 10)
        running.removeAll { it.uuid == player.uuid }
        running.add(Animation(player.uuid, clean(text), 0, seconds * 10))
    }

    fun playAll(server: MinecraftServer, key: String, text: String) {
        if (!enabled(key)) return
        server.playerList.players.forEach { play(it, key, text) }
    }

    private fun frameComponent(text: String, frame: Int, last: Boolean): Component {
        val result: MutableComponent = Component.empty()
        if (last) return result.append(Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFD24D))))
        text.forEachIndexed { index, char ->
            val color = palette[Math.floorMod(index - frame, palette.size)]
            result.append(Component.literal(char.toString()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)).withBold(true)))
        }
        return result
    }

    /** Se llama en cada tick del servidor; avanza un fotograma cada 2 ticks (10 por segundo). */
    fun tick(server: MinecraftServer) {
        if (running.isEmpty()) return
        tickCounter++
        if (tickCounter % 2 != 0) return
        val iterator = running.iterator()
        while (iterator.hasNext()) {
            val animation = iterator.next()
            val player = server.playerList.getPlayer(animation.uuid)
            if (player == null) {
                iterator.remove()
                continue
            }
            animation.frame++
            val last = animation.frame >= animation.frames
            runCatching { player.displayClientMessage(frameComponent(animation.text, animation.frame, last), true) }
            if (last) iterator.remove()
        }
    }
}
