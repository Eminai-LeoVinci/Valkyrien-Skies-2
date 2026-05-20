package org.valkyrienskies.mod.fabric.common

import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackType.SERVER_DATA
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.block.Block
import org.valkyrienskies.mod.client.EmptyRenderer
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents
import net.neoforged.fml.config.ModConfig
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.block.TestChairBlock
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.block.TestFlapBlock
import org.valkyrienskies.mod.common.block.TestHingeBlock
import org.valkyrienskies.mod.common.block.TestWingBlock
import org.valkyrienskies.mod.common.blockentity.TestHingeBlockEntity
import org.valkyrienskies.mod.common.command.VSCommands
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSEntityHandlerDataLoader
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.item.ShipAssemblerItem
import org.valkyrienskies.mod.common.item.ShipCreatorItem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ValkyrienSkiesModFabric : ModInitializer {

    companion object {
        private val hasInitialized = AtomicBoolean(false)
    }

    override fun onInitialize() {
        if (hasInitialized.getAndSet(true)) return

        ValkyrienSkiesMod.TEST_CHAIR = TestChairBlock()
        ValkyrienSkiesMod.TEST_HINGE = TestHingeBlock
        ValkyrienSkiesMod.TEST_FLAP = TestFlapBlock()
        ValkyrienSkiesMod.TEST_WING = TestWingBlock()
        ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM = Item(Properties())
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM = ShipCreatorItem(
            Properties(),
            { 1.0 },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM = ShipAssemblerItem(Properties())
        ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM = Item(Properties())
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER = ShipCreatorItem(
            Properties(),
            { VSGameConfig.SERVER.miniShipSize },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM = Item(Properties())

        ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE = EntityType.Builder.of(
            ::ShipMountingEntity,
            MobCategory.MISC
        ).sized(.3f, .3f)
            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity")))

        ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE =
            FabricBlockEntityTypeBuilder.create(::TestHingeBlockEntity, ValkyrienSkiesMod.TEST_HINGE).build()

        val isClient = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        if (isClient) onInitializeClient()

        ValkyrienSkiesMod.init()

        // Register our four ModConfigSpecs with fcap-fabric's NeoForgeConfigRegistry
        // (the Fabric-side equivalent of NeoForge's ModContainer.registerConfig). Without
        // this, every ConfigValue has no backing config file and operations like /vs
        // backend ... NPE with "Cannot set config value without assigned Config object
        // present". Mirrors the Forge-side registration in ValkyrienSkiesModForge.
        val modId = ValkyrienSkiesMod.MOD_ID
        NeoForgeConfigRegistry.INSTANCE.register(modId, ModConfig.Type.STARTUP, VSConfigUpdater.CORE_SERVER_SPEC, "valkyrienskies-core-server.toml")
        NeoForgeConfigRegistry.INSTANCE.register(modId, ModConfig.Type.SERVER, VSConfigUpdater.SERVER_SPEC, "valkyrienskies-server.toml")
        NeoForgeConfigRegistry.INSTANCE.register(modId, ModConfig.Type.COMMON, VSConfigUpdater.COMMON_SPEC, "valkyrienskies-common.toml")
        NeoForgeConfigRegistry.INSTANCE.register(modId, ModConfig.Type.CLIENT, VSConfigUpdater.CLIENT_SPEC, "valkyrienskies-client.toml")

        // Propagate TOML changes (first-load + reload on external edits) back into the
        // in-memory VsiConfigModel so things that read the Kotlin vars pick up changes.
        // The Fabric path uses fcap's NeoForgeModConfigEvents instead of NeoForge's mod
        // event bus.
        val applyConfig = fun(config: ModConfig) {
            val spec = config.spec as? net.neoforged.neoforge.common.ModConfigSpec ?: return
            val loaded = config.loadedConfig?.config() ?: return
            VSConfigUpdater.applyFromConfigLoad(spec) { key -> loaded.get<Any?>(key) }
        }
        NeoForgeModConfigEvents.loading(modId).register { applyConfig(it) }
        NeoForgeModConfigEvents.reloading(modId).register { applyConfig(it) }
        // VSEntityManager.registerContraptionHandler(ContraptionShipyardEntityHandlerFabric)

        registerBlockAndItem("test_chair", ValkyrienSkiesMod.TEST_CHAIR)
        registerBlockAndItem("test_hinge", ValkyrienSkiesMod.TEST_HINGE)
        registerBlockAndItem("test_flap", ValkyrienSkiesMod.TEST_FLAP)
        registerBlockAndItem("test_wing", ValkyrienSkiesMod.TEST_WING)
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "connection_checker"),
            ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "area_assembler"),
            ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_assembler"),
            ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator"),
            ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity"),
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "test_hinge_block_entity"),
            ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE
        )

        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            ValkyrienSkiesMod.VS_CREATIVE_TAB,
            ValkyrienSkiesMod.createCreativeTab()
        )

        CommandRegistrationCallback.EVENT.register { dispatcher ,d, _ ->
            VSCommands.registerServerCommands(dispatcher)
        }

        // registering data loaders
        val loader1 = MassDatapackResolver.loader // the get makes a new instance so get it only once
        val loader2 = VSEntityHandlerDataLoader // the get makes a new instance so get it only once
        ResourceManagerHelper.get(SERVER_DATA)
            .registerReloadListener(object : IdentifiableResourceReloadListener {
                override fun getFabricId(): Identifier {
                    return Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "vs_mass")
                }

                override fun reload(
                    sharedState: SharedState,
                    backgroundExecutor: Executor,
                    barrier: PreparationBarrier,
                    gameExecutor: Executor
                ): CompletableFuture<Void> {
                    return loader1.reload(sharedState, backgroundExecutor, barrier, gameExecutor)
                        .thenAcceptBoth(
                            loader2.reload(sharedState, backgroundExecutor, barrier, gameExecutor)
                        ) { _, _ -> }
                }
            })
        CommonLifecycleEvents.TAGS_LOADED.register { _, _ ->
            VSGameEvents.tagsAreLoaded.emit(Unit)
        }

        VSDataComponents.registerDataComponents()
    }

    /**
     * Only run on client
     */
    private fun onInitializeClient() {
        // Register the ship mounting entity renderer
        EntityRendererRegistry.register(
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        ) { context: Context ->
            EmptyRenderer(
                context
            )
        }
        VSKeyBindings.clientSetup {
            KeyBindingHelper.registerKeyBinding(it)
        }
    }

    private fun registerBlockAndItem(registryName: String, block: Block): Item {
        Registry.register(
            BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName),
            block
        )
        val item = BlockItem(block, Properties())
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName), item)
        return item
    }
}
