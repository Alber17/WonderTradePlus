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
        // Delay disk/config initialization until a logical server exists. This keeps the
        // mod harmless in a client-only session while still supporting integrated servers.
        ServerLifecycleEvents.SERVER_STARTED.register { WonderTradeService.initialize() }
        ServerTickEvents.END_SERVER_TICK.register { server -> WonderTradeService.tick(server) }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher, "wt")
            registerCommands(dispatcher, "wondertrade")
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>, root: String) {
        val command = literal(root)
            .executes { context ->
                val player = context.source.player
                if (player != null && ConfigManager.bool("gui.enabled", true)) {
                    WonderTradeGui.openMain(player)
                } else {
                    context.source.sendSuccess({ WonderTradeService.helpComponent() }, false)
                }
                1
            }
            .then(literal("help").executes { context ->
                context.source.sendSuccess({ WonderTradeService.helpComponent() }, false)
                1
            })
            .then(literal("gui").executes { context ->
                WonderTradeGui.openMain(context.source.playerOrException)
                1
            })
            .then(argument("slot", IntegerArgumentType.integer(1, 6))
                .executes { context ->
                    val player = context.source.playerOrException
                    WonderTradeService.request(player, IntegerArgumentType.getInteger(context, "slot"))
                    1
                }
                .then(literal("confirm").executes { context ->
                    val player = context.source.playerOrException
                    WonderTradeService.tradeNow(player, IntegerArgumentType.getInteger(context, "slot"))
                    1
                })
            )
            .then(literal("confirm").executes { context ->
                WonderTradeService.confirm(context.source.playerOrException)
                1
            })
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
                    .then(literal("set")
                        .then(argument("value", DoubleArgumentType.doubleArg(0.0, 100.0)).executes { context ->
                            setChance(context, DoubleArgumentType.getDouble(context, "value"))
                        }))
                    .then(literal("reset").executes { context -> setChance(context, 0.0) })))
            .then(literal("event").executes { context ->
                context.source.sendSuccess({ WonderTradeService.eventStatusComponent() }, false)
                1
            })
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
            .then(literal("top")
                .executes { context -> showTop(context, "trades") }
                .then(literal("trades").executes { context -> showTop(context, "trades") })
                .then(literal("received").executes { context -> showTop(context, "received") })
                .then(literal("donated").executes { context -> showTop(context, "donated") }))
            .then(literal("cancel").executes { context ->
                WonderTradeService.cancel(context.source.playerOrException)
                1
            })
            .then(literal("pool").executes { context ->
                val player = context.source.player
                if (player == null) {
                    context.source.sendFailure(WonderTradeService.info("&cEste comando solo puede usarse dentro del juego."))
                } else {
                    WonderTradeGui.openPool(player, 0)
                }
                1
            })
            .then(literal("reload").requires { it.hasPermission(2) }.executes { context ->
                WonderTradeService.reload()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("reload", "&aConfiguración y pool recargados.") }, true)
                1
            })
            .then(adminCommands())
            .then(literal("clearpool").requires { it.hasPermission(2) }.executes { context ->
                WonderTradeService.clearPool()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", "&aPool vaciado.") }, true)
                1
            })
        dispatcher.register(command)
    }

    private fun adminCommands() = literal("admin")
        .requires { it.hasPermission(WonderTradeService.adminLevel()) }
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
        .then(literal("addspecies").then(argument("species", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.speciesNames(), builder) }
            .executes { context -> addSpecies(context, false) }
            .then(literal("shiny").executes { context -> addSpecies(context, true) })))
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
        .then(literal("remove").executes { context ->
            WonderTradeGui.openPool(context.source.playerOrException, 0, true)
            1
        })
        .then(literal("event")
            .then(literal("start")
                .executes { context -> startEvent(context, WonderTradeService.defaultEventMinutes()) }
                .then(argument("minutes", IntegerArgumentType.integer(1, 10080)).executes { context ->
                    startEvent(context, IntegerArgumentType.getInteger(context, "minutes"))
                }))
            .then(literal("stop").executes { context ->
                if (WonderTradeService.stopEvent(context.source.server)) {
                    context.source.sendSuccess({ WonderTradeService.info("&aEvento terminado.") }, true)
                } else {
                    context.source.sendFailure(WonderTradeService.info("&cNo hay ningún evento activo."))
                }
                1
            }))
        .then(literal("backup").executes { context ->
            val name = WonderTradeService.backupNow()
            if (name == null) {
                context.source.sendFailure(WonderTradeService.info("&cNo se pudo crear la copia de seguridad. Revisa la consola."))
            } else {
                context.source.sendSuccess({ WonderTradeService.info("&aCopia creada en &fconfig/wondertradeplus/backups/$name&a.") }, true)
            }
            1
        })
        .then(literal("maintenance").executes { context ->
            val (expired, generated) = WonderTradeService.maintainPool(context.source.server)
            context.source.sendSuccess({ WonderTradeService.info("&aMantenimiento hecho: &f$expired &acaducados, &f$generated &agenerados.") }, true)
            1
        })
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
        .then(literal("removeplayer").then(argument("player", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(WonderTradeService.poolDonors(), builder) }
            .executes { context ->
            val donor = StringArgumentType.getString(context, "player")
            val removed = WonderTradeService.removeDonorEntries(donor)
            context.source.sendSuccess({ WonderTradeService.info("&aSe han eliminado &f$removed &aPokémon aportados por &f$donor&a.") }, true)
            1
        }))
        .then(literal("clear").executes { context ->
            WonderTradeService.clearPool()
            context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", "&aPool vaciado.") }, true)
            1
        })

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

    private fun startEvent(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, minutes: Int): Int {
        WonderTradeService.startEvent(context.source.server, minutes)
        context.source.sendSuccess({ WonderTradeService.info("&aEvento iniciado durante &f$minutes &aminutos.") }, true)
        return 1
    }

    private fun addSpecies(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, shiny: Boolean): Int {
        val player = context.source.playerOrException
        val species = StringArgumentType.getString(context, "species")
        val result = try {
            WonderTradeService.adminAddSpecies(player, species, shiny)
        } catch (exception: IllegalArgumentException) {
            context.source.sendFailure(WonderTradeService.info("&c${exception.message} Prueba con Tab para ver las formas disponibles."))
            return 1
        }
        if (result == null) {
            context.source.sendFailure(WonderTradeService.info("&cNo se pudo crear &f$species&c. Usa el nombre de la especie en inglés y sin espacios, por ejemplo &fpikachu&c, &fgreattusk &co una forma como &fvulpix-alola&c."))
        } else {
            context.source.sendSuccess({ WonderTradeService.info("&aSe ha añadido &f${result.name} &7(nivel aleatorio: ${result.level}${if (shiny) ", shiny" else ""}) &aal pool.") }, true)
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
        const val MOD_VERSION = "0.7.1"
        val LOGGER = LoggerFactory.getLogger("WonderTradePlus")
    }
}
