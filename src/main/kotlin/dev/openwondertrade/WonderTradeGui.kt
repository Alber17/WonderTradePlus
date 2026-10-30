package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Menú de cofre (solo servidor): los jugadores no necesitan ningún mod extra.
 * Los iconos de Pokémon usan el ítem de Cobblemon, que ya tienen instalado en el cliente.
 */
class GuiMenu(
    type: MenuType<*>,
    id: Int,
    inventory: Inventory,
    private val backing: SimpleContainer,
    rows: Int
) : ChestMenu(type, id, inventory, backing as Container, rows) {
    private val actions = HashMap<Int, (ServerPlayer) -> Unit>()
    private val actionSounds = HashMap<Int, String>()
    var isMainMenu: Boolean = false
        private set
    private var cooldownDisplaySlot: Int = -1

    fun put(slot: Int, stack: ItemStack) {
        backing.setItem(slot, stack)
        actions.remove(slot)
        actionSounds.remove(slot)
    }

    fun button(slot: Int, stack: ItemStack, soundKey: String = "buttonClick", action: (ServerPlayer) -> Unit) {
        backing.setItem(slot, stack)
        actions[slot] = action
        actionSounds[slot] = soundKey
    }

    fun markMainMenu(cooldownSlot: Int) {
        isMainMenu = true
        cooldownDisplaySlot = cooldownSlot
    }

    fun updateCooldownDisplay(stack: ItemStack) {
        if (isMainMenu && cooldownDisplaySlot >= 0) backing.setItem(cooldownDisplaySlot, stack)
    }

    fun wipe() {
        backing.clearContent()
        actions.clear()
        actionSounds.clear()
        isMainMenu = false
        cooldownDisplaySlot = -1
    }

    // No llamamos a super: así es imposible mover, coger o duplicar objetos del menú.
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (clickType == ClickType.PICKUP && slotId >= 0 && slotId < backing.containerSize) {
            val action = actions[slotId]
            if (action != null && player is ServerPlayer) {
                WonderTradeGui.playSound(player, actionSounds[slotId] ?: "buttonClick")
                action(player)
            }
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot): Boolean = false
    override fun canDragTo(slot: Slot): Boolean = false
}

enum class PoolSort(val label: String) {
    NEWEST("&eHora añadida: descendente"),
    OLDEST("&eHora añadida: ascendente");

    fun next(): PoolSort = values()[(ordinal + 1) % values().size]
}

enum class PoolFilter(val label: String) {
    ALL("&eTodos"),
    SPECIAL("&dEspeciales"),
    NORMAL("&7Normales");

    fun next(): PoolFilter = values()[(ordinal + 1) % values().size]

    fun matches(entry: PoolEntry): Boolean = when (this) {
        ALL -> true
        SPECIAL -> WonderTradeRules.isRareEntry(entry)
        NORMAL -> !WonderTradeRules.isRareEntry(entry)
    }
}

object WonderTradeGui {
    private const val PC_BOX_SLOTS = 30
    private const val POOL_PER_PAGE = 45

    // ---------------------------------------------------------------- apertura

    fun openMain(player: ServerPlayer, donationMode: Boolean = false) = open(
        player,
        if (donationMode) "&5Wonder Trade &8- &fDonar" else "&5Wonder Trade &8- &fTu equipo",
        3,
        "menuOpen"
    ) { renderMain(it, player, donationMode) }

    fun openPc(player: ServerPlayer, box: Int = 0, donationMode: Boolean = false) = open(
        player,
        if (donationMode) "&5Wonder Trade &8- &fDonar desde PC" else "&5Wonder Trade &8- &fTu PC",
        6,
        "pcOpen"
    ) { renderPc(it, player, box, donationMode) }

    fun canViewPool(player: ServerPlayer): Boolean =
        player.hasPermissions(ConfigManager.int("gui.poolViewPermissionLevel", 0).coerceIn(0, 4))

