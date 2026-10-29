package dev.openwondertrade

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.commands.CommandSourceStack
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
            .then(literal("clearpool").requires { it.hasPermission(2) }.executes { context ->
                WonderTradeService.clearPool()
                context.source.sendSuccess({ WonderTradeService.configuredMessage("poolCleared", "&aPool vaciado.") }, true)
                1
            })
        dispatcher.register(command)
    }

    companion object {
        const val MOD_VERSION = "0.2.1"
        val LOGGER = LoggerFactory.getLogger("WonderTradePlus")
    }
}
