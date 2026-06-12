package org.valkyrienskies.mod.common.util

import org.valkyrienskies.core.api.world.properties.DimensionId
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
 * about +/- waveHeight/2) at a world XZ position and the current wave-clock time.
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
    private const val MAX_ITERATIONS = 40

    /** A physics dimension that goes this long without ticking loses ownership of the wave clock. */
    private const val DRIVER_TIMEOUT_NANOS = 1_000_000_000L

    // The per-iteration wave direction and the frequency/speed/weight geometric series depend only
    // on the iteration index, never on position or time, so they are tabulated once. This removes
    // the direction sin/cos pair (2 of the 5 transcendentals per iteration) and the running
    // multiplies from every height() sample.
    private val DIR_X = DoubleArray(MAX_ITERATIONS)
    private val DIR_Z = DoubleArray(MAX_ITERATIONS)
    private val FREQ = DoubleArray(MAX_ITERATIONS)
    private val SPD = DoubleArray(MAX_ITERATIONS)
    private val WGT = DoubleArray(MAX_ITERATIONS)

    /** WAVE_SUM[n] = sum of the first n weights — the normalization denominator for n iterations. */
    private val WAVE_SUM = DoubleArray(MAX_ITERATIONS + 1)

    init {
        var iter = 0.0
        var frequency = FREQUENCY
        var speed = SPEED
        var weight = 1.0
        for (i in 0 until MAX_ITERATIONS) {
            DIR_X[i] = sin(iter)
            DIR_Z[i] = cos(iter)
            FREQ[i] = frequency
            SPD[i] = speed
            WGT[i] = weight
            WAVE_SUM[i + 1] = WAVE_SUM[i] + weight
            iter += ITER_INC
            weight *= WEIGHT
            frequency *= FREQUENCY_MULT
            speed *= SPEED_MULT
        }
    }

    /**
     * Immutable per-phys-tick snapshot of the live config plus the derived wave time, so the
     * n*n [height] samples each tick read consistent values instead of re-reading mutable config
     * fields, and a config edit mid-tick can't tear a sample.
     */
    private class Params(
        @JvmField val waveHeight: Double,
        @JvmField val xzScale: Double,
        @JvmField val offsetX: Double,
        @JvmField val offsetZ: Double,
        @JvmField val iterations: Int,
        @JvmField val modifiedTime: Double,
    )

    /** Monotonic wave clock, advanced once per physics frame from the global phys-tick event. */
    private var time = 0.0

    @Volatile
    private var params = snapshot(0.0)

    @Volatile
    private var driverDimension: DimensionId? = null

    @Volatile
    private var lastAdvanceNanos = 0L

    /**
     * Advance the wave clock for one physics frame. The phys-tick event fires once per physics
     * dimension per frame; without the driver gate the clock would advance N× real time with N
     * physics dimensions loaded and the physical waves would outrun the visible ones. The first
     * dimension to tick owns the clock; if it stops ticking (unloaded), another takes over after
     * [DRIVER_TIMEOUT_NANOS].
     */
    fun advanceTime(dimensionId: DimensionId, deltaSeconds: Double) {
        val now = System.nanoTime()
        val driver = driverDimension
        if (driver != dimensionId) {
            if (driver != null && now - lastAdvanceNanos < DRIVER_TIMEOUT_NANOS) return
            driverDimension = dimensionId
        }
        lastAdvanceNanos = now
        // guard against pause/teleport spikes
        if (deltaSeconds in 0.0..1.0) time += deltaSeconds
        params = snapshot(time)
    }

    private fun snapshot(time: Double): Params {
        val cfg = VSGameConfig.SERVER.OceanWaves
        return Params(
            waveHeight = cfg.waveHeight,
            xzScale = XZ_SCALE * cfg.horizontalScale,
            offsetX = cfg.offsetX,
            offsetZ = cfg.offsetZ,
            iterations = cfg.iterations.coerceIn(1, MAX_ITERATIONS),
            modifiedTime = (time + cfg.phaseOffset) * cfg.waveSpeed * TIME_MULT,
        )
    }

    /**
     * Vertical wave deviation (blocks) from mean sea level at world ([worldX], [worldZ]).
     * Positive = crest above mean, negative = trough below mean.
     */
    fun height(worldX: Double, worldZ: Double): Double {
        val p = params
        val oceanHeight = p.waveHeight
        if (oceanHeight <= 0.0) return 0.0

        var px = (worldX - p.offsetX) * p.xzScale
        var pz = (worldZ - p.offsetZ) * p.xzScale

        val modifiedTime = p.modifiedTime
        val iterations = p.iterations
        var heightSum = 0.0
        for (i in 0 until iterations) {
            val dirX = DIR_X[i]
            val dirZ = DIR_Z[i]
            val weight = WGT[i]
            val x = (dirX * px + dirZ * pz) * FREQ[i] + modifiedTime * SPD[i]
            val wave = exp(sin(x) - 1.0)
            val forceMag = wave * cos(x) * weight
            px -= forceMag * dirX * DRAG_MULT
            pz -= forceMag * dirZ * DRAG_MULT
            heightSum += wave * weight
        }

        // center on 0 like the shader (it subtracts oceanHeight*0.5); WAVE_SUM[n] >= 1 for n >= 1
        return heightSum / WAVE_SUM[iterations] * oceanHeight - oceanHeight * 0.5
    }
}
