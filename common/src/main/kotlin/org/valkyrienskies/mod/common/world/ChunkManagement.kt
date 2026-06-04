package org.valkyrienskies.mod.common.world

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.ChunkPos
import org.valkyrienskies.core.internal.world.VsiServerShipWorld
import org.valkyrienskies.core.internal.world.chunks.VsiChunkUnwatchTask
import org.valkyrienskies.core.internal.world.chunks.VsiChunkWatchTask
import org.valkyrienskies.mod.common.VS2ChunkAllocator
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isChunkLoadedForVS
import org.valkyrienskies.mod.common.isTickingChunk
import org.valkyrienskies.mod.common.mcPlayer
import org.valkyrienskies.mod.common.util.MinecraftPlayer
import org.valkyrienskies.mod.mixin.accessors.server.level.ChunkMapAccessor
import org.valkyrienskies.mod.util.logger

object ChunkManagement {

    /**
     * When true, do NOT tell the client to drop a ship's shipyard chunks when the player moves out of
     * vs-core's normal watch range (~350 blocks). The chunk stays loaded server-side anyway (the
     * SHIP_CHUNK ticket is kept for alive ships, see the unwatch block below), so this just stops the
     * client-side drop -- letting Sodium keep rendering the real ship at distance.
     *
     * Works together with the extended far clip-plane (MixinGameRenderer, driven by
     * config/valkyrienskies_client.json `shipRenderDistance`): without the far-plane bump the kept
     * chunks would just be clipped; without the keep the far-plane bump would have nothing to draw
     * past ~350.
     *
     * NOTE: currently UNBOUNDED -- once a ship's chunks reach the client they stay until the ship is
     * deleted or you change dimension. Fine for a handful of ships; a future refinement is to bound
     * the keep to the configured render distance and drop a ship's chunks once you're past it.
     */
    private const val KEEP_SHIP_CHUNKS_ON_CLIENT = true

