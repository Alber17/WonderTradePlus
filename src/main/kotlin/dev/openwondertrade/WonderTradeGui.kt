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
import net.minecraft.world.item.component.ResolvableProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import java.util.Optional
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                // Los textos que se generen al pulsar el botón van en el idioma de quien lo pulsa.
                Lang.withPlayer(player) { action(player) }
            }
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun canTakeItemForPickAll(stack: ItemStack, slot: Slot): Boolean = false
    override fun canDragTo(slot: Slot): Boolean = false
}

enum class PoolSort(private val source: String) {
    NEWEST("&eHora añadida: descendente"),
    OLDEST("&eHora añadida: ascendente"),
    SPECIES("&eEspecie: A-Z");

    /** Texto en el idioma activo (se calcula al usarlo para que el cambio de idioma se aplique al momento). */
    val label: String get() = Lang.tr(source)

    fun next(): PoolSort = values()[(ordinal + 1) % values().size]
}

enum class PoolFilter(private val source: String) {
    ALL("&fTodos"),
    NORMAL("&7Normales"),
    SPECIAL("&dEspeciales"),
    SHINY("&eShiny"),
    ULTRA_BEAST("&3Ultraentes"),
    PARADOX("&cParadoja"),
    LEGENDARY("&6Legendarios");

    val label: String get() = Lang.tr(source)

    fun next(): PoolFilter = values()[(ordinal + 1) % values().size]

    fun matches(entry: PoolEntry, registryAccess: net.minecraft.core.RegistryAccess): Boolean = when (this) {
        ALL -> true
        SPECIAL -> WonderTradeRules.isRareEntry(entry)
        NORMAL -> !WonderTradeRules.isRareEntry(entry)
        SHINY -> entry.shiny
        ULTRA_BEAST -> WonderTradeService.entryCategoryKey(entry, registryAccess) == "ultraBeast"
        PARADOX -> WonderTradeService.entryCategoryKey(entry, registryAccess) == "paradox"
        LEGENDARY -> WonderTradeService.entryCategoryKey(entry, registryAccess).let { it == "legendary" || it == "mythical" }
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
        val allowed = if (admin) player.hasPermissions(WonderTradeService.adminLevel()) else canViewPool(player)
        if (!admin && !WonderTradeService.ensureEnabled(player)) return
        if (!allowed) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (admin) Lang.tr("&5Wonder Trade &8- &fPool &c(admin)") else Lang.tr("&5Wonder Trade &8- &fPool")
        open(player, title, 6) { renderPool(it, player, page, admin, filter, deleteMode, sort, query) }
    }

