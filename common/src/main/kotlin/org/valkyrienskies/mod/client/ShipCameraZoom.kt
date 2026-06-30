package org.valkyrienskies.mod.client

import org.valkyrienskies.mod.common.config.VSClientConfig

/**
 * Scroll-wheel zoom state for the ship-mounted third-person camera.
 *
 * The ship view sets its own baseline pullback from the ship's size (see MixinCamera's
 * setupWithShipMounted); this multiplier scales that baseline. 1.0 = exactly the ship-set
 * distance (the configured minimum -- there is deliberately no zooming IN past it), and the
 * configured maximum (default 2.0) caps how far out the player can scroll. Hotbar scrolling is
 * useless while mounted, so MixinMouseHandler redirects the wheel here while the ship camera
 * is active; the multiplier resets when the player dismounts.
 */
object ShipCameraZoom {

    /** Notches of scroll-out applied for a full min-to-max sweep. */
    private const val STEPS = 8.0

    private var multiplier = 1.0

    /**
     * True only while the ship-mounted third-person camera is actually being rendered (set each
     * frame by MixinGameRenderer, the one place that decides). The scroll handler keys off this
     * so the wheel zooms in exactly the views where MixinCamera applies the multiplier -- standing
     * helm FRONT slot and any sitting-helm third person -- and falls through to the hotbar
     * everywhere else.
     */
    @Volatile
    private var shipCameraActive = false

    @JvmStatic
    fun setShipCameraActive(active: Boolean) {
        shipCameraActive = active
    }

    @JvmStatic
    fun isShipCameraActive(): Boolean = shipCameraActive

    private val minZoom: Double get() = VSClientConfig.CLIENT.shipCameraZoomMin.coerceAtLeast(0.1)
    private val maxZoom: Double get() = VSClientConfig.CLIENT.shipCameraZoomMax.coerceAtLeast(minZoom)

    /** Current multiplier, re-clamped live so config edits apply without rescrolling. */
    @JvmStatic
    fun getMultiplier(): Double = multiplier.coerceIn(minZoom, maxZoom)

    /** Scroll up (+1 notch) zooms in toward the ship-set baseline; down (-1) zooms out. */
    @JvmStatic
    fun scroll(notches: Double) {
        val step = (maxZoom - minZoom) / STEPS
        multiplier = (getMultiplier() - notches * step).coerceIn(minZoom, maxZoom)
    }

    @JvmStatic
    fun reset() {
        multiplier = minZoom
        shipCameraActive = false
    }
}
