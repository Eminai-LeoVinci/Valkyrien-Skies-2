package org.valkyrienskies.mod.common.world

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.KelpBlock
import net.minecraft.world.level.block.KelpPlantBlock
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.internal.world.VsiServerShipWorld
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import java.util.function.Predicate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A moving ship cuts away the kelp its hull physically passes through, so kelp no longer clips
 * inside the boat. Only KELP is touched -- seagrass and other soft cover are left alone. They
 * already phase harmlessly through the hull thanks to the MassDatapackResolver classification that
 * counts fluid-bearing, no-collision plants as water (so the ship never stops dead on them); kelp
 * gets that same phase-through treatment AND is mown out of the hull's path.
 *
 * Two rules make this match "delete the kelp the boat touches, and nothing more":
 *  1. Per-block occupancy gate ([occupiesWorldBlock]): a candidate kelp block is removed only when
 *     the ship's hull actually occupies that world voxel. Scanning the ship's world AABB alone
 *     over-deletes -- the AABB of a rotated or large hull is far bigger than the hull, which is why
 *     kelp used to vanish several blocks from the boat. Transforming each kelp voxel into shipyard
 *     space and reading the ship's own block there rejects the empty-water corners of the box.
 *  2. Single-segment removal ([mowKelp]): only the touched kelp blocks go. We do NOT fell the rest
 *     of the strand -- the lower portion stays rooted to the sea floor and any remainder above the
 *     cut is left in place. Removal uses UPDATE_KNOWN_SHAPE so the segments left behind are never
 *     told their support changed, so none of them break into a dropped kelp item.
 *
 * Removal is a silent [Level.setBlock][net.minecraft.world.level.Level.setBlock] to the water the
 * kelp carried -- never [destroyBlock][net.minecraft.world.level.Level.destroyBlock], so no loot
 * ever rolls (no kelp item, no break particles), and buoyancy bookkeeping stays consistent.
 *
 * Cost control: only ships actually moving are processed, candidate chunk sections are gated by a
 * palette check ([net.minecraft.world.level.chunk.LevelChunkSection.maybeHas]), the occupancy test
 * runs only for blocks already known to be kelp, and a hard per-tick block-scan budget bounds the
 * worst case.
 */
object ShipPlantMower {

    /** Ships drifting slower than this (m/s, squared) don't mow -- parked ships leave kelp be. */
    private const val MIN_SPEED_SQ = 0.01 * 0.01

    /** Defensive cap: skip pathological AABBs (corrupt transforms) instead of scanning the world. */
    private const val MAX_AXIS_SPAN = 512.0

    /**
     * Hard ceiling on blocks examined per server tick across ALL ships. The palette gate
     * ([net.minecraft.world.level.chunk.LevelChunkSection.maybeHas]) already makes kelp-free and
     * open-ocean sections free, so this only bites when a very large ship sits over a genuinely
     * dense kelp field. When the budget runs out the remaining volume is picked up on later ticks
     * -- a moving ship advances <1 block/tick, far slower than the budget clears, so nothing in its
     * path is missed for long.
     *
     * Each kelp candidate additionally costs one shipyard [occupiesWorldBlock] lookup (a cross-region
     * getBlockState, dearer than the in-section array read that the per-section charge below covers).
     * Those lookups are transitively bounded by this budget -- there can be no more of them than
     * blocks scanned -- and the ceiling is kept low enough that the combined worst case (section
     * reads + per-kelp lookups) still stays comfortably under a millisecond on the server thread.
     */
    private const val MAX_BLOCKS_PER_TICK = 32_768

    private val kelpPredicate = Predicate<BlockState> { isKelp(it) }

    /** Remaining block-scan budget for the current tick (single-threaded: server tick only). */
    private var blockBudget = 0

    // Scratch reused across the scan; safe because tick() only ever runs on the server thread.
    private val scratchShipPos = Vector3d()
    private val scratchBlockPos = BlockPos.MutableBlockPos()

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
        val aabb = ship.worldAABB ?: return
        if (aabb.maxX() - aabb.minX() > MAX_AXIS_SPAN ||
            aabb.maxY() - aabb.minY() > MAX_AXIS_SPAN ||
            aabb.maxZ() - aabb.minZ() > MAX_AXIS_SPAN
        ) return