    fun openHistory(player: ServerPlayer, filter: String? = null, page: Int = 0) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        val title = if (filter == null) Lang.tr("&5Wonder Trade &8- &fHistorial") else Lang.t("&5Historial &8- &f{0}", filter)
        open(player, title, 6) { renderHistory(it, player, filter, page) }
    }

    /** Historial del propio jugador: solo muestra los intercambios hechos con su nombre, nunca los de otros. */
    fun openMyHistory(player: ServerPlayer, page: Int = 0) {
        if (!ConfigManager.bool("history.enabled", true) || !ConfigManager.bool("history.playersCanViewOwn", true)) {
            player.sendSystemMessage(WonderTradeService.info(Lang.tr("&cEl historial personal está desactivado.")))
            return
        }
        open(player, Lang.tr("&5Wonder Trade &8- &fTu historial"), 6) { renderHistory(it, player, null, page, own = true) }
    }

    fun openSounds(player: ServerPlayer) {
        open(player, Lang.tr("&5Wonder Trade &8- &fSonidos"), 3) { menu -> renderSounds(menu, player) }
    }

    private fun renderSounds(menu: GuiMenu, player: ServerPlayer) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(
            Items.JUKEBOX,
            Lang.tr("&dPreferencias de sonido"),
            Lang.tr("&7Configura qué sonidos escuchas tú."),
            Lang.tr("&8Tus cambios se guardan automáticamente.")
        ))

        data class SoundOption(val key: String, val slot: Int, val item: Item, val title: String, val description: String)
        val options = listOf(
            SoundOption("menuOpen", 10, Items.CHEST, Lang.tr("&fApertura de menús"), Lang.tr("&7Sonido al abrir los menús y cofres.")),
            SoundOption("pcOpen", 11, Items.ENDER_CHEST, Lang.tr("&fApertura del PC"), Lang.tr("&7Sonido del cofre de Ender al abrir el PC.")),
            SoundOption("buttonClick", 12, Items.STONE_BUTTON, Lang.tr("&fClic de botones"), Lang.tr("&7Sonido al pulsar botones de los menús.")),
            SoundOption("cooldownReady", 13, Items.NOTE_BLOCK, Lang.tr("&fAviso de cooldown"), Lang.tr("&7Nota musical cuando vuelve a estar disponible el Wonder Trade.")),
            SoundOption("pageTurn", 14, Items.WRITABLE_BOOK, Lang.tr("&fCambio de página"), Lang.tr("&7Sonido al cambiar de página.")),
            SoundOption("specialReceived", 15, Items.EXPERIENCE_BOTTLE, Lang.tr("&fPokémon especial"), Lang.tr("&7Sonido al recibir un legendario, mítico, Ultraente o Paradoja.")),
            SoundOption("specialShinyReceived", 16, Items.TOTEM_OF_UNDYING, Lang.tr("&fEspecial shiny"), Lang.tr("&7Sonido de tótem al recibir un legendario, mítico, Ultraente o Paradoja shiny. Un shiny normal usa el sonido de especial."))
        )
        for (option in options) {
            val enabled = WonderTradeService.soundPreference(player, option.key)
            val status = if (enabled) Lang.tr("&aACTIVADO") else Lang.tr("&cDESACTIVADO")
            menu.button(option.slot, named(
                option.item,
                option.title,
                option.description,
                "",
                Lang.t("&7Estado: {0}", status),
                Lang.tr("&eClic para cambiar")
            )) { p ->
                val nowEnabled = WonderTradeService.toggleSoundPreference(p, option.key)
                renderSounds(menu, p)
                if (nowEnabled) playSound(p, option.key)
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
                    ConfigManager.string("messages.menuBookShinyGain", "&ePokémon shiny&7 (sin categoría especial): &e+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialGain", "&6Legendario&7 / &dMítico&7 / &3Ultraente&7 / &cParadoja&7: &f+{gain}%"),
                    mapOf("gain" to WonderTradeService.specialBonusGainText())
                )
                bookLore += WonderTradeService.format(
                    ConfigManager.string("messages.menuBookSpecialShinyGain", "&eEspecial shiny&7: &e+{base}% &7+ &e{extra}% &7extra = &e{total}%"),
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

    // ---------------------------------------------------------------- idioma de cada jugador

    /** Menú para que cada jugador elija el idioma de su propio Wonder Trade. */
    fun openLanguage(player: ServerPlayer) {
        open(player, Lang.tr("&5Wonder Trade &8- &bIdioma"), 3) { menu ->
            fill(menu, 27)
            val current = Lang.languageOf(player) ?: Lang.current()
            val chosen = WonderTradeService.storedLanguage(player.uuid)
            val list = Lang.available()
            menu.put(4, named(Items.COMPASS, Lang.tr("&bIdioma"), Lang.tr("&7Solo cambia lo que ves tú.")))
            val slots = listOf(10, 11, 12, 13, 14, 15, 16)
            list.take(slots.size).forEachIndexed { index, (id, label) ->
                val selected = chosen == id
                menu.button(slots[index], named(
                    if (selected) Items.LIME_DYE else Items.PAPER,
                    (if (selected) "&a✔ &f" else "&f") + label,
                    "&8$id", if (selected) Lang.tr("&7Idioma elegido") else Lang.tr("&eClic para elegir")
                )) { chooseLanguage(it, id) }
            }
            menu.button(22, named(
                if (chosen == null) Items.LIME_DYE else Items.ENDER_EYE,
                (if (chosen == null) "&a✔ &f" else "&f") + Lang.tr("Automático"),
                Lang.t("&7Usa el idioma de tu Minecraft ({0}).", Lang.nameOf(current)),
                if (chosen == null) Lang.tr("&7Idioma elegido") else Lang.tr("&eClic para elegir")
            )) { chooseLanguage(it, "auto") }
            menu.button(18, named(Items.ARROW, Lang.tr("&aVolver"))) { openMain(it) }
            menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    /** /wt rangos: un objeto por rango; sus ventajas se ven pasando el ratón por encima. */
    fun openRanks(player: ServerPlayer) {
        val ranks = WonderTradeRanks.all()
        val rows = if (ranks.size <= 7) 3 else 6
        // De izquierda a derecha, del rango más alto al más bajo. Gloria Ball es la Cherish Ball de Cobblemon.
        val icons = listOf("cherish_ball", "master_ball", "ultra_ball", "great_ball", "poke_ball").map { cobblemonItem(it) }
        open(player, Lang.tr("&5Wonder Trade &8- &bRangos"), rows) { menu ->
            fill(menu, rows * 9)
            if (!WonderTradeRanks.enabled() || ranks.isEmpty()) {
                menu.put(rows * 9 / 2, named(Items.BARRIER, ConfigManager.string("messages.rankDisabled", "&7Los rangos están desactivados.")))
            } else {
                val mine = WonderTradeRanks.rankOf(player)
                // Filas de hasta 7 rangos, cada una centrada. Con 3 filas el menú usa la del medio; con 6, las cuatro centrales.
                val perRow = 7
                val firstRow = if (rows == 3) 1 else 1
                ranks.take(perRow * (rows - 2)).chunked(perRow).forEachIndexed { rowIndex, group ->
                    val start = (firstRow + rowIndex) * 9 + (9 - group.size) / 2
                    group.forEachIndexed { column, rank ->
                        val index = rowIndex * perRow + column
                        val own = mine != null && mine.id == rank.id
                        val lore = mutableListOf(
                            Lang.t("&7Probabilidad mínima de especial: &d{0}%", WonderTradeService.percentText(rank.baseBonus)),
                            Lang.t("&7Mejora fija en cada intercambio: &a+{0}%", WonderTradeService.percentText(rank.improvementPercent)),
                            Lang.t("&7Ganancia por Pokémon ofrecido: &ax{0}", WonderTradeService.percentText(rank.gainMultiplier))
                        )
                        if (own) lore += Lang.tr("&a✔ Es tu rango")
                        menu.put(start + column, named(icons[index % icons.size], "&f" + rank.name, *lore.toTypedArray()))
                    }
                }
            }
            menu.button(rows * 9 - 9, named(Items.ARROW, Lang.tr("&aVolver"))) { openMain(it) }
            menu.button(rows * 9 - 1, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    /** Guarda el idioma elegido ("auto" = según el cliente) y vuelve a abrir el menú ya traducido. */
    fun chooseLanguage(player: ServerPlayer, code: String) {
        val id = Lang.normalize(code)
        if (id == "auto") {
            WonderTradeService.setPlayerLanguage(player, null)
        } else {
            if (Lang.available().none { it.first == id }) {
                player.sendSystemMessage(WonderTradeService.info(Lang.t("&cNo existe el idioma &f{0}&c. Disponibles: &f{1}", code, Lang.available().joinToString(", ") { it.first })))
                return
            }
            WonderTradeService.setPlayerLanguage(player, id)
        }
        player.sendSystemMessage(WonderTradeService.info(Lang.t("&aIdioma cambiado a &f{0}&a.", Lang.nameOf(Lang.languageOf(player) ?: Lang.current()))))
        if (player.containerMenu is GuiMenu) openLanguage(player)
    }

    private fun renderPc(menu: GuiMenu, player: ServerPlayer, requestedBox: Int, donationMode: Boolean) {
        menu.wipe()
        fill(menu, 54)
        val boxes = Cobblemon.storage.getPC(player).boxes
        if (boxes.isEmpty()) {
            menu.put(22, named(Items.BARRIER, Lang.tr("&cNo tienes cajas en el PC")))
        } else {
            val index = requestedBox.coerceIn(0, boxes.size - 1)
            val contents = boxes[index].getNonEmptySlots()
            for (i in 0 until PC_BOX_SLOTS) {
                val guiSlot = (i / 6) * 9 + (i % 6)
                val pokemon = contents[i]
                if (pokemon == null) {
                    menu.put(guiSlot, named(Items.LIGHT_GRAY_STAINED_GLASS_PANE, Lang.tr("&8Vacío")))
                } else {
                    menu.button(guiSlot, pokemonStack(pokemon, true)) { p -> select(p, pokemon, true, donationMode) }
                }
            }
            menu.button(48, named(Items.ARROW, Lang.tr("&eCaja anterior")), "pageTurn") {
                renderPc(menu, it, (index - 1 + boxes.size) % boxes.size, donationMode)
            }
            menu.put(49, named(Items.PAPER, Lang.t("&fCaja {0}/{1}", index + 1, boxes.size)))
            menu.button(50, named(Items.ARROW, Lang.tr("&eCaja siguiente")), "pageTurn") {
                renderPc(menu, it, (index + 1) % boxes.size, donationMode)
            }
        }
        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, if (donationMode) Lang.tr("&aVolver a donar") else Lang.tr("&aVolver al equipo"))) {
            openMain(it, donationMode)
        }
        menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
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
        val filtered = all.filter { filter.matches(it, player.server.registryAccess()) && matchesQuery(it, query, player.server.registryAccess()) }
        val entries = when (sort) {
            PoolSort.NEWEST -> filtered.sortedByDescending { it.addedAt }
            PoolSort.OLDEST -> filtered.sortedBy { it.addedAt }
            PoolSort.SPECIES -> filtered.sortedWith(compareBy({ it.species.substringAfter(':').lowercase() }, { -it.addedAt }))
        }
        val pages = maxOf(1, (entries.size + POOL_PER_PAGE - 1) / POOL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val registryAccess = player.server.registryAccess()
        val showDonors = ConfigManager.bool("gui.showDonorNames", true)
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm")

        entries.drop(page * POOL_PER_PAGE).take(POOL_PER_PAGE).forEachIndexed { i, entry ->
            val extra = mutableListOf<String>()
            if (showDonors) extra += Lang.t("&7Aportado por: &f{0}", WonderTradeService.donorLabel(entry.donorName))
            extra += Lang.t("&7Fecha: &f{0}", format.format(Date(entry.addedAt)))
            val pokemon = runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull()
            if (admin && deleteMode) extra += Lang.tr("&c✖ Clic para eliminar del pool")
            else if (admin) extra += Lang.tr("&7Activa &c&l«Eliminar Pokémon»&r&7 para poder borrarlo.")
            val stack = if (pokemon != null) {
                pokemonStack(pokemon, false, extra)
            } else named(Items.BARRIER, Lang.t("&c{0}", entry.species), Lang.tr("&7No se pudo cargar este Pokémon."))
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
        if (entries.isEmpty()) menu.put(22, named(Items.BARRIER, Lang.tr("&cNo hay Pokémon con este filtro")))

        menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aVolver"))) {
            if (admin) openAdmin(it) else openMain(it)
        }
        if (admin) {
            val deleteButton = if (deleteMode) {
                named(Items.TNT, Lang.tr("&cEliminar Pokémon: ACTIVADO"), Lang.tr("&7Haz clic en los Pokémon que quieras quitar."), Lang.tr("&eClic para desactivar"))
            } else {
                named(Items.SHEARS, Lang.tr("&cEliminar Pokémon"), Lang.tr("&7Activa el modo de eliminación."), Lang.tr("&7Después haz clic en los Pokémon que quieras quitar."))
            }
            menu.button(46, deleteButton) { p ->
                if (p.hasPermissions(WonderTradeService.adminLevel())) {
                    renderPool(menu, p, page, true, filter, !deleteMode, sort, query)
                }
            }
        }
        if (page > 0) menu.button(48, named(Items.ARROW, Lang.tr("&ePágina anterior")), "pageTurn") { renderPool(menu, it, page - 1, admin, filter, deleteMode, sort, query) }
        menu.put(49, named(Items.PAPER, Lang.t("&fPágina {0}/{1}", page + 1, pages), Lang.t("&7{0} Pokémon con este filtro", entries.size)))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, Lang.tr("&ePágina siguiente")), "pageTurn") { renderPool(menu, it, page + 1, admin, filter, deleteMode, sort, query) }
        val counts = PoolFilter.values().associateWith { option -> all.count { option.matches(it, registryAccess) } }
        val options = PoolFilter.values().map { option ->
            val count = "&7" + counts[option]
            if (option == filter) Lang.t("&f▶ {0} {1}", option.label, count)
            else Lang.t("&8· {0} {1}", option.label, count)
        }
        val sortOptions = PoolSort.values().map { option ->
            if (option == sort) Lang.t("&e▶ {0}", option.label) else Lang.t("&8   {0}", option.label.drop(2))
        }
        menu.button(51, named(Items.CLOCK, Lang.tr("&fOrdenar pool"), *sortOptions.toTypedArray(), Lang.tr("&7Ordena por fecha de incorporación o por especie."), Lang.tr("&eClic para cambiar"))) {
            renderPool(menu, it, 0, admin, filter, deleteMode, sort.next(), query)
        }
        if (query != null) {
            menu.button(47, named(Items.NAME_TAG, Lang.t("&fBúsqueda: &e{0}", query), Lang.tr("&7Mostrando solo los Pokémon que coinciden."), Lang.tr("&eClic para quitar la búsqueda"))) {
                renderPool(menu, it, 0, admin, filter, deleteMode, sort, null)
            }
        } else {
            menu.button(47, named(Items.NAME_TAG, Lang.tr("&fBuscar por nombre o especie"), Lang.tr("&7Encuentra un Pokémon concreto del pool."), Lang.tr("&eClic para buscar"))) {
                startSearch(it, admin, filter, deleteMode, sort)
            }
        }
        menu.button(52, named(Items.HOPPER, Lang.tr("&fCambiar filtro de pool"),
            *options.toTypedArray(),
            Lang.tr("&eClic para cambiar"))) {
            renderPool(menu, it, 0, admin, filter.next(), deleteMode, sort, query)
        }
        menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
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

    private val ivStatTokens = mapOf(
        "hp" to com.cobblemon.mod.common.api.pokemon.stats.Stats.HP,
        "atk" to com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK,
        "def" to com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE,
        "spatk" to com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK,
        "spdef" to com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE,
        "spd" to com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED
    )

    /** Texto de búsqueda ya separado: nombre, mínimo de stats a 31 y stats concretos a 31. */
    private data class ParsedQuery(val name: String, val minPerfect: Int, val perfectStats: Set<com.cobblemon.mod.common.api.pokemon.stats.Stats>) {
        val usesIvs: Boolean get() = minPerfect > 0 || perfectStats.isNotEmpty()
    }

    /** Admite «6x31», «5x31»… (mínimo de IVs a 31) y «31spd», «31hp»… (ese stat a 31), combinables con el nombre. */
    private fun parseQuery(query: String): ParsedQuery {
        val nameParts = mutableListOf<String>()
        var minPerfect = 0
        val stats = mutableSetOf<com.cobblemon.mod.common.api.pokemon.stats.Stats>()
        for (token in query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val count = Regex("^([1-6])x31$").matchEntire(token)?.groupValues?.get(1)?.toInt()
            val stat = if (token.startsWith("31")) ivStatTokens[token.removePrefix("31")] else null
            when {
                count != null -> minPerfect = maxOf(minPerfect, count)
                stat != null -> stats += stat
                else -> nameParts += token
            }
        }
        return ParsedQuery(nameParts.joinToString(" "), minPerfect, stats)
    }

    private fun perfectIvCount(pokemon: Pokemon): Int = runCatching {
        ivStatTokens.values.count { pokemon.ivs.getEffectiveBattleIV(it) >= 31 }
    }.getOrDefault(0)

    /** Coincide por especie ("greattusk", "great tusk", "tusk") o apodo, y por IVs perfectos («6x31», «31spd»). */
    private fun matchesQuery(entry: PoolEntry, query: String?, registryAccess: net.minecraft.core.RegistryAccess): Boolean {
        if (query.isNullOrBlank()) return true
        val parsed = parseQuery(query)
        if (parsed.usesIvs) {
            val pokemon = runCatching { Pokemon.loadFromJSON(registryAccess, entry.pokemonJson.deepCopy()) }.getOrNull() ?: return false
            if (perfectIvCount(pokemon) < parsed.minPerfect) return false
            if (parsed.perfectStats.any { runCatching { pokemon.ivs.getEffectiveBattleIV(it) }.getOrDefault(0) < 31 }) return false
        }
        val needle = normalize(parsed.name)
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
        val button = Component.literal(Lang.tr("§e§n[Pulsa aquí para buscar]"))
            .setStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/wt pool search ")))
        player.sendSystemMessage(
            WonderTradeService.colour(WonderTradeService.prefix() + Lang.tr("&7Escribe el nombre o la especie del Pokémon. &8Opcional: 6x31 (IVs perfectos), 5x31… o 31spd, 31hp, 31atk, 31def, 31spatk, 31spdef. ")).copy().append(button)
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

    // Es una propiedad calculada para que los nombres salgan en el idioma de quien abre el menú.
    private val specialCategories: List<Triple<String, String, Item>>
        get() = listOf(
            Triple("ultraBeast", Lang.tr("&3Ultraentes"), Items.ECHO_SHARD),
            Triple("paradox", Lang.tr("&cParadoja"), Items.ENDER_PEARL),
            Triple("mythical", Lang.tr("&dMíticos"), Items.AMETHYST_SHARD),
            Triple("legendary", Lang.tr("&6Legendarios"), Items.GOLD_INGOT)
        )

    fun openSpecials(player: ServerPlayer) {
        if (!WonderTradeService.ensureEnabled(player)) return
        open(player, Lang.tr("&5Wonder Trade &8- &dEspeciales"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(
                Items.ENCHANTED_BOOK, Lang.tr("&dPokémon especiales"),
                Lang.tr("&7Estas categorías son las que cuentan en los"),
                Lang.tr("&7eventos «Arriesgar» y «Recibir» de Wonder Trade."),
                Lang.tr("&7Un Pokémon shiny cuenta para los eventos Shiny.")
            ))
            val groups = WonderTradeService.speciesByCategory(player)
            val slots = listOf(10, 12, 14, 16)
            specialCategories.forEachIndexed { index, (key, label, icon) ->
                val count = groups[key]?.size ?: 0
                menu.button(slots[index], named(icon, label, Lang.t("&7Pokémon en esta categoría: &f{0}", count), Lang.tr("&eClic para ver la lista"))) {
                    openSpecialList(it, key, 0)
                }
            }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openMain(it) }
            menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    private fun openSpecialList(player: ServerPlayer, key: String, page: Int) {
        val label = specialCategories.firstOrNull { it.first == key }?.second ?: key
        open(player, Lang.t("&5Especiales &8- {0}", label), 6) { renderSpecialList(it, player, key, page) }
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
            val formName = WonderTradeRules.formLabel(pokemon)
            stack.set(DataComponents.CUSTOM_NAME, lit("&f" + pokemon.getDisplayName().string + (formName?.let { " &7($it)" } ?: "")))
            val lines = mutableListOf(Lang.t("&7Categoría: {0}", categoryLabel))
            formName?.let { lines += Lang.t("&7Forma: &f{0}", it) }
            WonderTradeRules.typesLabel(pokemon)?.let { lines += Lang.t("&7Tipo: {0}", it) }
            stack.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
            menu.put(index, stack)
        }
        if (list.isEmpty()) menu.put(22, named(Items.BARRIER, Lang.tr("&cNo hay Pokémon en esta categoría")))
        if (page > 0) menu.button(48, named(Items.ARROW, Lang.tr("&ePágina anterior")), "pageTurn") { renderSpecialList(menu, it, key, page - 1) }
        menu.put(49, named(Items.PAPER, Lang.t("&7Página {0}/{1}", page + 1, pages), Lang.t("&7{0}&7: &f{1}", categoryLabel, list.size)))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, Lang.tr("&ePágina siguiente")), "pageTurn") { renderSpecialList(menu, it, key, page + 1) }
        menu.button(45, named(Items.ARROW, Lang.tr("&aVolver"))) { openSpecials(it) }
        menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    fun openTop(player: ServerPlayer) {
        open(player, Lang.tr("&5Wonder Trade &8- &6Rankings"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.BOOK, Lang.tr("&6Rankings de Wonder Trade"), Lang.tr("&7Selecciona qué clasificación quieres consultar.")))
            menu.button(11, named(Items.GOLD_INGOT, Lang.tr("&6Top intercambios"), Lang.tr("&7Jugadores con más intercambios realizados."), Lang.tr("&eClic para consultar"))) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("trades", p.uuid))
            }
            menu.button(13, named(Items.DIAMOND, Lang.tr("&bTop especiales recibidos"), Lang.tr("&7Jugadores que más Pokémon especiales han recibido."), Lang.tr("&eClic para consultar"))) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("received", p.uuid))
            }
            menu.button(15, named(Items.EMERALD, Lang.tr("&aTop especiales aportados"), Lang.tr("&7Jugadores que más Pokémon especiales han aportado."), Lang.tr("&eClic para consultar"))) { p ->
                p.sendSystemMessage(WonderTradeService.topComponent("donated", p.uuid))
            }
            menu.button(22, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
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
            // Cada fila es un grupo (etiqueta a la izquierda) con los botones espaciados de dos en dos.
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
            btn(15, "deletePool", named(Items.BARRIER, Lang.tr("&cEliminar pool"), Lang.tr("&7Elige qué origen quieres eliminar."), Lang.tr("&eAbrir opciones"))) {
                openAdminPoolDeleteOptions(it)
            }

            // Fila 2: servidor
            menu.put(18, named(Items.MAGENTA_STAINED_GLASS_PANE, Lang.tr("&dServidor")))
            btn(20, "events", named(Items.NETHER_STAR, Lang.tr("&dGestionar eventos"), Lang.tr("&7Crea eventos temporales para todos."), Lang.tr("&eClic para abrir"))) {
                openAdminEvents(it)
            }
            // Top Donaciones: activar o desactivar el evento mensual de donadores.
            val topOn = WonderTradeTopDonations.enabled()
            val thisMonth = java.time.YearMonth.now(WonderTradeSchedule.zone())
            val monthState = if (WonderTradeTopDonations.isActiveMonth(thisMonth)) Lang.tr("&aEste mes hay Top") else Lang.tr("&7Este mes no hay Top")
            val topStack = if (topOn) named(
                Items.EMERALD, Lang.tr("&aTop Donaciones: ACTIVADO"), Lang.tr("&7Premios mensuales a los mejores donadores."), monthState, Lang.tr("&eClic para desactivar")
            ) else named(
                Items.REDSTONE, Lang.tr("&cTop Donaciones: DESACTIVADO"), Lang.tr("&7No se suman puntos ni se dan premios."), Lang.tr("&eClic para activar")
            )
            btn(22, "topDonations", topStack) {
                ConfigManager.setBool("topDonations.enabled", !WonderTradeTopDonations.enabled())
                openAdmin(it)
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
            btn(29, "history", named(Items.WRITABLE_BOOK, ConfigManager.string("messages.adminMenuHistory", "&eHistorial"), Lang.tr("&7Últimos intercambios."))) {
                openHistory(it)
            }
            btn(31, "resetStats", named(Items.BOOK, Lang.tr("&eReiniciar estadísticas"), Lang.tr("&7Reinicia las estadísticas de todos o de un jugador."), Lang.tr("&cNo se puede deshacer."), Lang.tr("&eClic para abrir"))) {
                openResetStatsMenu(it)
            }

            // Fila 4: sistema
            menu.put(36, named(Items.LIGHT_BLUE_STAINED_GLASS_PANE, Lang.tr("&bSistema")))
            btn(38, "reload", named(Items.COMPARATOR, ConfigManager.string("messages.adminMenuReload", "&bRecargar configuración"))) {
                WonderTradeService.reload()
                WonderTradeService.send(it, "reload")
                openAdmin(it)
            }
            btn(40, "maintenance", named(Items.HOPPER, ConfigManager.string("messages.adminMenuMaintenance", "&aEjecutar mantenimiento"), Lang.tr("&7Caducidad y purga de la pool."))) {
                val (expired, generated) = WonderTradeService.maintainPool(it.server)
                it.sendSystemMessage(WonderTradeService.info(Lang.t("&aMantenimiento: &f{0} &aPokémon eliminados, &f{1} &agenerados.", expired, generated)))
                openAdmin(it)
            }
            btn(42, "backup", named(Items.PAPER, ConfigManager.string("messages.adminMenuBackup", "&dCrear copia de seguridad"))) {
                val backup = WonderTradeService.backupNow()
                if (backup == null) it.sendSystemMessage(WonderTradeService.info(Lang.tr("&cNo se pudo crear la copia.")))
                else it.sendSystemMessage(WonderTradeService.info(Lang.t("&aCopia creada: &f{0}", backup)))
                openAdmin(it)
            }
            btn(44, "discord", discordHead(Lang.tr("&9Probar Discord"), Lang.tr("&7Enviar un mensaje de prueba al webhook."))) { p ->
                WonderTradeDiscord.test { ok, message ->
                    p.server.execute {
                        p.sendSystemMessage(WonderTradeService.info(if (ok) Lang.t("&a{0}", message) else Lang.t("&c{0}", message)))
                    }
                }
                p.sendSystemMessage(WonderTradeService.info(Lang.tr("&7Enviando mensaje de prueba a Discord…")))
            }

            menu.button(49, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    private fun openAdminEvents(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Administración &8- &dEventos"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(
                Items.NETHER_STAR, Lang.tr("&dEventos de Wonder Trade"),
                Lang.tr("&7Eventos temporales para todos los jugadores."),
                Lang.tr("&7Valores personalizados:"),
                Lang.tr("&e/wt admin eventos crear <tipo> <duración> [valor] [espera]")
            ))
            if (!WonderTradeEvents.enabled()) {
                menu.put(13, named(Items.BARRIER, Lang.tr("&cLos eventos están desactivados"), Lang.tr("&7Activa events.enabled en config.json.")))
            } else {
                val icons = mapOf(EventType.RISK to Items.GOLD_NUGGET, EventType.RECEIVE to Items.AMETHYST_SHARD)
                val types = EventType.values()
                types.forEachIndexed { index, type ->
                    menu.button(if (index == 0) 10 else 12, named(
                        icons[type] ?: Items.PAPER, Lang.t("&d{0}", type.title),
                        *eventTypeDescription(type).toTypedArray(), Lang.tr("&eClic para elegir la categoría")
                    )) { openEventCategory(it, type) }
                }
                val now = System.currentTimeMillis()
                WonderTradeEvents.running(now).take(3).forEachIndexed { index, event ->
                    val left = WonderTradeEvents.formatDuration((event.endAt - (if (event.paused) event.pausedAt else now) + 999L) / 1000L)
                    menu.button(14 + index, named(
                        if (event.paused) Items.BARRIER else Items.CLOCK, Lang.t("&d★ #{0} {1}", event.id, event.title) + if (event.paused) Lang.tr(" &6(pausado)") else "",
                        Lang.t("&7Tiempo restante: &f{0}", left),
                        Lang.t("&7WT hechos: &f{0} &7· jugadores: &f{1}", event.trades, event.playerIds.size),
                        Lang.tr("&eClic para gestionar")
                    )) { openEventDetail(it, event.id) }
                }
            }
            val scheduledCount = WonderTradeSchedule.all().size
            menu.button(18, named(
                Items.SNOWBALL, Lang.tr("&bHora navideña"),
                Lang.t("&7Estado: &f{0}", WonderTradeSeasons.statusText()),
                Lang.tr("&7Inicia, pausa o finaliza la sesión navideña."),
                Lang.tr("&eClic para gestionar")
            )) { openChristmasAdmin(it) }
            menu.button(20, named(
                Items.COMPASS, Lang.tr("&6Eventos programados"),
                Lang.t("&7Programados: &f{0}", scheduledCount),
                Lang.tr("&7Programa eventos desde los menús de tipo y categoría."),
                Lang.tr("&7También puedes elegir duración, día, horario y repetición."),
                Lang.tr("&eClic para ver y cancelar")
            )) { openAdminScheduled(it) }
            menu.button(24, named(
                Items.WRITABLE_BOOK, Lang.tr("&dHistorial de eventos"),
                Lang.tr("&7Eventos terminados con sus WT y jugadores."), Lang.tr("&eClic para ver")
            )) { openEventHistory(it) }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver al panel admin"))) { openAdmin(it) }
            menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    /** Panel de pruebas del evento navideño. */
    private fun openChristmasAdmin(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Administración &8- &bHora navideña"), 3) { menu ->
            fill(menu, 27)
            val status = WonderTradeSeasons.statusText()
            val season = WonderTradeSeasons.current()
            val remaining = ((WonderTradeSeasons.sessionEndAt() - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            menu.put(4, named(
                Items.SNOW_BLOCK, Lang.tr("&bHora navideña"),
                Lang.t("&7Estado: &f{0}", status),
                Lang.t("&7Duración por sesión: &f{0}", season?.let { WonderTradeEvents.formatDuration(it.sessionDurationMinutes * 60L) } ?: "1 hora"),
                if (WonderTradeSeasons.isRunning() || WonderTradeSeasons.isPaused())
                    Lang.t("&7Tiempo restante: &f{0}", WonderTradeEvents.formatDuration(remaining))
                else Lang.tr("&7La sesión no está activa."),
                Lang.tr("&8El inicio manual también sirve fuera de las fechas programadas.")
            ))
            menu.button(10, named(
                Items.LIME_CONCRETE, if (WonderTradeSeasons.isPaused()) Lang.tr("&aReanudar / Iniciar") else Lang.tr("&aIniciar ahora"),
                Lang.tr("&7Genera una pool navideña nueva y comienza una sesión.")
            )) { p ->
                val ok = WonderTradeSeasons.manualStart(p.server)
                p.sendSystemMessage(WonderTradeService.info(if (ok) Lang.tr("&aLa hora navideña se ha iniciado o reanudado.") else Lang.tr("&cNo se ha podido iniciar: comprueba que haya un jugador conectado y que exista una temporada configurada.")))
                openChristmasAdmin(p)
            }
            menu.button(13, named(
                Items.YELLOW_CONCRETE, Lang.tr("&6Pausar"),
                Lang.tr("&7Congela el tiempo restante y mantiene la pool navideña.")
            )) { p ->
                val ok = WonderTradeSeasons.manualPause(p.server)
                p.sendSystemMessage(WonderTradeService.info(if (ok) Lang.tr("&eLa hora navideña se ha pausado.") else Lang.tr("&cNo hay ninguna sesión activa que se pueda pausar.")))
                openChristmasAdmin(p)
            }
            menu.button(16, named(
                Items.RED_CONCRETE, Lang.tr("&cFinalizar"),
                Lang.tr("&7Restaura la pool habitual y termina la sesión.")
            )) { p ->
                val ok = WonderTradeSeasons.manualFinish(p.server)
                p.sendSystemMessage(WonderTradeService.info(if (ok) Lang.tr("&aLa hora navideña ha finalizado.") else Lang.tr("&cNo hay ninguna sesión que finalizar.")))
                openChristmasAdmin(p)
            }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver a eventos"))) { openAdminEvents(it) }
            menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    /** Detalle de un evento en curso: estadísticas, pausar/reanudar y finalizar (solo administradores). */
    private fun openEventDetail(player: ServerPlayer, id: Int) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Eventos &8- &dGestionar"), 3) { menu -> renderEventDetail(menu, id) }
    }

    private fun renderEventDetail(menu: GuiMenu, id: Int) {
        menu.wipe()
        fill(menu, 27)
        val now = System.currentTimeMillis()
        val event = WonderTradeEvents.running(now).firstOrNull { it.id == id }
        if (event == null) {
            menu.put(13, named(Items.BARRIER, Lang.tr("&cEste evento ya no está en marcha")))
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdminEvents(it) }
            return
        }
        val left = WonderTradeEvents.formatDuration((event.endAt - (if (event.paused) event.pausedAt else now) + 999L) / 1000L)
        menu.put(4, named(
            Items.NETHER_STAR, Lang.t("&d#{0} {1}", event.id, event.title),
            Lang.tr("&7Estado: ") + if (event.paused) Lang.tr("&6PAUSADO") else Lang.tr("&aen marcha"),
            Lang.t("&7Tiempo restante: &f{0}", left),
            Lang.t("&7WT de evento hechos: &f{0}", event.trades),
            Lang.t("&7Jugadores distintos: &f{0}", event.playerIds.size),
            Lang.t("&7Creado por: &f{0}", event.createdBy)
        ))
        if (event.paused) {
            menu.button(11, named(Items.LIME_CONCRETE, Lang.tr("&aReanudar"), Lang.tr("&7El tiempo restante y las esperas"), Lang.tr("&7de los jugadores se conservan."))) { p ->
                WonderTradeEvents.resume(p.server, id)
                renderEventDetail(menu, id)
            }
        } else {
            menu.button(11, named(Items.YELLOW_CONCRETE, Lang.tr("&6Pausar"), Lang.tr("&7Congela el tiempo y desactiva el evento"), Lang.tr("&7hasta que lo reanudes."))) { p ->
                WonderTradeEvents.pause(p.server, id)
                renderEventDetail(menu, id)
            }
        }
        menu.button(15, named(Items.RED_CONCRETE, Lang.tr("&cFinalizar"), Lang.tr("&7Termina el evento ahora."))) { p ->
            WonderTradeEvents.stop(p.server, id)
            openAdminEvents(p)
        }
        menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdminEvents(it) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun openEventHistory(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Eventos &8- &dHistorial"), 6) { menu ->
            fill(menu, 54)
            val list = WonderTradeEvents.history(45)
            if (list.isEmpty()) menu.put(22, named(Items.BARRIER, Lang.tr("&cTodavía no hay eventos en el historial")))
            list.forEachIndexed { index, r ->
                menu.put(index, named(
                    Items.BOOK, Lang.t("&d#{0} {1}", r.id, r.title),
                    Lang.t("&7WT de evento hechos: &f{0}", r.trades),
                    Lang.t("&7Jugadores distintos: &f{0}", r.players),
                    Lang.t("&7Inicio: &f{0}", WonderTradeEvents.historyDate(r.startAt)),
                    Lang.t("&7Fin: &f{0}", WonderTradeEvents.historyDate(r.endedAt)),
                    Lang.t("&8{0}", r.reason)
                ))
            }
            menu.button(45, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdminEvents(it) }
            menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    private fun openAdminScheduled(player: ServerPlayer) {
        if (!player.hasPermissions(WonderTradeService.adminLevel())) {
            WonderTradeService.send(player, "adminOnly")
            return
        }
        open(player, Lang.tr("&5Eventos &8- &6Programados"), 6) { renderAdminScheduled(it) }
    }

    private fun renderAdminScheduled(menu: GuiMenu) {
        menu.wipe()
        fill(menu, 54)
        val list = WonderTradeSchedule.all().take(45)
        if (list.isEmpty()) {
            menu.put(22, named(
                Items.BARRIER, Lang.tr("&cNo hay eventos programados"),
                Lang.tr("&7Crea uno desde Gestionar eventos → tipo → categoría."),
                Lang.tr("&7Podrás elegir día, hora y repetición.")
            ))
        }
        list.forEachIndexed { index, item ->
            val details = mutableListOf(
                Lang.t("&7Cuándo: &f{0}", WonderTradeSchedule.describeWhen(item)),
                Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(item.durationSeconds))
            )
            item.value?.let { details += Lang.t("&7Valor: &f{0}%", WonderTradeEvents.valueText(it)) }
            item.cooldownSeconds?.let { details += Lang.t("&7Espera: &f{0}", WonderTradeEvents.formatDuration(it)) }
            details += Lang.t("&7Creado por: &f{0}", item.createdBy)
            details += Lang.tr("&cClic para cancelar")
            menu.button(index, named(Items.CLOCK, Lang.t("&d#P{0} {1}", item.id, item.title), *details.toTypedArray())) {
                WonderTradeSchedule.remove(item.id)
                renderAdminScheduled(menu)
            }
        }
        menu.button(48, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdminEvents(it) }
        menu.button(50, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun eventTypeDescription(type: EventType): List<String> = when (type) {
        EventType.RISK -> listOf(Lang.tr("&7Ofrecer Pokémon de la categoría elegida"), Lang.tr("&7suma un % extra a tu probabilidad."))
        EventType.RECEIVE -> listOf(Lang.tr("&7Aumenta la probabilidad de recibir"), Lang.tr("&7Pokémon de la categoría elegida."))
    }

    private val eventCategoryIcons = mapOf(
        EventCategory.SHINY to Items.GLOWSTONE_DUST,
        EventCategory.LEGENDARY to Items.GOLD_INGOT,
        EventCategory.MYTHICAL to Items.AMETHYST_SHARD,
        EventCategory.ULTRABEAST to Items.ECHO_SHARD,
        EventCategory.PARADOX to Items.ENDER_PEARL
    )

    private fun eventTitle(type: EventType, categories: List<EventCategory>) = eventName(type, categories)

    private fun openEventCategory(player: ServerPlayer, type: EventType, selected: List<EventCategory> = emptyList()) {
        open(player, Lang.t("&5Eventos &8- &d{0}", type.title), 3) { renderEventCategory(it, type, selected) }
    }

    /** Selección múltiple: cada clic añade o quita una categoría y el menú se actualiza sin cerrarse. */
    private fun renderEventCategory(menu: GuiMenu, type: EventType, selected: List<EventCategory>) {
        menu.wipe()
        fill(menu, 27)
        val chosen = normalizeCategories(selected)
        menu.put(4, named(
            Items.NETHER_STAR, Lang.t("&d{0}", type.title), *eventTypeDescription(type).toTypedArray(),
            Lang.tr("&7Elige una o varias categorías."),
            Lang.tr("&7Seleccionadas: ") + if (chosen.isEmpty()) Lang.tr("&8ninguna") else chosen.joinToString("&7, ") { it.coloredLabel }
        ))
        val slots = listOf(11, 12, 13, 14, 15)
        EventCategory.values().forEachIndexed { index, category ->
            val isChosen = category in chosen
            menu.button(slots[index], named(
                eventCategoryIcons[category] ?: Items.PAPER,
                (if (isChosen) "&a✔ " else "&8· ") + category.coloredLabel,
                if (isChosen) Lang.tr("&eClic para quitarla") else Lang.tr("&eClic para añadirla")
            )) {
                val next = if (isChosen) chosen - category else chosen + category
                renderEventCategory(menu, type, next)
            }
        }
        if (chosen.isEmpty()) {
            menu.put(22, named(Items.GRAY_CONCRETE, Lang.tr("&7Continuar"), Lang.tr("&cElige al menos una categoría.")))
        } else {
            menu.button(22, named(
                Items.LIME_CONCRETE, Lang.tr("&aContinuar"),
                Lang.tr("&7Categorías: ") + chosen.joinToString("&7, ") { it.coloredLabel }, Lang.tr("&eClic para elegir la duración")
            )) { openEventDuration(it, type, chosen) }
        }
        menu.button(18, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdminEvents(it) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun openEventDuration(player: ServerPlayer, type: EventType, categories: List<EventCategory>) {
        val title = eventTitle(type, categories)
        open(player, Lang.tr("&5Eventos &8- &dDuración"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.NETHER_STAR, Lang.t("&d{0}", title), *WonderTradeEvents.describeDefault(type, categories).toTypedArray(), Lang.tr("&7Elige cuánto debe durar.")))
            val maximum = WonderTradeEvents.maximumDurationSeconds()
            val options = ConfigManager.strings("events.menuDurations")
                .mapNotNull { text -> WonderTradeEvents.parseDuration(text)?.takeIf { it <= maximum } }
                .distinct()
                .take(7)
            val slots = listOf(9, 10, 11, 12, 14, 15, 16)
            options.forEachIndexed { index, seconds ->
                menu.button(slots[index], named(
                    Items.CLOCK, Lang.t("&e{0}", WonderTradeEvents.formatDuration(seconds)),
                    Lang.t("&7Iniciar «{0}» durante este tiempo.", title), Lang.tr("&eClic para iniciar")
                )) { p ->
                    val event = WonderTradeEvents.create(p.server, type, categories, seconds, null, null, p.gameProfile.name)
                    if (event == null) p.sendSystemMessage(WonderTradeService.info(Lang.tr("&cLos eventos están desactivados.")))
                    openAdminEvents(p)
                }
            }
            menu.button(13, named(
                Items.CLOCK, Lang.tr("&6Tiempo personalizado"),
                Lang.tr("&7Ajusta la duración exacta con botones"), Lang.tr("&7de minutos, horas y días."), Lang.tr("&eClic para elegir")
            )) { openEventCustomDuration(it, type, categories) }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openEventCategory(it, type, categories) }
            menu.button(26, named(Items.CLOCK, Lang.tr("&6Programar evento"), Lang.tr("&7En lugar de iniciarlo ahora, elige"), Lang.tr("&7cuándo debe comenzar."), Lang.tr("&eAbrir programación"))) { openEventScheduleDuration(it, type, categories) }
            menu.button(25, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    /** Asistente de programación: un único menú que cambia de contenido (duración → día → hora → repetición). */
    private fun openEventScheduleDuration(player: ServerPlayer, type: EventType, categories: List<EventCategory>) {
        open(player, Lang.tr("&5Programar evento"), 3) { menu -> renderScheduleDuration(menu, type, categories) }
    }

    private fun renderScheduleDuration(menu: GuiMenu, type: EventType, categories: List<EventCategory>) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.CLOCK, Lang.tr("&6Duración del evento"), Lang.tr("&7Cuánto durará cada ejecución."), Lang.tr("&7Después elegirás día y horario.")))
        val maximum = WonderTradeEvents.maximumDurationSeconds()
        val options = ConfigManager.strings("events.menuDurations")
            .mapNotNull { WonderTradeEvents.parseDuration(it)?.takeIf { value -> value <= maximum } }
            .distinct().take(7)
        val slots = listOf(9, 10, 11, 12, 14, 15, 16)
        options.forEachIndexed { index, seconds ->
            menu.button(slots[index], named(Items.CLOCK, Lang.t("&e{0}", WonderTradeEvents.formatDuration(seconds)), Lang.tr("&7Duración de cada ejecución."), Lang.tr("&eClic para continuar"))) {
                renderScheduleDay(menu, type, categories, seconds)
            }
        }
        menu.button(13, named(Items.REPEATER, Lang.tr("&6Tiempo personalizado"), Lang.tr("&7Ajusta la duración con botones + y -."), Lang.tr("&eClic para personalizar"))) {
            renderScheduleCustomDuration(menu, type, categories, 3600L)
        }
        menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openEventDuration(it, type, categories) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun renderScheduleCustomDuration(menu: GuiMenu, type: EventType, categories: List<EventCategory>, seconds: Long) {
        val maximum = WonderTradeEvents.maximumDurationSeconds()
        val current = seconds.coerceIn(60L, maximum)
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.CLOCK, Lang.tr("&6Duración personalizada"), Lang.t("&7Duración actual: &f{0}", WonderTradeEvents.formatDuration(current))))
        val steps = listOf(9 to -86_400L, 10 to -3_600L, 11 to -600L, 12 to -60L, 14 to 60L, 15 to 600L, 16 to 3_600L, 17 to 86_400L)
        for ((slot, delta) in steps) {
            val sign = if (delta < 0) "-" else "+"
            val icon = if (delta < 0) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE
            val label = WonderTradeEvents.formatDuration(kotlin.math.abs(delta))
            menu.button(slot, named(icon, (if (delta < 0) "&c" else "&a") + "$sign$label", Lang.t("&7Actual: &f{0}", WonderTradeEvents.formatDuration(current)))) {
                renderScheduleCustomDuration(menu, type, categories, (current + delta).coerceIn(60L, maximum))
            }
        }
        menu.put(13, named(Items.CLOCK, Lang.t("&b{0}", WonderTradeEvents.formatDuration(current)), Lang.tr("&7Esta será la duración de cada repetición.")))
        menu.button(22, named(Items.LIME_CONCRETE, Lang.tr("&aContinuar"), Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(current)))) {
            renderScheduleDay(menu, type, categories, current)
        }
        menu.button(18, named(Items.ARROW, Lang.tr("&aVolver"))) { renderScheduleDuration(menu, type, categories) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun renderScheduleDay(menu: GuiMenu, type: EventType, categories: List<EventCategory>, durationSeconds: Long) {
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.CLOCK, Lang.tr("&6Elegir día de la semana"), Lang.tr("&7Selecciona el día de la primera ejecución."), Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(durationSeconds))))
        val days = listOf(
            DayOfWeek.MONDAY to Lang.tr("Lunes"), DayOfWeek.TUESDAY to Lang.tr("Martes"), DayOfWeek.WEDNESDAY to Lang.tr("Miércoles"),
            DayOfWeek.THURSDAY to Lang.tr("Jueves"), DayOfWeek.FRIDAY to Lang.tr("Viernes"), DayOfWeek.SATURDAY to Lang.tr("Sábado"), DayOfWeek.SUNDAY to Lang.tr("Domingo")
        )
        days.forEachIndexed { index, (day, label) ->
            menu.button(10 + index, named(Items.PAPER, Lang.t("&e{0}", label), Lang.t("&7Próximo {0} a la hora elegida.", label), Lang.tr("&eClic para continuar"))) {
                renderEventScheduleTime(menu, type, categories, durationSeconds, day, LocalTime.of(20, 0))
            }
        }
        menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { renderScheduleDuration(menu, type, categories) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    /** Selector de horario: suma o resta minutos/horas sin salir del menú. */
    private fun renderEventScheduleTime(
        menu: GuiMenu, type: EventType, categories: List<EventCategory>, durationSeconds: Long,
        day: DayOfWeek, time: LocalTime
    ) {
        menu.wipe()
        fill(menu, 27)
        val dayLabel = WonderTradeSchedule.dayName(day)
        val formatted = time.format(DateTimeFormatter.ofPattern("HH:mm"))
        menu.put(4, named(Items.CLOCK, Lang.tr("&6Horario del evento"), Lang.t("&7Día: &f{0}", dayLabel), Lang.t("&7Hora: &b{0}", formatted), Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(durationSeconds))))
        val steps = listOf(9 to -60L, 10 to -15L, 11 to -5L, 12 to -1L, 14 to 1L, 15 to 5L, 16 to 15L, 17 to 60L)
        for ((slot, delta) in steps) {
            val sign = if (delta < 0) "-" else "+"
            val amount = kotlin.math.abs(delta)
            val label = when (amount) { 1L -> Lang.tr("1 minuto"); 5L -> Lang.tr("5 minutos"); 15L -> Lang.tr("15 minutos"); else -> Lang.tr("1 hora") }
            val icon = if (delta < 0) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE
            menu.button(slot, named(icon, (if (delta < 0) "&c" else "&a") + "$sign$label", Lang.t("&7Horario actual: &f{0}", formatted))) {
                renderEventScheduleTime(menu, type, categories, durationSeconds, day, time.plusMinutes(delta))
            }
        }
        menu.put(13, named(Items.CLOCK, Lang.t("&bHora: &f{0}", formatted), Lang.tr("&7Usa los botones para ajustar el horario."), Lang.tr("&8Zona horaria del servidor.")))
        menu.button(22, named(Items.LIME_CONCRETE, Lang.tr("&aContinuar"), Lang.t("&7Día: &f{0}", dayLabel), Lang.t("&7Hora: &f{0}", formatted), Lang.tr("&eElegir repetición"))) {
            renderScheduleRepeat(menu, type, categories, durationSeconds, day, time)
        }
        menu.button(18, named(Items.ARROW, Lang.tr("&aVolver"))) { renderScheduleDay(menu, type, categories, durationSeconds) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun finishSchedule(p: ServerPlayer, type: EventType, categories: List<EventCategory>, durationSeconds: Long, spec: ScheduleSpec) {
        val scheduled = WonderTradeSchedule.add(type, categories, durationSeconds, spec, p.gameProfile.name)
        p.sendSystemMessage(WonderTradeService.info(Lang.t("&aEvento &f{0} &aprogramado: &f{1}&a.", scheduled.title, WonderTradeSchedule.describeWhen(scheduled))))
        openAdminScheduled(p)
    }

    private fun renderScheduleRepeat(
        menu: GuiMenu, type: EventType, categories: List<EventCategory>, durationSeconds: Long,
        day: DayOfWeek, time: LocalTime
    ) {
        menu.wipe()
        fill(menu, 27)
        val dayLabel = WonderTradeSchedule.dayName(day)
        val formatted = time.format(DateTimeFormatter.ofPattern("HH:mm"))
        menu.put(4, named(Items.REPEATER, Lang.tr("&dRepetición del evento"), Lang.t("&7Primer inicio: &f{0} {1}", dayLabel, formatted), Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(durationSeconds)), Lang.t("&8Mensual: se elige qué {0} del mes.", dayLabel)))
        val choices = listOf(
            Triple(ScheduleRepeat.NONE, Items.CLOCK, Lang.tr("Una sola vez")),
            Triple(ScheduleRepeat.DAILY, Items.SUNFLOWER, "Diario"),
            Triple(ScheduleRepeat.WEEKLY, Items.COMPASS, "Semanal"),
            Triple(ScheduleRepeat.MONTHLY, Items.CLOCK, "Mensual"),
            Triple(ScheduleRepeat.YEARLY, Items.FIREWORK_ROCKET, "Anual")
        )
        val slots = listOf(10, 11, 13, 15, 16)
        choices.forEachIndexed { index, (repeat, icon, label) ->
            val hint = if (repeat == ScheduleRepeat.MONTHLY) Lang.tr("&eClic para elegir la semana") else Lang.tr("&eClic para programar")
            menu.button(slots[index], named(icon, Lang.t("&e{0}", label), Lang.t("&7{0}", repeat.label), hint)) { p ->
                if (repeat == ScheduleRepeat.MONTHLY) renderScheduleMonthly(menu, type, categories, durationSeconds, day, time)
                else finishSchedule(p, type, categories, durationSeconds, WonderTradeSchedule.fromMenu(day, time.hour, time.minute, repeat))
            }
        }
        menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { renderEventScheduleTime(menu, type, categories, durationSeconds, day, time) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    /** Repetición mensual por día de la semana: primer, segundo, tercer, cuarto o último. */
    private fun renderScheduleMonthly(
        menu: GuiMenu, type: EventType, categories: List<EventCategory>, durationSeconds: Long,
        day: DayOfWeek, time: LocalTime
    ) {
        menu.wipe()
        fill(menu, 27)
        val formatted = time.format(DateTimeFormatter.ofPattern("HH:mm"))
        menu.put(4, named(Items.CLOCK, Lang.t("&dMensual: ¿qué {0}?", WonderTradeSchedule.dayName(day)), Lang.t("&7Hora: &f{0}", formatted), Lang.t("&7Duración: &f{0}", WonderTradeEvents.formatDuration(durationSeconds))))
        val slots = listOf(1 to 10, 2 to 11, 3 to 13, 4 to 15, 5 to 16)
        for ((ordinal, slot) in slots) {
            val text = WonderTradeSchedule.ordinalText(ordinal, day)
            menu.button(slot, named(Items.PAPER, "&e${text.replaceFirstChar { it.uppercase() }}", Lang.t("&7Cada {0} del mes", text), Lang.tr("&eClic para programar"))) { p ->
                finishSchedule(p, type, categories, durationSeconds, WonderTradeSchedule.fromMenu(day, time.hour, time.minute, ScheduleRepeat.MONTHLY, ordinal))
            }
        }
        menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { renderScheduleRepeat(menu, type, categories, durationSeconds, day, time) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun openEventCustomDuration(player: ServerPlayer, type: EventType, categories: List<EventCategory>) {
        open(player, Lang.tr("&5Eventos &8- &6Tiempo"), 3) { renderEventCustomDuration(it, type, categories, 3600L) }
    }

    /** Selector de duración: los botones suman o restan tiempo y el menú se actualiza sin cerrarse. */
    /** Abre el menú de penalizaciones con el tiempo que este admin tenía elegido. */
    fun openPenaltiesFor(player: ServerPlayer) = openPenalties(player, WonderTradePenalties.selection(player.uuid) ?: defaultPenaltyMillis(), 0)

    private fun defaultPenaltyMillis(): Long =
        WonderTradePenalties.parseDuration(ConfigManager.string("penalties.defaultDuration", "1d")) ?: 86_400_000L

    /** Menú de penalizaciones: cabezas de los jugadores conectados, selector de tiempo, «permanente» y «por nombre». */
    private fun openPenalties(player: ServerPlayer, millis: Long, page: Int) {
        open(player, Lang.tr("&5Administración &8- &cPenalizaciones"), 6) { menu -> renderPenalties(menu, player, millis, page) }
    }

    private fun renderPenalties(menu: GuiMenu, admin: ServerPlayer, millis: Long, page: Int) {
        val minimum = 60_000L
        val maximum = 365L * 86_400_000L
        val permanent = millis <= 0L
        val current = if (permanent) 0L else millis.coerceIn(minimum, maximum)
        val label = if (permanent) Lang.tr("permanente") else WonderTradeEvents.formatDuration(current / 1000L)
        val reason = WonderTradePenalties.reason(admin.uuid)
        val reasonLabel = if (reason.isBlank()) Lang.tr("&7(sin motivo)") else "&f$reason"
        val players = admin.server.playerList.players.filter { it.uuid != admin.uuid }.sortedBy { it.gameProfile.name.lowercase() }
        val pages = maxOf(1, (players.size + 35) / 36)
        val shown = page.coerceIn(0, pages - 1)
        menu.wipe()
        fill(menu, 54)
        if (players.isEmpty()) menu.put(13, named(Items.PAPER, Lang.tr("&7No hay otros jugadores conectados"), Lang.tr("&8Usa «Por nombre» para jugadores desconectados.")))
        players.drop(shown * 36).take(36).forEachIndexed { index, target ->
            val active = WonderTradePenalties.active(target.uuid)
            val state = if (active != null) Lang.t("&cYa penalizado: &f{0}", WonderTradePenalties.remainingText(active)) else Lang.tr("&7Sin penalización")
            val head = playerHead(
                target.gameProfile.name, target.uuid, WonderTradeService.profileSkin(target), Lang.t("&f{0}", target.gameProfile.name),
                state, Lang.t("&7Penalizar durante: &f{0}", label), Lang.t("&7Motivo: {0}", reasonLabel), Lang.tr("&eClic para penalizar")
            )
            menu.button(index, head) { p ->
                val penalty = WonderTradePenalties.penalize(target.uuid, target.gameProfile.name, if (permanent) null else current, p.gameProfile.name, reason)
                WonderTradeService.sendPenalty(target, "penaltyNotice", penalty)
                p.sendSystemMessage(WonderTradeService.info(Lang.t("&a{0} &apenalizado: &f{1}&a.", target.gameProfile.name, WonderTradePenalties.remainingText(penalty))))
                renderPenalties(menu, p, millis, shown)
            }
        }
        val steps = listOf(36 to -86_400_000L, 37 to -3_600_000L, 38 to -600_000L, 40 to 600_000L, 41 to 3_600_000L, 42 to 86_400_000L)
        for ((slot, delta) in steps) {
            val text = WonderTradeEvents.formatDuration(kotlin.math.abs(delta) / 1000L)
            val item = if (delta < 0) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE
            menu.button(slot, named(item, (if (delta < 0) "&c-" else "&a+") + text, Lang.t("&7Duración actual: &f{0}", label))) { p ->
                val base = if (permanent) defaultPenaltyMillis() else current
                val next = (base + delta).coerceIn(minimum, maximum)
                WonderTradePenalties.rememberSelection(p.uuid, next)
                renderPenalties(menu, p, next, shown)
            }
        }
        menu.put(39, named(Items.CLOCK, Lang.t("&6Tiempo: &f{0}", label), Lang.tr("&7Mínimo 1 minuto, máximo 365 días.")))
        menu.button(43, named(
            if (permanent) Items.LIME_CONCRETE else Items.GRAY_CONCRETE, if (permanent) Lang.tr("&aPermanente: SÍ") else Lang.tr("&7Permanente: NO"),
            Lang.tr("&7Hasta que lo perdones manualmente."), Lang.tr("&eClic para cambiar")
        )) { p ->
            val next = if (permanent) defaultPenaltyMillis() else 0L
            WonderTradePenalties.rememberSelection(p.uuid, next)
            renderPenalties(menu, p, next, shown)
        }
        menu.button(44, named(Items.NAME_TAG, Lang.tr("&ePor nombre"), Lang.tr("&7Para jugadores desconectados."), Lang.t("&7Usa el tiempo elegido: &f{0}", label), Lang.tr("&eClic para escribir el nombre"))) { p ->
            WonderTradePenalties.rememberSelection(p.uuid, if (permanent) 0L else current)
            p.closeContainer()
            val button = Component.literal(Lang.tr("§e§n[Pulsa aquí para escribir el nombre]"))
                .setStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/wt admin penalizar ")))
            p.sendSystemMessage(
                WonderTradeService.colour(WonderTradeService.prefix() + Lang.t("&7Escribe el nombre del jugador (tiempo: &f{0}&7). ", label)).copy().append(button)
            )
        }
        menu.button(45, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdmin(it) }
        // Motivo: un clic recorre los motivos rápidos (el último es «sin motivo»); «Escribir motivo» pide uno propio.
        menu.button(46, named(Items.WRITABLE_BOOK, Lang.t("&6Motivo: {0}", reasonLabel), Lang.tr("&7Se muestra al jugador y queda en el historial."), Lang.tr("&eClic para cambiar de motivo rápido"))) { p ->
            val presets = WonderTradePenalties.presetReasons()
            val index = presets.indexOf(reason)
            WonderTradePenalties.rememberReason(p.uuid, if (index + 1 < presets.size) presets[index + 1] else if (reason.isBlank()) presets.firstOrNull() ?: "" else "")
            renderPenalties(menu, p, millis, shown)
        }
        menu.button(49, named(Items.OAK_SIGN, Lang.tr("&eEscribir motivo"), Lang.tr("&7Escríbelo en el chat con /wt admin motivo."), Lang.tr("&eClic para escribir"))) { p ->
            WonderTradePenalties.rememberSelection(p.uuid, if (permanent) 0L else current)
            p.closeContainer()
            val button = Component.literal("§e§n" + Lang.tr("[Pulsa aquí para escribir el motivo]"))
                .setStyle(Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/wt admin motivo ")))
            p.sendSystemMessage(WonderTradeService.colour(WonderTradeService.prefix() + Lang.tr("&7Escribe el motivo (o «ninguno»). ")).copy().append(button))
        }
        menu.button(47, named(Items.BOOK, Lang.tr("&6Penalizados"), Lang.tr("&7Ver y perdonar penalizaciones vigentes."))) { openPenalized(it, millis) }
        if (shown > 0) menu.button(48, named(Items.ARROW, Lang.tr("&ePágina anterior"))) { renderPenalties(menu, it, millis, shown - 1) }
        if (shown < pages - 1) menu.button(50, named(Items.ARROW, Lang.tr("&ePágina siguiente"))) { renderPenalties(menu, it, millis, shown + 1) }
        menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    /** Penalizaciones vigentes: clic en una cabeza para perdonar. */
    private fun openPenalized(player: ServerPlayer, millis: Long) {
        open(player, Lang.tr("&5Penalizaciones &8- &cVigentes"), 6) { menu ->
            fill(menu, 54)
            val list = WonderTradePenalties.list().take(45)
            if (list.isEmpty()) menu.put(22, named(Items.PAPER, Lang.tr("&7No hay jugadores penalizados")))
            list.forEachIndexed { index, penalty ->
                val head = playerHead(
                    penalty.name, penalty.uuid, WonderTradeService.donorSkin(penalty.uuid), Lang.t("&f{0}", penalty.name),
                    Lang.t("&cTiempo restante: &f{0}", WonderTradePenalties.remainingText(penalty)), Lang.t("&7Por: &f{0}", penalty.by), Lang.tr("&eClic para perdonar")
                )
                menu.button(index, head) { p ->
                    WonderTradePenalties.pardon(penalty.uuid, p.gameProfile.name)
                    p.server.playerList.getPlayer(penalty.uuid)?.let { WonderTradeService.send(it, "penaltyLifted") }
                    p.sendSystemMessage(WonderTradeService.info(Lang.t("&aPenalización de &f{0} &aretirada.", penalty.name)))
                    openPenalized(p, millis)
                }
            }
            menu.button(49, named(Items.ARROW, Lang.tr("&aVolver"))) { openPenalties(it, millis, 0) }
            menu.button(53, named(Items.CLOCK, Lang.tr("&eHistorial"), Lang.tr("&7Últimas penalizaciones, perdones y caducidades."))) { openPenaltyHistory(it, millis) }
        }
    }

    /** Historial de penalizaciones (las 45 últimas acciones). */
    private fun openPenaltyHistory(player: ServerPlayer, millis: Long) {
        open(player, Lang.tr("&5Penalizaciones &8- &eHistorial"), 6) { menu ->
            fill(menu, 54)
            val records = WonderTradePenalties.history(45)
            if (records.isEmpty()) menu.put(22, named(Items.PAPER, Lang.tr("&7El historial está vacío")))
            records.forEachIndexed { index, record ->
                val item = when (record.action) { "pardoned" -> Items.LIME_DYE; "expired" -> Items.GRAY_DYE; else -> Items.RED_DYE }
                menu.put(index, named(item, "&f${record.name}", WonderTradePenalties.describeRecord(record)))
            }
            menu.button(49, named(Items.ARROW, Lang.tr("&aVolver"))) { openPenalized(it, millis) }
        }
    }

    private fun renderEventCustomDuration(menu: GuiMenu, type: EventType, categories: List<EventCategory>, seconds: Long) {
        val title = eventTitle(type, categories)
        val maximum = WonderTradeEvents.maximumDurationSeconds()
        val minimum = 60L
        val current = seconds.coerceIn(minimum, maximum)
        menu.wipe()
        fill(menu, 27)
        menu.put(4, named(Items.NETHER_STAR, Lang.t("&d{0}", title), *WonderTradeEvents.describeDefault(type, categories).toTypedArray()))
        val steps = listOf(
            9 to -86_400L, 10 to -3_600L, 11 to -600L, 12 to -60L,
            14 to 60L, 15 to 600L, 16 to 3_600L, 17 to 86_400L
        )
        for ((slot, delta) in steps) {
            val label = WonderTradeEvents.formatDuration(kotlin.math.abs(delta))
            val sign = if (delta < 0) "-" else "+"
            val item = if (delta < 0) Items.RED_STAINED_GLASS_PANE else Items.LIME_STAINED_GLASS_PANE
            menu.button(slot, named(item, (if (delta < 0) "&c" else "&a") + "$sign$label", Lang.t("&7Duración actual: &f{0}", WonderTradeEvents.formatDuration(current)))) {
                renderEventCustomDuration(menu, type, categories, current + delta)
            }
        }
        menu.put(13, named(
            Items.CLOCK, Lang.t("&6Duración: &f{0}", WonderTradeEvents.formatDuration(current)),
            Lang.t("&7Mínimo: &f{0}&7  Máximo: &f{1}", WonderTradeEvents.formatDuration(minimum), WonderTradeEvents.formatDuration(maximum))
        ))
        menu.button(22, named(
            Items.LIME_CONCRETE, Lang.t("&aIniciar «{0}»", title),
            Lang.t("&7Durante &f{0}&7.", WonderTradeEvents.formatDuration(current)), Lang.tr("&eClic para iniciar")
        )) { p ->
            val event = WonderTradeEvents.create(p.server, type, categories, current, null, null, p.gameProfile.name)
            if (event == null) p.sendSystemMessage(WonderTradeService.info(Lang.tr("&cLos eventos están desactivados.")))
            openAdminEvents(p)
        }
        menu.button(18, named(Items.ARROW, Lang.tr("&aVolver"))) { openEventDuration(it, type, categories) }
        menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
    }

    private fun openAdminFill(player: ServerPlayer) {
        open(player, Lang.tr("&5Administración &8- &aRellenar pool"), 3) { renderAdminFill(it) }
    }

    private fun renderAdminFill(menu: GuiMenu) {
        run {
            menu.wipe()
            fill(menu, 27)
            menu.put(4, named(Items.HOPPER, Lang.tr("&aRellenar la pool"), Lang.tr("&7Probabilidad automática configurada."), Lang.t("&7Niveles: {0}-{1}", ConfigManager.int("fallback.minimumLevel", 1), ConfigManager.int("fallback.maximumLevel", 100))))
            val maximumPool = ConfigManager.int("pool.maximumEntries", 500).coerceAtLeast(1)
            val remaining = WonderTradeService.poolRemainingCapacity()
            val amounts = ConfigManager.ints("admin.fillMenu.amounts")
                .filter { it > 0 && it <= maximumPool }
                .distinct()
                .sorted()
                .take(7)
            val slots = listOf(9, 10, 11, 12, 14, 15, 16)
            if (amounts.isEmpty()) {
                menu.put(13, named(Items.BARRIER, Lang.tr("&cNo hay cantidades configuradas"), Lang.tr("&7Edita admin.fillMenu.amounts en config.json.")))
            } else {
                amounts.forEachIndexed { index, amount ->
                    val effectiveAmount = minOf(amount, remaining)
                    val lore = if (remaining <= 0) {
                        listOf(Lang.tr("&cLa pool está completa."), Lang.t("&7Máximo: {0} Pokémon.", maximumPool))
                    } else {
                        listOf(Lang.t("&7Añadirá hasta &f{0} &7Pokémon.", effectiveAmount), Lang.t("&7Huecos disponibles: &f{0}", remaining), Lang.tr("&eClic para rellenar"))
                    }
                    menu.button(slots[index], named(Items.CHEST_MINECART, Lang.t("&eAñadir {0} Pokémon", amount), *lore.toTypedArray())) { p ->
                        val minLevel = ConfigManager.int("fallback.minimumLevel", 1).coerceIn(1, 100)
                        val maxLevel = ConfigManager.int("fallback.maximumLevel", 100).coerceIn(minLevel, 100)
                        val availableNow = minOf(amount, WonderTradeService.poolRemainingCapacity())
                        val result = WonderTradeService.adminFill(p, amount, null, minLevel, maxLevel)
                        val chance = String.format("%.2f", result.chancePercent)
                        p.sendSystemMessage(WonderTradeService.info(Lang.t("&aGenerados &f{0}/{1} &aPokémon; especiales: &d{2} &7(probabilidad: {3}%).", result.added, availableNow, result.special, chance)))
                        renderAdminFill(menu)
                    }
                }
            }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver al panel admin"))) { openAdmin(it) }
            menu.button(26, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
        }
    }

    private fun openAdminPoolDeleteOptions(player: ServerPlayer) {
        open(player, Lang.tr("&5Administración &8- &cEliminar pool"), 3) { menu ->
            fill(menu, 27)
            val generated = WonderTradeService.poolEntries().count { it.donorName.equals("Servidor", ignoreCase = true) }
            val playerEntries = WonderTradeService.poolEntries().count { !it.donorName.equals("Servidor", ignoreCase = true) }
            menu.put(4, named(Items.BARRIER, Lang.tr("&cEliminar por origen"), Lang.tr("&7Elige qué Pokémon retirar de la pool."), Lang.t("&7Generados por servidor: &f{0}", generated), Lang.t("&7Aportados por jugadores: &f{0}", playerEntries)))
            menu.button(11, named(Items.COMMAND_BLOCK, Lang.tr("&cSolo generados por el servidor"), Lang.tr("&7Elimina únicamente los Pokémon"), Lang.tr("&7generados automáticamente por el servidor."), Lang.tr("&cPide confirmación."))) { p ->
                openRemoveGeneratedPoolConfirm(p)
            }
            val playersHead = configHead(
                "gui.heads.players", Lang.tr("&cSolo generados por jugadores"), Lang.tr("&7Abre la lista de jugadores que han aportado."), Lang.tr("&7Selecciona uno para eliminar sus Pokémon.")
            ) ?: playerHead(player.gameProfile.name, player.uuid, WonderTradeService.profileSkin(player), Lang.tr("&cSolo generados por jugadores"), Lang.tr("&7Abre la lista de jugadores que han aportado."), Lang.tr("&7Selecciona uno para eliminar sus Pokémon."))
            menu.button(13, playersHead) { p ->
                openAdminDonors(p)
            }
            menu.button(15, named(Items.TNT, Lang.tr("&4Eliminar toda la pool"), Lang.tr("&7Elimina todas las entradas, sin importar su origen."), Lang.tr("&cPide confirmación."))) { openClearPoolConfirm(it) }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver al panel admin"))) { openAdmin(it) }
        }
    }

    private fun openAdminDonors(player: ServerPlayer, page: Int = 0) {
        val donors = WonderTradeService.poolDonorProfiles()
            .sortedWith(compareBy({ !it.first.equals("Servidor", ignoreCase = true) }, { it.first.lowercase() }))
        val pages = maxOf(1, (donors.size + 44) / 45)
        val current = page.coerceIn(0, pages - 1)
        open(player, Lang.tr("&5Administración &8- &cAportaciones"), 6) { menu ->
            fill(menu, 54)
            if (donors.isEmpty()) {
                menu.put(22, named(Items.PAPER, Lang.tr("&7No hay aportaciones"), Lang.tr("&8No hay donantes registrados en la pool.")))
            }
            donors.drop(current * 45).take(45).forEachIndexed { index, (donor, donorUuid) ->
                val isServer = donor.equals("Servidor", ignoreCase = true)
                val head = if (isServer) {
                    configHead("gui.heads.server", Lang.tr("&6Servidor"), Lang.tr("&7Pokémon generados por el servidor."), Lang.tr("&cClic para eliminar"))
                        ?: named(Items.DRAGON_HEAD, Lang.tr("&6Servidor"), Lang.tr("&7Pokémon generados por el servidor."), Lang.tr("&cClic para eliminar"))
                } else {
                    // Con UUID y textura guardados la cabeza se ve aunque el jugador esté desconectado; si no, se resuelve por nombre.
                    val skin = donorUuid?.let { WonderTradeService.donorSkin(it) }
                    playerHead(donor, donorUuid, skin, Lang.t("&f{0}", donor), Lang.tr("&7Eliminar todos sus Pokémon aportados."), Lang.tr("&cClic para eliminar"))
                }
                menu.button(index, head) { p ->
                    val removed = WonderTradeService.removeDonorEntries(donor)
                    p.sendSystemMessage(WonderTradeService.info(Lang.t("&aEliminados &f{0} &aPokémon aportados por &f{1}&a.", removed, donor)))
                    openAdminDonors(p, current)
                }
            }
            if (current > 0) menu.button(45, named(Items.ARROW, Lang.tr("&ePágina anterior"))) { openAdminDonors(it, current - 1) }
            menu.button(49, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aVolver a eliminar pool"))) { openAdminPoolDeleteOptions(it) }
            if (current < pages - 1) menu.button(53, named(Items.ARROW, Lang.tr("&ePágina siguiente"))) { openAdminDonors(it, current + 1) }
        }
    }

    // ---------------------------------------------------------------- permisos de los botones del panel

    /** Nivel necesario para un botón del panel: admin.buttonLevels.<id> (0 = el de admin.permissionLevel). */
    private fun buttonLevel(id: String): Int =
        ConfigManager.int("admin.buttonLevels.$id", 0).let { if (it <= 0) WonderTradeService.adminLevel() else it.coerceAtMost(4) }

    /** Botón del panel de administración: sin el rango necesario se ve bloqueado y avisa al pulsarlo. */
    private fun adminButton(menu: GuiMenu, player: ServerPlayer, slot: Int, id: String, stack: ItemStack, action: (ServerPlayer) -> Unit) {
        val level = buttonLevel(id)
        if (player.hasPermissions(level)) {
            // Se vuelve a comprobar al pulsar por si el rango cambió con el menú abierto.
            menu.button(slot, stack) { p -> if (p.hasPermissions(level)) action(p) else denyAdminButton(p, level) }
        } else {
            val locked = stack.copy()
            val old = locked.get(DataComponents.LORE)?.lines() ?: emptyList()
            locked.set(DataComponents.LORE, ItemLore(old + lit(Lang.t("&c✖ Requiere rango de administración (nivel {0})", level))))
            menu.button(slot, locked) { p -> denyAdminButton(p, level) }
        }
    }

    private fun denyAdminButton(player: ServerPlayer, level: Int) {
        player.sendSystemMessage(WonderTradeService.info(Lang.t("&cNo tienes rango suficiente para usar este botón (se necesita nivel {0}).", level)))
    }

    // ---------------------------------------------------------------- reiniciar estadísticas

    /** Elegir entre reiniciar las estadísticas de todos o las de un jugador concreto (por su cabeza). */
    private fun openResetStatsMenu(player: ServerPlayer) {
        open(player, Lang.tr("&5Administración &8- &eReiniciar estadísticas"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.BOOK, Lang.tr("&eReiniciar estadísticas"), Lang.tr("&7Elige a quién reiniciar."), Lang.tr("&7Bonus, cooldowns y límites no se tocan.")))
            menu.button(11, named(Items.TNT, Lang.tr("&cBorrar todas"), Lang.tr("&7Reinicia las estadísticas de todos los jugadores."), Lang.tr("&cNo se puede deshacer."), Lang.tr("&eClic para continuar"))) {
                openResetStatsConfirm(it)
            }
            val head = playerHead(
                player.gameProfile.name, player.uuid, WonderTradeService.profileSkin(player), Lang.tr("&bElegir jugador"),
                Lang.tr("&7Muestra las cabezas de los jugadores"), Lang.tr("&7conectados y desconectados."), Lang.tr("&eClic para abrir")
            )
            menu.button(15, head) { openResetStatsPlayers(it, 0) }
            menu.button(22, named(Items.ARROW, Lang.tr("&aVolver"))) { openAdmin(it) }
        }
    }

    private fun openResetStatsPlayers(player: ServerPlayer, page: Int) {
        open(player, Lang.tr("&5Reiniciar estadísticas &8- &fJugadores"), 6) { menu -> renderResetStatsPlayers(menu, player, page) }
    }

    private fun renderResetStatsPlayers(menu: GuiMenu, admin: ServerPlayer, requestedPage: Int) {
        menu.wipe()
        fill(menu, 54)
        val online = admin.server.playerList.players.associateBy { it.uuid }
        val known = WonderTradeService.knownPlayers().toMutableList()
        online.values.forEach { p -> if (known.none { it.first == p.uuid }) known += p.uuid to p.gameProfile.name }
        val all = known.sortedBy { it.second.lowercase() }
        val pages = maxOf(1, (all.size + 44) / 45)
        val page = requestedPage.coerceIn(0, pages - 1)
        if (all.isEmpty()) menu.put(22, named(Items.PAPER, Lang.tr("&7No hay jugadores guardados")))
        all.drop(page * 45).take(45).forEachIndexed { index, (uuid, name) ->
            val target = online[uuid]
            val skin = target?.let { WonderTradeService.profileSkin(it) } ?: WonderTradeService.savedSkin(uuid)
            val head = playerHead(
                name, uuid, skin, Lang.t("&f{0}", name),
                if (target != null) Lang.tr("&aConectado") else Lang.tr("&7Desconectado"),
                Lang.t("&7Intercambios: &f{0}", WonderTradeService.statOf(uuid, "trades")),
                Lang.t("&7Donaciones: &f{0}", WonderTradeService.statOf(uuid, "donations")),
                Lang.tr("&eClic para reiniciar sus estadísticas")
            )
            menu.button(index, head) { openResetPlayerConfirm(it, uuid, name, page) }
        }
        if (page > 0) menu.button(45, named(Items.ARROW, Lang.tr("&ePágina anterior")), "pageTurn") { renderResetStatsPlayers(menu, it, page - 1) }
        menu.button(49, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aVolver"))) { openResetStatsMenu(it) }
        if (page < pages - 1) menu.button(53, named(Items.ARROW, Lang.tr("&ePágina siguiente")), "pageTurn") { renderResetStatsPlayers(menu, it, page + 1) }
    }

    private fun openResetPlayerConfirm(player: ServerPlayer, uuid: java.util.UUID, name: String, page: Int) {
        open(player, Lang.t("&cReiniciar estadísticas de {0}", name), 3) { menu ->
            fill(menu, 27)
            val target = player.server.playerList.getPlayer(uuid)
            val skin = target?.let { WonderTradeService.profileSkin(it) } ?: WonderTradeService.savedSkin(uuid)
            menu.put(4, playerHead(
                name, uuid, skin, Lang.t("&f{0}", name),
                Lang.t("&7Intercambios: &f{0}", WonderTradeService.statOf(uuid, "trades")),
                Lang.t("&7Especiales recibidos: &d{0}", WonderTradeService.statOf(uuid, "specialsReceived")),
                Lang.t("&7Especiales aportados: &d{0}", WonderTradeService.statOf(uuid, "specialsDonated")),
                Lang.t("&7Donaciones directas: &f{0}", WonderTradeService.statOf(uuid, "donations"))
            ))
            menu.button(11, named(Items.TNT, Lang.tr("&cSí, reiniciar"), Lang.tr("&7Se ponen a 0 sus estadísticas."), Lang.tr("&cNo se puede deshacer."))) { p ->
                if (WonderTradeService.adminResetPlayerStats(uuid)) p.sendSystemMessage(WonderTradeService.info(Lang.t("&aEstadísticas de &f{0} &areiniciadas.", name)))
                else p.sendSystemMessage(WonderTradeService.info(Lang.t("&cNo hay datos guardados de &f{0}&c.", name)))
                openResetStatsPlayers(p, page)
            }
            menu.button(15, named(Items.ARROW, Lang.tr("&aCancelar"))) { openResetStatsPlayers(it, page) }
        }
    }

    private fun openResetStatsConfirm(player: ServerPlayer) {
        open(player, Lang.tr("&cConfirmar reinicio de estadísticas"), 3) { menu ->
            fill(menu, 27)
            menu.button(11, named(Items.TNT, Lang.tr("&cSí, reiniciar todo"), Lang.tr("&7Se reiniciarán los tres rankings."))) { p ->
                val affected = WonderTradeService.adminResetAllStats()
                p.sendSystemMessage(WonderTradeService.info(Lang.t("&aEstadísticas reiniciadas para &f{0} &ajugadores.", affected)))
                openAdmin(p)
            }
            menu.button(15, named(Items.ARROW, Lang.tr("&aCancelar"))) { openResetStatsMenu(it) }
        }
    }

    private fun openRemoveGeneratedPoolConfirm(player: ServerPlayer) {
        val generated = WonderTradeService.poolEntries().count { it.donorName.equals("Servidor", ignoreCase = true) }
        open(player, Lang.tr("&cConfirmar eliminación"), 3) { menu ->
            fill(menu, 27)
            menu.put(4, named(Items.COMMAND_BLOCK, Lang.tr("&cEliminar Pokémon del servidor"), Lang.t("&7Se eliminarán &f{0} &7Pokémon", generated), Lang.tr("&7generados automáticamente."), Lang.tr("&cLas aportaciones de jugadores se conservarán.")))
            menu.button(11, named(Items.TNT, Lang.tr("&cSí, eliminar"), Lang.tr("&7Eliminar los Pokémon generados por el servidor."))) { p ->
                val removed = WonderTradeService.removeGeneratedPoolEntries()
                p.sendSystemMessage(WonderTradeService.info(Lang.t("&aSe han eliminado &f{0} &aPokémon generados por el servidor.", removed)))
                openAdminPoolDeleteOptions(p)
            }
            menu.button(15, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aCancelar"), Lang.tr("&7Volver a la selección de eliminaciones."))) { openAdminPoolDeleteOptions(it) }
        }
    }

    private fun openClearPoolConfirm(player: ServerPlayer) {
        open(player, Lang.tr("&cConfirmar vaciado de pool"), 3) { menu ->
            fill(menu, 27)
            menu.button(11, named(Items.TNT, ConfigManager.string("messages.adminMenuClearConfirm", "&cConfirmar vaciado"))) {
                WonderTradeService.clearPool()
                WonderTradeService.send(it, "poolCleared")
                openAdminPoolDeleteOptions(it)
            }
            menu.button(15, named(Items.LIME_STAINED_GLASS_PANE, ConfigManager.string("messages.adminMenuBack", "&aVolver"))) { openAdminPoolDeleteOptions(it) }
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
                "cooldownReady" -> "block.note_block.pling"
                "specialShinyReceived" -> "item.totem.use"
                else -> return
            }
            val sound = BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.withDefaultNamespace(soundPath)).orElse(null) ?: return
            val volume = ConfigManager.double("sounds.volume", 0.45).coerceIn(0.0, 1.0).toFloat()
            // El aviso de cooldown usa una nota aguda y clara, distinta del sonido de experiencia.
            val pitch = if (key == "cooldownReady") 1.15f
                else ConfigManager.double("sounds.pitch", 1.0).coerceIn(0.5, 2.0).toFloat()
            player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch)
        }.onFailure { WonderTradePlus.LOGGER.debug("No se pudo reproducir el sonido {} de Wonder Trade Plus.", key, it) }
    }

    private fun renderHistory(menu: GuiMenu, player: ServerPlayer, filter: String?, requestedPage: Int, own: Boolean = false) {
        menu.wipe()
        fill(menu, 54)
        // En el historial propio se ignora cualquier filtro: siempre es el del jugador que mira.
        val entries = if (own) WonderTradeService.ownHistory(player) else WonderTradeService.historyEntries(filter)
        val pages = maxOf(1, (entries.size + POOL_PER_PAGE - 1) / POOL_PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm")

        entries.drop(page * POOL_PER_PAGE).take(POOL_PER_PAGE).forEachIndexed { i, entry ->
            menu.put(i, historyStack(player, entry, dateFormat))
        }
        if (entries.isEmpty()) menu.put(22, named(Items.BARRIER, Lang.tr("&cNo hay intercambios registrados")))

        if (own) menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aVolver al equipo"))) { openMain(it) }
        else menu.button(45, named(Items.LIME_STAINED_GLASS_PANE, Lang.tr("&aVolver al panel admin"))) { openAdmin(it) }
        if (page > 0) menu.button(48, named(Items.ARROW, Lang.tr("&ePágina anterior")), "pageTurn") { renderHistory(menu, it, filter, page - 1, own) }
        menu.put(49, named(Items.PAPER, Lang.t("&fPágina {0}/{1}", page + 1, pages), Lang.t("&7{0} intercambios", entries.size) + if (filter != null) Lang.t(" de {0}", filter) else ""))
        if (page < pages - 1) menu.button(50, named(Items.ARROW, Lang.tr("&ePágina siguiente")), "pageTurn") { renderHistory(menu, it, filter, page + 1, own) }
        menu.button(53, named(Items.BARRIER, Lang.tr("&cCerrar"))) { it.closeContainer() }
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

        val shinyLabel = Lang.tr("&e[Shiny]")
        icon.set(DataComponents.CUSTOM_NAME, lit(Lang.t("&b{0}", entry.player)))
        val lines = listOf(
            "&8────────────────",
            Lang.t("&c▼ Dio: &f{0} &7Nv.{1}", entry.sentName, entry.sentLevel) + if (entry.sentShiny) " $shinyLabel" else "",
            Lang.t("&a▲ Recibió: &f{0} &7Nv.{1}", entry.receivedName, entry.receivedLevel) + if (entry.receivedShiny) " $shinyLabel" else "",
            Lang.tr("&7Tipo: ") + (receivedTypes ?: Lang.tr("&8desconocido")),
            "&8────────────────",
            Lang.tr("&7Origen: &f") + if (entry.fromPool) Lang.tr("pool de jugadores") else Lang.tr("generado al azar"),
            Lang.t("&7Fecha: &f{0}", dateFormat.format(Date(entry.time)))
        )
        icon.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return icon
    }

    private fun openConfirm(player: ServerPlayer, pokemon: Pokemon, fromPc: Boolean, donationMode: Boolean) {
        open(
            player,
            if (donationMode) Lang.tr("&5Wonder Trade &8- &fConfirmar donación") else Lang.tr("&5Wonder Trade &8- &fConfirmar"),
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
                if (donationMode) Lang.tr("&aConfirmar donación") else Lang.tr("&aConfirmar intercambio"),
                Lang.t("&7Ofreces: {0}{1}", WonderTradeRules.nameColor(pokemon), pokemon.getDisplayName().string),
                if (donationMode) Lang.tr("&7No recibirás ningún Pokémon a cambio.") else Lang.tr("&7Recibirás otro Pokémon de la pool.")
            )) { p ->
                if (donationMode) WonderTradeService.donateFromStore(p, fromPc, pokemon.uuid)
                else WonderTradeService.tradeFromStore(p, fromPc, pokemon.uuid)
                p.closeContainer()
            }
            menu.button(15, named(Items.RED_CONCRETE, Lang.tr("&cCancelar"))) { openMain(it, donationMode) }
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

    /**
     * Cabeza de un jugador. Si hay textura guardada (valor y firma) se usa tal cual, junto con su UUID, y no depende de que esté
     * conectado ni de Mojang. Sin textura se usa solo el nombre (el cliente lo resuelve; en servidores no premium el UUID
     * no sirve para consultar a Mojang, por eso no se envía).
     */
    private fun playerHead(profileName: String, uuid: java.util.UUID?, skin: Pair<String, String?>?, name: String, vararg lore: String): ItemStack {
        val stack = named(Items.PLAYER_HEAD, name, *lore)
        val properties = PropertyMap()
        if (skin != null) {
            val signature = skin.second
            properties.put("textures", if (signature != null) Property("textures", skin.first, signature) else Property("textures", skin.first))
            stack.set(DataComponents.PROFILE, ResolvableProfile(Optional.of(profileName), Optional.ofNullable(uuid), properties))
        } else {
            stack.set(DataComponents.PROFILE, ResolvableProfile(Optional.of(profileName), Optional.empty(), properties))
        }
        return stack
    }

    /** Cabeza con textura propia indicada en la configuración (valor base64 de «textures»); null si no hay ninguna. */
    private fun configHead(key: String, name: String, vararg lore: String): ItemStack? {
        val value = ConfigManager.string(key, "").trim()
        if (value.isEmpty()) return null
        val stack = named(Items.PLAYER_HEAD, name, *lore)
        val properties = PropertyMap()
        properties.put("textures", Property("textures", value))
        stack.set(DataComponents.PROFILE, ResolvableProfile(Optional.empty(), Optional.empty(), properties))
        return stack
    }

    /** Cabeza decorativa con la textura oficial del logotipo de Discord. */
    private fun discordHead(name: String, vararg lore: String): ItemStack {
        val stack = named(Items.PLAYER_HEAD, name, *lore)
        val properties = PropertyMap()
        properties.put(
            "textures",
            Property(
                "textures",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGQ0MjMzN2JlMGJkY2EyMTI4MDk3ZjFjNWJiMTEwOWU1YzYzM2MxNzkyNmFmNWZiNmZjMjAwMDAwMTFhZWI1MyJ9fX0="
            )
        )
        stack.set(DataComponents.PROFILE, ResolvableProfile(Optional.empty(), Optional.empty(), properties))
        return stack
    }

    private fun cobblemonItem(path: String): Item =
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", path)).orElse(Items.PAPER)

    private fun named(item: Item, name: String, vararg lore: String): ItemStack {
        val stack = ItemStack(item)
        stack.set(DataComponents.CUSTOM_NAME, lit(name))
        if (lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(lore.map<String, Component> { lit(it) }))
        return stack
    }

    private fun fill(menu: GuiMenu, size: Int) {
        for (i in 0 until size) menu.put(i, named(Items.BLACK_STAINED_GLASS_PANE, " "))
    }

    /** IVs con un color por estadística; si hay chapa (plateada u oro) el IV efectivo aparece entre paréntesis. */
    private fun ivLines(pokemon: Pokemon): List<String> = runCatching {
        val stats = listOf(
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.HP, "HP", "&c"),
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK, "ATK", "&6"),
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE, "DEF", "&e"),
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK, "SPATK", "&9"),
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE, "SPDEF", "&a"),
            Triple(com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED, "SPD", "&d")
        )
        val parts = stats.map { (stat, label, color) ->
            val base = pokemon.ivs[stat] ?: 0
            val effective = pokemon.ivs.getEffectiveBattleIV(stat)
            val value = if (base >= 31) Lang.t("&f&l{0}", base) else Lang.t("&f{0}", base)
            val bonus = if (effective != base) Lang.t(" &7(&b{0}&7)", effective) else ""
            Lang.t("{0}{1} &r{2}{3}", color, label, value, bonus)
        }
        val perfect = perfectIvCount(pokemon)
        val header = if (perfect >= 6) Lang.tr("&7IVs: &6★ perfectos &8(6x31)") else if (perfect > 0) Lang.t("&7IVs: &8({0}x31)", perfect) else Lang.tr("&7IVs:")
        listOf(header, "  " + parts.take(3).joinToString(" &8| "), "  " + parts.drop(3).joinToString(" &8| "))
    }.getOrDefault(emptyList())

    private val natureNames = mapOf(
        "hardy" to "Fuerte", "lonely" to "Huraña", "brave" to "Audaz", "adamant" to "Firme", "naughty" to "Pícara",
        "bold" to "Osada", "docile" to "Dócil", "relaxed" to "Plácida", "impish" to "Agitada", "lax" to "Floja",
        "timid" to "Miedosa", "hasty" to "Activa", "serious" to "Seria", "jolly" to "Alegre", "naive" to "Ingenua",
        "modest" to "Modesta", "mild" to "Afable", "quiet" to "Mansa", "bashful" to "Tímida", "rash" to "Alocada",
        "calm" to "Serena", "gentle" to "Amable", "sassy" to "Grosera", "careful" to "Cauta", "quirky" to "Rara"
    )

    private fun natureLabel(nature: com.cobblemon.mod.common.pokemon.Nature): String =
        nature.name.path.let { natureNames[it.lowercase()]?.let { spanish -> Lang.tr(spanish) } ?: it.replaceFirstChar { c -> c.uppercase() } }

    /** Naturaleza en español; si el Pokémon usa una menta, la naturaleza de la menta va al lado entre paréntesis. */
    private fun natureLine(pokemon: Pokemon): String? = runCatching {
        val own = natureLabel(pokemon.nature)
        val minted = pokemon.mintedNature?.takeIf { it != pokemon.nature }
        Lang.t("&7Naturaleza: &f{0}", own) + if (minted != null) Lang.t(" &7(menta: &b{0}&7)", natureLabel(minted)) else ""
    }.getOrNull()

    private fun pokemonStack(pokemon: Pokemon, showOfferHint: Boolean, extra: List<String> = emptyList()): ItemStack {
        val stack = runCatching { PokemonItem.from(pokemon) }.getOrElse { ItemStack(Items.PAPER) }
        val name = pokemon.getDisplayName().string
        val size = if (ConfigManager.bool("pool.size.show", true)) " &8(&7" + WonderTradeRules.sizeLabel(pokemon) + "&8)" else ""
        stack.set(DataComponents.CUSTOM_NAME, lit(WonderTradeRules.nameColor(pokemon) + name + size))

        val lines = mutableListOf(Lang.t("&7Nivel: &f{0}", pokemon.level))
        WonderTradeRules.typesLabel(pokemon)?.let { lines += Lang.t("&7Tipo: {0}", it) }
        WonderTradeRules.formLabel(pokemon)?.let { lines += Lang.t("&7Forma: &f{0}", it) }
        val labels = mutableListOf<String>()
        WonderTradeRules.categoryLabel(pokemon)?.let { labels += it }
        if (pokemon.shiny) labels += Lang.tr("&eShiny")
        if (labels.isNotEmpty()) lines += Lang.tr("&7Categoría: ") + labels.joinToString(" &7· ")
        if (pokemon.isAlpha) lines += Lang.tr("&cAlfa")
        natureLine(pokemon)?.let { lines += it }
        if (ConfigManager.bool("menu.showIvs", true)) lines += ivLines(pokemon)
        lines += extra
        if (showOfferHint) {
            lines += if (WonderTradeRules.validateOffer(pokemon) != null) Lang.tr("&c✖ No se puede ofrecer ahora") else Lang.tr("&a▶ Clic para ofrecer")
        }
        stack.set(DataComponents.LORE, ItemLore(lines.map<String, Component> { lit(it) }))
        return stack
    }
}
