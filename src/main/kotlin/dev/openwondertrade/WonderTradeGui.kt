package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.component.DataComponents
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

    fun put(slot: Int, stack: ItemStack) {
        backing.setItem(slot, stack)
        actions.remove(slot)
    }

    fun button(slot: Int, stack: ItemStack, action: (ServerPlayer) -> Unit) {
        backing.setItem(slot, stack)
        actions[slot] = action
    }

    fun wipe() {
        backing.clearContent()
        actions.clear()
    }

    // No llamamos a super: así es imposible mover, coger o duplicar objetos del menú.
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (clickType == ClickType.PICKUP && slotId >= 0 && slotId < backing.containerSize) {
            val action = actions[slotId]
            if (action != null && player is ServerPlayer) action(player)
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot): Boolean = false
    override fun canDragTo(slot: Slot): Boolean = false
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

    fun openMain(player: ServerPlayer) = open(player, "&5Wonder Trade &8- &fTu equipo", 3) { renderMain(it, player) }

    fun openPc(player: ServerPlayer, box: Int = 0) = open(player, "&5Wonder Trade &8- &fTu PC", 6) { renderPc(it, player, box) }

    fun canViewPool(player: ServerPlayer): Boolean =
        player.hasPermissions(ConfigManager.int("gui.poolViewPermissionLevel", 0).coerceIn(0, 4))

    fun openPool(player: ServerPlayer, page: Int = 0, admin: Boolean = false, filter: PoolFilter = PoolFilter.ALL) {
        val allowed = if (admin) player.hasPermissions(WonderTradeService.adminLevel()) else canViewPool(player)
        if (!allowed) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (admin) "&5Wonder Trade &8- &fPool &c(admin)" else "&5Wonder Trade &8- &fPool"
        open(player, title, 6) { renderPool(it, player, page, admin, filter) }
    }

    fun openHistory(player: ServerPlayer, filter: String? = null, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (filter == null) "&5Wonder Trade &8- &fHistorial" else "&5Historial &8- &f$filter"
        open(player, title, 6) { renderHistory(it, player, filter, page) }
    }

    private fun open(player: ServerPlayer, title: String, rows: Int, build: (GuiMenu) -> Unit) {
        val type = if (rows == 3) MenuType.GENERIC_9x3 else MenuType.GENERIC_9x6
        player.openMenu(SimpleMenuProvider({ id, inventory, _ ->
            val menu = GuiMenu(type, id, inventory, SimpleContainer(rows * 9), rows)
            build(menu)
            menu
        }, lit(title)))
    }

    // ---------------------------------------------------------------- pantallas

    private fun renderMain(menu: GuiMenu, player: ServerPlayer) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.BOOK, "&dWonder Trade",
            "&7Elige un Pokémon para ofrecerlo.",
            "&7Recibirás otro a cambio."))

        val party = Cobblemon.storage.getParty(player)
        for (i in 0 until 6) {
            val guiSlot = 10 + i
            val pokemon = party.get(i)
            if (pokemon == null) {
                menu.put(guiSlot, named(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Hueco vacío"))
            } else {
                menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, false) }
            }
        }

        menu.button(22, named(Items.ENDER_CHEST, "&bAbrir PC", "&7Elige un Pokémon de tus cajas.")) { openPc(it, 0) }
        if (canViewPool(player)) {
            menu.button(24, named(Items.CHEST, "&6Ver pool", "&7Pokémon aportados por los jugadores.")) { openPool(it, 0, false) }
        }
        menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun renderPc(menu: GuiMenu, player: ServerPlayer, requestedBox: Int) {
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
                    menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, true) }
                }
            }
            menu.button(48, named(Items.ARROW, "&eCaja anterior")) {
                renderPc(menu, it, (index - 1 + boxes.size) % boxes.size)
            }
            menu.put(49, named(Items.PAPER, "&fCaja ${index + 1}/${boxes.size}"))
            menu.button(50, named(Items.ARROW, "&eCaja siguiente")) {
                renderPc(menu, it, (index + 1) % boxes.size)
            }
        }
        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver al equipo")) { openMain(it) }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun renderPool(menu: GuiMenu, player: ServerPlayer, requestedPage: Int, admin: Boolean, filter: PoolFilter) {
        menu.wipe()
        fill(menu, 54)
        val all = WonderTradeService.poolEntries()
        val entries = all.filter { filter.matches(it) }.sortedByDescending { it.addedAt }
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
            if (admin) extra += "&c✖ Clic para eliminar del pool"
            val stack = if (pokemon != null) pokemonStack(pokemon, false, extra)
            else named(Items.BARRIER, "&c${entry.species}", "&7No se pudo cargar este Pokémon.")
            if (admin) {
                menu.button(i, stack) { p ->
                    if (p.hasPermissions(WonderTradeService.adminLevel())) {
                        WonderTradeService.removeFromPool(entry.id)
                        renderPool(menu, p, page, true, filter)
                    }
                }
            } else {
                menu.put(i, stack)
            }
        }
        if (entries.isEmpty()) menu.put(22, named(Items.BARRIER, "&cNo hay Pokémon con este filtro"))

        val special = all.count { WonderTradeRules.isRareEntry(it) }
        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver")) { openMain(it) }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior")) { renderPool(menu, it, page - 1, admin, filter) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} Pokémon con este filtro"))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente")) { renderPool(menu, it, page + 1, admin, filter) }
        menu.button(52, named(Items.HOPPER, "&fFiltro: ${filter.label}",
            "&7Total: &f${all.size}",
            "&dEspeciales: &f$special",
            "&7Normales: &f${all.size - special}",
            "&8(shiny, legendarios, míticos,",
            "&8Ultraentes y Paradoja)",
            "&eClic para cambiar")) { renderPool(menu, it, 0, admin, filter.next()) }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
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

        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aCerrar historial")) { it.closeContainer() }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior")) { renderHistory(menu, it, filter, page - 1) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} intercambios" + if (filter != null) " de $filter" else ""))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente")) { renderHistory(menu, it, filter, page + 1) }
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

        val star = "&e✨"
        icon.set(DataComponents.CUSTOM_NAME, lit("&b${entry.player}"))
        val lines = listOf(
            "&8────────────────",
            "&c▼ Dio: &f${entry.sentName} &7Nv.${entry.sentLevel}" + if (entry.sentShiny) " $star" else "",
            "&a▲ Recibió: &f${entry.receivedName} &7Nv.${entry.receivedLevel}" + if (entry.receivedShiny) " $star" else "",
            "&7Tipo: " + (receivedTypes ?: "&8desconocido"),
            "&8────────────────",
            "&7Origen: &f" + if (entry.fromPool) "pool de jugadores" else "generado al azar",
            "&7Fecha: &f${dateFormat.format(Date(entry.time))}"
        )
        icon.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return icon
    }

    private fun openConfirm(player: ServerPlayer, pokemon: Pokemon, fromPc: Boolean) {
        open(player, "&5Wonder Trade &8- &fConfirmar", 3) { menu ->
            fill(menu, 27)
            menu.put(13, pokemonStack(pokemon, false))
            menu.button(11, named(Items.LIME_CONCRETE, "&aConfirmar intercambio",
                "&7Ofreces: &f${pokemon.getDisplayName().string}")) { p ->
                WonderTradeService.tradeFromStore(p, fromPc, pokemon.uuid)
                p.closeContainer()
            }
            menu.button(15, named(Items.RED_CONCRETE, "&cCancelar")) { openMain(it) }
        }
    }

    // ---------------------------------------------------------------- lógica

    private fun select(player: ServerPlayer, pokemon: Pokemon, fromPc: Boolean) {
        if (!WonderTradeService.precheck(player, pokemon)) return
        if (ConfigManager.bool("general.requireConfirmation", true)) {
            openConfirm(player, pokemon, fromPc)
        } else {
            WonderTradeService.tradeFromStore(player, fromPc, pokemon.uuid)
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
        stack.set(DataComponents.CUSTOM_NAME, lit((if (pokemon.shiny) "&e✨ " else "&f") + name))

        val lines = mutableListOf("&7Nivel: &f${pokemon.level}")
        WonderTradeRules.typesLabel(pokemon)?.let { lines += "&7Tipo: $it" }
        WonderTradeRules.formLabel(pokemon)?.let { lines += "&7Forma: &f$it" }
        val labels = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { labels += it }
        if (pokemon.shiny) labels += "&e✨ Shiny"
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
