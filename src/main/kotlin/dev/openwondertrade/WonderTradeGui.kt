package dev.openwondertrade

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.ClickEvent
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
import java.util.UUID

/**
 * Menú de cofre controlado por el servidor.
 * La interfaz se ejecuta íntegramente desde el servidor; los jugadores no instalan este mod.
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

    fun openMain(player: ServerPlayer, donationMode: Boolean = false) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(
            player,
            if (donationMode) "&5Wonder Trade &8- &fDonar" else "&5Wonder Trade &8- &fTu equipo",
            3,
            "menuOpen"
        ) { renderMain(it, player, donationMode) }
    }

    fun openPc(player: ServerPlayer, box: Int = 0, donationMode: Boolean = false) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(
            player,
            if (donationMode) "&5Wonder Trade &8- &fDonar desde PC" else "&5Wonder Trade &8- &fTu PC",
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
        val allowed = if (admin) player.hasPermissions(WonderTradeService.adminLevel()) else canViewPool(player)
        if (!admin && !WonderTradeService.ensureEnabled(player)) return
        if (!allowed) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (admin) "&5Wonder Trade &8- &fPool &c(admin)" else "&5Wonder Trade &8- &fPool"
        open(player, title, 6) { renderPool(it, player, page, admin, filter, deleteMode, sort, query) }
    }

    fun openHistory(player: ServerPlayer, filter: String? = null, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (filter == null) "&5Wonder Trade &8- &fHistorial" else "&5Historial &8- &f$filter"
        open(player, title, 6) { renderHistory(it, player, filter, page) }
    }

    fun openSounds(player: ServerPlayer) {
        open(player, "&5Wonder Trade &8- &fSonidos", 3) { menu -> renderSounds(menu, player) }
    }

    private fun renderSounds(menu: GuiMenu, player: ServerPlayer) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(
            Items.NOTE_BLOCK,
            "&dPreferencias de sonido",
            "&7Configura qué sonidos escuchas tú.",
            "&8Tus cambios se guardan automáticamente."
        ))

        data class SoundOption(val key: String, val slot: Int, val item: Item, val title: String, val description: String)
        val options = listOf(
            SoundOption("menuOpen", 10, Items.CHEST, "&fApertura de menús", "&7Sonido al abrir los menús y cofres."),
            SoundOption("pcOpen", 11, Items.ENDER_CHEST, "&fApertura del PC", "&7Sonido del cofre de Ender al abrir el PC."),
            SoundOption("buttonClick", 12, Items.STONE_BUTTON, "&fClic de botones", "&7Sonido al pulsar botones de los menús."),
            SoundOption("pageTurn", 14, Items.WRITABLE_BOOK, "&fCambio de página", "&7Sonido al cambiar de página."),
            SoundOption("specialReceived", 15, Items.EXPERIENCE_BOTTLE, "&fPokémon especial", "&7Sonido al recibir un legendario, mítico, Ultraente o Paradoja."),
            SoundOption("specialShinyReceived", 16, Items.TOTEM_OF_UNDYING, "&fEspecial shiny", "&7Sonido de tótem al recibir un legendario, mítico, Ultraente o Paradoja shiny. Un shiny normal usa el sonido de especial.")
        )
        for (option in options) {
            val enabled = WonderTradeService.soundPreference(player, option.key)
            val status = if (enabled) "&aACTIVADO" else "&cDESACTIVADO"
            menu.button(option.slot, named(
                option.item,
                option.title,
                option.description,
                "",
                "&7Estado: $status",
                "&eClic para cambiar"
            )) { p ->
                val nowEnabled = WonderTradeService.toggleSoundPreference(p, option.key)
                renderSounds(menu, p)
                if (nowEnabled) playSound(p, option.key)
            }
        }

        val allEnabled = WonderTradeService.soundPreference(player, "all")
        menu.button(22, named(
            if (allEnabled) Items.LIME_DYE else Items.RED_DYE,
            if (allEnabled) "&aTodos los sonidos activados" else "&cTodos los sonidos silenciados",
            "&7Activa o desactiva todos los sonidos",
            "&eClic para cambiar"
        )) { p ->
            val nowEnabled = WonderTradeService.toggleSoundPreference(p, "all")
            renderSounds(menu, p)
            if (nowEnabled) playSound(p, "menuOpen")
        }
        menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
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
        if (!donationMode) WonderTradeService.rankInfoLine(player)?.let { bookLore += it }
        menu.put(4, named(
            Items.BOOK,
            ConfigManager.string("messages.menuBookTitle", if (donationMode) "&dDonar a Wonder Trade" else "&dWonder Trade"),
            *bookLore.toTypedArray()
        ))
        menu.markMainMenu(0)
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
                openPool(it, 0, false)
            }
        }
        menu.button(24, named(Items.ENCHANTED_BOOK, "&dVer especiales", "&7Qué Pokémon son míticos, legendarios,", "&7Ultraentes y Paradoja.", "&eClic para ver")) { openSpecials(it) }
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
        sort: PoolSort,
        query: String? = null
    ) {
        menu.wipe()
        fill(menu, 54)
        val all = WonderTradeService.poolEntries()
        val filtered = all.filter { filter.matches(it) && matchesQuery(it, query) }
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
            else if (admin) extra += "&7Activa &c&l«Eliminar Pokémon»&r&7 para poder borrarlo."
            val stack = if (pokemon != null) pokemonStack(pokemon, false, extra)
            else named(Items.BARRIER, "&c${entry.species}", "&7No se pudo cargar este Pokémon.")
            if (admin && deleteMode) {
                menu.button(i, stack) { p ->
                    if (p.hasPermissions(WonderTradeService.adminLevel())) {
                        WonderTradeService.removeFromPool(entry.id)
                        renderPool(menu, p, page, true, filter, true, sort, query)
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
                    renderPool(menu, p, page, true, filter, !deleteMode, sort, query)
                }
            }
        }
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior"), "pageTurn") { renderPool(menu, it, page - 1, admin, filter, deleteMode, sort, query) }
        menu.put(49, named(Items.PAPER, "&fPágina ${page + 1}/$pages", "&7${entries.size} Pokémon con este filtro"))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente"), "pageTurn") { renderPool(menu, it, page + 1, admin, filter, deleteMode, sort, query) }
        val counts = mapOf(PoolFilter.ALL to all.size, PoolFilter.SPECIAL to special, PoolFilter.NORMAL to all.size - special)
        val options = PoolFilter.values().map { option ->
            if (option == filter) "&e▶ ${option.label} &f${counts[option]}"
            else "&8   ${option.label.drop(2)} ${counts[option]}"
        }
        val sortOptions = PoolSort.values().map { option ->
            if (option == sort) "&e▶ ${option.label}" else "&8   ${option.label.drop(2)}"
        }
        menu.button(51, named(Items.CLOCK, "&fOrdenar pool", *sortOptions.toTypedArray(), "&7Ordena por fecha de incorporación.", "&eClic para cambiar")) {
            renderPool(menu, it, 0, admin, filter, deleteMode, sort.next(), query)
        }
        if (query != null) {
            menu.button(47, named(Items.NAME_TAG, "&fBúsqueda: &e$query", "&7Mostrando solo los Pokémon que coinciden.", "&eClic para quitar la búsqueda")) {
                renderPool(menu, it, 0, admin, filter, deleteMode, sort, null)
            }
        } else {
            menu.button(47, named(Items.NAME_TAG, "&fBuscar por nombre o especie", "&7Encuentra un Pokémon concreto del pool.", "&eClic para buscar")) {
                startSearch(it, admin, filter, deleteMode, sort)
            }
        }
        menu.button(52, named(Items.HOPPER, "&fCambiar filtro de pool",
            *options.toTypedArray(),
            "&eClic para cambiar")) {
            renderPool(menu, it, 0, admin, filter.next(), deleteMode, sort, query)
        }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    // ---------------------------------------------------------------- búsqueda en la pool

    private data class SearchContext(
        val admin: Boolean,
        val filter: PoolFilter,
        val deleteMode: Boolean,
        val sort: PoolSort,
        val expiresAt: Long
    )

    private val pendingSearch = HashMap<UUID, SearchContext>()

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** Coincide por especie ("greattusk", "great tusk", "tusk") o por apodo del Pokémon. */
    private fun matchesQuery(entry: PoolEntry, query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        if (normalize(entry.species.substringAfter(':')).contains(needle)) return true
        val nickname = entry.pokemonJson.entrySet().firstOrNull { it.key.equals("nickname", ignoreCase = true) }?.value?.toString()
            ?: return false
        return normalize(nickname).contains(needle)
    }

    /** Cierra el menú y envía un mensaje clicable que rellena el comando de búsqueda en el chat. */
    private fun startSearch(player: ServerPlayer, admin: Boolean, filter: PoolFilter, deleteMode: Boolean, sort: PoolSort) {
        pendingSearch[player.uuid] = SearchContext(admin, filter, deleteMode, sort, System.currentTimeMillis() + 120_000L)
        player.closeContainer()
        val button = Component.literal("§e§n[Pulsa aquí para buscar]")
            .setStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/wt pool search ")))
        player.sendSystemMessage(
            WonderTradeService.colour(ConfigManager.string("messages.prefix", "") + "&7Escribe el nombre o la especie del Pokémon. ").copy().append(button)
        )
    }

    /** Abre la pool con el texto buscado, conservando el modo (normal o admin), filtro y orden desde donde se pulsó el botón. */
    fun openSearch(player: ServerPlayer, text: String) {
        val context = pendingSearch.remove(player.uuid)?.takeIf { it.expiresAt >= System.currentTimeMillis() }
        openPool(
            player, 0,
            context?.admin ?: false,
            context?.filter ?: PoolFilter.ALL,
            context?.deleteMode ?: false,
            context?.sort ?: PoolSort.NEWEST,
            text.trim()
        )
    }

    // ---------------------------------------------------------------- categorías especiales

    private val specialCategories = listOf(
        Triple("ultraBeast", "&3Ultraentes", Items.ECHO_SHARD),
        Triple("paradox", "&cParadoja", Items.ENDER_PEARL),
        Triple("mythical", "&dMíticos", Items.AMETHYST_SHARD),
        Triple("legendary", "&6Legendarios", Items.GOLD_INGOT)
    )

    fun openSpecials(player: ServerPlayer) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(player, "&5Wonder Trade &8- &dEspeciales", 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(
                Items.ENCHANTED_BOOK, "&dPokémon especiales",
                "&7Estas categorías son las que cuentan en los",
                "&7eventos «Arriesgar» y «Recibir» de Wonder Trade.",
                "&7Un Pokémon shiny cuenta para los eventos Shiny."
            ))
            val groups = WonderTradeService.speciesByCategory(player)
            val slots = listOf(10, 12, 14, 16)
            specialCategories.forEachIndexed { index, (key, label, icon) ->
                val count = groups[key]?.size ?: 0
                menu.button(slots[index], named(icon, label, "&7Pokémon en esta categoría: &f$count", "&eClic para ver la lista")) {
                    openSpecialList(it, key, 0)
                }
            }
            menu.button(22, named(Items.ARROW, "&aVolver")) { openMain(it) }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openSpecialList(player: ServerPlayer, key: String, page: Int) {
        val label = specialCategories.firstOrNull { it.first == key }?.second ?: key
        open(player, "&5Especiales &8- $label", 6) { renderSpecialList(it, player, key, page) }
    }

    private fun renderSpecialList(menu: GuiMenu, player: ServerPlayer, key: String, requestedPage: Int) {
        menu.wipe()
        fill(menu, 54)
        val list = WonderTradeService.speciesByCategory(player)[key].orEmpty()
        val pages = maxOf(1, (list.size + POOL_PER_PAGE - 1) / POOL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val categoryLabel = specialCategories.firstOrNull { it.first == key }?.second ?: key
        list.drop(page * POOL_PER_PAGE).take(POOL_PER_PAGE).forEachIndexed { index, pokemon ->
            val stack = runCatching { PokemonItem.from(pokemon) }.getOrElse { ItemStack(Items.PAPER) }
            stack.set(DataComponents.CUSTOM_NAME, lit("&f" + pokemon.getDisplayName().string))
            val lines = mutableListOf("&7Categoría: $categoryLabel")
            WonderTradeRules.typesLabel(pokemon)?.let { lines += "&7Tipo: $it" }
            stack.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
            menu.put(index, stack)
        }
        if (list.isEmpty()) menu.put(22, named(Items.BARRIER, "&cNo hay Pokémon en esta categoría"))
        if (page > 0) menu.button(48, named(Items.ARROW, "&ePágina anterior"), "pageTurn") { renderSpecialList(menu, it, key, page - 1) }
        menu.put(49, named(Items.PAPER, "&7Página ${page + 1}/$pages", "&7$categoryLabel&7: &f${list.size}"))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, "&ePágina siguiente"), "pageTurn") { renderSpecialList(menu, it, key, page + 1) }
        menu.button(45, named(Items.ARROW, "&aVolver")) { openSpecials(it) }
        menu.button(53, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    fun openTop(player: ServerPlayer) {
        open(player, "&5Wonder Trade &8- &6Rankings", 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.BOOK, "&6Rankings de Wonder Trade", "&7Selecciona qué clasificación quieres consultar."))
            menu.button(11, named(Items.GOLD_INGOT, "&6Top intercambios", "&7Jugadores con más intercambios realizados.", "&eClic para consultar")) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("trades", p.uuid))
            }
            menu.button(13, named(Items.DIAMOND, "&bTop especiales recibidos", "&7Jugadores que más Pokémon especiales han recibido.", "&eClic para consultar")) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("received", p.uuid))
            }
            menu.button(15, named(Items.EMERALD, "&aTop especiales aportados", "&7Jugadores que más Pokémon especiales han aportado.", "&eClic para consultar")) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("donated", p.uuid))
            }
            menu.button(22, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    fun openAdmin(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, ConfigManager.string("messages.adminMenuTitle", "&5Wonder Trade &8- &cAdministración"), 6) { menu ->
            fill(menu, 54)
            val on = WonderTradeService.isEnabled()
            menu.put(4, named(
                Items.NETHER_STAR, "&cPanel de administración",
                "&7Wonder Trade: ${if (on) "&aactivado" else "&cdesactivado"}"
            ))

            // Fila 1: pool
            menu.put(9, named(Items.ORANGE_STAINED_GLASS_PANE, "&6Pool"))
            menu.button(10, named(Items.CHEST, ConfigManager.string("messages.adminMenuPool", "&6Gestionar pool"), "&7Ver y administrar los Pokémon.")) {
                openPool(it, 0, true)
            }
            if (ConfigManager.bool("admin.fillMenu.enabled", true)) {
                menu.button(11, named(Items.HOPPER, "&aRellenar pool", "&7Generar Pokémon automáticamente.", "&eAbrir opciones")) {
                    openAdminFill(it)
                }
            }
            menu.button(12, named(Items.BARRIER, "&cEliminar Pokémon", "&7Selecciona las entradas que quieras borrar.")) {
                openPool(it, 0, true, deleteMode = true)
            }
            menu.button(13, named(Items.PLAYER_HEAD, "&cEliminar aportaciones", "&7Borrar de la pool los Pokémon de un donante.")) {
                openAdminDonors(it)
            }
            menu.button(14, named(Items.TNT, ConfigManager.string("messages.adminMenuClear", "&cVaciar pool"), "&7Elimina todos los Pokémon.", "&cPide confirmación.")) {
                openClearPoolConfirm(it)
            }

            // Fila 2: servidor
            menu.put(18, named(Items.MAGENTA_STAINED_GLASS_PANE, "&dServidor"))
            menu.button(19, named(Items.NETHER_STAR, "&dGestionar eventos", "&7Crea eventos temporales para todos.", "&eClic para abrir")) {
                openAdminEvents(it)
            }
            val toggleStack = if (on) named(Items.LIME_DYE, "&aWonder Trade: ACTIVADO", "&7Los jugadores pueden intercambiar y donar.", "&eClic para desactivar")
            else named(Items.RED_DYE, "&cWonder Trade: DESACTIVADO", "&7Los jugadores no pueden intercambiar ni donar.", "&eClic para activar")
            menu.button(20, toggleStack) {
                WonderTradeService.setEnabled(!WonderTradeService.isEnabled())
                openAdmin(it)
            }
            menu.button(21, named(Items.WRITABLE_BOOK, ConfigManager.string("messages.adminMenuHistory", "&eHistorial"), "&7Últimos intercambios.")) {
                openHistory(it)
            }
            menu.button(22, named(Items.BOOK, "&eReiniciar estadísticas", "&7Reinicia todos los rankings y estadísticas.", "&cNo se puede deshacer.")) {
                openResetStatsConfirm(it)
            }

            // Fila 3: sistema
            menu.put(27, named(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "&bSistema"))
            menu.button(28, named(Items.COMPARATOR, ConfigManager.string("messages.adminMenuReload", "&bRecargar configuración"))) {
                WonderTradeService.reload()
                WonderTradeService.send(it, "reload")
                openAdmin(it)
            }
            menu.button(29, named(Items.HOPPER, ConfigManager.string("messages.adminMenuMaintenance", "&aEjecutar mantenimiento"), "&7Caducidad y purga de la pool.")) {
                val (expired, generated) = WonderTradeService.maintainPool(it.server)
                it.sendSystemMessage(WonderTradeService.info("&aMantenimiento: &f$expired &aPokémon eliminados, &f$generated &agenerados."))
                openAdmin(it)
            }
            menu.button(30, named(Items.PAPER, ConfigManager.string("messages.adminMenuBackup", "&dCrear copia de seguridad"))) {
                val backup = WonderTradeService.backupNow()
                if (backup == null) it.sendSystemMessage(WonderTradeService.info("&cNo se pudo crear la copia."))
                else it.sendSystemMessage(WonderTradeService.info("&aCopia creada: &f$backup"))
                openAdmin(it)
            }
            menu.button(31, named(Items.NOTE_BLOCK, "&9Probar Discord", "&7Enviar un mensaje de prueba al webhook.")) { p ->
                WonderTradeService.discordTest { ok, message ->
                    p.server.execute {
                        p.sendSystemMessage(WonderTradeService.info(if (ok) "&a$message" else "&c$message"))
                    }
                }
                p.sendSystemMessage(WonderTradeService.info("&7Enviando mensaje de prueba a Discord…"))
            }

            menu.button(49, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openAdminEvents(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, "&5Administración &8- &dEventos", 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(
                Items.NETHER_STAR, "&dEventos de Wonder Trade",
                "&7Eventos temporales para todos los jugadores.",
                "&7Valores personalizados:",
                "&e/wt admin eventos crear <tipo> <duración> [valor] [espera]"
            ))
            if (!WonderTradeEvents.enabled()) {
                menu.put(13, named(Items.BARRIER, "&cLos eventos están desactivados", "&7Activa events.enabled en config.json."))
            } else {
                val icons = mapOf(EventType.RISK to Items.GOLD_NUGGET, EventType.RECEIVE to Items.AMETHYST_SHARD)
                val types = EventType.values()
                types.forEachIndexed { index, type ->
                    menu.button(if (index == 0) 10 else 12, named(
                        icons[type] ?: Items.PAPER, "&d${type.title}",
                        *eventTypeDescription(type).toTypedArray(), "&eClic para elegir la categoría"
                    )) { openEventCategory(it, type) }
                }
                val now = System.currentTimeMillis()
                WonderTradeEvents.active(now).take(3).forEachIndexed { index, event ->
                    menu.button(14 + index, named(
                        Items.CLOCK, "&d★ #${event.id} ${event.title}",
                        "&7Termina en &f${WonderTradeEvents.formatDuration((event.endAt - now + 999L) / 1000L)}",
                        "&cClic para finalizar"
                    )) { p ->
                        WonderTradeEvents.stop(p.server, event.id)
                        openAdminEvents(p)
                    }
                }
            }
            val scheduledCount = WonderTradeSchedule.all().size
            menu.button(20, named(
                Items.COMPASS, "&6Eventos programados",
                "&7Programados: &f$scheduledCount",
                "&7Para programar usa el comando:",
                "&e/wt admin eventos programar <tipo> <categoría> <duración> <cuándo>",
                "&eClic para ver y cancelar"
            )) { openAdminScheduled(it) }
            menu.button(22, named(Items.ARROW, "&aVolver al panel admin")) { openAdmin(it) }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openAdminScheduled(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, "&5Eventos &8- &6Programados", 6) { renderAdminScheduled(it) }
    }

    private fun renderAdminScheduled(menu: GuiMenu) {
        menu.wipe()
        fill(menu, 54)
        val list = WonderTradeSchedule.all().take(45)
        if (list.isEmpty()) {
            menu.put(22, named(
                Items.BARRIER, "&cNo hay eventos programados",
                "&7Prográmalos con:", "&e/wt admin eventos programar <tipo> <categoría> <duración> <cuándo>",
                "&7Ejemplo: &fprogramar recibir shiny 3h sabado 20:00 semanal"
            ))
        }
        list.forEachIndexed { index, item ->
            val details = mutableListOf(
                "&7Cuándo: &f${WonderTradeSchedule.describeWhen(item)}",
                "&7Duración: &f${WonderTradeEvents.formatDuration(item.durationSeconds)}"
            )
            item.value?.let { details += "&7Valor: &f${WonderTradeEvents.valueText(it)}%" }
            item.cooldownSeconds?.let { details += "&7Espera: &f${WonderTradeEvents.formatDuration(it)}" }
            details += "&7Creado por: &f${item.createdBy}"
            details += "&cClic para cancelar"
            menu.button(index, named(Items.CLOCK, "&d#P${item.id} ${item.title}", *details.toTypedArray())) {
                WonderTradeSchedule.remove(item.id)
                renderAdminScheduled(menu)
            }
        }
        menu.button(48, named(Items.ARROW, "&aVolver")) { openAdminEvents(it) }
        menu.button(50, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun eventTypeDescription(type: EventType): List<String> = when (type) {
        EventType.RISK -> listOf("&7Ofrecer Pokémon de la categoría elegida", "&7suma un % extra a tu probabilidad.")
        EventType.RECEIVE -> listOf("&7Aumenta la probabilidad de recibir", "&7Pokémon de la categoría elegida.")
    }

    private fun openEventCategory(player: ServerPlayer, type: EventType) {
        open(player, "&5Eventos &8- &d${type.title}", 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.NETHER_STAR, "&d${type.title}", *eventTypeDescription(type).toTypedArray(), "&7Elige la categoría."))
            val icons = mapOf(
                EventCategory.SHINY to Items.GLOWSTONE_DUST,
                EventCategory.LEGENDARY to Items.GOLD_INGOT,
                EventCategory.MYTHICAL to Items.AMETHYST_SHARD,
                EventCategory.ULTRABEAST to Items.ECHO_SHARD,
                EventCategory.PARADOX to Items.ENDER_PEARL
            )
            val slots = listOf(11, 12, 13, 14, 15)
            EventCategory.values().forEachIndexed { index, category ->
                menu.button(slots[index], named(
                    icons[category] ?: Items.PAPER, "&d${type.title} ${category.label}",
                    *WonderTradeEvents.describeDefault(type, category).toTypedArray(), "&eClic para elegir la duración"
                )) { openEventDuration(it, type, category) }
            }
            menu.button(22, named(Items.ARROW, "&aVolver")) { openAdminEvents(it) }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openEventDuration(player: ServerPlayer, type: EventType, category: EventCategory) {
        val title = "${type.title} ${category.label}"
        open(player, "&5Eventos &8- &d$title", 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.NETHER_STAR, "&d$title", *WonderTradeEvents.describeDefault(type, category).toTypedArray(), "&7Elige cuánto debe durar."))
            val maximum = WonderTradeEvents.maximumDurationSeconds()
            val options = ConfigManager.strings("events.menuDurations")
                .mapNotNull { text -> WonderTradeEvents.parseDuration(text)?.takeIf { it <= maximum } }
                .distinct()
                .take(7)
            val slots = listOf(9, 10, 11, 12, 14, 15, 16)
            options.forEachIndexed { index, seconds ->
                menu.button(slots[index], named(
                    Items.CLOCK, "&e${WonderTradeEvents.formatDuration(seconds)}",
                    "&7Iniciar «$title» durante este tiempo.", "&eClic para iniciar"
                )) { p ->
                    val event = WonderTradeEvents.create(p.server, type, category, seconds, null, null, p.gameProfile.name)
                    if (event == null) p.sendSystemMessage(WonderTradeService.info("&cLos eventos están desactivados."))
                    openAdminEvents(p)
                }
            }
            menu.button(13, named(
                Items.CLOCK, "&6Tiempo personalizado",
                "&7Ajusta la duración exacta con botones", "&7de minutos, horas y días.", "&eClic para elegir"
            )) { openEventCustomDuration(it, type, category) }
            menu.button(22, named(Items.ARROW, "&aVolver")) { openEventCategory(it, type) }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openEventCustomDuration(player: ServerPlayer, type: EventType, category: EventCategory) {
        open(player, "&5Eventos &8- &6Tiempo", 3) { renderEventCustomDuration(it, type, category, 3600L) }
    }

    /** Selector de duración: los botones suman o restan tiempo y el menú se actualiza sin cerrarse. */
    private fun renderEventCustomDuration(menu: GuiMenu, type: EventType, category: EventCategory, seconds: Long) {
        val title = "${type.title} ${category.label}"
        val maximum = WonderTradeEvents.maximumDurationSeconds()
        val minimum = 60L
        val current = seconds.coerceIn(minimum, maximum)
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.NETHER_STAR, "&d$title", *WonderTradeEvents.describeDefault(type, category).toTypedArray()))
        val steps = listOf(
            9 to -86_400L, 10 to -3_600L, 11 to -600L, 12 to -60L,
            14 to 60L, 15 to 600L, 16 to 3_600L, 17 to 86_400L
        )
        for ((slot, delta) in steps) {
            val label = WonderTradeEvents.formatDuration(kotlin.math.abs(delta))
            val sign = if (delta < 0) "-" else "+"
            val item = if (delta < 0) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE
            menu.button(slot, named(item, (if (delta < 0) "&c" else "&a") + "$sign$label", "&7Duración actual: &f${WonderTradeEvents.formatDuration(current)}")) {
                renderEventCustomDuration(menu, type, category, current + delta)
            }
        }
        menu.put(13, named(
            Items.CLOCK, "&6Duración: &f${WonderTradeEvents.formatDuration(current)}",
            "&7Mínimo: &f${WonderTradeEvents.formatDuration(minimum)}&7  Máximo: &f${WonderTradeEvents.formatDuration(maximum)}"
        ))
        menu.button(22, named(
            Items.LIME_CONCRETE, "&aIniciar «$title»",
            "&7Durante &f${WonderTradeEvents.formatDuration(current)}&7.", "&eClic para iniciar"
        )) { p ->
            val event = WonderTradeEvents.create(p.server, type, category, current, null, null, p.gameProfile.name)
            if (event == null) p.sendSystemMessage(WonderTradeService.info("&cLos eventos están desactivados."))
            openAdminEvents(p)
        }
        menu.button(18, named(Items.ARROW, "&aVolver")) { openEventDuration(it, type, category) }
        menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
    }

    private fun openAdminFill(player: ServerPlayer) {
        open(player, "&5Administración &8- &aRellenar pool", 3) { renderAdminFill(it) }
    }

    private fun renderAdminFill(menu: GuiMenu) {
        run {
            menu.wipe()
            fill(menu, 27)
            menu.put(4, named(Items.HOPPER, "&aRellenar la pool", "&7Probabilidad automática configurada.", "&7Niveles: ${ConfigManager.int("fallback.minimumLevel", 1)}-${ConfigManager.int("fallback.maximumLevel", 100)}"))
            val maximumPool = ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)
            val remaining = WonderTradeService.poolRemainingCapacity()
            val amounts = ConfigManager.ints("admin.fillMenu.amounts")
                .filter { it > 0 && it <= maximumPool }
                .distinct()
                .sorted()
                .take(7)
            val slots = listOf(9, 10, 11, 12, 14, 15, 16)
            if (amounts.isEmpty()) {
                menu.put(13, named(Items.BARRIER, "&cNo hay cantidades configuradas", "&7Edita admin.fillMenu.amounts en config.json."))
            } else {
                amounts.forEachIndexed { index, amount ->
                    val effectiveAmount = minOf(amount, remaining)
                    val lore = if (remaining <= 0) {
                        listOf("&cLa pool está completa.", "&7Máximo: $maximumPool Pokémon.")
                    } else {
                        listOf("&7Añadirá hasta &f$effectiveAmount &7Pokémon.", "&7Huecos disponibles: &f$remaining", "&eClic para rellenar")
                    }
                    menu.button(slots[index], named(Items.CHEST_MINECART, "&eAñadir $amount Pokémon", *lore.toTypedArray())) { p ->
                        val minLevel = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
                        val maxLevel = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(minLevel, 100)
                        val availableNow = minOf(amount, WonderTradeService.poolRemainingCapacity())
                        val result = WonderTradeService.adminFill(p, amount, null, minLevel, maxLevel)
                        val chance = String.format("%.2f", result.chancePercent)
                        p.sendSystemMessage(WonderTradeService.info("&aGenerados &f${result.added}/$availableNow &aPokémon; especiales: &d${result.special} &7(probabilidad: $chance%)."))
                        renderAdminFill(menu)
                    }
                }
            }
            menu.button(22, named(Items.ARROW, "&aVolver al panel admin")) { openAdmin(it) }
            menu.button(26, named(Items.BARRIER, "&cCerrar")) { it.closeContainer() }
        }
    }

    private fun openAdminDonors(player: ServerPlayer, page: Int = 0) {
        val donors = WonderTradeService.poolDonors().sortedBy { it.lowercase() }
        val pages = maxOf(1, (donors.size + 44) / 45)
        val current = page.coerceIn(0, pages - 1)
        open(player, "&5Administración &8- &cAportaciones", 6) { menu ->
            fill(menu, 54)
            if (donors.isEmpty()) {
                menu.put(22, named(Items.PAPER, "&7No hay aportaciones", "&8No hay donantes registrados en la pool."))
            }
            donors.drop(current * 45).take(45).forEachIndexed { index, donor ->
                menu.button(index, named(Items.PLAYER_HEAD, "&f$donor", "&7Eliminar todos sus Pokémon aportados.", "&cClic para eliminar")) { p ->
                    val removed = WonderTradeService.removeDonorEntries(donor)
                    p.sendSystemMessage(WonderTradeService.info("&aEliminados &f$removed &aPokémon aportados por &f$donor&a."))
                    openAdminDonors(p, current)
                }
            }
            if (current > 0) menu.button(45, named(Items.ARROW, "&ePágina anterior")) { openAdminDonors(it, current - 1) }
            menu.button(49, named(Items.LIME_STAINED_GLASS_PANE, "&aVolver al panel admin")) { openAdmin(it) }
            if (current < pages - 1) menu.button(53, named(Items.ARROW, "&ePágina siguiente")) { openAdminDonors(it, current + 1) }
        }
    }

    private fun openResetStatsConfirm(player: ServerPlayer) {
        open(player, "&cConfirmar reinicio de estadísticas", 3) { menu ->
            fill(menu, 27)
            menu.button(11, named(Items.TNT, "&cSí, reiniciar todo", "&7Se reiniciarán los tres rankings.")) { p ->
                val affected = WonderTradeService.adminResetAllStats()
                p.sendSystemMessage(WonderTradeService.info("&aEstadísticas reiniciadas para &f$affected &ajugadores."))
                openAdmin(p)
            }
            menu.button(15, named(Items.ARROW, "&aCancelar")) { openAdmin(it) }
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
        val eventLines = WonderTradeEvents.clockLines(player)
        return named(Items.CLOCK, ConfigManager.string("messages.cooldownMenuTitle", "&bSiguiente Wonder Trade"), lore, *eventLines.toTypedArray())
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
            if (!WonderTradeService.playerSoundsEnabled(player, key)) return
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
            if (!donationMode && ConfigManager.bool("gui.showTradePreview", true)) {
                val preview = WonderTradeService.tradePreviewLines(player, pokemon)
                menu.put(22, named(
                    Items.PAPER,
                    ConfigManager.string("messages.previewTitle", "&dProbabilidades de este intercambio"),
                    *preview.toTypedArray()
                ))
            }
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