    @JvmStatic
    fun tickChunkLoading(shipWorld: VsiServerShipWorld, server: MinecraftServer) {
        val (chunkWatchTasks, chunkUnwatchTasks) = shipWorld.getChunkWatchTasks()

        // for now, just do all the watch tasks

        chunkWatchTasks.forEach { chunkWatchTask: VsiChunkWatchTask ->
            logger.debug(
                "Watch task for dimension " + chunkWatchTask.dimensionId + ": " +
                    chunkWatchTask.chunkX + " : " + chunkWatchTask.chunkZ
            )

            val chunkPos = ChunkPos(chunkWatchTask.chunkX, chunkWatchTask.chunkZ)

            val level = server.getLevelFromDimensionId(chunkWatchTask.dimensionId)!!
            if (VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
                // Shipyard chunks use radius-0 tickets (level 33 = FULL status) to avoid
                // loading ~25 neighbor chunks per ship chunk. The chunk pipeline's neighbor
                // requirements are bypassed by MixinChunkMapShipyard.
                level.chunkSource.addTicketWithRadius(VSTicketType.SHIP_CHUNK, chunkPos, 0)
            } else {
                level.chunkSource.updateChunkForced(chunkPos, true)
            }

            val isShipyard = VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)
            // Shipyard chunks use FULL status (level 33), so isTickingChunk never returns true.
            // Use isChunkLoadedForVS which accepts FULL status for shipyard chunks.
            val condition = if (isShipyard) {
                { level.isChunkLoadedForVS(chunkPos) }
            } else {
                { level.isTickingChunk(chunkPos) }
            }
            level.server.executeIf(condition) {
                for (player in chunkWatchTask.playersNeedWatching) {
                    if (player !is MinecraftPlayer) continue
                    val serverPlayer = player.playerEntityReference.get() as ServerPlayer?
                    if (serverPlayer != null) {
                        if (chunkWatchTask.dimensionId != player.dimension) {
                            logger.warn("Player received watch task for chunk in dimension that they are not also in!")
                        }
                        val map = level.chunkSource.chunkMap as ChunkMapAccessor
                        if (isShipyard) {
                            // 1.21 still only auto-tracks ticking chunks here. Shipyard chunks stop at FULL,
                            // so send the ready chunk directly once the ticketed chunk exists.
                            val chunk = level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z)
                            if (chunk != null) {
                                serverPlayer.connection.send(
                                    ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null)
                                )
                            }
                        } else {
                            map.callMarkChunkPendingToSend(serverPlayer, chunkPos)
                        }
                    }
                }
            }
        }

        chunkUnwatchTasks.forEach { chunkUnwatchTask: VsiChunkUnwatchTask ->
            logger.debug(
                "Unwatch task for dimension " + chunkUnwatchTask.dimensionId + ": " +
                    chunkUnwatchTask.chunkX + " : " + chunkUnwatchTask.chunkZ
            )
            val chunkPos = ChunkPos(chunkUnwatchTask.chunkX, chunkUnwatchTask.chunkZ)

            // Whether this unwatch corresponds to a ship that was actually deleted
            // (vs. the player just moving out of range on a still-alive ship).
            var shipDeleteUnwatch = false

            if (chunkUnwatchTask.shouldUnload) {
                val level = server.getLevelFromDimensionId(chunkUnwatchTask.dimensionId)!!
                if (VS2ChunkAllocator.isChunkInShipyardCompanion(chunkPos.x, chunkPos.z)) {
                    // Only release the SHIP_CHUNK ticket if the ship that owns this chunk
                    // is actually gone. If the ship still exists (just no player watcher),
                    // keep the chunk loaded — otherwise scheduled ticks, block entities,
                    // and fluid flow on unattended ships all silently stop working.
                    val taskShip = chunkUnwatchTask.ship
                    val shipStillAlive = shipWorld.allShips.getById(taskShip.id) != null
                    if (!shipStillAlive) {
                        shipDeleteUnwatch = true
                        // Mark the chunk as clean so MC's scheduleUnload doesn't even
                        // try to serialize it. Paired with MixinChunkMapScheduleUnload
                        // which short-circuits the whole async unload chain for
                        // shipyard chunks — this is the belt, that's the suspenders.
                        // 1.21.11: LevelChunk.isUnsaved is read-only now. The MixinChunkMapScheduleUnload
                        // belt path still short-circuits unload serialization for shipyard chunks.
                        level.chunkSource.removeTicketWithRadius(VSTicketType.SHIP_CHUNK, chunkPos, 0)
                    }
                } else {
                    level.chunkSource.updateChunkForced(chunkPos, false)
                }
            }

            // Skip the per-player dropChunk pass for ship-delete unwatches: shipyard
            // chunks never go through PlayerChunkSender, so dropChunk is a no-op for
            // tracking state. Clients already learn the ship went away via the
            // vs-core delete sync.
            if (!shipDeleteUnwatch && !KEEP_SHIP_CHUNKS_ON_CLIENT) {
                for (player in chunkUnwatchTask.playersNeedUnwatching) {
                    if (player !is MinecraftPlayer) continue
                    val serverPlayer = player.mcPlayer as ServerPlayer
                    (server.getLevelFromDimensionId(chunkUnwatchTask.dimensionId)!!.chunkSource.chunkMap as ChunkMapAccessor)
                        .callDropChunk(serverPlayer, chunkPos)
                }
            }
        }

        shipWorld.setExecutedChunkWatchTasks(chunkWatchTasks, chunkUnwatchTasks)
    }

    /**
     * Returns the list of pending tracking updates (currently empty — stub for tests).
     */
    @JvmStatic
    fun getPendingTrackingUpdates(): List<Any> = emptyList()

    /**
     * Clears any pending chunk management state (stub for tests).
     */
    @JvmStatic
    fun clearPendingState() {
        // No-op in current implementation
    }

    private val logger by logger()
}
