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

object WonderTradeGui {
    private const val PC_BOX_SLOTS = 30
    private const val POOL_PER_PAGE = 45

    // ---------------------------------------------------------------- apertura

    fun openMain(player: ServerPlayer) = open(player, "&5Wonder Trade &8- &fTu equipo", 3) { renderMain(it, player) }

    fun openPc(player: ServerPlayer, box: Int = 0) = open(player, "&5Wonder Trade &8- &fTu PC", 6) { renderPc(it, player, box) }

    fun canViewPool(player: ServerPlayer): Boolean =
        player.hasPermissions(ConfigManager.int("gui.poolViewPermissionLevel", 0).coerceIn(0, 4))

    fun openPool(player: ServerPlayer, page: Int = 0) {
        if (!canViewPool(player)) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, "&5Wonder Trade &8- &fPool", 6) { renderPool(it, player, page) }
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
            menu.button(24, named(Items.CHEST, "&6Ver pool", "&7Pokémon aportados por los jugadores.")) { openPool(it, 0) }
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

    private fun renderPool(menu: GuiMenu, player: ServerPlayer, requestedPage: Int) {
        menu.wipe()
        fill(menu, 54)
        val entries = WonderTradeService.poolEntries().sortedByDescending { it.addedAt }
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
            val stack = if (pokemon != null) pokemonStack(pokemon, false, extra)
            else named(Items.BARRIER, "&c${entry.species}", "&7No se pudo cargar este Pokémon.")
            menu.put(i, stack)
        }

        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver")) { openMain(it) }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior")) { renderPool(menu, it, page - 1) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} Pokémon en el pool"))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente")) { renderPool(menu, it, page + 1) }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
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
        if (pokemon.shiny) lines += "&eShiny"
        if (WonderTradeRules.isSpecial(pokemon)) lines += "&dEspecial"
        if (pokemon.isAlpha) lines += "&cAlfa"
        lines += extra
        if (showOfferHint) {
            lines += if (WonderTradeRules.validateOffer(pokemon) != null) "&c✖ No se puede ofrecer ahora" else "&a▶ Clic para ofrecer"
        }
        stack.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return stack
    }
}
