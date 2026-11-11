package dev.openwondertrade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import org.slf4j.LoggerFactory

class WonderTradePlus : ModInitializer {
    override fun onInitialize() {
        LOGGER.info("Registrando WonderTrade Plus...")
        // La inicialización y todas las funciones se ejecutan en el servidor.
        ServerLifecycleEvents.SERVER_STARTED.register { server -> WonderTradeService.initialize(server) }
        ServerTickEvents.END_SERVER_TICK.register { server -> WonderTradeService.tick(server) }
        // Aviso de eventos activos al entrar (todo en el servidor).
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            runCatching { WonderTradeService.rememberPlayer(handler.player) }
            Lang.withPlayer(handler.player) { WonderTradeEventDisplay.onJoin(handler.player) }
            runCatching { WonderTradePenalties.onJoin(handler.player) }
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher, "wt")
            registerCommands(dispatcher, "wondertrade")
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>, root: String) {
        // PERMISO: cualquier jugador puede ejecutar el comando raíz para abrir la GUI o ver ayuda.
        val command = literal(root)
            // Todo lo que se ejecute con /wt se redacta en el idioma del jugador que lo escribe.
            .requires { source -> Lang.bindPlayer(source.player); true }
            .executes { context ->
                val player = context.source.player
                if (player != null && ConfigManager.bool("gui.enabled", true)) {
                    WonderTradeGui.openMain(player)
                } else {
                    context.source.sendSuccess({ WonderTradeService.helpComponent(context.source.player) }, false)
                }
                1
            }
            // PERMISO: admin.permissionLevel; activa o desactiva Wonder Trade sin quitar el mod.
            .then(literal("on").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                WonderTradeService.setEnabled(true)
                context.source.sendSuccess({ WonderTradeService.info(Lang.tr("&aWonder Trade activado.")) }, true)
                1
            })
            .then(literal("off").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                WonderTradeService.setEnabled(false)
                context.source.sendSuccess({ WonderTradeService.info(Lang.tr("&cWonder Trade desactivado. Los jugadores no podrán intercambiar ni donar hasta usar &f/wt on&c.")) }, true)
                1
            })
            // Modo infinito (pruebas del admin): sin cooldown de /wt ni de /wt donar. Oculto en la ayuda y en el menú.
            .then(literal("infinito").requires { it.hasPermission(WonderTradeService.adminLevel()) }
                .then(literal("on").executes { context -> infiniteCommand(context, true) })
                .then(literal("off").executes { context -> infiniteCommand(context, false) }))
            // Historial propio: cada jugador solo ve sus intercambios (no admite nombre de otro jugador).
            .then(literal("historial").executes { context -> ownHistoryCommand(context) })
            .then(literal("history").executes { context -> ownHistoryCommand(context) })
            // Idioma propio de cada jugador. «language» es alias de «idioma».
            .then(literal("idioma").executes { context -> openLanguageCommand(context) }
                .then(argument("codigo", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(Lang.available().map { it.first } + "auto", builder) }
                    .executes { context -> setLanguageCommand(context, StringArgumentType.getString(context, "codigo")) }))
            .then(literal("language").executes { context -> openLanguageCommand(context) }
                .then(argument("codigo", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(Lang.available().map { it.first } + "auto", builder) }
                    .executes { context -> setLanguageCommand(context, StringArgumentType.getString(context, "codigo")) }))
            // PERMISO: público; muestra la ayuda.
            .then(literal("help").executes { context ->
                context.source.sendSuccess({ WonderTradeService.helpComponent(context.source.player) }, false)
                1
            })
            // Preferencias individuales de sonido; no requiere permisos ni instalación en el cliente.
            // «sounds» es alias de «sonidos».
            .then(literal("sonidos").executes { openSoundsCommand(it) })
            .then(literal("sounds").executes { openSoundsCommand(it) })
            // PERMISO: público; cualquier jugador puede donar si las donaciones están habilitadas y hay espacio.
            // La capacidad se vuelve a comprobar al confirmar la donación.
            .then(literal("donar").executes { context ->
                val player = context.source.playerOrException
                if (WonderTradeService.canOpenDonation(player)) {
                    WonderTradeGui.openMain(player, donationMode = true)
                }
                1
            })
            // PERMISO: público; inicia un intercambio desde el slot 1–6.
            .then(argument("slot", IntegerArgumentType.integer(1, 6))
                .executes { context ->
                    val player = context.source.playerOrException
                    WonderTradeService.request(player, IntegerArgumentType.getInteger(context, "slot"))
                    1
                }
                // PERMISO: público; confirma directamente el slot indicado.
                .then(literal("confirm").executes { context ->
                    val player = context.source.playerOrException
                    WonderTradeService.tradeNow(player, IntegerArgumentType.getInteger(context, "slot"))
                    1
                })
            )
            // PERMISO: público; confirma el intercambio pendiente.
            .then(literal("confirm").executes { context ->
                WonderTradeService.confirm(context.source.playerOrException)
                1
            })
            // PERMISO: público; estadísticas propias y públicas visibles sin OP.
            .then(literal("stats")
                .requires { it.hasPermission(ConfigManager.int("gui.statsOwnPermissionLevel", 0).coerceIn(0, 4)) }
                .executes { context ->
                    val player = context.source.playerOrException
                    context.source.sendSuccess({ WonderTradeService.statsComponent(player.uuid, player.gameProfile.name) }, false)
                    1
                }
                .then(argument("player", StringArgumentType.word())
                    .requires { it.hasPermission(ConfigManager.int("gui.statsOthersPermissionLevel", 0).coerceIn(0, 4)) }
                    .suggests { context, builder ->
                        SharedSuggestionProvider.suggest(
                            (context.source.onlinePlayerNames + WonderTradeService.knownPlayerNames()).distinct(),
                            builder
                        )
                    }
                    .executes { context ->
                        val name = StringArgumentType.getString(context, "player")
                        val found = WonderTradeService.resolvePlayer(context.source.server, name)
                        if (found == null) {
                            context.source.sendFailure(WonderTradeService.info(Lang.t("&cNo hay datos de &f{0}&c.", name)))
                        } else {
                            context.source.sendSuccess({ WonderTradeService.statsComponent(found.first, found.second) }, false)
                        }
                        1
                    }))
            // PERMISO: público; el ranking es visible para todos.
            .then(literal("top").executes { context ->
                WonderTradeGui.openTop(context.source.playerOrException)
                1
            })
            // PERMISO: público; qué Pokémon cuentan como míticos, legendarios, Ultraentes y Paradoja.
            .then(literal("especiales").executes { openSpecialsCommand(it) })
            // PERMISO: público; Top Donaciones del mes (puntos, ranking y próximo mes activo).
            .then(literal("donaciones").executes { showTopDonationsCommand(it) })
            // PERMISO: público; menú para reclamar las recompensas del Top Donaciones. «rewards» es alias.
            .then(literal("recompensas").executes { WonderTradeGui.openRewards(it.source.playerOrException); 1 })
            .then(literal("rewards").executes { WonderTradeGui.openRewards(it.source.playerOrException); 1 })
            // PERMISO: público; muestra tu rango y sus ventajas. «rank» es alias de «rango».
            .then(literal("rango").executes { context -> showOwnRank(context) })
            .then(literal("rank").executes { context -> showOwnRank(context) })
            // PERMISO: público; menú con todos los rangos y sus ventajas. «ranks» es alias.
            .then(literal("rangos").executes { context -> openRanksCommand(context) })
            .then(literal("ranks").executes { context -> openRanksCommand(context) })
            // PERMISO: público; muestra los eventos activos.
            // «events» es alias de «eventos».
            .then(literal("eventos").executes { showEventsCommand(it) })
            .then(literal("events").executes { showEventsCommand(it) })
            // PERMISO: público; cancela el intercambio pendiente.
            .then(literal("cancel").executes { context ->
                WonderTradeService.cancel(context.source.playerOrException)
                1
            })
            // PERMISO: gui.poolViewPermissionLevel para jugadores; los admins acceden con admin.permissionLevel.
            .then(literal("pool")
                .executes { context ->
                    val player = context.source.player
                    if (player == null) {
                        context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo puede usarse dentro del juego.")))
                    } else {
                        // Vista normal para todos; la gestión de la pool está en /wt admin.
                        WonderTradeGui.openPool(player, 0, false)
                    }
                    1
                }
                .then(literal("search").then(argument("text", StringArgumentType.greedyString()).executes { context ->
                    val player = context.source.player
                    if (player == null) {
                        context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo puede usarse dentro del juego.")))
                    } else {
                        WonderTradeGui.openSearch(player, StringArgumentType.getString(context, "text"))
                    }
                    1
                })))
            // PERMISO: admin.permissionLevel (configurable; por defecto OP nivel 2).
            .then(literal("reload").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                val problems = WonderTradeService.reload()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("reload", Lang.tr("&aConfiguración y pool recargados.")) }, true)
                if (problems.isNotEmpty()) {
                    context.source.sendFailure(WonderTradeService.info(Lang.t("&eLa revisión de la configuración ha encontrado &f{0} &eaviso(s):", problems.size)))
                    problems.take(10).forEach { context.source.sendFailure(WonderTradeService.info("&7- &f$it")) }
                    if (problems.size > 10) context.source.sendFailure(WonderTradeService.info(Lang.t("&7... y {0} más (mira la consola).", problems.size - 10)))
                }
                1
            })
            .then(adminCommands())
            // PERMISO: admin.permissionLevel (también sirve desde consola). Bloquea la línea evolutiva ganadora del MegaHunt
            // hasta las 00:00. «/wt megahunt <especie>» activa, «/wt megahunt quitar» desactiva, «/wt megahunt» muestra el estado.
            .then(literal("megahunt").requires { it.hasPermission(WonderTradeService.adminLevel()) }
                .executes { context ->
                    val message = if (WonderTradeMegaHunt.isActive())
                        Lang.t("&7MegaHunt: bloqueada la línea de &f{0} &7({1}) hasta el final del día.", WonderTradeMegaHunt.rootName(), WonderTradeMegaHunt.lineNames())
                    else Lang.tr("&7MegaHunt: no hay ningún bloqueo activo.")
                    context.source.sendSuccess({ WonderTradeService.info(message) }, false)
                    1
                }
                .then(literal("quitar").executes { context ->
                    WonderTradeMegaHunt.clear()
                    context.source.sendSuccess({ WonderTradeService.info(Lang.tr("&aMegaHunt desactivado: ya se pueden intercambiar esos Pokémon.")) }, true)
                    1
                })
                .then(argument("especie", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeMegaHunt.speciesNames(), builder) }
                    .executes { context ->
                        val name = StringArgumentType.getString(context, "especie")
                        if (!WonderTradeMegaHunt.set(name)) {
                            context.source.sendFailure(WonderTradeService.info(Lang.t("&cNo existe la especie &f{0}&c.", name)))
                        } else {
                            context.source.sendSuccess({
                                WonderTradeService.info(Lang.t("&aMegaHunt: bloqueados los Pokémon de la línea de &f{0} &a({1}) hasta que termine el día.", WonderTradeMegaHunt.rootName(), WonderTradeMegaHunt.lineNames()))
                            }, true)
                            if (!WonderTradeMegaHunt.enabled()) {
                                context.source.sendFailure(WonderTradeService.info(Lang.tr("&cLa regla del MegaHunt está desactivada en la config (megaHunt.enabled).")))
                            }
                        }
                        1
                    }))
            // PERMISO: admin.permissionLevel (configurable; por defecto OP nivel 2).
            // Alias antiguo de «/wt admin clear».
            .then(literal("clearpool").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { clearPoolCommand(it) })
        dispatcher.register(command)
    }

    private fun openSoundsCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        WonderTradeGui.openSounds(context.source.playerOrException)
        return 1
    }

    private fun openSpecialsCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        WonderTradeGui.openSpecials(context.source.playerOrException)
        return 1
    }

    private fun showEventsCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess({ WonderTradeEvents.eventsComponent(context.source.player) }, false)
        return 1
    }

    private fun showTopDonationsCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess({ WonderTradeTopDonations.component(context.source.player) }, false)
        return 1
    }

    /** /wt admin penalizar <jugador> [duración]: sin duración usa la elegida en el menú o penalties.defaultDuration. */
    private fun penalizeCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, durationText: String?, reasonText: String? = null): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, "jugador")
        val found = WonderTradeService.resolvePlayer(source.server, name)
            ?: runCatching { source.server.profileCache?.get(name)?.orElse(null)?.let { it.id to it.name } }.getOrNull()
        if (found == null) {
            source.sendFailure(WonderTradeService.info(Lang.t("&cNo conozco a &f{0}&c: debe haber entrado al servidor o usado Wonder Trade.", name)))
            return 0
        }
        val duration: Long? = if (durationText != null) WonderTradePenalties.parseDuration(durationText)
        else (source.player?.let { WonderTradePenalties.selection(it.uuid) }
            ?: WonderTradePenalties.parseDuration(ConfigManager.string("penalties.defaultDuration", "1d")))
        if (duration == null) {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cDuración no válida: usa por ejemplo 30m, 2h, 7d o permanente.")))
            return 0
        }
        val reason = reasonText ?: source.player?.let { WonderTradePenalties.reason(it.uuid) } ?: ""
        val penalty = WonderTradePenalties.penalize(found.first, found.second, duration, source.textName, reason)
        source.server.playerList.getPlayer(found.first)?.let {
            WonderTradeService.sendPenalty(it, "penaltyNotice", penalty)
        }
        source.sendSuccess({ WonderTradeService.info(Lang.t("&a{0} &apenalizado: &f{1}&a.", found.second, WonderTradePenalties.remainingText(penalty))) }, true)
        return 1
    }

    private fun ownHistoryCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val player = context.source.player
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo se puede usar dentro del juego.")))
            return 0
        }
        WonderTradeGui.openMyHistory(player)
        return 1
    }

    private fun statusCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess({ WonderTradeService.statusComponent(context.source.server) }, false)
        return 1
    }

    private fun infiniteCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, value: Boolean): Int {
        val player = context.source.player
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo se puede usar dentro del juego.")))
            return 0
        }
        WonderTradeService.setInfinite(player, value)
        context.source.sendSuccess({
            WonderTradeService.info(
                if (value) Lang.tr("&aModo infinito ACTIVADO: sin cooldown de /wt ni de /wt donar, sin límites ni repeticiones. Desactívalo con &f/wt infinito off&a.")
                else Lang.tr("&7Modo infinito desactivado: vuelves a tener cooldown y límites.")
            )
        }, false)
        return 1
    }

    private fun showOwnRank(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        context.source.sendSuccess({ WonderTradeService.rankComponent(player) }, false)
        return 1
    }

    private fun openRanksCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val player = context.source.player
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo puede usarse dentro del juego.")))
            return 0
        }
        WonderTradeGui.openRanks(player)
        return 1
    }

    private fun openLanguageCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val player = context.source.player
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.t("&7Idioma del servidor: &f{0}&7. Disponibles: &f{1}", Lang.current(), Lang.available().joinToString(", ") { it.first })))
            return 0
        }
        WonderTradeGui.openLanguage(player)
        return 1
    }

    private fun setLanguageCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, code: String): Int {
        val player = context.source.player ?: return 0
        WonderTradeGui.chooseLanguage(player, code)
        return 1
    }

    private fun penaltyHistoryCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, name: String?): Int {
        val source = context.source
        val uuid = name?.let { WonderTradeService.resolvePlayer(source.server, it)?.first }
        if (name != null && uuid == null) {
            source.sendFailure(WonderTradeService.info(Lang.t("&cNo conozco a &f{0}&c.", name)))
            return 0
        }
        val records = WonderTradePenalties.history(15, uuid)
        if (records.isEmpty()) source.sendSuccess({ WonderTradeService.info(Lang.tr("&7El historial de penalizaciones está vacío.")) }, false)
        else source.sendSuccess({
            WonderTradeService.colour(WonderTradeService.prefix() + Lang.tr("&d&lHistorial de penalizaciones:\n") +
                records.joinToString("\n") { WonderTradePenalties.describeRecord(it) })
        }, false)
        return 1
    }

    private fun listEventsCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess({ WonderTradeEvents.adminListComponent() }, false)
        return 1
    }

    private fun clearPoolCommand(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        WonderTradeService.clearPool()
        context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", Lang.tr("&aPool vaciado.")) }, true)
        return 1
    }

    // PERMISO: /wt admin y cada subcomando hijo requieren admin.permissionLevel (OP 2 por defecto).
    private fun adminCommands() = literal("admin")
        .requires { it.hasPermission(WonderTradeService.adminLevel()) }
        .executes { context ->
            val player = context.source.player
            if (player == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste panel solo se puede abrir dentro del juego.")))
            else WonderTradeGui.openAdmin(player)
            1
        }
        // Penalizaciones: el jugador no puede intercambiar ni donar durante un tiempo o para siempre.
        .then(literal("penalizar").then(argument("jugador", StringArgumentType.word())
            .suggests { context, builder -> SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder) }
            .executes { context -> penalizeCommand(context, null) }
            .then(argument("duracion", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("30m", "1h", "1d", "7d", "30d", "permanente"), builder) }
                .executes { context -> penalizeCommand(context, StringArgumentType.getString(context, "duracion")) }
                .then(argument("motivo", StringArgumentType.greedyString())
                    .executes { context -> penalizeCommand(context, StringArgumentType.getString(context, "duracion"), StringArgumentType.getString(context, "motivo")) }))))
        // Motivo que se usará en las siguientes penalizaciones de este admin (10 minutos). «ninguno» lo borra.
        .then(literal("motivo").then(argument("texto", StringArgumentType.greedyString()).executes { context ->
            val player = context.source.player
            val text = StringArgumentType.getString(context, "texto")
            if (player == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando solo se puede usar dentro del juego.")))
            else {
                val clear = text.equals("ninguno", ignoreCase = true) || text.equals("none", ignoreCase = true)
                WonderTradePenalties.rememberReason(player.uuid, if (clear) "" else text)
                context.source.sendSuccess({ WonderTradeService.info(if (clear) Lang.tr("&7Motivo borrado.") else Lang.t("&aMotivo guardado: &f{0}", text)) }, false)
                WonderTradeGui.openPenaltiesFor(player)
            }
            1
        }))
        .then(literal("perdonar").then(argument("jugador", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradePenalties.list().map { it.name }, builder) }
            .executes { context ->
                val name = StringArgumentType.getString(context, "jugador")
                val target = WonderTradePenalties.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (target == null) context.source.sendFailure(WonderTradeService.info(Lang.t("&c{0} no tiene ninguna penalización.", name)))
                else {
                    WonderTradePenalties.pardon(target.uuid, context.source.textName)
                    context.source.server.playerList.getPlayer(target.uuid)?.let { WonderTradeService.send(it, "penaltyLifted") }
                    context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aPenalización de &f{0} &aretirada.", target.name)) }, true)
                }
                1
            }))
        .then(literal("penalizaciones").executes { context ->
            val list = WonderTradePenalties.list()
            if (list.isEmpty()) context.source.sendSuccess({ WonderTradeService.info(Lang.tr("&7No hay jugadores penalizados.")) }, false)
            else context.source.sendSuccess({
                WonderTradeService.colour(WonderTradeService.prefix() + Lang.tr("&d&lPenalizados:\n") +
                    list.joinToString("\n") { Lang.t("&f{0} &7— &c{1} &8(por {2})", it.name, WonderTradePenalties.remainingText(it), it.by) + (if (it.reason.isBlank()) "" else " &7· ${it.reason}") })
            }, false)
            1
        }.then(literal("historial").executes { context -> penaltyHistoryCommand(context, null) }
            .then(argument("jugador", StringArgumentType.word())
                .suggests { context, builder -> SharedSuggestionProvider.suggest(context.source.onlinePlayerNames, builder) }
                .executes { context -> penaltyHistoryCommand(context, StringArgumentType.getString(context, "jugador")) })))
        // Diagnóstico del mod para admins.
        .then(literal("estado").executes { context -> statusCommand(context) })
        .then(literal("status").executes { context -> statusCommand(context) })
        // PERMISO: admin.permissionLevel.
        .then(literal("add").then(argument("slot", IntegerArgumentType.integer(1, 6)).executes { context ->
            val player = context.source.player
            if (player == null) {
                context.source.sendFailure(WonderTradeService.info(Lang.tr("&c/wt admin add requiere un jugador dentro del juego porque copia un Pokémon de su equipo.")))
                return@executes 0
            }
            val name = WonderTradeService.adminAddFromParty(player, IntegerArgumentType.getInteger(context, "slot"))
            if (name == null) {
                context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo hay ningún Pokémon en ese slot o no se pudo copiar.")))
            } else {
                context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aSe ha añadido una copia de &f{0} &aal pool.", name)) }, true)
            }
            1
        }))
        // PERMISO: admin.permissionLevel.
        .then(addSpeciesCommand())
        // PERMISO: admin.permissionLevel.
        .then(literal("fill").then(argument("amount", IntegerArgumentType.integer(1, 500))
            .executes { context ->
                fill(context, null, ConfigManager.int("fallback.minimumLevel", 1), ConfigManager.int("fallback.maximumLevel", 100))
            }
            .then(argument("chance", DoubleArgumentType.doubleArg(0.0, 100.0))
                .executes { context ->
                    fill(
                        context,
                        DoubleArgumentType.getDouble(context, "chance"),
                        ConfigManager.int("fallback.minimumLevel", 1),
                        ConfigManager.int("fallback.maximumLevel", 100)
                    )
                }
                .then(argument("minLevel", IntegerArgumentType.integer(1, 100))
                    .then(argument("maxLevel", IntegerArgumentType.integer(1, 100)).executes { context ->
                        fill(
                            context,
                            DoubleArgumentType.getDouble(context, "chance"),
                            IntegerArgumentType.getInteger(context, "minLevel"),
                            IntegerArgumentType.getInteger(context, "maxLevel")
                        )
                    })))))
        // PERMISO: admin.permissionLevel.
        .then(literal("remove").executes { context ->
            val player = context.source.player
            if (player == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cEste comando abre un menú y solo puede usarse dentro del juego.")))
            else WonderTradeGui.openPool(player, 0, true, deleteMode = true)
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(literal("resetstats").then(argument("player", StringArgumentType.word())
            .suggests { context, builder ->
                SharedSuggestionProvider.suggest(
                    listOf("all") + (context.source.onlinePlayerNames + WonderTradeService.knownPlayerNames()).distinct(),
                    builder
                )
            }
            .executes { context ->
                val target = StringArgumentType.getString(context, "player")
                if (target.equals("all", ignoreCase = true)) {
                    val affected = WonderTradeService.adminResetAllStats()
                    context.source.sendSuccess({
                        WonderTradeService.info(Lang.t("&aEstadísticas y rankings reiniciados para &f{0} &ajugadores. Se conservan bonus, cooldowns y límites diarios.", affected))
                    }, true)
                } else {
                    val resetName = WonderTradeService.adminResetStats(context.source.server, target)
                    if (resetName == null) {
                        context.source.sendFailure(WonderTradeService.info(Lang.t("&cNo hay datos guardados de &f{0}&c. El jugador debe conectarse o haber usado Wonder Trade.", target)) )
                    } else {
                        context.source.sendSuccess({
                            WonderTradeService.info(Lang.t("&aEstadísticas y posición en los tops de &f{0} &areiniciadas. Se conserva su bonus y sus límites.", resetName))
                        }, true)
                    }
                }
                1
            }))
        // PERMISO: admin.permissionLevel.
        .then(literal("backup").executes { context ->
            val name = WonderTradeService.backupNow()
            if (name == null) {
                context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo se pudo crear la copia de seguridad. Revisa la consola.")))
            } else {
                context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aCopia creada en &fconfig/wondertradeplus/backups/{0}&a.", name)) }, true)
            }
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(eventAdminCommands())
        // PERMISO: admin.permissionLevel.
        .then(literal("maintenance").executes { context ->
            val (expired, generated) = WonderTradeService.maintainPool(context.source.server)
            context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aMantenimiento hecho: &f{0} &acaducados, &f{1} &agenerados.", expired, generated)) }, true)
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(literal("discord").then(literal("test").executes { context ->
            val source = context.source
            WonderTradeDiscord.test { ok, text ->
                source.server.execute {
                    if (ok) source.sendSuccess({ WonderTradeService.info(Lang.t("&a{0}", text)) }, false)
                    else source.sendFailure(WonderTradeService.info(Lang.t("&c{0}", text)))
                }
            }
            source.sendSuccess({ WonderTradeService.info(Lang.tr("&7Enviando mensaje de prueba a Discord…")) }, false)
            1
        }))
        // PERMISO: admin.permissionLevel.
        .then(literal("history")
            .executes { context ->
                val player = context.source.player
                if (player == null) {
                    val entries = WonderTradeService.historyEntries().take(15)
                    context.source.sendSuccess({ WonderTradeService.info(Lang.t("&eÚltimos {0} intercambios del historial:", entries.size)) }, false)
                    entries.forEach { entry ->
                        context.source.sendSuccess({ WonderTradeService.info(Lang.t("&7{0} &f{1}&7: {2} → {3}", java.time.Instant.ofEpochMilli(entry.time), entry.player, entry.sentName, entry.receivedName)) }, false)
                    }
                } else WonderTradeGui.openHistory(player)
                1
            }
            .then(argument("player", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.historyPlayers(), builder) }
                .executes { context ->
                    val player = context.source.player
                    val filter = StringArgumentType.getString(context, "player")
                    if (player == null) {
                        val entries = WonderTradeService.historyEntries(filter).take(15)
                        context.source.sendSuccess({ WonderTradeService.info(Lang.t("&eÚltimos {0} intercambios de &f{1}&e:", entries.size, filter)) }, false)
                        entries.forEach { entry ->
                            context.source.sendSuccess({ WonderTradeService.info(Lang.t("&7{0} &f{1}&7: {2} → {3}", java.time.Instant.ofEpochMilli(entry.time), entry.player, entry.sentName, entry.receivedName)) }, false)
                        }
                    } else WonderTradeGui.openHistory(player, filter)
                    1
                }))
        // PERMISO: admin.permissionLevel.
        .then(literal("removeplayer").then(argument("player", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.poolDonors(), builder) }
            .executes { context ->
            val donor = StringArgumentType.getString(context, "player")
            val removed = WonderTradeService.removeDonorEntries(donor)
            context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aSe han eliminado &f{0} &aPokémon aportados por &f{1}&a.", removed, donor)) }, true)
            1
        }))
        // PERMISO: admin.permissionLevel. Estos dos comandos también funcionan desde consola.
        .then(literal("removegenerated").executes { context ->
            val removed = WonderTradeService.removeGeneratedPoolEntries()
            context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aSe han eliminado &f{0} &aPokémon generados por el servidor. Las aportaciones de jugadores se conservan.", removed)) }, true)
            1
        })
        .then(literal("removeplayers").executes { context ->
            val removed = WonderTradeService.removePlayerPoolEntries()
            context.source.sendSuccess({ WonderTradeService.info(Lang.t("&aSe han eliminado &f{0} &aPokémon aportados por jugadores. Los generados por el servidor se conservan.", removed)) }, true)
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(literal("clear").executes { clearPoolCommand(it) })

    // PERMISO: admin.permissionLevel, heredado de /wt admin.
    private fun eventAdminCommands() = literal("eventos")
        .executes { listEventsCommand(it) }
        // «lista» y «programados» hacen lo mismo que «/wt admin eventos»; se conservan como alias.
        .then(literal("lista").executes { listEventsCommand(it) })
        // /wt admin eventos crear <tipo> <categorías> <duración> [valor] [espera]
        .then(literal("crear").then(argument("tipo", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(EventType.values().map { it.key }, builder) }
            .then(argument("categoria", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(EventCategory.values().map { it.key } + listOf("mitico+legendario", "ultraente+paradoja", "shiny+legendario"), builder) }
            .then(argument("duracion", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("5m", "30m", "1h", "3h", "1d"), builder) }
                .executes { context -> createEvent(context, null, null) }
                .then(argument("valor", DoubleArgumentType.doubleArg(0.0, 100.0))
                    .executes { context -> createEvent(context, DoubleArgumentType.getDouble(context, "valor"), null) }
                    .then(argument("espera", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("30s", "1m", "5m"), builder) }
                        .executes { context ->
                            createEvent(context, DoubleArgumentType.getDouble(context, "valor"), StringArgumentType.getString(context, "espera"))
                        }))))))
        // /wt admin eventos programar <tipo> <categorías> <duración> <cuándo...>
        // Ejemplos de «cuándo»: 20:00 | sabado 20:00 semanal | 15/11 18:30 | 20:00 diario valor=25 espera=2m
        .then(literal("programar").then(argument("tipo", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(EventType.values().map { it.key }, builder) }
            .then(argument("categoria", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(EventCategory.values().map { it.key } + listOf("mitico+legendario", "ultraente+paradoja", "shiny+legendario"), builder) }
                .then(argument("duracion", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("30m", "1h", "3h", "1d"), builder) }
                    .then(argument("cuando", StringArgumentType.greedyString())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("20:00", "sabado 20:00 semanal", "20:00 diario", "20:00 mensual", "20:00 anual", "15/11 18:30"), builder) }
                        .executes { context -> scheduleEvent(context) })))))
        .then(literal("programados").executes { listEventsCommand(it) })
        .then(literal("desprogramar").then(argument("id", IntegerArgumentType.integer(1)).executes { context ->
            val item = WonderTradeSchedule.remove(IntegerArgumentType.getInteger(context, "id"))
            if (item == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo existe ningún evento programado con ese id.")))
            else context.source.sendSuccess({
                val (color, name) = eventChat(item.type, item.categories)
                WonderTradeService.info(Lang.t("&aEvento programado {0}{1}&a cancelado.", color, name))
            }, true)
            1
        }))
        // Pausa o reanuda el tiempo de un evento activo (el tiempo restante y las esperas se conservan).
        .then(literal("pausar").then(argument("id", IntegerArgumentType.integer(1)).executes { context ->
            val event = WonderTradeEvents.pause(context.source.server, IntegerArgumentType.getInteger(context, "id"))
            if (event == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo existe un evento en marcha con ese id (o ya está pausado).")))
            else context.source.sendSuccess({
                val (color, name) = eventChat(event.type, event.categories)
                WonderTradeService.info(Lang.t("&aEvento {0}{1}&a pausado. Usa &f/wt admin eventos reanudar {2}&a.", color, name, event.id))
            }, true)
            1
        }))
        .then(literal("reanudar").then(argument("id", IntegerArgumentType.integer(1)).executes { context ->
            val event = WonderTradeEvents.resume(context.source.server, IntegerArgumentType.getInteger(context, "id"))
            if (event == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo existe ningún evento pausado con ese id.")))
            else context.source.sendSuccess({
                val (color, name) = eventChat(event.type, event.categories)
                WonderTradeService.info(Lang.t("&aEvento {0}{1}&a reanudado.", color, name))
            }, true)
            1
        }))
        // Historial de eventos terminados (solo administradores, heredado de /wt admin).
        .then(literal("historial").executes { context ->
            context.source.sendSuccess({ WonderTradeEvents.historyComponent() }, false)
            1
        })
        .then(literal("parar").then(argument("id", IntegerArgumentType.integer(1)).executes { context ->
            val event = WonderTradeEvents.stop(context.source.server, IntegerArgumentType.getInteger(context, "id"))
            if (event == null) context.source.sendFailure(WonderTradeService.info(Lang.tr("&cNo existe ningún evento activo con ese id.")))
            else context.source.sendSuccess({
                val (color, name) = eventChat(event.type, event.categories)
                WonderTradeService.info(Lang.t("&aEvento {0}{1}&a finalizado.", color, name))
            }, true)
            1
        }))

    private fun scheduleEvent(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val type = EventType.parse(StringArgumentType.getString(context, "tipo")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cTipo no válido. Usa: &farriesgar &co &frecibir&c.")))
            return 0
        }
        val categories = parseCategories(StringArgumentType.getString(context, "categoria")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cCategoría no válida. Usa: &fshiny&c, &fultraente&c, &fparadoja&c, &fmitico &co &flegendario&c. Para varias a la vez únelas con +, por ejemplo &fmitico+legendario&c.")))
            return 0
        }
        val duration = WonderTradeEvents.parseDuration(StringArgumentType.getString(context, "duracion")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cDuración no válida. Ejemplos: &f30s, 5m, 2h, 1d, 1h30m&c.")))
            return 0
        }
        if (duration > WonderTradeEvents.maximumDurationSeconds()) {
            source.sendFailure(WonderTradeService.info(Lang.t("&cLa duración máxima es &f{0}&c (events.maximumDurationHours).", WonderTradeEvents.formatDuration(WonderTradeEvents.maximumDurationSeconds()))))
            return 0
        }
        val parsed = WonderTradeSchedule.parse(StringArgumentType.getString(context, "cuando"))
        val spec = parsed.spec ?: run {
            source.sendFailure(WonderTradeService.info(Lang.t("&c{0}", parsed.error)))
            return 0
        }
        val item = WonderTradeSchedule.add(type, categories, duration, spec, source.textName)
        source.sendSuccess({
            val (color, name) = eventChat(item.type, item.categories)
            WonderTradeService.info(Lang.t("&aEvento {0}{1}&a programado (&f#P{2}&a): &f{3}&a.", color, name, item.id, WonderTradeSchedule.describeWhen(item)))
        }, true)
        return 1
    }

    private fun createEvent(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, value: Double?, cooldownText: String?): Int {
        val source = context.source
        val type = EventType.parse(StringArgumentType.getString(context, "tipo")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cTipo no válido. Usa: &farriesgar &co &frecibir&c.")))
            return 0
        }
        val categories = parseCategories(StringArgumentType.getString(context, "categoria")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cCategoría no válida. Usa: &fshiny&c, &fultraente&c, &fparadoja&c, &fmitico &co &flegendario&c. Para varias a la vez únelas con +, por ejemplo &fmitico+legendario&c.")))
            return 0
        }
        val duration = WonderTradeEvents.parseDuration(StringArgumentType.getString(context, "duracion")) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cDuración no válida. Ejemplos: &f30s, 5m, 2h, 1d, 1h30m&c.")))
            return 0
        }
        if (duration > WonderTradeEvents.maximumDurationSeconds()) {
            source.sendFailure(WonderTradeService.info(Lang.t("&cLa duración máxima es &f{0}&c (events.maximumDurationHours).", WonderTradeEvents.formatDuration(WonderTradeEvents.maximumDurationSeconds()))))
            return 0
        }
        val cooldown = if (cooldownText == null) null else (WonderTradeEvents.parseDuration(cooldownText) ?: run {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cEspera no válida. Ejemplos: &f30s, 1m, 5m&c.")))
            return 0
        })
        val event = WonderTradeEvents.create(source.server, type, categories, duration, value, cooldown, source.textName)
        if (event == null) {
            source.sendFailure(WonderTradeService.info(Lang.tr("&cLos eventos están desactivados (events.enabled).")))
            return 0
        }
        source.sendSuccess({
            val (color, name) = eventChat(event.type, event.categories)
            WonderTradeService.info(Lang.t("&aEvento {0}{1}&a creado con id &f#{2}&a.", color, name, event.id))
        }, true)
        return 1
    }

    private fun showTop(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, category: String): Int {
        val viewer = context.source.player?.uuid
        context.source.sendSuccess({ WonderTradeService.topComponent(category, viewer) }, false)
        return 1
    }


    /**
     * Registra las opciones regionales con la misma sintaxis de atributos que /pokegive:
     * /wt admin addspecies vulpix alolan [shiny]
     * También se acepta el orden /wt admin addspecies vulpix shiny alolan.
     * PERMISO: admin.permissionLevel (OP 2 por defecto), heredado de /wt admin.
     */
    private fun addSpeciesCommand() = literal("addspecies").then(
        argument("species", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.speciesNames(), builder) }
            .executes { context -> addSpecies(context, shiny = false, regionalForm = null) }
            .then(argument("options", StringArgumentType.greedyString()).executes { context ->
                val tokens = StringArgumentType.getString(context, "options")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                val regionalOptions = setOf("alolan", "galarian", "hisuian", "paldean")
                var shiny = false
                var regionalForm: String? = null
                for (token in tokens) {
                    when (token.lowercase()) {
                        "shiny", "shiny=true" -> shiny = true
                        in regionalOptions -> {
                            if (regionalForm != null && regionalForm != token.lowercase()) {
                                context.source.sendFailure(WonderTradeService.info(Lang.tr("&cSolo puedes indicar una forma regional por Pokémon.")))
                                return@executes 1
                            }
                            regionalForm = token.lowercase()
                        }
                        else -> {
                            context.source.sendFailure(WonderTradeService.info(
                                Lang.t("&cOpción desconocida: &f{0}&c. Usa &fshiny&c, &falolan&c, &fgalarian&c, &fhisuian&c o &fpaldean&c.", token)
                            ))
                            return@executes 1
                        }
                    }
                }
                addSpecies(context, shiny = shiny, regionalForm = regionalForm)
            })
    )

    private fun addSpecies(
        context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
        shiny: Boolean,
        regionalForm: String?
    ): Int {
        val player = context.source.player ?: context.source.server.playerList.players.firstOrNull()
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.tr("&cPara generar Pokémon desde consola debe haber al menos un jugador conectado.")))
            return 0
        }
        val species = StringArgumentType.getString(context, "species")
        val result = try {
            WonderTradeService.adminAddSpecies(player, species, shiny, regionalForm)
        } catch (exception: IllegalArgumentException) {
            context.source.sendFailure(WonderTradeService.info(Lang.t("&c{0}", exception.message)))
            return 1
        }
        if (result == null) {
            context.source.sendFailure(WonderTradeService.info(
                Lang.t("&cNo se pudo crear &f{0}&c. Usa el nombre base en inglés y, opcionalmente, &falolan&c, &fgalarian&c, &fhisuian&c, &fpaldean&c o &fshiny&c. Ejemplo: &fvulpix alolan shiny&c.", species)
            ))
        } else {
            val options = buildList {
                regionalForm?.let(::add)
                if (shiny) add("shiny")
            }.joinToString(", ").ifEmpty { "normal" }
            context.source.sendSuccess({
                WonderTradeService.info(Lang.t("&aSe ha añadido &f{0} &7(nivel aleatorio: {1}; {2}) &aal pool.", result.name, result.level, options))
            }, true)
        }
        return 1
    }

    private fun fill(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, chance: Double?, minLevel: Int, maxLevel: Int): Int {
        val player = context.source.player ?: context.source.server.playerList.players.firstOrNull()
        if (player == null) {
            context.source.sendFailure(WonderTradeService.info(Lang.tr("&cPara rellenar la pool desde consola debe haber al menos un jugador conectado.")))
            return 0
        }
        val amount = IntegerArgumentType.getInteger(context, "amount")
        val result = WonderTradeService.adminFill(player, amount, chance, minOf(minLevel, maxLevel), maxOf(minLevel, maxLevel))
        val chanceText = String.format("%.2f", result.chancePercent)
        val mode = if (chance == null) "automática" else "indicada"
        context.source.sendSuccess({
            WonderTradeService.info(
                Lang.t("&aSe han generado &f{0} &ade &f{1} &aPokémon. Especiales: &d{2} &7(probabilidad {3}: {4}% por Pokémon)&a.", result.added, amount, result.special, mode, chanceText)
            )
        }, true)
        return 1
    }

    companion object {
        const val MOD_VERSION = "0.30.0"
        val LOGGER = LoggerFactory.getLogger("WonderTradePlus")
    }
}
