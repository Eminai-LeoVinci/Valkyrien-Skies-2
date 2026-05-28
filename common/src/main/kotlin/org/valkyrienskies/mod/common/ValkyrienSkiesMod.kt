package org.valkyrienskies.mod.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.core.impl.config.VSCoreConfig
import org.valkyrienskies.core.internal.VsiCore
import org.valkyrienskies.core.internal.VsiCoreClient
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.api.EntityPhysicsListener
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.api.getShipManagingBlock
import org.valkyrienskies.mod.api_impl.events.VsApiImpl
import org.valkyrienskies.mod.common.blockentity.TestAntigravBlockEntity
import org.valkyrienskies.mod.common.blockentity.TestHingeBlockEntity
import org.valkyrienskies.mod.common.blockentity.TestThrusterBlockEntity
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.jackson.BlockPosDeserializer
import org.valkyrienskies.mod.common.jackson.BlockPosKeyDeserializer
import org.valkyrienskies.mod.common.jackson.BlockPosKeySerializer
import org.valkyrienskies.mod.common.jackson.BlockPosSerializer
import org.valkyrienskies.mod.common.networking.VSGamePackets
import org.valkyrienskies.mod.common.util.BuoyancyHandlerAttachment
import org.valkyrienskies.mod.common.util.GameToPhysicsAdapter
import org.valkyrienskies.mod.common.util.ShipSettings
import org.valkyrienskies.mod.common.util.SplitHandler
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment
import org.valkyrienskies.mod.mixinducks.client.world.ClientChunkCacheDuck
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

object ValkyrienSkiesMod {
    const val MOD_ID = "valkyrienskies"

    lateinit var TEST_CHAIR: Block
    lateinit var TEST_HINGE: Block
    lateinit var TEST_FLAP: Block
    lateinit var TEST_WING: Block
    lateinit var TEST_SPHERE: Block
    lateinit var TEST_THRUSTER: Block
    lateinit var TEST_ANTIGRAV: Block
    lateinit var CONNECTION_CHECKER_ITEM: Item
    lateinit var SHIP_CREATOR_ITEM: Item
    lateinit var SHIP_REMOVER_ITEM: Item
    lateinit var SHIP_ASSEMBLER_ITEM: Item
    lateinit var SHIP_CREATOR_ITEM_SMALLER: Item
    lateinit var AREA_ASSEMBLER_ITEM: Item
    lateinit var PHYSICS_ENTITY_CREATOR_ITEM: Item
    lateinit var SHIP_MOUNTING_ENTITY_TYPE: EntityType<ShipMountingEntity>
    lateinit var PHYSICS_ENTITY_TYPE: EntityType<out Entity>
    lateinit var TEST_HINGE_BLOCK_ENTITY_TYPE: BlockEntityType<TestHingeBlockEntity>
    lateinit var BLOCK_POS_COMPONENT: DataComponentType<BlockPos>
    lateinit var TEST_THRUSTER_BLOCK_ENTITY_TYPE: BlockEntityType<TestThrusterBlockEntity>
    lateinit var TEST_ANTIGRAV_BLOCK_ENTITY_TYPE: BlockEntityType<TestAntigravBlockEntity>

    private val dimensionalGTPAs: HashMap<DimensionId, GameToPhysicsAdapter> = HashMap()

