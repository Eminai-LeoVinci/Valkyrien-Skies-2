package org.valkyrienskies.mod.common.world

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.impl.config.VSCoreConfig
import org.valkyrienskies.core.internal.world.VsiPlayer
import org.valkyrienskies.core.internal.world.VsiServerShipWorld
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.util.ShipObserverPlayer
import org.valkyrienskies.mod.common.util.ShipSettings
import org.valkyrienskies.mod.util.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Keeps "active" ships simulating regardless of the vanilla simulation-distance setting.
 *
 * ## Why this exists
 * A VS2 ship lives as blocks in far-away shipyard chunks, but vs-core only physics-ticks a ship while
 * the ship's WORLD position sits in a ticking chunk -- i.e. within some player's vanilla "Simulation
 * Distance". Fly an autopilot ship past that radius and it would freeze.
 *
 * ## What it does
 * Once per server tick, for every "active" ship -- [ShipSettings.keepActive] is set, a player is seated
 * piloting it, a player is standing on / inside / touching it, or control logic called [markControlled]
 * for it this interval (e.g. Eureka cruise) -- this manager force-loads the world chunks the ship
 * currently overlaps via vanilla [net.minecraft.server.level.ServerChunkCache.updateChunkForced] (a
 * FORCED entity-ticking ticket), so the ship's footprint reports isPositionTicking and vs-core keeps
 * stepping it. The forced set follows the moving ship; stale chunks are un-forced.
 *
 * The world-chunk forceload is REFCOUNTED across ships ([worldForceRefs]): vanilla updateChunkForced
 * uses a single shared ticket per chunk, so without refcounting two overlapping ships would un-force
 * each other's chunks and freeze (the close-formation cruise stall). It also pins each ship's shipyard
 * chunks loaded (SHIP_ACTIVE_VOXEL) and emits one synthetic observer per active ship
 * ([activeShipObservers]) to satisfy vs-core's proximity gate.
 *
 * updateChunkForced (unlike ServerLevel.setChunkForced, which we never call) does NOT write the
 * persisted ForcedChunksSavedData, so nothing leaks across restarts. Cost scales with (active ship
 * count x footprint), so idle/parked ships pay nothing.
 */
object ShipActivationManager {

    private val logger by logger()

    /** Padding (blocks) around a ship's world AABB for the "a player is aboard / touching it" test. */
    private const val OCCUPY_MARGIN = 2.0

    /**
     * Ships flagged "being controlled" by external logic since the last [tick] sweep. Writers may be on
     * the physics thread (e.g. Eureka's physTick), so this is concurrent; [tick] drains it each server
     * tick (heartbeat -- a ship must be re-marked every tick to stay control-active, so it auto-releases
     * the moment control logic stops running for it).
     */
    private val controlledHeartbeat: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** ship id -> (dimension id, world chunk positions [packed long] we currently force-tick). */
    private val forced = HashMap<Long, ForcedEntry>()

    /**
     * WORLD-chunk forceload refcount: dimensionId -> (packed chunk -> # active ships forcing it).
     * Vanilla updateChunkForced uses a SINGLE shared FORCED ticket per chunk with NO per-requester
     * count. So when two ships' footprints overlap and one slides off a chunk the other is still on,
     * the naive "un-force on my exit" flipped that chunk's forceload OFF under the neighbour -- which
     * never flipped it back (its own set still listed it) -> the chunk left the tick set -> a frozen
     * ship. That was the close-formation cruise stall. Refcount it -- force on 0->1, un-force on 1->0.
     */
    private val worldForceRefs = HashMap<String, HashMap<Long, Int>>()

    private class ForcedEntry(val dimensionId: String) {
        /** Packed WORLD-position chunks we force-tick under the ship (vanilla updateChunkForced). */
        val chunks = HashSet<Long>()

        /** Packed SHIPYARD (ship-block) chunks we pin loaded via SHIP_ACTIVE_VOXEL so the ship's
         *  voxels stay fully loaded and vs-core keeps stepping it (see VSTicketType.SHIP_ACTIVE_VOXEL). */
        val voxelChunks = HashSet<Long>()

