package org.valkyrienskies.mod.common.world

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.KelpBlock
import net.minecraft.world.level.block.KelpPlantBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.internal.world.VsiServerShipWorld
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import java.util.function.Predicate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Moving ships mow down the soft plants they pass through -- kelp, seagrass, grass and other
 * replaceable cover -- instead of leaving them clipped inside the hull. Removal is silent
 * [Level.setBlock][net.minecraft.world.level.Level.setBlock], which never rolls loot: no kelp
 * items, no seeds, no break-noise wall when crossing a kelp forest. Waterlogged plants leave
 * their water behind, so buoyancy bookkeeping stays consistent.
 *
 * Pairs with the MassDatapackResolver change that registers fluid-bearing no-collision plants
 * as water (so kelp no longer stops ships dead); this pass handles the visual/world side and
 * the dry plants (bank grass) that stay solid-family.
 *
 * Cost control: only ships actually moving are processed, candidate chunk sections are gated
 * by a palette check ([net.minecraft.world.level.chunk.LevelChunkSection.maybeHas]), and open
 * ocean sections (water + air palettes) skip without touching a single block.
 */
object ShipPlantMower {

    /** Ships drifting slower than this (m/s, squared) don't mow -- parked ships leave plants be. */
    private const val MIN_SPEED_SQ = 0.01 * 0.01

    /** Defensive cap: skip pathological AABBs (corrupt transforms) instead of scanning the world. */
    private const val MAX_AXIS_SPAN = 512.0

    /**
     * Hard ceiling on blocks examined per server tick across ALL ships. The palette gate
     * ([net.minecraft.world.level.chunk.LevelChunkSection.maybeHas]) already makes plant-free and
     * open-ocean sections free, so this only bites when a very large ship sits over a genuinely
     * dense plant field (e.g. a kelp forest). When the budget runs out the remaining volume is
     * simply picked up on later ticks -- a moving ship advances <1 block/tick, far slower than the
     * budget clears, so nothing in its path is ever missed for long. 65,536 getBlockState calls is
     * well under a millisecond on the server thread.
     */
    private const val MAX_BLOCKS_PER_TICK = 65_536

    private val mowablePredicate = Predicate<BlockState> { isMowable(it) }

    /** Remaining block-scan budget for the current tick (single-threaded: server tick only). */
    private var blockBudget = 0

    @JvmStatic
    fun tick(shipWorld: VsiServerShipWorld, server: MinecraftServer) {
        if (!VSGameConfig.SERVER.shipsDestroyPlants) return
        blockBudget = MAX_BLOCKS_PER_TICK
        for (ship in shipWorld.loadedShips) {
            if (blockBudget <= 0) break
            ship.shipAABB ?: continue // blockless ship
            if (ship.velocity.lengthSquared() < MIN_SPEED_SQ) continue
            val level = server.getLevelFromDimensionId(ship.chunkClaimDimension) ?: continue
            mow(ship, level)
        }
    }

    private fun mow(ship: LoadedServerShip, level: ServerLevel) {
        val aabb = ship.worldAABB
        if (aabb.maxX() - aabb.minX() > MAX_AXIS_SPAN ||
            aabb.maxY() - aabb.minY() > MAX_AXIS_SPAN ||
            aabb.maxZ() - aabb.minZ() > MAX_AXIS_SPAN
        ) return

        // Pad half a block plus one tick of motion on the leading side, so plants in the ship's
        // path are gone before the physics ever sees contact.
        val vel = ship.velocity
        val minX = floor(aabb.minX() - 0.5 + min(vel.x() / 20.0, 0.0)).toInt()
        val maxX = floor(aabb.maxX() + 0.5 + max(vel.x() / 20.0, 0.0)).toInt()
        val minZ = floor(aabb.minZ() - 0.5 + min(vel.z() / 20.0, 0.0)).toInt()
        val maxZ = floor(aabb.maxZ() + 0.5 + max(vel.z() / 20.0, 0.0)).toInt()
        val minY = max(floor(aabb.minY() - 0.5 + min(vel.y() / 20.0, 0.0)).toInt(), level.minY)
        val maxY = min(floor(aabb.maxY() + 0.5 + max(vel.y() / 20.0, 0.0)).toInt(), level.maxY - 1)
        if (minY > maxY) return

        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (sy in max(minY shr 4, level.minSectionY)..min(maxY shr 4, level.maxSectionY)) {
                    val section = chunk.getSection(level.getSectionIndexFromSectionY(sy))
                    if (section.hasOnlyAir() || !section.maybeHas(mowablePredicate)) continue

                    val x0 = max(minX, cx shl 4)
                    val x1 = min(maxX, (cx shl 4) + 15)
                    val y0 = max(minY, sy shl 4)
                    val y1 = min(maxY, (sy shl 4) + 15)
                    val z0 = max(minZ, cz shl 4)
                    val z1 = min(maxZ, (cz shl 4) + 15)
                    // Charge this section's volume up front; scan it whole, then bail if the
                    // per-tick budget is spent (overshoot is at most one section = 4096 blocks).
                    blockBudget -= (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            for (z in z0..z1) {
                                val state = section.getBlockState(x and 15, y and 15, z and 15)
                                if (isMowable(state)) {
                                    mowBlock(level, BlockPos(x, y, z), state)
                                }
                            }
                        }
                    }
                    if (blockBudget <= 0) return
                }
            }
        }
    }

    private fun mowBlock(level: ServerLevel, pos: BlockPos, state: BlockState) {
        // Silent removal: setBlock never rolls loot, so nothing ever drops. Fluid-bearing plants
        // (kelp, seagrass) collapse back into the water they carry; dry plants become air.
        level.setBlock(pos, state.fluidState.createLegacyBlock(), 3)

        // Kelp above the removed block loses support and would pop AS AN ITEM on its scheduled
        // tick next game tick; fell the rest of the stalk now instead (this also covers column
        // parts above the swept box, e.g. a mostly-submerged hull cutting a surface-high stalk).
        if (state.block is KelpBlock || state.block is KelpPlantBlock) {
            var y = pos.y + 1
            while (y <= level.maxY) {
                val up = BlockPos(pos.x, y, pos.z)
                val upState = level.getBlockState(up)
                if (upState.block !is KelpBlock && upState.block !is KelpPlantBlock) break
                level.setBlock(up, upState.fluidState.createLegacyBlock(), 3)
                y++
            }
        }
    }

    private fun isMowable(state: BlockState): Boolean {
        val block = state.block
        // Kelp isn't tagged replaceable, so it needs the explicit check.
        if (block is KelpBlock || block is KelpPlantBlock) return true
        // "Replaceable" is vanilla's notion of soft cover a placed block stomps -- grass, ferns,
        // seagrass, vines, snow layers -- which is exactly what a moving hull should flatten.
        // Fluids themselves are also tagged replaceable, so exclude actual fluid blocks; a plant
        // that merely CARRIES water (seagrass) is fine.
        return !state.isAir && state.canBeReplaced() && block !is LiquidBlock
    }
}