    fun openPool(
        player: ServerPlayer,
        page: Int = 0,
        admin: Boolean = false,
        filter: PoolFilter = PoolFilter.ALL,
        deleteMode: Boolean = false,
        sort: PoolSort = PoolSort.NEWEST
    ) {
        val allowed = if (admin) player.hasPermissions(WonderTradeService.adminLevel()) else canViewPool(player)
        if (!allowed) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (admin) "&5Wonder Trade &8- &fPool &c(admin)" else "&5Wonder Trade &8- &fPool"
        open(player, title, 6) { renderPool(it, player, page, admin, filter, deleteMode, sort) }
    }

    fun openHistory(player: ServerPlayer, filter: String? = null, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (filter == null) "&5Wonder Trade &8- &fHistorial" else "&5Historial &8- &f$filter"
        open(player, title, 6) { renderHistory(it, player, filter, page) }
    }

    private fun open(player: ServerPlayer, title: String, rows: Int, openSound: String = "menuOpen", build: (GuiMenu) -> Unit) {
        val type = if (rows == 3) MenuType.GENERIC_9x3 else MenuType.GENERIC_9x6
        player.openMenu(SimpleMenuProvider({ id, inventory, _ ->
            val menu = GuiMenu(type, id, inventory, SimpleContainer(rows * 9), rows)
            build(menu)
            menu
        }, lit(title)))
        playSound(player, openSound)
    }

    // ---------------------------------------------------------------- pantallas

    private fun renderMain(menu: GuiMenu, player: ServerPlayer, donationMode: Boolean) {
        menu.wipe()
        fill(menu, 27)
        val bookLore = mutableListOf(
            ConfigManager.string(
                if (donationMode) "messages.donationBookDescription" else "messages.menuBookDescription",
                if (donationMode) "&7Elige un Pokémon para donarlo a la pool." else "&7Elige un Pokémon para ofrecerlo. Recibirás otro a cambio."
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
                    ConfigManager.string("messages.menuBookShinyGain", "&7Pokémon shiny (sin categoría especial): &d+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialGain", "&7Legendario / Mítico / Ultraente / Paradoja: &d+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialShinyGain", "&7Especial shiny: &d+{base}% + {extra}% extra = {total}%"),
                    mapOf(
                        "base" to WonderTradeService.specialBonusGainText(),
                        "extra" to WonderTradeService.specialShinyExtraGainText(),
                        "total" to WonderTradeService.specialShinyTotalGainText()
                    )
                )
            }
        }
        menu.put(4, named(
            Items.BOOK,
            ConfigManager.string("messages.menuBookTitle", if (donationMode) "&dDonar a Wonder Trade" else "&dWonder Trade"),
            *bookLore.toTypedArray()
        ))
        menu.markMainMenu(8)
        menu.updateCooldownDisplay(cooldownStack(player))

        val party = Cobblemon.storage.getParty(player)
        for (i in 0 until 6) {
            val guiSlot = 10 + i
            val pokemon = party.get(i)
            if (pokemon == null) {
                menu.put(guiSlot, named(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Hueco vacío"))
            } else {
                menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, false, donationMode) }
            }
        }

