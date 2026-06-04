package org.valkyrienskies.mod.common.world

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import org.valkyrienskies.core.api.ships.LoadedServerShip
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
 * A VS2 ship lives as blocks in far-away shipyard chunks, but vs-core only physics-ticks a ship
 * while the ship's WORLD position sits in a ticking chunk — i.e. within some player's vanilla
 * "Simulation Distance". Fly an autopilot ship past that radius and it freezes, even though its
 * shipyard chunks stay loaded (those are kept at BLOCK_TICKING by MixinChunkHolder, which is why
 * the helm/redstone keep ticking but the ship still stops moving). [VSCoreConfig].shipLoadDistance
 * only force-loads shipyard chunks, never the world-position chunks, so raising it does not help.
 *
 * ## What it does
 * Once per server tick, for every ship that is "active" — either its persisted
 * [ShipSettings.keepActive] flag is set, or control logic called [markControlled] for it this
 * interval (e.g. Eureka cruise/pilot) — this manager force-ticks the real-world chunks the ship
 * currently overlaps, using a [VSTicketType.SHIP_ACTIVE_WORLD] ticket at [ACTIVE_TICKET_RADIUS]
 * (BLOCK_TICKING). That keeps the ship's full footprint isPositionTicking, which fixes the
 * off-centre-helm freeze. NOTE: it does NOT keep a far, player-less ship simulating — vs-core gates
 * physics on player proximity, not chunk ticking (see [ACTIVE_TICKET_RADIUS]). The set of
 * chunks is recomputed each tick and follows the moving ship; stale chunks are released, so nothing
 * is force-loaded behind the ship and nothing leaks across restarts (these are plain tickets, not
 * vanilla /forceload).
 *
 * Cost scales with (active ship count x footprint), so idle/parked ships pay nothing.
 */
object ShipActivationManager {

    private val logger by logger()

    /**
     * Chunk-ticket radius for an active ship's WORLD chunks. radius 1 -> ticket level 32
     * (BLOCK_TICKING), making the ship's full world footprint report isPositionTicking. That is what
     * the piloted/near case needs (a large ship flown from an off-centre helm whose centre chunk
     * would otherwise fall outside the pilot's sim distance — the original freeze this manager fixed).
     *
     * It does NOT keep a far, player-less ship simulating: vs-core gates ship physics on PLAYER
     * PROXIMITY (its own player set, ~[VSCoreConfig] shipLoadDistance), not on chunk-ticking level.
     * Bumping this to entity-ticking (radius 2) was tried and made zero difference at range, so it is
     * back at 1 to avoid pointless entity-tick cost. See [VSTicketType.SHIP_ACTIVE_WORLD].
     */
    private const val ACTIVE_TICKET_RADIUS = 1

    /**
     * Ships flagged "being controlled" by external logic since the last [tick] sweep. Writers may
     * be on the physics thread (e.g. Eureka's physTick), so this is concurrent; [tick] drains it
     * each server tick (heartbeat — a ship must be re-marked every tick to stay control-active,
     * which means it auto-releases the moment control logic stops running for it).
     */
    private val controlledHeartbeat: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** ship id -> (dimension id, world chunk positions [packed long] we currently force-tick). */
    private val forced = HashMap<Long, ForcedEntry>()

    private class ForcedEntry(val dimensionId: String) {
        val chunks = HashSet<Long>()
    }

