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
import java.util.concurrent.ConcurrentLinkedQueue

object EntityShipCollisionUtils {

    /**
     * Tracks recently-spawned ships by their ID and the game-tick they were created. Ships within the
     * grace period are excluded from unloaded-ship collision checks to prevent freezing the player when
     * a new ship is assembled nearby but its chunks haven't loaded yet.
     *
     * Entries expire lazily in [isInSpawnGracePeriod] against the level's current gameTime (the same
     * monotonic per-tick clock the callers stamp). The old version compared a tick stamp that was never
     * cleaned up -- [isInSpawnGracePeriod] only did containsKey and [cleanupExpiredGracePeriods] had no
     * callers -- so every assembled ship stayed in "grace" forever and the unloaded-ship movement guard
     * was permanently disabled for it. (Tick-based, NOT nanoTime: matches DST's existing gameTime model.)
     */
    private val recentlySpawnedShips = ConcurrentHashMap<ShipId, Long>()
    private const val SPAWN_GRACE_PERIOD_TICKS = 100L // ~5 seconds

    @JvmStatic
    fun markShipAsRecentlySpawned(shipId: ShipId, currentTick: Long) {
        recentlySpawnedShips[shipId] = currentTick
    }

    @JvmStatic
    fun isInSpawnGracePeriod(shipId: ShipId, currentTick: Long): Boolean {
        val spawnTick = recentlySpawnedShips[shipId] ?: return false
        if (currentTick - spawnTick > SPAWN_GRACE_PERIOD_TICKS) {
            recentlySpawnedShips.remove(shipId)
            return false
        }
        return true
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

    /**
     * Per-ship, per-tick cache of the rough world-space AABB derived from the ship's active chunk set.
     * Rebuilding the chunk-set union for EVERY entity move (this sits at the HEAD of Entity.move()) was
     * the single hottest per-entity cost on the server tick; the underlying data only changes once per
     * tick. Client and server keep separate caches (singleplayer runs both in one JVM, transforms differ).
     */
    private class RoughAABBEntry {
        var gameTime = Long.MIN_VALUE
        val aabb = AABBd()
    }

    private val roughAABBCacheServer = ConcurrentHashMap<ShipId, RoughAABBEntry>()
    private val roughAABBCacheClient = ConcurrentHashMap<ShipId, RoughAABBEntry>()
    private var lastCacheSweepServer = Long.MIN_VALUE
    private var lastCacheSweepClient = Long.MIN_VALUE
    private const val CACHE_SWEEP_INTERVAL_TICKS = 1200L // drop entries for deleted ships ~once a minute

    private fun roughWorldAABB(ship: Ship, level: Level, gameTime: Long): AABBdc {
        val cache: ConcurrentHashMap<ShipId, RoughAABBEntry>
        if (level.isClientSide) {
            cache = roughAABBCacheClient
            if (gameTime - lastCacheSweepClient >= CACHE_SWEEP_INTERVAL_TICKS) {
                cache.clear()
                lastCacheSweepClient = gameTime
            }
        } else {
            cache = roughAABBCacheServer
            if (gameTime - lastCacheSweepServer >= CACHE_SWEEP_INTERVAL_TICKS) {
                cache.clear()
                lastCacheSweepServer = gameTime
            }
        }
        val entry = cache.computeIfAbsent(ship.id) { RoughAABBEntry() }
        if (entry.gameTime != gameTime) {
            // shipAABB and worldAABB are sometimes too small when the ship was just loaded for the
            // first time, so use activeChunksSet for a rougher box that always contains the ship.
            getShipyardChunkAABBAround(ship).toAABBd(entry.aabb).transform(ship.shipToWorld)
            entry.gameTime = gameTime
        }
        return entry.aabb
    }

    @JvmStatic
    fun isCollidingWithUnloadedShips(entity: Entity): Boolean {
        val level = entity.level()

        if (level is ServerLevel || (level.isClientSide && level is ClientLevel)) {
            if (level.isClientSide && level is ClientLevel && !level.shipObjectWorld.isSyncedWithServer) {
                return true
            }

            val allShips = level.allShips
            if (allShips.none()) {
                return false
            }

            // Plain loop with early exit instead of a Stream pipeline: this runs at the HEAD of
            // every Entity.move() call, so per-entity allocation and lambda overhead matter.
            val gameTime = level.gameTime
            val aabb = entity.boundingBox.toJOML()
            for (ship in allShips) {
                if (ship.chunkClaimDimension != level.dimensionId) continue
                if (!roughWorldAABB(ship, level, gameTime).intersectsAABB(aabb)) continue
                // Skip collision check for recently-spawned ships whose chunks are still
                // loading. Without this, spawning a new ship near a player would freeze
                // them because isCollidingWithUnloadedShips returns true (the new ship's
                // chunks haven't loaded yet), which cancels all entity movement.
                // This must be checked BEFORE vs_isKnownShip, because the player won't
                // know about a brand-new ship yet either.
                if (isInSpawnGracePeriod(ship.id, gameTime)) continue // pretend it's loaded → don't block movement
                if (entity is PlayerKnownShipsDuck && !entity.vs_isKnownShip(ship.id)) {
                    return true
                }
                val aabbInShip = AABBd(aabb).transform(ship.worldToShip)
                if (!areAllChunksLoaded(ship, aabbInShip, level)) {
                    return true
                }
            }
            return false
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

    // === Gravity-only hold through ship transitions ===
    // A short, bounded "hold the entity's downward movement" window used while a ship's voxel collision isn't
    // solid yet (login / assembly / disassembly). Login + assembly reuse the ship-id [recentlySpawnedShips]
    // grace path (the ship still exists); DISASSEMBLY has no ship to key on, so it arms a world-space box here.
    private class WorldFreezeEntry(val dimensionId: String, val aabb: AABBd, val deadlineGameTime: Long)
    private val worldFreezes = ConcurrentLinkedQueue<WorldFreezeEntry>()

    /** The ship's world-space bounding box (from its shipyard AABB transformed to world), for [markWorldFreeze]. */
    @JvmStatic
    fun worldAABBForShip(ship: Ship): AABBd {
        val sb = ship.shipAABB
        return if (sb != null) {
            AABBd(
                sb.minX().toDouble(), sb.minY().toDouble(), sb.minZ().toDouble(),
                (sb.maxX() + 1).toDouble(), (sb.maxY() + 1).toDouble(), (sb.maxZ() + 1).toDouble()
            ).transform(ship.shipToWorld)
        } else {
            val p = ship.transform.position
            AABBd(p.x() - 32.0, p.y() - 32.0, p.z() - 32.0, p.x() + 32.0, p.y() + 32.0, p.z() + 32.0)
        }
    }

    /**
     * Arm a bounded gravity-hold over a WORLD-space box for [durationTicks] (used on ship DISASSEMBLY, where the
     * ship no longer exists to key on). Tick-based against level.gameTime, matching the rest of this class (NOT
     * System.nanoTime). The disassembly caller lives in the Eureka repo.
     */
    @JvmStatic
    fun markWorldFreeze(level: Level, aabb: AABBd, durationTicks: Long) {
        worldFreezes.add(WorldFreezeEntry(level.dimensionId, aabb, level.gameTime + durationTicks))
    }

    @JvmStatic
    fun isInWorldFreeze(entity: Entity): Boolean {
        if (worldFreezes.isEmpty()) return false
        val now = entity.level().gameTime
        val dim = entity.level().dimensionId
        val px = entity.x
        val py = entity.y
        val pz = entity.z
        var held = false
        val it = worldFreezes.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.deadlineGameTime > 0) {
                it.remove()
                continue
            }
            if (!held && e.dimensionId == dim &&
                px >= e.aabb.minX() && px <= e.aabb.maxX() &&
                py >= e.aabb.minY() && py <= e.aabb.maxY() &&
                pz >= e.aabb.minZ() && pz <= e.aabb.maxZ()
            ) {
                held = true
            }
        }
        return held
    }

    /**
     * Whether [entity]'s GRAVITY (only) should be held this tick: it is in a ship-transition zone where the
     * deck collision isn't solid yet -- a DISASSEMBLY world-freeze, OR over a freshly-loaded/assembled ship
     * still in its spawn-grace (login / assembly). Used by MixinEntity.vs$holdGravityDuringShipTransition to
     * clamp ONLY the downward movement, so the entity keeps full X/Z + camera control but cannot fall through.
     * Deadline-bounded by both [worldFreezes] and [recentlySpawnedShips], so it never holds forever.
     *
     * MOBS / ENTITIES ONLY -- players are excluded (the downward-clamp made elytra/creative flight feel floaty,
     * and they don't fall through on transitions anyway), keeping player flight + gravity completely vanilla.
     */
    @JvmStatic
    fun shouldHoldGravity(entity: Entity): Boolean {
        if (entity is Player) return false
        if (isInWorldFreeze(entity)) return true
        val level = entity.level()
        if (!(level is ServerLevel || (level.isClientSide && level is ClientLevel))) return false
        val allShips = level.allShips
        if (allShips.none()) return false
        val gameTime = level.gameTime
        val aabb = entity.boundingBox.toJOML()
        for (ship in allShips) {
            if (ship.chunkClaimDimension != level.dimensionId) continue
            if (!roughWorldAABB(ship, level, gameTime).intersectsAABB(aabb)) continue
            if (isInSpawnGracePeriod(ship.id, gameTime)) return true
        }
        return false
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
        if (entity != null) {
            val standingOnShip = entity.level().getLoadedShipManagingPos(entity.onPos)
            if (shipCollidingWith != null && standingOnShip != null && standingOnShip.id == shipCollidingWith) {
                // Update the [IEntity.lastShipStoodOn]
                (entity as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                for (entityRiding in entity.indirectPassengers) {
                    (entityRiding as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                }
            }
        }
        return newMovement.toMinecraft()
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
