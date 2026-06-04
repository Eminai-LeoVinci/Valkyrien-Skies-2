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
    }
}
