package org.valkyrienskies.mod.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.mojang.logging.LogUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `config/valkyrienskies_client.json` and copies its values into [VSClientConfig.CLIENT] at
 * client init. Mirrors Eureka's [org.valkyrienskies.eureka.EurekaConfigLoader] (the in-port
 * replacement for VS2's unwired ModConfigSpec framework).
 *
 * The path resolves against MC's working directory (the instance root), so no loader plumbing is
 * needed. Behavior:
 * - File missing: write defaults from the current [VSClientConfig.CLIENT] singleton.
 * - File present and parses: merge values onto the singleton via Jackson's `readerForUpdating`,
 *   then re-serialize so newly-added fields appear on next launch.
 * - File present but malformed: log a warning and keep defaults; do NOT overwrite the broken file.
 */
object VSClientConfigLoader {
    private val LOGGER = LogUtils.getLogger()
    private val CONFIG_FILE: Path = Path.of("config", "valkyrienskies_client.json")

    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    @JvmStatic
    fun loadOrCreate() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                writeConfig()
                LOGGER.info("Created default VS2 client config at {}", CONFIG_FILE.toAbsolutePath())
                return
            }

            val tree = mapper.readTree(CONFIG_FILE.toFile())
            tree.get("client")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(VSClientConfig.CLIENT).readValue<Any>(it)
            }
            LOGGER.info("Loaded VS2 client config from {}", CONFIG_FILE.toAbsolutePath())

            // Re-write so newly-added fields flow into the file and obsolete ones drop out.
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn(
                "Failed to load VS2 client config at {} ({}); using built-in defaults.",
                CONFIG_FILE.toAbsolutePath(), e.message, e
            )
        }
    }

    /**
     * Persist the current [VSClientConfig.CLIENT] singleton back to `config/valkyrienskies_client.json`.
     * Used by the `/vs expand-influence` / `/vs contract-influence` commands so live border tuning sticks
     * across restarts. Best-effort: a write failure is logged, not thrown (the in-memory change still applies).
     */
    @JvmStatic
    fun save() {
        try {
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn("Failed to save VS2 client config to {}: {}", CONFIG_FILE.toAbsolutePath(), e.message, e)
        }
    }

    private fun writeConfig() {
        CONFIG_FILE.parent?.let { Files.createDirectories(it) }
        mapper.writeValue(CONFIG_FILE.toFile(), linkedMapOf("client" to VSClientConfig.CLIENT))
    }
}
