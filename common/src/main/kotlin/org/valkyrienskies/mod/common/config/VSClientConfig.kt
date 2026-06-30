package org.valkyrienskies.mod.common.config

/**
 * Client-side VS2 settings loaded from `config/valkyrienskies_client.json` by [VSClientConfigLoader].
 *
 * VS2's primary config ([VSGameConfig]) is TOML/Forge-Config-API-Port backed. This small JSON config
 * -- mirroring Eureka's `vs_eureka.json` loader -- is how the influence-border extension becomes
 * editable without recompiling, and how the `/vs expand-influence` / `/vs contract-influence` commands
 * persist their live tuning across restarts.
 *
 * NOTE (1.21.1 port): the upstream 1.21.11 [VSClientConfig] also carries `shipRenderDistance` (ship
 * draw-distance far-clip extension). That FEATURE is not ported to 1.21.1 yet (no far-clip-plane mixin),
 * so its field is intentionally omitted here rather than added as dead no-op config. Re-add it alongside
 * the feature port. The `shipCameraZoomMin/Max` fields below ARE ported (see [ShipCameraZoom]).
 */
object VSClientConfig {

    @JvmField
    val CLIENT = Client()

    class Client {
        /**
         * Zoom-in limit for the ship-mounted third-person camera, as a multiplier on the camera
         * distance the ship sets from its size. 1.0 = the ship-set baseline; with the default 0.125
         * the scroll wheel can zoom in to one-eighth of that baseline distance.
         */
        var shipCameraZoomMin: Double = 0.125

        /**
         * Zoom-out limit for the ship-mounted third-person camera (scroll wheel while the ship
         * view is active). 2.0 = up to twice the ship-set camera distance.
         */
        var shipCameraZoomMax: Double = 2.0

        // Ship "influence" border extension, in blocks, added OUTWARD to each of the ship's six faces
        // independently. A player who jumps/walks off a moving ship (or creative-flies / elytra-glides off it)
        // keeps being carried with the ship until they leave this border; raising a value lets them drift farther
        // past that face before the ship releases them. 0 on a face = the exact ship dimension there (no lip).
        // Touching non-ship ground or water releases the carry immediately regardless of these values.
        //
        // Ship-space axis -> face: -X = Left / +X = Right, -Y = Bottom / +Y = Top (gravity-aligned, certain),
        // -Z = Back / +Z = Front. Top/Bottom are unambiguous; Left/Right and Front/Back are nominal axis-sign
        // labels -- if in-world a pair reads reversed, just swap the two values, it's purely cosmetic naming.
        //
        // (These live in the client JSON because on a single-player world the integrated server reads these same
        // values live each tick via EntityDragger, so editing them here -- or via the /vs influence commands --
        // takes effect immediately, no reassemble or relog.)
        var influenceExtendLeft: Double = 2.0
        var influenceExtendRight: Double = 2.0
        var influenceExtendBottom: Double = 2.0
        var influenceExtendTop: Double = 2.0
        var influenceExtendBack: Double = 2.0
        var influenceExtendFront: Double = 2.0
    }
}
