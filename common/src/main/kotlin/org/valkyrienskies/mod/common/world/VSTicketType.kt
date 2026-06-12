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
     * Ticket for a ship's own SHIPYARD chunks (its blocks), placed by [ShipActivationManager] on EVERY
     * active chunk of an active ship — not just the subset vs-core's watch system tickets.
     *
     * vs-core only physics-steps a ship while [org.valkyrienskies.core.impl.api.ServerShipInternal.areVoxelsFullyLoaded]
     * is true, i.e. NONE of the ship's active shipyard chunks are unloaded (an unloaded active chunk is
     * added to ShipData.missingLoadedChunks, which gates the step). A large ship spans many shipyard
     * chunks; the watch system only loads the ones near a watcher, so a big craft's bow/stern chunks
     * unload and the WHOLE ship freezes (the size-dependent cruise stall — small ships fit in the watch
     * radius and never lose a chunk). This radius-0 (level 33 FULL) ticket pins them all loaded.
     *
     * Deliberately a SEPARATE type from [SHIP_CHUNK] so it doesn't collide with ChunkManagement's
     * watch-driven SHIP_CHUNK add/remove (a shared type dedupes to one ticket, so an unwatch-remove
     * would drop the chunk we're trying to keep). The manager releases these as the ship deactivates.
     */
    @JvmField
    val SHIP_ACTIVE_VOXEL: TicketType = TicketType.register(
        "vs_ship_active_voxel", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING or TicketType.FLAG_SIMULATION
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
