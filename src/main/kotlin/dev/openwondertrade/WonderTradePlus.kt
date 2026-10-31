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
        ServerLifecycleEvents.SERVER_STARTED.register { WonderTradeService.initialize() }
        ServerTickEvents.END_SERVER_TICK.register { server -> WonderTradeService.tick(server) }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher, "wt")
            registerCommands(dispatcher, "wondertrade")
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>, root: String) {
        // PERMISO: cualquier jugador puede ejecutar el comando raíz para abrir la GUI o ver ayuda.
        val command = literal(root)
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
                context.source.sendSuccess({ WonderTradeService.info("&aWonder Trade activado.") }, true)
                1
            })
            .then(literal("off").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                WonderTradeService.setEnabled(false)
                context.source.sendSuccess({ WonderTradeService.info("&cWonder Trade desactivado. Los jugadores no podrán intercambiar ni donar hasta usar &f/wt on&c.") }, true)
                1
            })
            // PERMISO: público; muestra la ayuda.
            .then(literal("help").executes { context ->
                context.source.sendSuccess({ WonderTradeService.helpComponent(context.source.player) }, false)
                1
            })
            // Preferencias individuales de sonido; no requiere permisos ni instalación en el cliente.
            .then(literal("sonidos").executes { context ->
                WonderTradeGui.openSounds(context.source.playerOrException)
                1
            })
            .then(literal("sounds").executes { context ->
                WonderTradeGui.openSounds(context.source.playerOrException)
                1
            })
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
            // PERMISO: /wt chance es público; consultar/modificar el bonus de otro jugador requiere admin.permissionLevel.
            .then(literal("chance")
                .executes { context ->
                    WonderTradeService.showBonus(context.source.playerOrException)
                    1
                }
                .then(argument("player", StringArgumentType.word())
                    .requires { it.hasPermission(WonderTradeService.adminLevel()) }
                    .suggests { context, builder ->
                        SharedSuggestionProvider.suggest(
                            (context.source.onlinePlayerNames + WonderTradeService.knownPlayerNames()).distinct(),
                            builder
                        )
                    }
                    .executes { context ->
                        val name = StringArgumentType.getString(context, "player")
                        val result = WonderTradeService.lookupBonus(context.source.server, name)
                        if (result == null) {
                            context.source.sendFailure(WonderTradeService.info("&cNo hay datos de &f$name&c: no está conectado ni ha usado Wonder Trade todavía."))
                        } else {
                            val (shownName, value) = result
                            context.source.sendSuccess({
                                WonderTradeService.info(
                                    "&7Probabilidad acumulada de especial de &f$shownName&7: &d${WonderTradeService.bonusPercentText(value)}% &8(máx. ${WonderTradeService.bonusMaximum()}%)"
                                )
                            }, false)
                        }
                        1
                    }
                    // PERMISO: admin.permissionLevel, heredado del argumento "player".
                    .then(literal("set")
                        .then(argument("value", DoubleArgumentType.doubleArg(0.0, 100.0)).executes { context ->
                            setChance(context, DoubleArgumentType.getDouble(context, "value"))
                        }))
                    // PERMISO: admin.permissionLevel, heredado del argumento "player".
                    .then(literal("reset").executes { context -> setChance(context, 0.0) })))
            // PERMISO: público; estadísticas propias y públicas visibles sin OP.
            .then(literal("stats")
                .executes { context ->
                    val player = context.source.playerOrException
                    context.source.sendSuccess({ WonderTradeService.statsComponent(player.uuid, player.gameProfile.name) }, false)
                    1
                }
                .then(argument("player", StringArgumentType.word())
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
                            context.source.sendFailure(WonderTradeService.info("&cNo hay datos de &f$name&c."))
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
            // PERMISO: público; muestra los eventos activos.
            .then(literal("eventos").executes { context ->
                context.source.sendSuccess({ WonderTradeEvents.eventsComponent(context.source.player) }, false)
                1
            })
            .then(literal("events").executes { context ->
                context.source.sendSuccess({ WonderTradeEvents.eventsComponent(context.source.player) }, false)
                1
            })
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
                        context.source.sendFailure(WonderTradeService.info("&cEste comando solo puede usarse dentro del juego."))
                    } else {
                        // Vista normal para todos; la gestión de la pool está en /wt admin.
                        WonderTradeGui.openPool(player, 0, false)
                    }
                    1
                }
                .then(literal("search").then(argument("text", StringArgumentType.greedyString()).executes { context ->
                    val player = context.source.player
                    if (player == null) {
                        context.source.sendFailure(WonderTradeService.info("&cEste comando solo puede usarse dentro del juego."))
                    } else {
                        WonderTradeGui.openSearch(player, StringArgumentType.getString(context, "text"))
                    }
                    1
                })))
            // PERMISO: admin.permissionLevel (configurable; por defecto OP nivel 2).
            .then(literal("reload").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                WonderTradeService.reload()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("reload", "&aConfiguración y pool recargados.") }, true)
                1
            })
            .then(adminCommands())
            // PERMISO: admin.permissionLevel (configurable; por defecto OP nivel 2).
            .then(literal("clearpool").requires { it.hasPermission(WonderTradeService.adminLevel()) }.executes { context ->
                WonderTradeService.clearPool()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", "&aPool vaciado.") }, true)
                1
            })
        dispatcher.register(command)
    }

    // PERMISO: /wt admin y cada subcomando hijo requieren admin.permissionLevel (OP 2 por defecto).
    private fun adminCommands() = literal("admin")
        .requires { it.hasPermission(WonderTradeService.adminLevel()) }
        .executes { context ->
            val player = context.source.player
            if (player == null) context.source.sendFailure(WonderTradeService.info("&cEste panel solo se puede abrir dentro del juego."))
            else WonderTradeGui.openAdmin(player)
            1
        }
        // PERMISO: admin.permissionLevel.
        .then(literal("add").then(argument("slot", IntegerArgumentType.integer(1, 6)).executes { context ->
            val player = context.source.playerOrException
            val name = WonderTradeService.adminAddFromParty(player, IntegerArgumentType.getInteger(context, "slot"))
            if (name == null) {
                context.source.sendFailure(WonderTradeService.info("&cNo hay ningún Pokémon en ese slot o no se pudo copiar."))
            } else {
                context.source.sendSuccess({ WonderTradeService.info("&aSe ha añadido una copia de &f$name &aal pool.") }, true)
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
            WonderTradeGui.openPool(context.source.playerOrException, 0, true, deleteMode = true)
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
                        WonderTradeService.info("&aEstadísticas y rankings reiniciados para &f$affected &ajugadores. Se conservan bonus, cooldowns y límites diarios.")
                    }, true)
                } else {
                    val resetName = WonderTradeService.adminResetStats(context.source.server, target)
                    if (resetName == null) {
                        context.source.sendFailure(WonderTradeService.info("&cNo hay datos guardados de &f$target&c. El jugador debe conectarse o haber usado Wonder Trade.") )
                    } else {
                        context.source.sendSuccess({
                            WonderTradeService.info("&aEstadísticas y posición en los tops de &f$resetName &areiniciadas. Se conserva su bonus y sus límites.")
                        }, true)
                    }
                }
                1
            }))
        // PERMISO: admin.permissionLevel.
        .then(literal("backup").executes { context ->
            val name = WonderTradeService.backupNow()
            if (name == null) {
                context.source.sendFailure(WonderTradeService.info("&cNo se pudo crear la copia de seguridad. Revisa la consola."))
            } else {
                context.source.sendSuccess({ WonderTradeService.info("&aCopia creada en &fconfig/wondertradeplus/backups/$name&a.") }, true)
            }
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(eventAdminCommands())
        // PERMISO: admin.permissionLevel.
        .then(literal("maintenance").executes { context ->
            val (expired, generated) = WonderTradeService.maintainPool(context.source.server)
            context.source.sendSuccess({ WonderTradeService.info("&aMantenimiento hecho: &f$expired &acaducados, &f$generated &agenerados.") }, true)
            1
        })
        // PERMISO: admin.permissionLevel.
        .then(literal("discord").then(literal("test").executes { context ->
            val source = context.source
            WonderTradeService.discordTest { ok, text ->
                source.server.execute {
                    if (ok) source.sendSuccess({ WonderTradeService.info("&a$text") }, false)
                    else source.sendFailure(WonderTradeService.info("&c$text"))
                }
            }
            source.sendSuccess({ WonderTradeService.info("&7Enviando mensaje de prueba a Discord…") }, false)
            1
        }))
        // PERMISO: admin.permissionLevel.
        .then(literal("history")
            .executes { context ->
                WonderTradeGui.openHistory(context.source.playerOrException)
                1
            }
            .then(argument("player", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.historyPlayers(), builder) }
                .executes { context ->
                    WonderTradeGui.openHistory(context.source.playerOrException, StringArgumentType.getString(context, "player"))
                    1
                }))
        // PERMISO: admin.permissionLevel.
        .then(literal("removeplayer").then(argument("player", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.poolDonors(), builder) }
            .executes { context ->
            val donor = StringArgumentType.getString(context, "player")
            val removed = WonderTradeService.removeDonorEntries(donor)
            context.source.sendSuccess({ WonderTradeService.info("&aSe han eliminado &f$removed &aPokémon aportados por &f$donor&a.") }, true)
            1
        }))
        // PERMISO: admin.permissionLevel.
        .then(literal("clear").executes { context ->
            WonderTradeService.clearPool()
            context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", "&aPool vaciado.") }, true)
            1
        })

    // PERMISO: admin.permissionLevel, heredado de /wt admin.
    private fun eventAdminCommands() = literal("eventos")
        .executes { context ->
            context.source.sendSuccess({ WonderTradeEvents.adminListComponent() }, false)
            1
        }
        .then(literal("lista").executes { context ->
            context.source.sendSuccess({ WonderTradeEvents.adminListComponent() }, false)
            1
        })
        // /wt admin eventos crear <tipo> <categoría> <duración> [valor] [espera]
        .then(literal("crear").then(argument("tipo", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(EventType.values().map { it.key }, builder) }
            .then(argument("categoria", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(EventCategory.values().map { it.key }, builder) }
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
        .then(literal("parar").then(argument("id", IntegerArgumentType.integer(1)).executes { context ->
            val event = WonderTradeEvents.stop(context.source.server, IntegerArgumentType.getInteger(context, "id"))
            if (event == null) context.source.sendFailure(WonderTradeService.info("&cNo existe ningún evento activo con ese id."))
            else context.source.sendSuccess({ WonderTradeService.info("&aEvento &f${event.title} &afinalizado.") }, true)
            1
        }))

    private fun createEvent(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, value: Double?, cooldownText: String?): Int {
        val source = context.source
        val type = EventType.parse(StringArgumentType.getString(context, "tipo")) ?: run {
            source.sendFailure(WonderTradeService.info("&cTipo no válido. Usa: &farriesgar &co &frecibir&c."))
            return 0
        }
        val category = EventCategory.parse(StringArgumentType.getString(context, "categoria")) ?: run {
            source.sendFailure(WonderTradeService.info("&cCategoría no válida. Usa: &fshiny&c, &flegendario&c, &fmitico&c, &fultraente &co &fparadoja&c."))
            return 0
        }
        val duration = WonderTradeEvents.parseDuration(StringArgumentType.getString(context, "duracion")) ?: run {
            source.sendFailure(WonderTradeService.info("&cDuración no válida. Ejemplos: &f30s, 5m, 2h, 1d, 1h30m&c."))
            return 0
        }
        if (duration > WonderTradeEvents.maximumDurationSeconds()) {
            source.sendFailure(WonderTradeService.info("&cLa duración máxima es &f${WonderTradeEvents.formatDuration(WonderTradeEvents.maximumDurationSeconds())}&c (events.maximumDurationHours)."))
            return 0
        }
        val cooldown = if (cooldownText == null) null else (WonderTradeEvents.parseDuration(cooldownText) ?: run {
            source.sendFailure(WonderTradeService.info("&cEspera no válida. Ejemplos: &f30s, 1m, 5m&c."))
            return 0
        })
        val event = WonderTradeEvents.create(source.server, type, category, duration, value, cooldown, source.textName)
        if (event == null) {
            source.sendFailure(WonderTradeService.info("&cLos eventos están desactivados (events.enabled)."))
            return 0
        }
        source.sendSuccess({ WonderTradeService.info("&aEvento &f${event.title} &acreado con id &f#${event.id}&a.") }, true)
        return 1
    }

    private fun setChance(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, value: Double): Int {
        val name = StringArgumentType.getString(context, "player")
        val result = WonderTradeService.adminSetBonus(context.source.server, name, value)
        if (result == null) {
            context.source.sendFailure(WonderTradeService.info("&cNo hay datos de &f$name&c: no está conectado ni ha usado Wonder Trade todavía."))
        } else {
            context.source.sendSuccess({
                WonderTradeService.info("&aLa probabilidad acumulada de &f${result.first} &aahora es &d${WonderTradeService.bonusPercentText(result.second)}%&a.")
            }, true)
        }
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
                                context.source.sendFailure(WonderTradeService.info("&cSolo puedes indicar una forma regional por Pokémon."))
                                return@executes 1
                            }
                            regionalForm = token.lowercase()
                        }
                        else -> {
                            context.source.sendFailure(WonderTradeService.info(
                                "&cOpción desconocida: &f$token&c. Usa &fshiny&c, &falolan&c, &fgalarian&c, &fhisuian&c o &fpaldean&c."
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
        val player = context.source.playerOrException
        val species = StringArgumentType.getString(context, "species")
        val result = try {
            WonderTradeService.adminAddSpecies(player, species, shiny, regionalForm)
        } catch (exception: IllegalArgumentException) {
            context.source.sendFailure(WonderTradeService.info("&c${exception.message}"))
            return 1
        }
        if (result == null) {
            context.source.sendFailure(WonderTradeService.info(
                "&cNo se pudo crear &f$species&c. Usa el nombre base en inglés y, opcionalmente, &falolan&c, &fgalarian&c, &fhisuian&c, &fpaldean&c o &fshiny&c. Ejemplo: &fvulpix alolan shiny&c."
            ))
        } else {
            val options = buildList {
                regionalForm?.let(::add)
                if (shiny) add("shiny")
            }.joinToString(", ").ifEmpty { "normal" }
            context.source.sendSuccess({
                WonderTradeService.info("&aSe ha añadido &f${result.name} &7(nivel aleatorio: ${result.level}; $options) &aal pool.")
            }, true)
        }
        return 1
    }

    private fun fill(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, chance: Double?, minLevel: Int, maxLevel: Int): Int {
        val player = context.source.playerOrException
        val amount = IntegerArgumentType.getInteger(context, "amount")
        val result = WonderTradeService.adminFill(player, amount, chance, minOf(minLevel, maxLevel), maxOf(minLevel, maxLevel))
        val chanceText = String.format("%.2f", result.chancePercent)
        val mode = if (chance == null) "automática" else "indicada"
        context.source.sendSuccess({
            WonderTradeService.info(
                "&aSe han generado &f${result.added} &ade &f$amount &aPokémon. Especiales: &d${result.special} &7(probabilidad $mode: $chanceText% por Pokémon)&a."
            )
        }, true)
        return 1
    }

    companion object {
        const val MOD_VERSION = "0.11.1"
        val LOGGER = LoggerFactory.getLogger("WonderTradePlus")
    }
}