        /** Whether this ship's activation is worth logging (keepActive/piloted/controlled, not the
         *  frequent walk-on/walk-off occupancy case). */
        var loggable = false
    }

    /**
     * ship id -> last live position of a keepActive ship while it was loaded. Lets us keep emitting its
     * observer even after it briefly drops out of loadedShips (a chunk-save hiccup / autosave), which is
     * the only way to revive it: the observer that would reload it requires it to already be loaded.
     * vs-core only exposes attachments on a LoadedServerShip, so the cache is how a flagged ship survives
     * a momentary unload. Server-thread only.
     */
    private val keepActiveCache = HashMap<Long, ObserverPos>()

    private class ObserverPos(val dimensionId: String, val x: Double, val y: Double, val z: Double)

    /**
     * Snapshot of the ship ids "active" this server tick (keepActive / piloted / controlled / occupied),
     * republished at the end of every [tick]. Volatile reference + immutable contents = safe to read off
     * any thread.
     */
    @Volatile
    private var activeShipIdsSnapshot: Set<Long> = emptySet()

    /** See [activeShipIdsSnapshot]. Safe to read from any thread. */
    @JvmStatic
    val activeShipIds: Set<Long> get() = activeShipIdsSnapshot

    /** Set once we've force-zeroed the post-load freeze clamp at runtime; see [tick]. */
    private var freezeForced = false

    /**
     * Mark [shipId] as actively controlled for this server-tick interval, keeping it simulating even with
     * no player nearby. Safe to call from any thread; must be called every tick to keep the ship active
     * (heartbeat). Used by control attachments (e.g. Eureka cruise/autopilot).
     */
    @JvmStatic
    fun markControlled(shipId: Long) {
        controlledHeartbeat.add(shipId)
    }

    @JvmStatic
    fun tick(shipWorld: VsiServerShipWorld, server: MinecraftServer) {
        // Drain the control heartbeat collected since the last sweep.
        val controlled: Set<Long> = if (controlledHeartbeat.isEmpty()) {
            emptySet()
        } else {
            val snapshot = HashSet(controlledHeartbeat)
            controlledHeartbeat.clear()
            snapshot
        }

        // Force-disable the post-load freeze clamp at runtime. init() sets it as the config spec default,
        // but the world's saved vs-core server config overrides it back to 5s on load; that clamp would
        // pin a cruising ship to a kinematic target on voxel/terrain updates. Re-assert 0 every tick so
        // live reads always see 0.
        val fz = VSCoreConfig.SERVER.physics.shipLoadFreezeSeconds
        if (fz != 0.0) {
            if (!freezeForced) {
                logger.info("VS2: forcing shipLoadFreezeSeconds 0.0 (was ${fz}s) to prevent the post-load cruise clamp")
                freezeForced = true
            }
            VSCoreConfig.SERVER.physics.shipLoadFreezeSeconds = 0.0
        }

        val activeIds = HashSet<Long>()

        for (ship in shipWorld.loadedShips) {
            val manual = ship.getAttachment(ShipSettings::class.java)?.keepActive == true
            // A seated player actively piloting keeps the ship active (the pilot may sit far from the
            // centre-of-mass chunk, so that chunk can fall outside their sim distance).
            val piloted = ship.getAttachment(SeatedControllingPlayer::class.java) != null
            val controlledNow = ship.id in controlled

            val level = server.getLevelFromDimensionId(ship.chunkClaimDimension) ?: continue

            // QoL: a ship a player is standing on / inside / touching keeps simulating, so walking to the
            // far end of a big craft never freezes it. Checked only when no cheaper flag already applies.
            val occupied = !manual && !piloted && !controlledNow && playersAboard(ship, level)

            if (!manual && !piloted && !controlledNow && !occupied) continue
            activeIds.add(ship.id)

            val desired = worldChunksUnder(ship)
            val firstActivation = ship.id !in forced
            val entry = forced.getOrPut(ship.id) { ForcedEntry(ship.chunkClaimDimension) }
            entry.loggable = manual || piloted || controlledNow

            // Force-load+tick newly-overlapped world chunks (refcounted across ships -- see worldForceRefs).
            for (packed in desired) {
                if (entry.chunks.add(packed)) {
                    forceWorldChunk(level, ship.chunkClaimDimension, packed)
                }
            }
            // Un-force chunks the ship has moved off of (only truly un-forces when the LAST ship leaves).
            val iter = entry.chunks.iterator()
            while (iter.hasNext()) {
                val packed = iter.next()
                if (packed !in desired) {
                    unforceWorldChunk(level, entry.dimensionId, packed)
                    iter.remove()
                }
            }

            // Pin EVERY one of the ship's active shipyard chunks loaded (SHIP_ACTIVE_VOXEL): an unloaded
            // active chunk flips areVoxelsFullyLoaded() false and excludes the whole ship from the step.
            val desiredVoxel = HashSet<Long>()
            ship.activeChunksSet.iterateChunkPos { vcx, vcz -> desiredVoxel.add(ChunkPos.asLong(vcx, vcz)) }
            for (packed in desiredVoxel) {
                if (entry.voxelChunks.add(packed)) {
                    level.chunkSource.addTicketWithRadius(VSTicketType.SHIP_ACTIVE_VOXEL, ChunkPos(packed), 0)
                }
            }
            val vIter = entry.voxelChunks.iterator()
            while (vIter.hasNext()) {
                val packed = vIter.next()
                if (packed !in desiredVoxel) {
                    level.chunkSource.removeTicketWithRadius(VSTicketType.SHIP_ACTIVE_VOXEL, ChunkPos(packed), 0)
                    vIter.remove()
                }
            }

            if (firstActivation && entry.loggable) {
                val reason = if (manual) "keepActive" else if (piloted) "piloted" else "controlled"
                logger.info("Keeping ship ${ship.id} active ($reason) — ${entry.chunks.size} world chunks force-ticked")
            }
        }

        // Fully release ships that are no longer active (flag cleared, control stopped, or unloaded).
        if (forced.isNotEmpty()) {
            val gone = forced.keys.filter { it !in activeIds }
            for (id in gone) {
                val wasLoggable = forced[id]?.loggable == true
                release(id, server)
                if (wasLoggable) logger.info("Ship $id no longer kept active — released world-chunk tickets")
            }
        }

        activeShipIdsSnapshot = activeIds
    }