    val VS_CREATIVE_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.parse("valkyrienskies"))

    val ASSEMBLE_BLACKLIST: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "assemble_blacklist"))

    @JvmStatic
    var currentServer: MinecraftServer? = null

    @JvmStatic
    val vsCoreProvider: VSCoreProvider by lazy {
        val loader = ServiceLoader.load(VSCoreProvider::class.java, VSCoreProvider::class.java.classLoader)

        loader.findFirst().orElseThrow {
            IllegalStateException("No VSCoreProvider implementation found via ServiceLoader!")
        }
    }

    @JvmStatic
    val vsCore: VsiCore = vsCoreProvider.newVSCore()

    @JvmStatic
    val vsCoreClient get() = vsCore as VsiCoreClient

    @JvmStatic
    val api by lazy {
        VsApiImpl(vsCore)
    }

    val blockEntityPhysListeners: ConcurrentHashMap<DimensionId, ConcurrentHashMap<BlockPos, Pair<ShipId?, BlockEntityPhysicsListener>>> =
        ConcurrentHashMap()
    val entityPhysListeners: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Int, EntityPhysicsListener>> =
        ConcurrentHashMap()

    @JvmStatic
    lateinit var splitHandler: SplitHandler

    @OptIn(PhysTickOnly::class, GameTickOnly::class)
    fun init() {
        val core = this.vsCore

        // vs-core watches/streams a ship's chunks (and runs its physics) only while at
        // least one player is within shipLoadDistance of it. This is *independent* of
        // MC's view-distance and simulation-distance, so we can let ships keep simulating
        // far past where the world stops ticking - exactly what you want for ships that
        // fly out of render range and don't "pause" when you look away.
        //
        // vs-core default is 128 blocks (8 chunks). Bumped to 4096/4480 (256/280 chunks)
        // so a ship in flight keeps moving even when it's well outside the player's view
        // distance. Increase further if you want longer unattended flights; cost scales
        // with ship-count x area, not world chunks.
        //
        // Set before VSConfigUpdater is class-loaded so these are the config-spec defaults;
        // the vs-core server TOML can still override them per-world without a rebuild.
        VSCoreConfig.SERVER.shipLoadDistance = 4096.0
        VSCoreConfig.SERVER.shipUnloadDistance = 4480.0

        BlockStateInfo.init()
        VSGamePackets.register()
        VSGamePackets.registerHandlers()

        // region Register BlockPos for serialization in force inducers
        val aabbModule = SimpleModule()
        aabbModule.addSerializer(BlockPos::class.java, BlockPosSerializer())
        aabbModule.addDeserializer(BlockPos::class.java, BlockPosDeserializer())
        aabbModule.addKeySerializer(BlockPos::class.java, BlockPosKeySerializer())
        aabbModule.addKeyDeserializer(BlockPos::class.java, BlockPosKeyDeserializer())
        val mapper = ObjectMapper()
        mapper.registerModule(aabbModule)
        // end region

        splitHandler = SplitHandler(this.vsCore.hooks.enableBlockEdgeConnectivity, this.vsCore.hooks.enableBlockCornerConnectivity)

        core.registerAttachment(ShipSettings::class.java)
        core.registerAttachment(SeatedControllingPlayer::class.java) {
            useLegacySerializer()
        }
        core.registerAttachment(SplittingDisablerAttachment::class.java) {
            useLegacySerializer()
        }
        core.registerAttachment(BuoyancyHandlerAttachment::class.java)

        core.shipLoadEvent.on { event ->
            event.ship.setAttachment(SplittingDisablerAttachment(true))
            event.ship.setAttachment(BuoyancyHandlerAttachment())
        }

        core.physTickEvent.on { event ->
            dimensionalGTPAs.forEach { dimensionId, gameTickForceApplier ->
                if (event.world.dimension == dimensionId) {
                    gameTickForceApplier.physTick(event.world, event.delta)
                }
            }
            blockEntityPhysListeners.getOrPut(event.world.dimension, { ConcurrentHashMap() }).forEach { pos, infoPair ->
                val shipId = infoPair.first
                val listener = infoPair.second
                val ship = if (shipId != null) {
                    event.world.getShipById(shipId)
                } else {
                    null
                }
                listener.physTick(ship, event.world)
            }
            entityPhysListeners.getOrPut(event.world.dimension, { ConcurrentHashMap() }).forEach { _, listener ->
                listener.physTick(event.world)
            }
        }
        core.shipUnloadEventClient.on { event ->
            val level = Minecraft.getInstance().level
            if (level != null) {
                (level.getChunkSource() as ClientChunkCacheDuck).`vs$removeShip`(event.ship)
            }
            val player = Minecraft.getInstance().player
            if (player is PlayerKnownShipsDuck) {
                player.vs_removeKnownShip(event.ship.id)
            }
        }
    }

    fun createCreativeTab(): CreativeModeTab {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.valkyrienSkies"))
            .icon {
                when {
                    ::SHIP_CREATOR_ITEM.isInitialized -> ItemStack(SHIP_CREATOR_ITEM)
                    ::SHIP_ASSEMBLER_ITEM.isInitialized -> ItemStack(SHIP_ASSEMBLER_ITEM)
                    ::CONNECTION_CHECKER_ITEM.isInitialized -> ItemStack(CONNECTION_CHECKER_ITEM)
                    else -> ItemStack.EMPTY
                }
            }
            .displayItems { _, output ->
                if (::TEST_CHAIR.isInitialized) output.accept(TEST_CHAIR)
                if (::TEST_HINGE.isInitialized) output.accept(TEST_HINGE)
                if (::TEST_FLAP.isInitialized) output.accept(TEST_FLAP)
                if (::TEST_WING.isInitialized) output.accept(TEST_WING)
                if (::TEST_THRUSTER.isInitialized) output.accept(TEST_THRUSTER)
                if (::TEST_ANTIGRAV.isInitialized) output.accept(TEST_ANTIGRAV)
                if (::CONNECTION_CHECKER_ITEM.isInitialized) output.accept(CONNECTION_CHECKER_ITEM)
                // Dev-only ship debug items hidden from the creative tab. Still registered
                // so existing item stacks in chests / inventories load fine and /give works.
                // if (::SHIP_CREATOR_ITEM.isInitialized) output.accept(SHIP_CREATOR_ITEM)
                // if (::SHIP_ASSEMBLER_ITEM.isInitialized) output.accept(SHIP_ASSEMBLER_ITEM)
                // if (::SHIP_CREATOR_ITEM_SMALLER.isInitialized) output.accept(SHIP_CREATOR_ITEM_SMALLER)
                if (::AREA_ASSEMBLER_ITEM.isInitialized) output.accept(AREA_ASSEMBLER_ITEM)
                if (::PHYSICS_ENTITY_CREATOR_ITEM.isInitialized) output.accept(PHYSICS_ENTITY_CREATOR_ITEM)
            }
            .build()
    }

    @JvmStatic
    fun getOrCreateGTPA(dimensionId: DimensionId): GameToPhysicsAdapter {
        return dimensionalGTPAs.getOrPut(dimensionId) { GameToPhysicsAdapter() }
    }

    fun addBlockEntityPhysTicker(
        dimensionId: DimensionId, pos: BlockPos, blockEntity: BlockEntityPhysicsListener
    ) {
        val level = (blockEntity as BlockEntity).level ?: return
        if (level.isClientSide) return
        var shipId : ShipId? = null
        if (!level.isClientSide) {
            val ship = level.getShipManagingBlock(pos)
            shipId = ship?.id
        }
        blockEntityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() })[pos] = Pair(shipId, blockEntity)
    }

    fun getBlockEntityPhysTicker(dimensionId: DimensionId, pos: BlockPos): BlockEntityPhysicsListener? {
        return blockEntityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() })[pos]?.second
    }

    fun removeBlockEntityPhysTicker(pos: BlockPos, dimensionId: DimensionId) {
        blockEntityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() }).remove(pos)
    }

    fun addEntityPhysTicker(
        dimensionId: DimensionId, entity: Entity
    ) {
        if (entity.level() == null || entity.level().isClientSide) return
        entityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() })[entity.id] = entity as EntityPhysicsListener
    }

    fun removeEntityPhysTicker(entity: Entity, dimensionId: DimensionId) {
        entityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() }).remove(entity.id)
    }

    fun getEntityPhysTicker(dimensionId: DimensionId, entityId: Int): EntityPhysicsListener? {
        return entityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() })[entityId]
    }

    fun getEntityPhysTicker(dimensionId: DimensionId, entity: Entity): EntityPhysicsListener? {
        return entityPhysListeners.getOrPut(dimensionId, { ConcurrentHashMap() })[entity.id]
    }

}
