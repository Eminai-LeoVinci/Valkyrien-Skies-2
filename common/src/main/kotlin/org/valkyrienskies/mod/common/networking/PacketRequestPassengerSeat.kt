package org.valkyrienskies.mod.common.networking

import org.valkyrienskies.core.impl.networking.simple.SimplePacket

/**
 * C2S: the player pressed the sit-down hotkey (VSKeyBindings.shipSeat). Carries no real payload (a
 * data class needs one property, hence the placeholder) -- the server derives the ship and the seat
 * position from ITS OWN view of the sender's ship carry, so a modified client can't seat itself
 * anywhere it isn't actually standing.
 */
data class PacketRequestPassengerSeat(
    val unused: Boolean = false,
) : SimplePacket
