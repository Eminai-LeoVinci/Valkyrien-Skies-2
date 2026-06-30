package org.valkyrienskies.mod.client

/**
 * Transient (non-persisted) client-side debug-render toggles. These are deliberately NOT in any config file --
 * they reset to false on every launch and are flipped at runtime by "/vs ..." client commands.
 */
object ShipDebugRender {
    /**
     * When true, every loaded ship's influence border (its ship-space AABB inflated per-FACE by
     * VSClientConfig.CLIENT.influenceExtend{Left,Right,Bottom,Top,Back,Front} -- the exact region EntityDragger uses to
     * keep an airborne player carried) is drawn as a thin blue oriented wireframe. Toggled by "/vs influence-border <bool>".
     */
    @JvmField
    var influenceBorder = false
}
