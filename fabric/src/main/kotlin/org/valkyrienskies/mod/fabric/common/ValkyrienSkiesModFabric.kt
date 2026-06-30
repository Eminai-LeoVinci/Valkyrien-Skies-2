package org.valkyrienskies.mod.fabric.common

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.CameraType
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.network.chat.Component
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType.SERVER_DATA
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.block.Block
import org.valkyrienskies.mod.client.EmptyRenderer
import org.valkyrienskies.mod.client.ShipCameraZoom
import org.valkyrienskies.mod.client.ShipDebugRender
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
import org.valkyrienskies.mod.common.command.arguments.RelativeVector3Argument
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.command.arguments.ShipArgumentInfo
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.config.VSClientConfigLoader
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
            .build(ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity").toString())

        ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE =
            FabricBlockEntityTypeBuilder.create(::TestHingeBlockEntity, ValkyrienSkiesMod.TEST_HINGE).build()

        val isClient = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        if (isClient) {
            // Generate / load config/valkyrienskies_client.json (ship influence-border extension) before
            // client setup, so the /vs influence commands and EntityDragger read the persisted values.
            VSClientConfigLoader.loadOrCreate()
            onInitializeClient()
        }

        ValkyrienSkiesMod.init()

        // Register VS2's custom command argument types via Fabric API.
        // The cross-loader MixinArgumentTypeInfos (@Inject into ArgumentTypeInfos.bootstrap)
        // does NOT reliably weave -- ArgumentTypeInfos is class-loaded too early (during
        // BuiltInRegistries static init) for VS2's mixin config to apply, so ShipArgument
        // never landed in ArgumentTypeInfos.BY_CLASS and the server silently STRIPPED every
        // /vs subcommand that uses it (teleport, set-keep-active, set-static, delete, rename,
        // scale, remass, splitting, influence...) from the command tree synced to the client
        // -- they vanish from autofill and can't be run. desnow (no ShipArgument) survived.
        // ArgumentTypeRegistry runs during mod init and populates BY_CLASS + the
        // COMMAND_ARGUMENT_TYPE registry. The mixin is removed from valkyrienskies-common.mixins.json.
        // Mirrors the working 1.21.11 build.
        ArgumentTypeRegistry.registerArgumentType(
            ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_argument"),
            ShipArgument::class.java,
            ShipArgumentInfo()
        )
        ArgumentTypeRegistry.registerArgumentType(
            ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "relative_vector3_argument"),
            RelativeVector3Argument::class.java,
            SingletonArgumentInfo.contextFree(::RelativeVector3Argument)
        )

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
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "connection_checker"),
            ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "area_assembler"),
            ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_assembler"),
            ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator"),
            ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity"),
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "test_hinge_block_entity"),
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
                override fun getFabricId(): ResourceLocation {
                    return ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "vs_mass")
                }

                override fun reload(
                    stage: PreparationBarrier,
                    resourceManager: ResourceManager,
                    preparationsProfiler: ProfilerFiller,
                    reloadProfiler: ProfilerFiller,
                    backgroundExecutor: Executor,
                    gameExecutor: Executor
                ): CompletableFuture<Void> {
                    return loader1.reload(
                        stage, resourceManager, preparationsProfiler, reloadProfiler,
                        backgroundExecutor, gameExecutor
                    ).thenAcceptBoth(
                        loader2.reload(
                            stage, resourceManager, preparationsProfiler, reloadProfiler,
                            backgroundExecutor, gameExecutor
                        )
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
        // Client-side /vs subcommands. Two groups share this registration:
        //  - INFLUENCE TUNING (expand-influence, contract-influence): adjust the per-face ship influence-border
        //    extension (VSClientConfig, config/valkyrienskies_client.json) in-game instead of editing the JSON.
        //  - influence-border <bool>: toggle the blue wireframe debug render of every ship's influence border
        //    (ShipDebugRender.influenceBorder; drawn by ShipInfluenceBorderRenderer). Defaults OFF, not persisted.
        // All set CLIENT state directly, so they MUST stay client-side; they coexist with the server-side /vs tree
        // (Fabric runs client commands first, the vs_command_passthrough mixin forwards unmatched server subcommands
        // to the server, and the command_suggestion_merge mixin makes these show up in `/vs ` tab-completion despite
        // the colliding `vs` root). The 1.21.11 build also registers ship-shadows / ship-emissive debug toggles here
        // -- those render features are not on the 1.21.1 port, so they are omitted.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("vs")
                    .then(
                        ClientCommandManager.literal("influence-border").then(
                            ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes { ctx ->
                                val enabled = BoolArgumentType.getBool(ctx, "enabled")
                                ShipDebugRender.influenceBorder = enabled
                                ctx.source.sendFeedback(
                                    Component.literal("VS influence border " + if (enabled) "ENABLED" else "DISABLED")
                                )
                                1
                            }
                        )
                    )
                    .then(
                        ClientCommandManager.literal("expand-influence").then(
                            ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0)).then(
                                ClientCommandManager.argument("direction", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        INFLUENCE_FACE_NAMES.forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        adjustInfluenceExtend(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "direction"),
                                            DoubleArgumentType.getDouble(ctx, "amount")
                                        )
                                    }
                            )
                        )
                    )
                    .then(
                        ClientCommandManager.literal("contract-influence").then(
                            ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0)).then(
                                ClientCommandManager.argument("direction", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        INFLUENCE_FACE_NAMES.forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        adjustInfluenceExtend(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "direction"),
                                            -DoubleArgumentType.getDouble(ctx, "amount")
                                        )
                                    }
                            )
                        )
                    )
            )
        }

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

        // Always drop back to first person when the player dismounts a ship mount (helm seat),
        // whatever camera the ship view was in, and reset the scroll-zoom for the next mount.
        // Dismounts are server-driven (sneak poll, helm broken, seat killed), so the client
        // detects them as a falling edge on the vehicle field.
        var wasRidingShipMount = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player
            val riding = player?.vehicle is ShipMountingEntity
            if (wasRidingShipMount && !riding) {
                ShipCameraZoom.reset()
                if (player != null && !client.options.cameraType.isFirstPerson) {
                    client.options.cameraType = CameraType.FIRST_PERSON
                }
            }
            wasRidingShipMount = riding
        }

        // Per-frame thin blue oriented wireframe of each ship's influence border, gated on the
        // "/vs influence-border <bool>" toggle above (ShipDebugRender.influenceBorder).
        ShipInfluenceBorderRenderer.register()
    }

    private val INFLUENCE_FACE_NAMES = listOf("Top", "Bottom", "Left", "Right", "Front", "Back")

    /**
     * Apply a signed change to one face of the global ship influence-border extension
     * ([VSClientConfig.CLIENT]). Positive [delta] expands the border outward, negative contracts it; the
     * per-face value is clamped at 0 -- the exact ship dimension, never smaller -- so e.g. contracting 10
     * from a value of 2 lands at 0, not -8. EntityDragger (the carry) reads these values live each tick, so
     * the change takes effect immediately (no reassemble, no relog), and it is persisted to the client JSON
     * so it survives a restart.
     */
    private fun adjustInfluenceExtend(source: FabricClientCommandSource, direction: String, delta: Double): Int {
        val c = VSClientConfig.CLIENT
        val current = when (direction.lowercase()) {
            "top" -> c.influenceExtendTop
            "bottom" -> c.influenceExtendBottom
            "left" -> c.influenceExtendLeft
            "right" -> c.influenceExtendRight
            "front" -> c.influenceExtendFront
            "back" -> c.influenceExtendBack
            else -> {
                source.sendError(
                    Component.literal("Unknown direction '$direction' -- use Top, Bottom, Left, Right, Front, or Back.")
                )
                return 0
            }
        }
        val updated = (current + delta).coerceAtLeast(0.0)
        when (direction.lowercase()) {
            "top" -> c.influenceExtendTop = updated
            "bottom" -> c.influenceExtendBottom = updated
            "left" -> c.influenceExtendLeft = updated
            "right" -> c.influenceExtendRight = updated
            "front" -> c.influenceExtendFront = updated
            "back" -> c.influenceExtendBack = updated
        }
        VSClientConfigLoader.save()
        val face = direction.lowercase().replaceFirstChar { it.uppercase() }
        source.sendFeedback(
            Component.literal("Influence border: $face %.1f -> %.1f blocks".format(current, updated))
        )
        return 1
    }

    private fun registerBlockAndItem(registryName: String, block: Block): Item {
        Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName),
            block
        )
        val item = BlockItem(block, Properties())
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName), item)
        return item
    }
}
