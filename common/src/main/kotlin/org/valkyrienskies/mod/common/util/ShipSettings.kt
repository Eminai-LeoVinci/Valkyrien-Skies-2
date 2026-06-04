package org.valkyrienskies.mod.common.util

import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.config.ShipRenderer

/**
 * A attachment that stores ship specific settings.
 */
data class ShipSettings(
    /**
     * Should the ship chunks try to generate? If true it will generate chunks in the shipyard.
     * You normally don't want this EVER
     */
    var shouldGenerateChunks: Boolean = false,

    /**
     * If true this ship will change dimensions when it touches a portal
     */
    var changeDimensionOnTouchPortals: Boolean = true,

    /**
     * If true, VS2 keeps this ship's world-position chunks force-ticked every server tick, so the
     * ship keeps simulating (physics, cruise/autopilot, machinery) even when no player is nearby —
     * independent of the vanilla "Simulation Distance" video setting.
     *
     * Background: a VS2 ship only physics-ticks while its WORLD position sits in a ticking chunk
     * (i.e. within a player's vanilla simulation distance). [shipLoadDistance] only force-loads the
     * shipyard chunks, not the ship's world-position chunks, which is why raising it never kept an
     * unattended ship moving. When this flag is set, [ShipActivationManager] follows the ship and
     * force-ticks the chunks under it so simulation never pauses. Costs CPU only for flagged ships,
     * so leave it off for parked ships and on for ones you want to keep flying unattended.
     *
     * Persisted per ship. Toggle in-game with `/vs set-keep-active <ships> <true|false>`.
     */
    var keepActive: Boolean = false
)

@OptIn(VsBeta::class)
val LoadedServerShip.settings: ShipSettings
    get() = getAttachment(ShipSettings::class.java) ?: ShipSettings().also { setAttachment(it) }

data class ClientShipSettings(
    /**
     * If null it will use the default
     */
    var renderer: ShipRenderer? = null
)

val ClientShip.settings: ClientShipSettings
    get() = ClientShipSettings() //TODO have a way to store/pull from server a per ship client preference