        menu.button(22, named(Items.ENDER_CHEST, "&bAbrir PC", "&7Elige un Pokémon de tus cajas.")) { openPc(it, 0, donationMode) }
        if (canViewPool(player) || player.hasPermissions(WonderTradeService.adminLevel())) {
            menu.button(17, named(Items.CHEST, "&6Ver pool", "&7Pokémon aportados por los jugadores.")) {
                openPool(it, 0, it.hasPermissions(WonderTradeService.adminLevel()))
            }
        }
        menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun renderPc(menu: GuiMenu, player: ServerPlayer, requestedBox: Int, donationMode: Boolean) {
        menu.wipe()
        fill(menu, 54)
        val boxes = Cobblemon.storage.getPC(player).boxes
        if (boxes.isEmpty()) {
            menu.put(22, named(Items.BARRIER, "&cNo tienes cajas en el PC"))
        } else {
            val index = requestedBox.coerceIn(0, boxes.size - 1)
            val contents = boxes[index].getNonEmptySlots()
            for (i in 0 until PC_BOX_SLOTS) {
                val guiSlot = (i / 6) * 9 + (i % 6)
                val pokemon = contents[i]
                if (pokemon == null) {
                    menu.put(guiSlot, named(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Vacío"))
                } else {
                    menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, true, donationMode) }
                }
            }
            menu.button(48, named(Items.ARROW, "&eCaja anterior"), "pageTurn") {
                renderPc(menu, it, (index - 1 + boxes.size) % boxes.size, donationMode)
            }
            menu.put(49, named(Items.PAPER, "&fCaja ${index + 1}/${boxes.size}"))
            menu.button(50, named(Items.ARROW, "&eCaja siguiente"), "pageTurn") {
                renderPc(menu, it, (index + 1) % boxes.size, donationMode)
            }
        }
        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, if (donationMode) "&aVolver a donar" else "&aVolver al equipo")) {
            openMain(it, donationMode)
        }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun renderPool(
        menu: GuiMenu,
        player: ServerPlayer,
        requestedPage: Int,
        admin: Boolean,
        filter: PoolFilter,
        deleteMode: Boolean,
        sort: PoolSort
    ) {
        menu.wipe()
        fill(menu, 54)
        val all = WonderTradeService.poolEntries()
        val filtered = all.filter { filter.matches(it) }
        val entries = when (sort) {
            PoolSort.NEWEST -> filtered.sortedByDescending { it.addedAt }
            PoolSort.OLDEST -> filtered.sortedBy { it.addedAt }
        }
        val pages = maxOf(1, (entries.size + POOL_PER_PAGE - 1) / POOL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val registryAccess = player.server.registryAccess()
        val showDonors = ConfigManager.bool("gui.showDonorNames", true)
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm")

        entries.drop(page * POOL_PER_PAGE).take(POOL_PER_PAGE).forEachIndexed { i, entry ->
            val extra = mutableListOf<String>()
            if (showDonors) extra += "&7Aportado por: &f${entry.donorName}"
            extra += "&7Fecha: &f${format.format(Date(entry.addedAt))}"
            val pokemon = runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull()
            if (admin && deleteMode) extra += "&c✖ Clic para eliminar del pool"
            else if (admin) extra += "&7Activa «Eliminar Pokémon» para poder borrarlo."
            val stack = if (pokemon != null) pokemonStack(pokemon, false, extra)
            else named(Items.BARRIER, "&c${entry.species}", "&7No se pudo cargar este Pokémon.")
            if (admin && deleteMode) {
                menu.button(i, stack) { p ->
                    if (p.hasPermissions(WonderTradeService.adminLevel())) {
                        WonderTradeService.removeFromPool(entry.id)
                        renderPool(menu, p, page, true, filter, true, sort)
                    }
                }
            } else {
                menu.put(i, stack)
            }
        }
        if (entries.isEmpty()) menu.put(22, named(Items.BARRIER, "&cNo hay Pokémon con este filtro"))

        val special = all.count { WonderTradeRules.isRareEntry(it) }
        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver")) {
            if (admin) openAdmin(it) else openMain(it)
        }
        if (admin) {
            val deleteButton = if (deleteMode) {
                named(Items.TNT, "&cEliminar Pokémon: ACTIVADO", "&7Haz clic en los Pokémon que quieras quitar.", "&eClic para desactivar")
            } else {
                named(Items.SHEARS, "&cEliminar Pokémon", "&7Activa el modo de eliminación.", "&7Después haz clic en los Pokémon que quieras quitar.")
            }
            menu.button(46, deleteButton) { p ->
                if (p.hasPermissions(WonderTradeService.adminLevel())) {
                    renderPool(menu, p, page, true, filter, !deleteMode, sort)
                }
            }
        }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior"), "pageTurn") { renderPool(menu, it, page - 1, admin, filter, deleteMode, sort) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} Pokémon con este filtro"))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente"), "pageTurn") { renderPool(menu, it, page + 1, admin, filter, deleteMode, sort) }
        val counts = mapOf(PoolFilter.ALL to all.size, PoolFilter.SPECIAL to special, PoolFilter.NORMAL to all.size - special)
        val options = PoolFilter.values().map { option ->
            if (option == filter) "&e▶ ${option.label} &f${counts[option]}"
            else "&8   ${option.label.drop(2)} ${counts[option]}"
        }
        menu.button(51, named(Items.CLOCK, "&fOrdenar pool", sort.label, "&7Ordena por fecha de incorporación.", "&eClic para cambiar")) {
            renderPool(menu, it, 0, admin, filter, deleteMode, sort.next())
        }
        menu.button(52, named(Items.HOPPER, "&fCambiar filtro de pool",
            *options.toTypedArray(),
            "&eClic para cambiar")) {
            renderPool(menu, it, 0, admin, filter.next(), deleteMode, sort)
        }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    fun openAdmin(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, ConfigManager.string("messages.adminMenuTitle", "&5Wonder Trade &8- &cAdministración"), 3) { menu ->
            fill(menu, 27)
            menu.button(10, named(Items.CHEST, ConfigManager.string("messages.adminMenuPool", "&6Gestionar pool"))) {
                openPool(it, 0, true)
            }
            menu.button(12, named(Items.WRITABLE_BOOK, ConfigManager.string("messages.adminMenuHistory", "&eHistorial"))) {
                openHistory(it)
            }
            menu.button(14, named(Items.COMPARATOR, ConfigManager.string("messages.adminMenuReload", "&bRecargar configuración"))) {
                WonderTradeService.reload()
                WonderTradeService.send(it, "reload")
                openAdmin(it)
            }
            menu.button(16, named(Items.HOPPER, ConfigManager.string("messages.adminMenuMaintenance", "&aEjecutar mantenimiento"))) {
                val (expired, generated) = WonderTradeService.maintainPool(it.server)
                it.sendSystemMessage(WonderTradeService.info("&aMantenimiento: &f$expired &aPokémon caducados."))
                openAdmin(it)
            }
            menu.button(20, named(Items.PAPER, ConfigManager.string("messages.adminMenuBackup", "&dCrear copia de seguridad"))) {
                val backup = WonderTradeService.backupNow()
                if (backup == null) it.sendSystemMessage(WonderTradeService.info("&cNo se pudo crear la copia."))
                else it.sendSystemMessage(WonderTradeService.info("&aCopia creada: &f$backup"))
                openAdmin(it)
            }
            menu.button(22, named(Items.TNT, ConfigManager.string("messages.adminMenuClear", "&cVaciar pool"))) {
                openClearPoolConfirm(it)
            }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openClearPoolConfirm(player: ServerPlayer) {
        open(player, "&cConfirmar vaciado de pool", 3) { menu ->
            fill(menu, 27)
            menu.button(11, named(Items.TNT, ConfigManager.string("messages.adminMenuClearConfirm", "&cConfirmar vaciado"))) {
                WonderTradeService.clearPool()
                WonderTradeService.send(it, "poolCleared")
                openAdmin(it)
            }
            menu.button(15, named(Items.LIME_STAINED_GLASS_PANE, ConfigManager.string("messages.adminMenuBack", "&aVolver"))) { openAdmin(it) }
        }
    }

    private fun cooldownStack(player: ServerPlayer): ItemStack {
        val remaining = WonderTradeService.cooldownDisplayText(player)
        val lore = ConfigManager.string("messages.cooldownMenuLore", "&7Tiempo restante: &f{time}")
            .replace("{time}", remaining)
        return named(Items.CLOCK, ConfigManager.string("messages.cooldownMenuTitle", "&bSiguiente Wonder Trade"), lore)
    }

    fun refreshCooldown(player: ServerPlayer) {
        val menu = player.containerMenu as? GuiMenu ?: return
        menu.updateCooldownDisplay(cooldownStack(player))
    }

    fun playSpecialSound(player: ServerPlayer, shiny: Boolean) {
        playSound(player, if (shiny) "specialShinyReceived" else "specialReceived")
    }

    internal fun playSound(player: ServerPlayer, key: String) {
        runCatching {
            if (!ConfigManager.bool("sounds.enabled", true) || !ConfigManager.bool("sounds.$key", true)) return
            val soundPath = when (key) {
                "menuOpen" -> "block.chest.open"
                "pcOpen" -> "block.ender_chest.open"
                "buttonClick" -> "ui.button.click"
                "pageTurn" -> "item.book.page_turn"
                "specialReceived" -> "entity.experience_orb.pickup"
                "specialShinyReceived" -> "item.totem.use"
                else -> return
            }
            val sound = BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.withDefaultNamespace(soundPath)).orElse(null) ?: return
            val volume = ConfigManager.double("sounds.volume", 0.45).coerceIn(0.0, 1.0).toFloat()
            val pitch = ConfigManager.double("sounds.pitch", 1.0).coerceIn(0.5, 2.0).toFloat()
            player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch)
        }.onFailure { WonderTradePlus.LOGGER.debug("No se pudo reproducir el sonido {} de Wonder Trade Plus.", key, it) }
    }

    private fun renderHistory(menu: GuiMenu, player: ServerPlayer, filter: String?, requestedPage: Int) {
        menu.wipe()
        fill(menu, 54)
        val entries = WonderTradeService.historyEntries(filter)
        val pages = maxOf(1, (entries.size + POOL_PER_PAGE - 1) / POOL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm")

        entries.drop(page * POOL_PER_PAGE).take(POOL_PER_PAGE).forEachIndexed { i, entry ->
            menu.put(i, historyStack(player, entry, dateFormat))
        }
        if (entries.isEmpty()) menu.put(22, named(Items.BARRIER, "&cNo hay intercambios registrados"))

        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver al panel admin")) { openAdmin(it) }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior"), "pageTurn") { renderHistory(menu, it, filter, page - 1) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} intercambios" + if (filter != null) " de $filter" else ""))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente"), "pageTurn") { renderHistory(menu, it, filter, page + 1) }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun historyStack(player: ServerPlayer, entry: HistoryEntry, dateFormat: SimpleDateFormat): ItemStack {
        // El icono es el Pokémon que RECIBIÓ el jugador; se recrea a partir del resumen guardado.
        val pokemon = runCatching {
            com.cobblemon.mod.common.api.pokemon.PokemonProperties().apply {
                this.species = entry.receivedSpecies
                this.level = entry.receivedLevel.coerceIn(1, 100)
                this.shiny = entry.receivedShiny
            }.create(player)
        }.getOrNull()
        val icon = pokemon?.let { runCatching { PokemonItem.from(it) }.getOrNull() } ?: ItemStack(Items.PAPER)
        val receivedTypes = pokemon?.let { WonderTradeRules.typesLabel(it) }

        val shinyLabel = "&e[Shiny]"
        icon.set(DataComponents.CUSTOM_NAME, lit("&b${entry.player}"))
        val lines = listOf(
            "&8────────────────",
            "&c▼ Dio: &f${entry.sentName} &7Nv.${entry.sentLevel}" + if (entry.sentShiny) " $shinyLabel" else "",
            "&a▲ Recibió: &f${entry.receivedName} &7Nv.${entry.receivedLevel}" + if (entry.receivedShiny) " $shinyLabel" else "",
            "&7Tipo: " + (receivedTypes ?: "&8desconocido"),
            "&8────────────────",
            "&7Origen: &f" + if (entry.fromPool) "pool de jugadores" else "generado al azar",
            "&7Fecha: &f${dateFormat.format(Date(entry.time))}"
        )
        icon.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return icon
    }

    private fun openConfirm(player: ServerPlayer, pokemon: Pokemon, fromPc: Boolean, donationMode: Boolean) {
        open(
            player,
            if (donationMode) "&5Wonder Trade &8- &fConfirmar donación" else "&5Wonder Trade &8- &fConfirmar",
            3
        ) { menu ->
            fill(menu, 27)
            menu.put(13, pokemonStack(pokemon, false))
            menu.button(11, named(
                Items.LIME_CONCRETE,
                if (donationMode) "&aConfirmar donación" else "&aConfirmar intercambio",
                "&7Ofreces: &f${pokemon.getDisplayName().string}",
                if (donationMode) "&7No recibirás ningún Pokémon a cambio." else "&7Recibirás otro Pokémon de la pool."
            )) { p ->
                if (donationMode) WonderTradeService.donateFromStore(p, fromPc, pokemon.uuid)
                else WonderTradeService.tradeFromStore(p, fromPc, pokemon.uuid)
                p.closeContainer()
            }
            menu.button(15, named(Items.RED_CONCRETE, "&cCancelar")) { openMain(it, donationMode) }
        }
    }

    // ---------------------------------------------------------------- lógica

    private fun select(player: ServerPlayer, pokemon: Pokemon, fromPc: Boolean, donationMode: Boolean) {
        val allowed = if (donationMode) WonderTradeService.precheckDonation(player, pokemon)
        else WonderTradeService.precheck(player, pokemon)
        if (!allowed) return
        if (ConfigManager.bool("general.requireConfirmation", true)) {
            openConfirm(player, pokemon, fromPc, donationMode)
        } else {
            if (donationMode) WonderTradeService.donateFromStore(player, fromPc, pokemon.uuid)
            else WonderTradeService.tradeFromStore(player, fromPc, pokemon.uuid)
            player.closeContainer()
        }
    }

    // ---------------------------------------------------------------- ítems

    private fun lit(value: String): Component =
        Component.literal(value.replace('&', '§')).setStyle(Style.EMPTY.withItalic(false))

    private fun named(item: Item, name: String, vararg lore: String): ItemStack {
        val stack = ItemStack(item)
        stack.set(DataComponents.CUSTOM_NAME, lit(name))
        if (lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(lore.map<String, Component> { lit(it) }))
        return stack
    }

    private fun fill(menu: GuiMenu, size: Int) {
        for (i in 0 until size) menu.put(i, named(Items.BLACK_STAINED_GLASS_PANE, " "))
    }

    private fun pokemonStack(pokemon: Pokemon, showOfferHint: Boolean, extra: List<String> = emptyList()): ItemStack {
        val stack = runCatching { PokemonItem.from(pokemon) }.getOrElse { ItemStack(Items.PAPER) }
        val name = pokemon.getDisplayName().string
        stack.set(DataComponents.CUSTOM_NAME, lit((if (pokemon.shiny) "&e" else "&f") + name))

        val lines = mutableListOf("&7Nivel: &f${pokemon.level}")
        WonderTradeRules.typesLabel(pokemon)?.let { lines += "&7Tipo: $it" }
        WonderTradeRules.formLabel(pokemon)?.let { lines += "&7Forma: &f$it" }
        val labels = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { labels += it }
        if (pokemon.shiny) labels += "&eShiny"
        if (labels.isNotEmpty()) lines += "&7Categoría: " + labels.joinToString(" &7· ")
        if (pokemon.isAlpha) lines += "&cAlfa"
        lines += extra
        if (showOfferHint) {
            lines += if (WonderTradeRules.validateOffer(pokemon) != null) "&c✖ No se puede ofrecer ahora" else "&a▶ Clic para ofrecer"
        }
        stack.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return stack
    }
}
