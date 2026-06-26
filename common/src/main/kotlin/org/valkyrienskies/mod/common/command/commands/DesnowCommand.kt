package org.valkyrienskies.mod.common.command.commands
import org.valkyrienskies.mod.common.command.hasOpPermission

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.forEach

/**
 * Registers the `/vs desnow` command (companion to [DryCommand]).
 *
 * In a snowy biome snow keeps piling onto a ship's deck while it sails; once the layers stack up
 * their collision box stops the player from walking. This strips every snow LAYER (minecraft:snow)
 * inside the ship's shipyard AABB in one shot. Whole snow blocks (minecraft:snow_block) are left
 * untouched -- only the thin layered block is removed.
 */
object DesnowCommand {
    private const val DESNOW_SHIP_NO_BLOCKS_MESSAGE = "command.valkyrienskies.desnow.no_blocks"
    private const val DESNOW_SHIP_SUCCESS_MESSAGE = "command.valkyrienskies.desnow.success"

    fun register(vs: LiteralArgumentBuilder<CommandSourceStack>) {
        vs.then(literal("desnow")
            .requires { it.hasOpPermission(VSGameConfig.SERVER.Commands.desnowShipCommandPerms) }
            .then(argument("ship", ShipArgument.ships())
                .executes {
                    desnowShip(it, ShipArgument.getShip(it, "ship"))
                }
            )
        )
    }

    fun desnowShip(context: CommandContext<CommandSourceStack>, ship: Ship): Int {
        val level = context.source.level

        // Shouldn't ever happen, but just in case
        if (ship.shipAABB == null) {
            context.source.sendFailure(Component.translatable(DESNOW_SHIP_NO_BLOCKS_MESSAGE))
            return 0
        }

        var clearCount = 0

        ship.shipAABB!!.forEach { x, y, z ->
            val pos = BlockPos(x, y, z)
            if (level.getBlockState(pos).block is SnowLayerBlock) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)
                clearCount += 1
            }
        }

        context.source.sendSuccess(
            {
                Component.translatable(DESNOW_SHIP_SUCCESS_MESSAGE, clearCount)
            }, true
        )

        return 1
    }
}