    /** Remove every chunk ticket for [id] and forget it. */
    private fun release(id: Long, server: MinecraftServer) {
        val entry = forced.remove(id) ?: return
        val level = server.getLevelFromDimensionId(entry.dimensionId) ?: return
        for (packed in entry.chunks) {
            unforceWorldChunk(level, entry.dimensionId, packed)
        }
        for (packed in entry.voxelChunks) {
            level.chunkSource.removeTicketWithRadius(VSTicketType.SHIP_ACTIVE_VOXEL, ChunkPos(packed), 0)
        }
    }

    /** Release everything (call on server shutdown, before MC's chunk-drain loop). */
    @JvmStatic
    fun clearAll(server: MinecraftServer) {
        for (id in forced.keys.toList()) {
            release(id, server)
        }
        worldForceRefs.clear()
        controlledHeartbeat.clear()
        keepActiveCache.clear()
    }

    /** Force-load one WORLD chunk for a ship, refcounted across all active ships (see [worldForceRefs]). */
    private fun forceWorldChunk(level: ServerLevel, dim: String, packed: Long) {
        val m = worldForceRefs.getOrPut(dim) { HashMap() }
        val n = m.getOrDefault(packed, 0)
        if (n == 0) level.chunkSource.updateChunkForced(ChunkPos(packed), true)
        m[packed] = n + 1
    }

    /** Drop one ship's hold on a WORLD chunk; only un-forces it once the LAST holder leaves. */
    private fun unforceWorldChunk(level: ServerLevel, dim: String, packed: Long) {
        val m = worldForceRefs[dim] ?: return
        val n = m.getOrDefault(packed, 0)
        if (n <= 1) {
            m.remove(packed)
            if (m.isEmpty()) worldForceRefs.remove(dim)
            level.chunkSource.updateChunkForced(ChunkPos(packed), false)
        } else {
            m[packed] = n - 1
        }
    }