        // Scan the ship's exact world AABB -- NO radius/velocity padding. Deletion is gated per
        // block by occupiesWorldBlock(), so a tight box is correct: we only want kelp the hull is
        // actually inside, and the occupancy test rejects everything else inside the bounding box.
        val minX = floor(aabb.minX()).toInt()
        val maxX = floor(aabb.maxX()).toInt()
        val minZ = floor(aabb.minZ()).toInt()
        val maxZ = floor(aabb.maxZ()).toInt()
        val minY = max(floor(aabb.minY()).toInt(), level.minBuildHeight)
        // On 1.21.1 getMaxBuildHeight() is EXCLUSIVE (first invalid Y), so -1 gives the highest
        // VALID block Y (inclusive); the per-section y1 clamp below still keeps every read in a real section.
        val maxY = min(floor(aabb.maxY()).toInt(), level.maxBuildHeight - 1)
        if (minY > maxY) return

        val worldToShip = ship.transform.worldToShip

        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (sy in max(minY shr 4, level.minSection)..min(maxY shr 4, level.maxSection - 1)) {
                    val section = chunk.getSection(level.getSectionIndexFromSectionY(sy))
                    if (section.hasOnlyAir() || !section.maybeHas(kelpPredicate)) continue

                    val x0 = max(minX, cx shl 4)
                    val x1 = min(maxX, (cx shl 4) + 15)
                    val y0 = max(minY, sy shl 4)
                    val y1 = min(maxY, (sy shl 4) + 15)
                    val z0 = max(minZ, cz shl 4)
                    val z1 = min(maxZ, (cz shl 4) + 15)
                    // Charge the section's volume up front; scan it whole, then bail if the per-tick
                    // budget is spent (overshoot is at most one section = 4096 blocks).
                    blockBudget -= (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            for (z in z0..z1) {
                                val state = section.getBlockState(x and 15, y and 15, z and 15)
                                if (isKelp(state) && occupiesWorldBlock(worldToShip, level, x, y, z)) {
                                    mowKelp(level, BlockPos(x, y, z), state)
                                }
                            }
                        }
                    }
                    if (blockBudget <= 0) return
                }
            }
        }
    }

    /**
     * True if the ship's hull physically occupies the world voxel (x, y, z). VS2 stores ship blocks
     * as real blocks in the same level at shipyard coordinates, so we transform the voxel's CENTER
     * into shipyard space and read the ship's own block there: a non-air block means the hull is
     * inside this world voxel. This is the "physically touches" gate that separates kelp the boat
     * actually passes through from kelp merely sitting inside the loose world AABB. (Established
     * idiom -- see MixinEntity's "standing on ship" check, same transform + non-air test.)
     */
    private fun occupiesWorldBlock(worldToShip: Matrix4dc, level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val shipPos = worldToShip.transformPosition(x + 0.5, y + 0.5, z + 0.5, scratchShipPos)
        val bp = scratchBlockPos.set(floor(shipPos.x).toInt(), floor(shipPos.y).toInt(), floor(shipPos.z).toInt())
        return !level.getBlockState(bp).isAir
    }

    private fun mowKelp(level: ServerLevel, pos: BlockPos, state: BlockState) {
        // Silent, no-drop removal of just THIS segment, collapsing it back to the water it carried.
        // Flags = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE:
        //   UPDATE_CLIENTS (2)      -- players see the kelp vanish.
        //   UPDATE_KNOWN_SHAPE (16) -- suppress neighbour shape updates. This is the load-bearing
        //     bit: without it, removing a segment notifies the kelp ABOVE that its support is gone,
        //     scheduling a break tick that calls destroyBlock and DROPS a kelp item. With it, the
        //     rest of the strand (rooted lower portion and any remainder above the cut) is left
        //     undisturbed and never drops. setBlock itself never rolls loot, so no SUPPRESS_DROPS.
        level.setBlock(pos, state.fluidState.createLegacyBlock(), Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
    }

    private fun isKelp(state: BlockState): Boolean {
        val block = state.block
        return block is KelpBlock || block is KelpPlantBlock
    }
}
