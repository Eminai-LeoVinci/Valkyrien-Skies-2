package org.valkyrienskies.mod.common.command.commands

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.util.settings

/**
 * `/vs set-keep-active <ships> <true|false>` — toggle [org.valkyrienskies.mod.common.util.ShipSettings.keepActive].
 *
 * When true, the targeted ships keep simulating (physics / cruise / machinery) even when no player
 * is within the vanilla simulation distance; see [org.valkyrienskies.mod.common.world.ShipActivationManager].
 * Reuses the same permission as `/vs set-static`.
 */
object KeepActiveCommand {
    fun register(vs: LiteralArgumentBuilder<CommandSourceStack>) {
        vs.then(
            literal("set-keep-active")
                .requires { it.hasPermission(VSGameConfig.SERVER.Commands.setStaticShipCommandPerms) }
                .then(
                    argument("ships", ShipArgument.ships()).then(
                        argument("keep-active", BoolArgumentType.bool()).executes {
                            val ships = ShipArgument.getShips(it, "ships").toList() as List<ServerShip>
                            val value = BoolArgumentType.getBool(it, "keep-active")
                            var changed = 0
                            ships.forEach { ship ->
                                (ship as? LoadedServerShip)?.let { loaded ->
                                    loaded.settings.keepActive = value
                                    changed++
                                }
                            }
                            val applied = changed
                            it.source.sendSuccess(
                                { Component.literal("Set keepActive=$value on $applied ship(s)") }, true
                            )
                            applied
                        })
                )
        )
    }
}