    /**
     * Mark [shipId] as actively controlled for this server-tick interval, keeping it simulating
     * even with no player nearby. Safe to call from any thread; must be called every tick to keep
     * the ship active (heartbeat). Used by control attachments (e.g. Eureka cruise/autopilot).
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

        val activeIds = HashSet<Long>()

        for (ship in shipWorld.loadedShips) {
            val manual = ship.getAttachment(ShipSettings::class.java)?.keepActive == true
            // A seated player actively piloting the ship keeps it active too. vs-core otherwise stops
            // ticking a ship once its centre-of-mass chunk leaves every player's simulation distance,
            // which freezes a large ship flown from an off-centre helm (the pilot sits far from the
            // centre, so the centre's chunk can fall outside their sim distance). The
            // SeatedControllingPlayer attachment is present the whole time a player is seated
            // controlling (set by the driving-packet handler, cleared on dismount), and that handler
            // runs even while the ship is frozen -- so this both prevents and recovers the freeze.
            val piloted = ship.getAttachment(SeatedControllingPlayer::class.java) != null
            if (!manual && !piloted && ship.id !in controlled) continue

            val level = server.getLevelFromDimensionId(ship.chunkClaimDimension) ?: continue
            activeIds.add(ship.id)

            val desired = worldChunksUnder(ship)
            val firstActivation = ship.id !in forced
            val entry = forced.getOrPut(ship.id) { ForcedEntry(ship.chunkClaimDimension) }

            // Add tickets for newly-overlapped chunks.
            for (packed in desired) {
                if (entry.chunks.add(packed)) {
                    level.chunkSource.addTicketWithRadius(VSTicketType.SHIP_ACTIVE_WORLD, ChunkPos(packed), ACTIVE_TICKET_RADIUS)
                }
            }
            // Release tickets for chunks the ship has moved off of.
            val iter = entry.chunks.iterator()
            while (iter.hasNext()) {
                val packed = iter.next()
                if (packed !in desired) {
                    level.chunkSource.removeTicketWithRadius(VSTicketType.SHIP_ACTIVE_WORLD, ChunkPos(packed), ACTIVE_TICKET_RADIUS)
                    iter.remove()
                }
            }

            if (firstActivation) {
                val reason = if (manual) "keepActive" else if (piloted) "piloted" else "controlled"
                logger.info("Keeping ship ${ship.id} active ($reason) — ${entry.chunks.size} world chunks force-ticked")
            }
        }

        // Fully release ships that are no longer active (flag cleared, control stopped, or unloaded).
        if (forced.isNotEmpty()) {
            val gone = forced.keys.filter { it !in activeIds }
            for (id in gone) {
                release(id, server)
                logger.info("Ship $id no longer kept active — released world-chunk tickets")
            }
        }
    }

    /** Remove every world-chunk ticket for [id] and forget it. */
    private fun release(id: Long, server: MinecraftServer) {
        val entry = forced.remove(id) ?: return
        val level = server.getLevelFromDimensionId(entry.dimensionId) ?: return
        for (packed in entry.chunks) {
            level.chunkSource.removeTicketWithRadius(VSTicketType.SHIP_ACTIVE_WORLD, ChunkPos(packed), 1)
        }
    }

    /** Release everything (call on server shutdown, before MC's chunk-drain loop). */
    @JvmStatic
    fun clearAll(server: MinecraftServer) {
        for (id in forced.keys.toList()) {
            release(id, server)
        }
        controlledHeartbeat.clear()
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
     * Synthetic observers (one per active ship) to add to vs-core's player set so its
     * proximity-based load/physics gate keeps each active ship simulating regardless of real-player
     * distance. vs-core has no working per-ship force-load in this build and gates physics on player
     * proximity, so a far, player-less active ship only keeps moving if it thinks a player is on it.
     * See [ShipObserverPlayer]. Built fresh each tick from current positions; only active ships pay.
     */
    @JvmStatic
    fun activeShipObservers(shipWorld: VsiServerShipWorld): Set<VsiPlayer> {
        val observers = HashSet<VsiPlayer>()
        for (ship in shipWorld.loadedShips) {
            if (!isPersistentlyActive(ship)) continue
            val aabb = ship.worldAABB
            observers.add(
                ShipObserverPlayer(
                    ship.id,
                    ship.chunkClaimDimension,
                    (aabb.minX() + aabb.maxX()) * 0.5,
                    (aabb.minY() + aabb.maxY()) * 0.5,
                    (aabb.minZ() + aabb.maxZ()) * 0.5,
                )
            )
        }
        return observers
    }

    /**
     * keepActive flag set, or a player actively seated-controlling it. (Heartbeat-controlled ships
     * are handled in [tick]; markControlled is currently unused, so it is omitted here.)
     */
    private fun isPersistentlyActive(ship: LoadedServerShip): Boolean =
        ship.getAttachment(ShipSettings::class.java)?.keepActive == true ||
            ship.getAttachment(SeatedControllingPlayer::class.java) != null
}
