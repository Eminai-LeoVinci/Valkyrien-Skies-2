package org.valkyrienskies.mod.common.util

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.joml.primitives.AABBi
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.collision.VsiConvexPolygonc
import org.valkyrienskies.core.util.extend
import org.valkyrienskies.core.util.toAABBd
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.mod.common.allShips
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck
import org.valkyrienskies.mod.util.BugFixUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

object EntityShipCollisionUtils {

    /**
     * Tracks recently-spawned ships by their ID and the tick they were created.
     * Ships in this grace period are excluded from unloaded-ship collision checks
     * to prevent freezing the player when a new ship is assembled nearby but its
     * chunks haven't loaded yet.
     */
    private val recentlySpawnedShips = ConcurrentHashMap<ShipId, Long>()
    private const val SPAWN_GRACE_PERIOD_TICKS = 100L // ~5 seconds

    @JvmStatic
    fun markShipAsRecentlySpawned(shipId: ShipId, currentTick: Long) {
        recentlySpawnedShips[shipId] = currentTick
    }

    @JvmStatic
    fun cleanupExpiredGracePeriods(currentTick: Long) {
        recentlySpawnedShips.entries.removeIf { (_, spawnTick) ->
            currentTick - spawnTick > SPAWN_GRACE_PERIOD_TICKS
        }
    }

    @JvmStatic
    fun isInSpawnGracePeriod(shipId: ShipId): Boolean {
        return recentlySpawnedShips.containsKey(shipId)
    }
    private const val PARTICLE_COLLISION_BOX_EXPANSION = 0.00390625 //1.0 / 256.0

    private val collider = vsCore.entityPolygonCollider

    private fun getShipyardChunkAABBAround(ship: Ship): AABBi {
        val box = AABBi()
        // Since we don't know how big the ship is vertically we'll just have to trust the shipAABB and add some margin of error.
        val minY = (ship.shipAABB?.minY() ?: Mth.floor(ship.transform.position.y())) - 16
        val maxY = (ship.shipAABB?.maxY() ?: Mth.ceil(ship.transform.position.y())) + 16
        ship.activeChunksSet.forEach { x, z ->
            val minX = SectionPos.sectionToBlockCoord(x)
            val minZ = SectionPos.sectionToBlockCoord(z)
            val maxX = SectionPos.sectionToBlockCoord(x, 15)
            val maxZ = SectionPos.sectionToBlockCoord(z, 15)
            box.union(minX, minY, minZ).union(maxX, maxY, maxZ)
        }
        return box
    }

    private fun getAllShipsIntersectingEvenIfNotYetFullyLoaded(level: Level, aabb: AABBd): Stream<Ship> {
        // shipAABB and worldAABB are sometimes too small when ship was just loaded for the first time.
        // To circumvent this, we use activeChunksSet to find a rougher bounding box which should always contain the entire ship.
        return level.allShips.stream().filter { ship ->
            ship.chunkClaimDimension == level.dimensionId &&
            getShipyardChunkAABBAround(ship).toAABBd(AABBd()).transform(ship.shipToWorld).intersectsAABB(aabb)
        }
    }

    @JvmStatic
    fun isCollidingWithUnloadedShips(entity: Entity): Boolean {
        val level = entity.level()

        if (level is ServerLevel || (level.isClientSide && level is ClientLevel)) {
            if (level.isClientSide && level is ClientLevel && !level.shipObjectWorld.isSyncedWithServer) {
                return true
            }

            val aabb = entity.boundingBox.toJOML()
            return getAllShipsIntersectingEvenIfNotYetFullyLoaded(level, aabb)
                .allMatch { ship ->
                    // Skip collision check for recently-spawned ships whose chunks are still
                    // loading. Without this, spawning a new ship near a player would freeze
                    // them because isCollidingWithUnloadedShips returns true (the new ship's
                    // chunks haven't loaded yet), which cancels all entity movement.
                    // This must be checked BEFORE vs_isKnownShip, because the player won't
                    // know about a brand-new ship yet either.
                    if (isInSpawnGracePeriod(ship.id)) {
                        return@allMatch true // pretend it's loaded → don't block movement
                    }
                    if (entity is PlayerKnownShipsDuck && !entity.vs_isKnownShip(ship.id)) {
                        return@allMatch false
                    }
                    val aabbInShip = AABBd(aabb).transform(ship.worldToShip)
                    areAllChunksLoaded(ship, aabbInShip, level)
                }
                .not()
        }

        return false
    }