    /**
     * Packed-long ChunkPos set covering the ship's current world AABB, padded by one chunk so the
     * leading edge of a moving ship is loaded before it arrives.
     */
    private fun worldChunksUnder(ship: LoadedServerShip): Set<Long> {
        val aabb = ship.worldAABB
        val minCX = (floor(aabb.minX()).toInt() shr 4) - 1
        val maxCX = (floor(aabb.maxX()).toInt() shr 4) + 1
        val minCZ = (floor(aabb.minZ()).toInt() shr 4) - 1
        val maxCZ = (floor(aabb.maxZ()).toInt() shr 4) + 1
        val out = HashSet<Long>()
        var cx = minCX
        while (cx <= maxCX) {
            var cz = minCZ
            while (cz <= maxCZ) {
                out.add(ChunkPos.asLong(cx, cz))
                cz++
            }
            cx++
        }
        return out
    }

    /**
     * True if any player in [level] is inside or touching [ship]'s world AABB (padded by [OCCUPY_MARGIN]).
     * Cheap: short-circuits when the level has no players.
     */
    private fun playersAboard(ship: LoadedServerShip, level: ServerLevel): Boolean {
        val players = level.players()
        if (players.isEmpty()) return false
        val aabb = ship.worldAABB ?: return false
        val minX = aabb.minX() - OCCUPY_MARGIN
        val maxX = aabb.maxX() + OCCUPY_MARGIN
        val minY = aabb.minY() - OCCUPY_MARGIN
        val maxY = aabb.maxY() + OCCUPY_MARGIN
        val minZ = aabb.minZ() - OCCUPY_MARGIN
        val maxZ = aabb.maxZ() + OCCUPY_MARGIN
        for (p in players) {
            if (p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ) return true
        }
        return false
    }

    /**
     * Synthetic observers (one per active ship, pinned to its centre at distance 0) added to vs-core's
     * player set so its proximity-based load/physics gate keeps each active ship simulating regardless of
     * real-player distance. See [ShipObserverPlayer]. Built fresh each tick; only active ships pay.
     */
    @JvmStatic
    fun activeShipObservers(shipWorld: VsiServerShipWorld, server: MinecraftServer): Set<VsiPlayer> {
        val observers = HashSet<VsiPlayer>()
        val loadedActive = HashSet<Long>()

        // One observer per currently-loaded active ship -- keepActive, seated-piloted, OR a player
        // standing on / inside / touching it. The centre pin (distance 0) keeps vs-core's proximity gate
        // satisfied so the ship stays loaded and stepping. Each keepActive ship's live position is cached
        // for the revival pass below; non-keepActive ships are pruned from the cache.
        for (ship in shipWorld.loadedShips) {
            val keepActive = ship.getAttachment(ShipSettings::class.java)?.keepActive == true
            if (!keepActive) keepActiveCache.remove(ship.id)
            val piloted = ship.getAttachment(SeatedControllingPlayer::class.java) != null
            var occupied = false
            if (!keepActive && !piloted) {
                val level = server.getLevelFromDimensionId(ship.chunkClaimDimension)
                occupied = level != null && playersAboard(ship, level)
            }
            if (!keepActive && !piloted && !occupied) continue
            val aabb = ship.worldAABB
            val cx = (aabb.minX() + aabb.maxX()) * 0.5
            val cy = (aabb.minY() + aabb.maxY()) * 0.5
            val cz = (aabb.minZ() + aabb.maxZ()) * 0.5
            observers.add(ShipObserverPlayer(ship.id, ship.chunkClaimDimension, cx, cy, cz, 0))
            loadedActive.add(ship.id)
            if (keepActive) {
                keepActiveCache[ship.id] = ObserverPos(ship.chunkClaimDimension, cx, cy, cz)
            }
        }

        // Revive keepActive ships that briefly fell out of loadedShips (chunk-save hiccup / autosave /
        // momentary range excursion) using their last live position; prune entries for deleted ships.
        if (keepActiveCache.isNotEmpty()) {
            val iter = keepActiveCache.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key in loadedActive) continue
                if (shipWorld.allShips.getById(entry.key) == null) {
                    iter.remove()
                    continue
                }
                val pos = entry.value
                observers.add(ShipObserverPlayer(entry.key, pos.dimensionId, pos.x, pos.y, pos.z, 0))
            }
        }
        return observers
    }
}
