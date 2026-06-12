package org.valkyrienskies.mod.common.util

import org.valkyrienskies.mod.common.config.VSGameConfig
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * CPU port of the canonical Physics-Mod ocean wave function (the Gerstner-style
 * `physics_waveHeight` that shaderpacks implement in `oceans.glsl` for PHYSICS_OCEAN support).
 *
 * Because the SAME math drives the visible water surface in the shader, sampling it here lets a
 * VS2 ship physically bob/pitch/roll in sync with the waves the player actually sees — and, unlike
 * Physics Mod bobbing the ship from outside, this rides VS2's own transform so [EntityDragger]
 * carries riders along (no more "hovering player").
 *
 * [height] returns the wave's vertical deviation from mean sea level (roughly centered on 0, range
 * about +/- waveHeight/2) at a world XZ position and the current [time].
 *
 * Constants are taken verbatim from the shader's `oceans.glsl`; the per-instance scale/height/speed
 * are exposed through [VSGameConfig] so they can be matched to the active Physics Mod ocean settings.
 *
 * Phase 2 (future): if Physics Mod is present, reflect its own CPU-side ocean height for a
 * pixel-perfect time/offset match (Voxy-style); this port is the always-available fallback.
 */
object OceanWaveField {

    // --- canonical Physics Mod ocean constants (oceans.glsl) ---
    private const val DRAG_MULT = 0.048
    private const val XZ_SCALE = 0.035
    private const val TIME_MULT = 0.45
    private const val FREQUENCY = 6.0
    private const val SPEED = 2.0
    private const val WEIGHT = 0.8
    private const val FREQUENCY_MULT = 1.18
    private const val SPEED_MULT = 1.07
    private const val ITER_INC = 12.0

    /** Monotonic wave clock, advanced once per physics frame from the global phys-tick event. */
    @Volatile
    private var time = 0.0

    fun advanceTime(deltaSeconds: Double) {
        // guard against pause/teleport spikes
        if (deltaSeconds in 0.0..1.0) time += deltaSeconds
    }

    /**
     * Vertical wave deviation (blocks) from mean sea level at world ([worldX], [worldZ]).
     * Positive = crest above mean, negative = trough below mean.
     */
    fun height(worldX: Double, worldZ: Double): Double {
        val cfg = VSGameConfig.SERVER.OceanWaves
        val oceanHeight = cfg.waveHeight
        if (oceanHeight <= 0.0) return 0.0

        var px = (worldX - cfg.offsetX) * XZ_SCALE * cfg.horizontalScale
        var pz = (worldZ - cfg.offsetZ) * XZ_SCALE * cfg.horizontalScale

        var iter = 0.0
        var frequency = FREQUENCY
        var speed = SPEED
        var weight = 1.0
        var heightSum = 0.0
        var waveSum = 0.0
        val modifiedTime = (time + cfg.phaseOffset) * cfg.waveSpeed * TIME_MULT

        val iterations = cfg.iterations.coerceIn(1, 40)
        for (i in 0 until iterations) {
            val dirX = sin(iter)
            val dirZ = cos(iter)
            val x = (dirX * px + dirZ * pz) * frequency + modifiedTime * speed
            val wave = exp(sin(x) - 1.0)
            val result = wave * cos(x)
            val forceMag = result * weight
            px -= forceMag * dirX * DRAG_MULT
            pz -= forceMag * dirZ * DRAG_MULT

            heightSum += wave * weight
            iter += ITER_INC
            waveSum += weight
            weight *= WEIGHT
            frequency *= FREQUENCY_MULT
            speed *= SPEED_MULT
        }

        if (waveSum <= 0.0) return 0.0
        // center on 0 like the shader (it subtracts oceanHeight*0.5)
        return heightSum / waveSum * oceanHeight - oceanHeight * 0.5
    }
}
