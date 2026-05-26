package org.valkyrienskies.mod.common.world

import net.minecraft.server.level.TicketType

/**
 * Custom ticket type for ship chunks. Used with radius 0, giving ticket level 33 (FULL status).
 *
 * This loads ONLY the requested chunk with zero neighbor chunks:
 * - Vanilla FORCED ticket: level 31 (entity ticking) = 2-chunk radius = ~25 chunks per ship chunk
 * - Previous VS2 ticket: radius 1 (level 32, ticking) = 1-chunk radius = ~9 chunks per ship chunk
 * - Current VS2 ticket: radius 0 (level 33, FULL) = 0-chunk radius = 1 chunk per ship chunk
 *
 * For 100 ships this means loading 100 chunks instead of 900 (or 2500 vanilla) — a 9x improvement.
 * FULL status is sufficient for shipyard chunks: block reads, block entities, and terrain updates
 * all work at FULL status. Entity ticking and block ticking are not needed in the shipyard.
 */
object VSTicketType {
    // 1.21.11: TicketType is no longer generic and TicketType.create() is gone;
    // use TicketType.register(name, timeout, flags) instead. We want chunks to load
    // and simulate (block ticks/updates), so OR both flags.
    @JvmField
    val SHIP_CHUNK: TicketType = TicketType.register(
        "vs_ship_chunk", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING or TicketType.FLAG_SIMULATION
    )

    /**
     * Forces this object's class-init so [SHIP_CHUNK] is registered.
     *
     * 1.21.11: TicketType.register writes to BuiltInRegistries.TICKET_TYPE, which freezes
     * after mod init. This object must be initialized inside the mod-init window, not lazily
     * the first time a ship chunk is ticketed (which happens mid-tick, long after the freeze,
     * and crashes with "Registry is already frozen"). Call this from the mod initializer.
     */
    @JvmStatic
    fun init() {
    }
}