    private fun areAllChunksLoaded(ship: Ship, aABB: AABBdc, level: Level): Boolean {
        val minX = (Mth.floor(aABB.minX() - 1.0E-7) - 1) shr 4
        val maxX = (Mth.floor(aABB.maxX() + 1.0E-7) + 1) shr 4
        val minZ = (Mth.floor(aABB.minZ() - 1.0E-7) - 1) shr 4
        val maxZ = (Mth.floor(aABB.maxZ() + 1.0E-7) + 1) shr 4

        for (chunkX in minX..maxX) {
            for (chunkZ in minZ..maxZ) {
                if (ship.activeChunksSet.contains(chunkX, chunkZ) &&
                    level.getChunkForCollisions(chunkX, chunkZ) == null
                ) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * @return [movement] modified such that the entity collides with ships.
     */
    fun adjustEntityMovementForShipCollisions(
        entity: Entity?,
        movement: Vec3,
        entityBoundingBox: AABB,
        world: Level
    ): Vec3 {
        // Inflate the bounding box more for players than other entities, to give players a better collision result.
        // Note that this increases the cost of doing collision, so we only do it for the players
        val inflation = if (entity is Player) 0.5 else 0.1
        val stepHeight: Double = entity?.maxUpStep()?.toDouble() ?: 0.0
        // Add [max(stepHeight - inflation, 0.0)] to search for polygons we might collide with while stepping

        // This part was slightly changed to inflate the bounding box in y-axis and adjust the center point. - Bunting_chj
        val collidingShipPolygons =
            getShipPolygonsCollidingWithEntity(
                entity, Vec3(movement.x(), movement.y() + stepHeight / 2, movement.z()),
                entityBoundingBox.inflate(inflation, inflation + stepHeight / 2, inflation), world
            )

        if (collidingShipPolygons.isEmpty()) {
            return movement
        }

        val collisionBoundingBox = if (entity == null) {
            entityBoundingBox.inflate(PARTICLE_COLLISION_BOX_EXPANSION)
        } else {
            entityBoundingBox
        }

        val (newMovement, shipCollidingWith) = collider.adjustEntityMovementForPolygonCollisions(
            movement.toJOML(), collisionBoundingBox.toJOML(), stepHeight, collidingShipPolygons
        )

        // 2.4.88 fix: per-axis magnitude clamp for gliding (fall-flying / elytra) entities.
        // The polygon collider is only allowed to DECREASE movement magnitude on each axis,
        // never INCREASE it. Without this, two cases inject boost velocity into the glider:
        //
        //   1. Polygon extraction: when the player tunnels into a ship block at high glide
        //      speed, the collider's next-tick response is a large normal-direction push to
        //      extract them. That push has magnitude far exceeding requested movement, and
        //      the 2.4.86 projection mixin converts the resulting deltaMovement into
        //      tangent-to-hull velocity — which elytra realignment then rotates back into
        //      the look direction next tick. Speed never bleeds off, and the condition is
        //      re-triggered every subsequent glide near the ship, so the boost persists
        //      until the world reloads.
        //   2. Ship-velocity carry-over: an ascending or moving ship's per-tick velocity
        //      gets baked into the collider's adjusted movement (e.g., to make passengers
        //      ride along). A gliding player isn't a passenger, but if the collider treats
        //      their overlapping bounding box as one, ship velocity injects into the
        //      player's movement and accumulates the same way as case 1.
        //
        // Per-axis rule: if |adjusted_axis| > |requested_axis|, clamp the axis to the
        // requested value (sign of requested preserved). Otherwise pass through. This
        // means normal collision responses (collider clipping movement on a wall) work as
        // before — only the over-injection cases are neutralized. It also fixes a subtle
        // bug in 2.4.87's Y-only clamp: that condition was `newMovement.y > movement.y`,
        // which is true both when a ship pushes a hovering glider upward AND when a ship
        // slows a falling glider. The latter shouldn't be clamped (the slow is the deck
        // catching the player, not an injection); the magnitude rule handles both cases
        // correctly.
        //
        // Tradeoff: gliding entities can no longer be PUSHED by ships, only STOPPED. A
        // player phased inside a ship will need to stop gliding before the polygon collider
        // can extract them. We also skip the lastShipStoodOn assignment so the player isn't
        // implicitly tracked as "on the ship" while gliding above it (this would otherwise
        // route ship-block speed factors and dragger state through them via
        // EntityDragger and getBlockPosBelowThatAffectsMyMovement).
        val isGliding = entity is IEntityDraggingInformationProvider && (entity as IEntityDraggingInformationProvider).`vs$isGliding`()
        val finalMovement: Vector3dc = if (isGliding) {
            Vector3d(
                if (kotlin.math.abs(newMovement.x()) > kotlin.math.abs(movement.x())) movement.x() else newMovement.x(),
                if (kotlin.math.abs(newMovement.y()) > kotlin.math.abs(movement.y())) movement.y() else newMovement.y(),
                if (kotlin.math.abs(newMovement.z()) > kotlin.math.abs(movement.z())) movement.z() else newMovement.z()
            )
        } else {
            newMovement
        }

        if (entity != null && !isGliding) {
            val standingOnShip = entity.level().getLoadedShipManagingPos(entity.onPos)
            if (shipCollidingWith != null && standingOnShip != null && standingOnShip.id == shipCollidingWith) {
                // Update the [IEntity.lastShipStoodOn]
                (entity as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                for (entityRiding in entity.indirectPassengers) {
                    (entityRiding as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                }
            }
        }
        return finalMovement.toMinecraft()
    }

    fun getShipPolygonsCollidingWithEntity(
        entity: Entity?,
        movement: Vec3,
        entityBoundingBox: AABB,
        world: Level
    ): List<VsiConvexPolygonc> {
        val entityBoxWithMovement = entityBoundingBox.expandTowards(movement)
        val collidingPolygons: MutableList<VsiConvexPolygonc> = ArrayList()
        val entityBoundingBoxExtended = entityBoundingBox.toJOML().extend(movement.toJOML())
        for (shipObject in world.shipObjectWorld.loadedShips.getIntersecting(entityBoundingBoxExtended, world.dimensionId)) {
            val shipTransform = shipObject.transform
            val entityPolyInShipCoordinates: VsiConvexPolygonc = collider.createPolygonFromAABB(
                entityBoxWithMovement.toJOML(),
                shipTransform.worldToShip
            )
            val entityBoundingBoxInShipCoordinates: AABBdc = entityPolyInShipCoordinates.getEnclosingAABB(AABBd())
            if (BugFixUtil.isCollisionBoxTooBig(entityBoundingBoxInShipCoordinates.toMinecraft())) {
                // Box too large, skip it
                continue
            }
            val shipBlockCollisionStream =
                world.getBlockCollisions(entity, entityBoundingBoxInShipCoordinates.toMinecraft())
            shipBlockCollisionStream.forEach { voxelShape: VoxelShape ->
                voxelShape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                    val shipPolygon: VsiConvexPolygonc = vsCore.entityPolygonCollider.createPolygonFromAABB(
                        AABBd(minX, minY, minZ, maxX, maxY, maxZ),
                        shipTransform.shipToWorld,
                        shipObject.id
                    )
                    collidingPolygons.add(shipPolygon)
                }
            }
        }
        return collidingPolygons
    }
}
