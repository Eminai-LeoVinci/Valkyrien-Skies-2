package org.valkyrienskies.mod.common

import net.minecraft.nbt.CompoundTag
import net.minecraft.core.HolderLookup
import net.minecraft.world.level.saveddata.SavedData
import org.valkyrienskies.core.internal.world.VsiPipeline

/**
 * This class saves/loads ship data for a world.
 *
 * This is only a temporary solution, and should be replaced eventually because it is very inefficient.
 */
class ShipSavedData : SavedData() {

    companion object {
        const val SAVED_DATA_ID = "vs_ship_data"
        private const val QUERYABLE_SHIP_DATA_NBT_KEY = "queryable_ship_data"
        private const val CHUNK_ALLOCATOR_NBT_KEY = "chunk_allocator"
        private const val PIPELINE_NBT_KEY = "vs_pipeline"

        fun createEmpty(): ShipSavedData {
            return ShipSavedData().apply { pipeline = vsCore.newPipeline() }
        }

        @JvmStatic
        fun load(compoundTag: CompoundTag): ShipSavedData {
            val logger = org.slf4j.LoggerFactory.getLogger("VS2")
            val data = ShipSavedData()

            // Read bytes from the [CompoundTag] (1.21.11: getByteArray returns Optional)
            val empty = ByteArray(0)
            val queryableShipDataAsBytes = compoundTag.getByteArray(QUERYABLE_SHIP_DATA_NBT_KEY).orElse(empty)
            val chunkAllocatorAsBytes = compoundTag.getByteArray(CHUNK_ALLOCATOR_NBT_KEY).orElse(empty)
            val pipelineAsBytes = compoundTag.getByteArray(PIPELINE_NBT_KEY).orElse(empty)

            logger.info(" ShipSavedData.load(): pipeline bytes = {} KB, legacy queryable = {} KB, legacy allocator = {} KB",
                pipelineAsBytes.size / 1024, queryableShipDataAsBytes.size / 1024, chunkAllocatorAsBytes.size / 1024)

            try {
                if (pipelineAsBytes.isNotEmpty()) {
                    data.pipeline = vsCore.newPipeline(pipelineAsBytes)
                    logger.info(" ShipSavedData.load(): loaded pipeline from {} KB of data", pipelineAsBytes.size / 1024)
                } else if (queryableShipDataAsBytes.isNotEmpty() && chunkAllocatorAsBytes.isNotEmpty()) {
                    data.pipeline = vsCore.newPipelineLegacyData(queryableShipDataAsBytes, chunkAllocatorAsBytes)
                    logger.info(" ShipSavedData.load(): loaded pipeline from legacy data")
                } else {
                    throw IllegalStateException("Couldn't find serialized ship data")
                }
            } catch (ex: Exception) {
                logger.error(" ShipSavedData.load(): FAILED to load pipeline: {}", ex.message)
                data.loadingException = ex
            }
            return data
        }
    }

    lateinit var pipeline: VsiPipeline

    var loadingException: Throwable? = null
        private set

    /**
     * Serialize the ship pipeline to a [CompoundTag].
     *
     * 1.21.11: [SavedData] no longer has an overridable `save(CompoundTag, Provider)` — persistence
     * is now driven by a `SavedDataType` + `Codec` registered at the data-storage call site.
     * This is kept as a plain method; the codec/SavedDataType wiring lives in MixinMinecraftServer.
     */
    fun saveToTag(compoundTag: CompoundTag): CompoundTag {
        val logger = org.slf4j.LoggerFactory.getLogger("VS2")
        val bytes = vsCore.serializePipeline(pipeline)
        logger.info(" ShipSavedData.save(): pipeline bytes = {} KB", bytes.size / 1024)
        compoundTag.putByteArray(PIPELINE_NBT_KEY, bytes)
        return compoundTag
    }

    /**
     * Always report as dirty since ship physics transforms change every tick.
     */
    override fun isDirty(): Boolean {
        return true
    }
}
