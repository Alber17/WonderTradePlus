package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Optional

object WonderTradeGui {
    private const val PC_BOX_SLOTS = 30
    private const val POOL_PER_PAGE = 45

    // ---------------------------------------------------------------- apertura

    fun openMain(player: ServerPlayer, donationMode: Boolean = false) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(
            player,
            if (donationMode) Lang.tr("&5Wonder Trade &8- &fDonar") else Lang.tr("&5Wonder Trade &8- &fTu equipo"),
            3,
            "menuOpen"
        ) { renderMain(it, player, donationMode) }
    }

    fun openPc(player: ServerPlayer, box: Int = 0, donationMode: Boolean = false) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(
            player,
            if (donationMode) Lang.tr("&5Wonder Trade &8- &fDonar desde PC") else Lang.tr("&5Wonder Trade &8- &fTu PC"),
            6,
            "pcOpen"
        ) { renderPc(it, player, box, donationMode) }
    }

    fun canViewPool(player: ServerPlayer): Boolean =
        player.hasPermissions(ConfigManager.int("gui.poolViewPermissionLevel", 0).coerceIn(0, 4))

    fun openPool(
        player: ServerPlayer,
        page: Int = 0,
        admin: Boolean = false,
        filter: PoolFilter = PoolFilter.ALL,
        deleteMode: Boolean = false,
        sort: PoolSort = PoolSort.NEWEST,
        query: String? = null
    ) {
        if (!admin && !canViewPool(player)) {
            WonderTradeService.send(player, "noPoolPermission")
            return
        }
        val title = if (admin) Lang.tr("&5Wonder Trade &8- &fPool &c(admin)") else Lang.tr("&5Wonder Trade &8- &fPool")
        open(player, title, 6) { renderPool(it, player, page, admin, filter, deleteMode, sort, query) }
    }

    fun openDonationHistory(player: ServerPlayer, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Wonder Trade &8- &aDonaciones"), 6) { renderDonationPlayers(it, player, page) }
    }

    fun openHistory(player: ServerPlayer, filter: String? = null, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (filter == null) Lang.tr("&5Wonder Trade &8- &fHistorial") else Lang.t("&5Historial &8- &f{0}", filter)
        open(player, title, 6) { menu ->
            if (filter == null) renderHistoryPlayers(menu, player, page) else renderHistory(menu, player, filter, page)
        }
    }

    fun openMyHistory(player: ServerPlayer, page: Int = 0) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(player, Lang.tr("&5Wonder Trade &8- &eTu historial"), 6) { menu ->
            renderHistory(menu, player, player.gameProfile.name, page, own = true)
        }
    }

    fun openSounds(player: ServerPlayer) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(player, Lang.tr("&5Wonder Trade &8- &fSonidos"), 3) { menu -> renderSounds(menu, player) }
    }

    private fun renderSounds(menu: GuiMenu, player: ServerPlayer) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.NOTE_BLOCK, Lang.tr("&fSonidos de Wonder Trade"), Lang.tr("&7Configura qué efectos de sonido quieres escuchar.")))
        
        val options = listOf(
            "tradeSuccess" to ("&aIntercambio realizado" to Items.EMERALD),
            "specialReceived" to ("&dPokémon especial recibido" to Items.AMETHYST_SHARD),
            "cooldownReady" to ("&eEspera finalizada" to Items.CLOCK),
            "specialShinyReceived" to ("&ePokémon Shiny recibido" to Items.GOLD_INGOT)
        )

        options.forEachIndexed { index, (key, info) ->
            val (label, icon) = info
            val enabled = WonderTradeService.soundPreference(player, key)
            val slot = 10 + index * 2
            menu.button(slot, named(
                if (enabled) icon else Items.GRAY_DYE,
                (if (enabled) "&a✔ &f" else "&c✖ &f") + label,
                if (enabled) Lang.tr("&7Estado: &aActivado") else Lang.tr("&7Estado: &cDesactivado"),
                Lang.tr("&eClic para cambiar")
            )) { p ->
                val nowEnabled = WonderTradeService.toggleSoundPreference(p, key)
                renderSounds(menu, p)
                if (nowEnabled) playSound(p, key)
            }
        }

        val allEnabled = WonderTradeService.soundPreference(player, "all")
        menu.button(22, named(
            if (allEnabled) Items.LIME_DYE else Items.RED_DYE,
            if (allEnabled) Lang.tr("&aTodos los sonidos activados") else Lang.tr("&cTodos los sonidos silenciados"),
            Lang.tr("&7Activa o desactiva todos los sonidos"),
            Lang.tr("&eClic para cambiar")
        )) { p ->
            val nowEnabled = WonderTradeService.toggleSoundPreference(p, "all")
            renderSounds(menu, p)
            if (nowEnabled) playSound(p, "menuOpen")
        }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun open(player: ServerPlayer, title: String, rows: Int, openSound: String = "menuOpen", build: (GuiMenu) -> Unit) {
        val type = if (rows == 3) MenuType.GENERIC_9x3 else MenuType.GENERIC_9x6
        val menu = GuiMenu(type, title, player)
        build(menu)
        player.openMenu(menu)
        playSound(player, openSound)
    }

    // ---------------------------------------------------------------- pantallas

    private fun renderMain(menu: GuiMenu, player: ServerPlayer, donationMode: Boolean) {
        menu.wipe()
        fill(menu, 27)
        val bookLore = mutableListOf(
            ConfigManager.string(
                if (donationMode) "messages.donationBookDescription" else "messages.menuBookDescription",
                if (donationMode) Lang.tr("&7Elige un Pokémon para donarlo a la pool.") else Lang.tr("&7Elige un Pokémon para ofrecerlo. Recibirás otro a cambio.")
            )
        )
        if (!donationMode && ConfigManager.bool("gui.showBonusRatesInBook", true)) {
            if (!ConfigManager.bool("bonus.enabled", true)) {
                bookLore += ConfigManager.string("messages.menuBookBonusDisabled", "&cEl sistema de bonus está desactivado.")
            } else {
                bookLore += ConfigManager.string("messages.menuBookBonusHeader", "&7Bonus por ofrecer Pokémon:")
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookNormalGain", "&7Pokémon normal: &a+{gain}%"),
                    mapOf("gain" to WonderTradeService.normalBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookShinyGain", "&ePokémon shiny&7 (sin categoría especial): &f+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialGain", "&6Legendario&7 / &dMítico&7 / &3Ultraente&7 / &cParadoja&7: &f+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialShinyGain", "&6Especial shiny&7: &f+{gain}%"),
                    mapOf(
                        "gain" to WonderTradeService.specialShinyGainText(),
                        "total" to WonderTradeService.specialShinyTotalGainText()
                    )
                )
            }
        }
        if (!donationMode) WonderTradeService.rankInfoLine(player)?.let { bookLore += it }
        menu.put(4, named(
            Items.BOOK,
            ConfigManager.string("messages.menuBookTitle", if (donationMode) Lang.tr("&dDonar a Wonder Trade") else Lang.tr("&dWonder Trade")),
            *bookLore.toTypedArray()
        ))
        menu.markMainMenu(0)
        menu.updateCooldownDisplay(cooldownStack(player))

        val party = Cobblemon.storage.getParty(player)
        for (i in 0 until 6) {
            val guiSlot = 10 + i
            val pokemon = party.get(i)
            if (pokemon == null) {
                menu.put(guiSlot, named(Items.LIGHT_GRAY_STAINED_GLASS_PANE, Lang.tr("&8Hueco vacío")))
            } else {
                menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, false, donationMode) }
            }
        }

        menu.button(22, named(Items.ENDER_CHEST, Lang.tr("&bAbrir PC"), Lang.tr("&7Elige un Pokémon de tus cajas."))) { openPc(it, 0, donationMode) }
        if (canViewPool(player) || player.hasPermissions(WonderTradeService.adminLevel())) {
            menu.button(17, named(Items.CHEST, Lang.tr("&6Ver pool"), Lang.tr("&7Pokémon aportados por los jugadores."))) {
                openPool(it, 0, false)
            }
        }
        menu.button(18, named(Items.ENCHANTED_BOOK, Lang.tr("&dVer especiales"), Lang.tr("&7Qué Pokémon son míticos, legendarios,"), Lang.tr("&7Ultraentes y Paradoja."), Lang.tr("&eClic para ver"))) { openSpecials(it) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        if (ConfigManager.bool("history.enabled", true) && ConfigManager.bool("history.playersCanViewOwn", true)) {
            menu.button(24, named(Items.WRITABLE_BOOK, Lang.tr("&eTu historial"), Lang.tr("&7Tus últimos intercambios."), Lang.tr("&eClic para ver"))) { openMyHistory(it) }
        }
        menu.button(8, named(
            Items.COMPASS, Lang.t("&bIdioma: &f{0}", Lang.nameOf(Lang.languageOf(player) ?: Lang.current())),
            Lang.tr("&7Elige el idioma de tu Wonder Trade."), Lang.tr("&eClic para cambiar")
        )) { openLanguage(it) }
    }

    fun openAdmin(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, ConfigManager.string("messages.adminMenuTitle", "&5Wonder Trade &8- &cAdministración"), 6) { menu ->
            fill(menu, 54)
            fun btn(slot: Int, id: String, stack: ItemStack, action: (ServerPlayer) -> Unit) = adminButton(menu, player, slot, id, stack, action)
            val on = WonderTradeService.isEnabled()
            
            // Fila 1: pool
            menu.put(9, named(Items.ORANGE_STAINED_GLASS_PANE, Lang.tr("&6Pool")))
            btn(11, "pool", named(Items.CHEST, ConfigManager.string("messages.adminMenuPool", "&6Gestionar pool"), Lang.tr("&7Ver y administrar los Pokémon."))) {
                openPool(it, 0, true)
            }
            if (ConfigManager.bool("admin.fillMenu.enabled", true)) {
                btn(13, "fill", named(Items.HOPPER, Lang.tr("&aRellenar pool"), Lang.tr("&7Generar Pokémon automáticamente."), Lang.tr("&eAbrir opciones"))) {
                    openAdminFill(it)
                }
            }
            btn(15, "deletePool", named(Items.STRUCTURE_VOID, Lang.tr("&cEliminar pool"), Lang.tr("&7Elige qué origen quieres eliminar."), Lang.tr("&eAbrir opciones"))) {
                openAdminPoolDeleteOptions(it)
            }
            btn(17, "randomAdd", named(cobblemonItem("luxury_ball"), Lang.tr("&dAñadir Pokémon aleatorios"), Lang.tr("&7Genera Pokémon de categorías elegidas."), Lang.tr("&eAbrir selector"))) {
                openAdminRandomCategory(it)
            }

            // Fila 2: servidor
            menu.put(18, named(Items.MAGENTA_STAINED_GLASS_PANE, Lang.tr("&dServidor")))
            btn(20, "events", named(Items.NETHER_STAR, Lang.tr("&dGestionar eventos"), Lang.tr("&7Crea eventos temporales para todos."), Lang.tr("&eClic para abrir"))) {
                openAdminEvents(it)
            }

            // CASILLA 22 RESTAURADA (Top Donaciones)
            val topOn = WonderTradeTopDonations.enabled()
            val topStack = if (topOn) named(
                Items.GOLD_INGOT,
                Lang.tr("&6Top Donaciones: &aACTIVADO"),
                Lang.tr("&7El Top Donaciones mensual está activo."),
                Lang.tr("&eClic para desactivar")
            ) else named(
                Items.RAW_GOLD,
                Lang.tr("&6Top Donaciones: &cDESACTIVADO"),
                Lang.tr("&7El Top Donaciones mensual está desactivado."),
                Lang.tr("&eClic para activar")
            )
            btn(22, "topDonations", topStack) { p ->
                WonderTradeTopDonations.setEnabled(!WonderTradeTopDonations.enabled())
                openAdmin(p)
            }

            val toggleStack = if (on) named(Items.LIME_DYE, Lang.tr("&aWonder Trade: ACTIVADO"), Lang.tr("&7Los jugadores pueden intercambiar y donar."), Lang.tr("&eClic para desactivar"))
            else named(Items.RED_DYE, Lang.tr("&cWonder Trade: DESACTIVADO"), Lang.tr("&7Los jugadores no pueden intercambiar ni donar."), Lang.tr("&eClic para activar"))
            btn(24, "toggle", toggleStack) {
                WonderTradeService.setEnabled(!WonderTradeService.isEnabled())
                openAdmin(it)
            }
            btn(26, "penalties", named(
                Items.IRON_BARS, Lang.tr("&cPenalizaciones"), Lang.tr("&7Impide intercambiar y donar a un jugador"), Lang.tr("&7durante un tiempo o para siempre."), Lang.tr("&eClic para abrir")
            )) { openPenalties(it, WonderTradePenalties.selection(it.uuid) ?: defaultPenaltyMillis(), 0) }

            // Fila 3: datos
            menu.put(27, named(Items.YELLOW_STAINED_GLASS_PANE, Lang.tr("&eDatos")))
            btn(29, "history", named(Items.WRITABLE_BOOK, ConfigManager.string("messages.adminMenuHistory", "&eHistorial"), Lang.tr("&7Por jugador o historial general."))) {
                openHistory(it)
            }
            btn(31, "donations", named(Items.BOOK, Lang.tr("&aDonaciones"), Lang.tr("&7Qué ha donado cada jugador."), Lang.tr("&eClic para ver"))) {
                openDonationHistory(it)
            }
            btn(33, "resetStats", named(Items.KNOWLEDGE_BOOK, Lang.tr("&eReiniciar estadísticas"), Lang.tr("&7Reinicia las estadísticas de todos o de un jugador."), Lang.tr("&cNo se puede deshacer."), Lang.tr("&eClic para abrir"))) {
                openResetStatsMenu(it)
            }

            // Fila 4: sistema
            menu.put(36, named(Items.LIGHT_BLUE_STAINED_GLASS_PANE, Lang.tr("&bSistema")))
            btn(38, "reload", named(Items.COMPARATOR, ConfigManager.string("messages.adminMenuReload", "&bRecargar configuración"))) {
                val problems = WonderTradeService.reload()
                WonderTradeService.send(it, "reload")
                if (problems.isNotEmpty()) it.sendSystemMessage(WonderTradeService.info(Lang.t("&eLa revisión de la configuración ha encontrado &f{0} &eaviso(s). Mira la consola o usa /wt reload.", problems.size)))
                openAdmin(it)
            }
            btn(40, "maintenance", named(Items.REPEATING_COMMAND_BLOCK, ConfigManager.string("messages.adminMenuMaintenance", "&aEjecutar mantenimiento"), Lang.tr("&7Caducidad y purga de la pool."))) {
                val (expired, generated) = WonderTradeService.maintainPool(it.server)
                it.sendSystemMessage(WonderTradeService.info(Lang.t("&aMantenimiento: &f{0} &aPokémon eliminados, &f{1} &agenerados.", expired, generated)))
                openAdmin(it)
            }
            menu.button(49, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }
}
