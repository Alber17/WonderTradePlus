package dev.openwondertrade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
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
            .then(literal("cancel").executes { context ->
                WonderTradeService.cancel(context.source.playerOrException)
                1
            })
            .then(literal("pool").executes { context ->
                context.source.sendSuccess({ WonderTradeService.poolSummaryComponent() }, false)
                1
            }.then(literal("view")
                .requires { it.hasPermission(ConfigManager.int("gui.poolViewPermissionLevel", 0).coerceIn(0, 4)) }
                .executes { context ->
                    WonderTradeGui.openPool(context.source.playerOrException, 0)
                    1
                }))
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
            .then(argument("level", IntegerArgumentType.integer(1, 100))
                .executes { context -> addSpecies(context, false) }
                .then(literal("shiny").executes { context -> addSpecies(context, true) }))))
        .then(literal("fill").then(argument("amount", IntegerArgumentType.integer(1, 500))
            .executes { context ->
                fill(context, ConfigManager.int("fallback.minimumLevel", 1), ConfigManager.int("fallback.maximumLevel", 100))
            }
            .then(argument("minLevel", IntegerArgumentType.integer(1, 100))
                .then(argument("maxLevel", IntegerArgumentType.integer(1, 100)).executes { context ->
                    fill(context, IntegerArgumentType.getInteger(context, "minLevel"), IntegerArgumentType.getInteger(context, "maxLevel"))
                }))))
        .then(literal("remove").executes { context ->
            WonderTradeGui.openPool(context.source.playerOrException, 0, true)
            1
        })
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

    private fun addSpecies(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, shiny: Boolean): Int {
        val player = context.source.playerOrException
        val species = StringArgumentType.getString(context, "species")
        val level = IntegerArgumentType.getInteger(context, "level")
        val name = WonderTradeService.adminAddSpecies(player, species, level, shiny)
        if (name == null) {
            context.source.sendFailure(WonderTradeService.info("&cNo se pudo crear &f$species&c. Usa el nombre de la especie en inglés y sin espacios, por ejemplo &fpikachu &co &fgreattusk&c."))
        } else {
            context.source.sendSuccess({ WonderTradeService.info("&aSe ha añadido &f$name &7(nivel $level${if (shiny) ", shiny" else ""}) &aal pool.") }, true)
        }
        return 1
    }

    private fun fill(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, minLevel: Int, maxLevel: Int): Int {
        val player = context.source.playerOrException
        val amount = IntegerArgumentType.getInteger(context, "amount")
        val added = WonderTradeService.adminFill(player, amount, minOf(minLevel, maxLevel), maxOf(minLevel, maxLevel))
        context.source.sendSuccess({ WonderTradeService.info("&aSe han generado &f$added &ade &f$amount &aPokémon aleatorios para el pool.") }, true)
        return 1
    }

    companion object {
        const val MOD_VERSION = "0.3.0"
        val LOGGER = LoggerFactory.getLogger("WonderTradePlus")
    }
}
