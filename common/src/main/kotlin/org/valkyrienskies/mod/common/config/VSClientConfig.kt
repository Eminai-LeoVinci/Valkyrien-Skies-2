package org.valkyrienskies.mod.common.config

/**
 * Client-side VS2 settings loaded from `config/valkyrienskies_client.json` by [VSClientConfigLoader].
 *
 * VS2's primary config ([VSGameConfig]) is TOML/Forge-Config-API-Port backed, which is currently
 * unwired in the 1.21.11 port (values fall back to compiled-in defaults and aren't file-editable).
 * This small JSON config -- mirroring Eureka's `vs_eureka.json` loader -- is how client render
 * settings become editable without recompiling, until the ModConfigSpec framework is restored.
 */
object VSClientConfig {

    @JvmField
    val CLIENT = Client()

    class Client {
        /**
         * Max distance (in blocks) at which ships render. This raises the camera far clip-plane so
         * distant ships stay visible far past your vanilla render distance (which only extends the
         * plane to ~renderDistanceChunks * 64 blocks). Ship block chunks are kept on the client out
         * to this range so the real ship -- not just a marker -- draws.
         *
         * Higher = see ships from farther, at some cost to depth precision (z-fighting) and the
         * amount of distant ship geometry drawn. Values below the vanilla far plane have no effect
         * (the larger of the two always wins). Ships still unload server-side near ~8700 blocks, so
         * there's no point going much past that.
         */
        var shipRenderDistance: Float = 2048.0f

        /**
         * Zoom-in limit for the ship-mounted third-person camera, as a multiplier on the camera
         * distance the ship sets from its size. 1.0 = the ship-set baseline; the scroll wheel
         * can never zoom in closer than this.
         */
        var shipCameraZoomMin: Double = 0.125

        /**
         * Zoom-out limit for the ship-mounted third-person camera (scroll wheel while the ship
         * view is active). 2.0 = up to twice the ship-set camera distance.
         */
        var shipCameraZoomMax: Double = 2.0

        // Ship "influence" border extension, in blocks, added OUTWARD to each of the ship's six faces
        // independently. A player who jumps/walks off a moving ship (or elytra/creative-flies off it) keeps being
        // carried with the ship until they leave this border; raising a value lets them drift farther past that face
        // before the ship releases them. 0 on a face = the exact ship dimension there (no lip). Touching non-ship
        // ground or water releases the carry immediately regardless of these values.
        //
        // Ship-space axis -> face: -X = Left / +X = Right, -Y = Bottom / +Y = Top (gravity-aligned, certain),
        // -Z = Back / +Z = Front. Top/Bottom are unambiguous; Left/Right and Front/Back are nominal axis-sign labels
        // (no ship-facing convention exists for the influence AABB) — if in-world a pair reads reversed, just swap the
        // two values, it's purely cosmetic naming.
        //
        // (These live in the client JSON because VS2's server config framework is unwired on the 1.21.11 port; on a
        // single-player world the integrated server reads these same values, so editing them here takes effect.)
        var influenceExtendLeft: Double = 2.0
        var influenceExtendRight: Double = 2.0
        var influenceExtendBottom: Double = 2.0
        var influenceExtendTop: Double = 2.0
        var influenceExtendBack: Double = 2.0
        var influenceExtendFront: Double = 2.0
    }
}
