package org.valkyrienskies.mod.common.util

import com.fasterxml.jackson.annotation.JsonIgnore
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.common.config.VSGameConfig

/**
 * Makes a water-borne ship physically ride ocean waves (heave + pitch + roll) in sync with the
 * waves the shader draws, by sampling [OceanWaveField] at a grid of hull points and applying
 * buoyancy forces at those model positions. Because the force flows through VS2's own transform,
 * the existing [EntityDragger] carries players/mobs with the bob — fixing the "player hovers while
 * the ship bobs" desync you get when an external mod (Physics Mod) moves the ship from outside.
 *
 * Force model (per hull sample point): a symmetric spring toward the LOCAL wave surface deviation
 * plus vertical-velocity damping, scaled by the ship's mass. Using the wave's deviation-from-mean
 * (not the point's absolute Y) means we add only the oscillation and don't fight VS2's baseline
 * buoyancy that keeps the ship floating at mean sea level. Off-center points naturally produce
 * pitch and roll as the ship straddles a wave.
 *
 * Registered on every ship beside [BuoyancyHandlerAttachment]; no-op unless enabled in config and
 * the ship is actually in liquid. Defensive: any error disables wave buoyancy for that ship only,
 * instead of crashing the physics thread.
 */
class WaveBuoyancyAttachment : ShipPhysicsListener {

    @JsonIgnore
    internal var ship: LoadedServerShip? = null

    /** Set after an error to keep THIS ship's wave forces off without crashing the physics thread. */
    @JsonIgnore
    private var disabled = false

    // Scratch vectors reused across the sample grid. Safe for the position argument because
    // vs-core's PhysShipImpl.applyWorldForceToModelPos copies it (new Vector3d(pos).sub(...));
    // the FORCE vector is queued by reference (invPosForces.add) and must stay a fresh allocation.
    @JsonIgnore
    private val scratchModelPos = Vector3d()

    @JsonIgnore
    private val scratchWorldPos = Vector3d()

    // Per-point wave deviations cached between the two passes (sampleGrid is clamped to 8).
    @JsonIgnore
    private val scratchDeviations = DoubleArray(64)

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (disabled) return
        val cfg = VSGameConfig.SERVER.OceanWaves
        if (!cfg.enableWaveBuoyancy) return
        // Only ships touching liquid should bob.
        if (physShip.liquidOverlap <= 0.0) return

        val ship = ship ?: return
        try {
            val mass = physShip.mass
            if (mass <= 0.0) return

            val aabb = ship.shipAABB ?: return
            val minX = aabb.minX().toDouble()
            val maxX = aabb.maxX().toDouble()
            val minZ = aabb.minZ().toDouble()
            val maxZ = aabb.maxZ().toDouble()
            // Sample at the hull's vertical middle; only the horizontal spread matters for pitch/roll.
            val midY = (aabb.minY().toDouble() + aabb.maxY().toDouble()) * 0.5

            val transform = physShip.transform
            val shipToWorld = transform.shipToWorld
            val center = transform.positionInWorld
            val vel = physShip.velocity
            val omega = physShip.omega

            val n = cfg.sampleGrid.coerceIn(1, 8)
            val pointMass = mass / (n * n)
            val stiffness = cfg.stiffness
            val damping = cfg.damping

            // Big ships heave less and more slowly: attenuate the common-mode (average) wave
            // forcing by hull footprint. The differential part -- what tilts the ship into pitch
            // and roll -- stays at full strength, so only the up/down ride height calms down.
            // (Same forcing frequency, weaker amplitude against unchanged damping = the slower,
            // smaller response of a heavy hull; no per-ship wave clock, so the visible shader
            // swell stays in sync.)
            val footprint = maxOf(maxX - minX, maxZ - minZ)
            val heaveStart = cfg.heaveAttenuationStartSize
            val heaveEnd = cfg.heaveAttenuationEndSize.coerceAtLeast(heaveStart + 1e-9)
            val heaveMin = cfg.heaveAttenuationMinScale.coerceIn(0.0, 1.0)
            val t = ((footprint - heaveStart) / (heaveEnd - heaveStart)).coerceIn(0.0, 1.0)
            val heaveScale = 1.0 - (1.0 - heaveMin) * t * t * (3.0 - 2.0 * t)

            // Pass 1: sample the wave field once per grid point and accumulate the average.
            var deviationSum = 0.0
            for (ix in 0 until n) {
                for (iz in 0 until n) {
                    val fx = if (n == 1) 0.5 else ix.toDouble() / (n - 1)
                    val fz = if (n == 1) 0.5 else iz.toDouble() / (n - 1)
                    val mx = minX + (maxX - minX) * fx
                    val mz = minZ + (maxZ - minZ) * fz

                    val worldPos = shipToWorld.transformPosition(mx, midY, mz, scratchWorldPos)

                    // Wave deviation from mean sea level at this point's world XZ.
                    val deviation = OceanWaveField.height(worldPos.x, worldPos.z)
                    scratchDeviations[ix * n + iz] = deviation
                    deviationSum += deviation
                }
            }
            val deviationAvg = deviationSum / (n * n)

            // Pass 2: apply forces with the heave (average) component attenuated.
            for (ix in 0 until n) {
                for (iz in 0 until n) {
                    val fx = if (n == 1) 0.5 else ix.toDouble() / (n - 1)
                    val fz = if (n == 1) 0.5 else iz.toDouble() / (n - 1)
                    val mx = minX + (maxX - minX) * fx
                    val mz = minZ + (maxZ - minZ) * fz

                    val modelPos = scratchModelPos.set(mx, midY, mz)
                    val worldPos = shipToWorld.transformPosition(mx, midY, mz, scratchWorldPos)

                    val deviation = scratchDeviations[ix * n + iz]
                    val forcing = deviationAvg * heaveScale + (deviation - deviationAvg)

                    // Vertical velocity at this point = vel.y + (omega x r).y, r = worldPos - center.
                    val rx = worldPos.x - center.x()
                    val rz = worldPos.z - center.z()
                    val pointVelY = vel.y() + (omega.z() * rx - omega.x() * rz)

                    val force = (forcing * stiffness - pointVelY * damping) * pointMass
                    if (force.isFinite()) {
                        physShip.applyWorldForceToModelPos(Vector3d(0.0, force, 0.0), modelPos)
                    }
                }
            }
        } catch (t: Throwable) {
            disabled = true
            LOGGER.error("[vs wave-buoyancy] disabled for ship ${ship.id} after error in physTick", t)
        }
    }

    companion object {
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("vs-wave-buoyancy")
    }
}
