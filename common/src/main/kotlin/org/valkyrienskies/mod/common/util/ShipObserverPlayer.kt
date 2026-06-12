package org.valkyrienskies.mod.common.util

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.core.internal.world.VsiPlayer
import org.valkyrienskies.core.internal.world.VsiPlayerState
import java.util.UUID

/**
 * A synthetic, non-game [VsiPlayer] pinned to the current world position of an "always active" ship.
 *
 * vs-core decides which ships to keep loaded and physics-tick from its player set + proximity (its
 * own shipLoadDistance), NOT from vanilla simulation distance or chunk tickets -- and its per-ship
 * `forceWatchingShips` override is dead code in the bundled vs-core build (defined in the API,
 * referenced by none of its impl classes). So the only way to keep a far, player-less ship
 * simulating is to make vs-core believe a player is sitting right on it.
 * [org.valkyrienskies.mod.common.world.ShipActivationManager] builds one of these per active ship
 * each tick and adds them to the set handed to `setPlayers`.
 *
 * It is deliberately NOT a [MinecraftPlayer]: every VS2 path that sends/tracks per real client
 * guards `is MinecraftPlayer` (and `VSFabricNetworking.sendToClient` drops non-[MinecraftPlayer]),
 * so this observer only ever feeds vs-core's proximity gate -- nothing is networked to it. Recreated
 * each tick at the ship's latest centre, so it follows the moving ship.
 */
class ShipObserverPlayer(
    private val shipId: ShipId,
    private val dimensionId: DimensionId,
    private val posX: Double,
    private val posY: Double,
    private val posZ: Double,
    /**
     * Distinguishes the multiple observers [org.valkyrienskies.mod.common.world.ShipActivationManager]
     * spreads across one ship's footprint -- it's the grid-cell index, mixed into [uuid] so each cell
     * is a distinct synthetic player (vs-core dedupes players by uuid).
     */
    private val generation: Int = 0,
) : VsiPlayer {

    override val uuid: UUID = observerUuid(shipId, generation)

    override val isAdmin: Boolean get() = false

    override val canModifyServerConfig: Boolean get() = false

    override val dimension: DimensionId get() = dimensionId

    override val forceWatchingShips: Set<ShipId> = emptySet()

    override fun getPosition(dest: Vector3d): Vector3d = dest.set(posX, posY, posZ)

    // mountedShip is left null on purpose. VSPhysicsPipelineStage keeps a ship's voxels loaded in Krunch
    // only within a 3x3x3 chunk box around each player's *getPos()* (a mounted player is instead mapped
    // onto a single point of its ship). ShipActivationManager spreads MANY of these observers across a
    // ship's world footprint so their boxes tile the whole hull -- that only works if each observer's box
    // sits at its own position, so we keep getPlayerState's position = getPos() and mountedShip null.
    override fun getPlayerState(): VsiPlayerState =
        VsiPlayerState(Vector3d(posX, posY, posZ), Vector3d(), dimensionId, null, null)

    override fun hashCode(): Int = uuid.hashCode()

    override fun equals(other: Any?): Boolean = other is ShipObserverPlayer && other.uuid == uuid

    companion object {
        /**
         * Stable per-(ship,generation) UUID in a private namespace, so it never collides with a real
         * player's. [generation] lets the manager mint a fresh identity for a frozen ship to force a
         * vs-core watcher re-arrival (see the [generation] ctor param).
         */
        fun observerUuid(shipId: ShipId, generation: Int = 0): UUID =
            UUID.nameUUIDFromBytes("valkyrienskies:ship-observer:$shipId:$generation".toByteArray())
    }
}
