package org.valkyrienskies.mod.fabric.common

import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.CameraType
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
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
import org.valkyrienskies.mod.client.ShipCameraZoom
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents
import net.neoforged.fml.config.ModConfig
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.itemKey
import org.valkyrienskies.mod.common.itemProps
import org.valkyrienskies.mod.common.withRegistryId
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.command.VSCommands
import org.valkyrienskies.mod.common.command.arguments.RelativeVector3Argument
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.command.arguments.ShipArgumentInfo
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSEntityHandlerDataLoader
import org.valkyrienskies.mod.common.config.VSClientConfigLoader
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.item.ShipAssemblerItem
import org.valkyrienskies.mod.common.item.ShipCreatorItem
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache
import org.valkyrienskies.mod.common.world.VSTicketType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ValkyrienSkiesModFabric : ModInitializer {

    companion object {
        private val hasInitialized = AtomicBoolean(false)
    }

    override fun onInitialize() {
        if (hasInitialized.getAndSet(true)) return

        ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "connection_checker")) { Item(itemProps()) }
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_creator")) {
                ShipCreatorItem(
                    itemProps(),
                    { 1.0 },
                    { VSGameConfig.SERVER.minScaling }
                )
            }
        ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_assembler")) { ShipAssemblerItem(itemProps()) }
        ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "area_assembler")) { Item(itemProps()) }
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller")) {
                ShipCreatorItem(
                    itemProps(),
                    { VSGameConfig.SERVER.miniShipSize },
                    { VSGameConfig.SERVER.minScaling }
                )
            }
        ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator")) { Item(itemProps()) }

        ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE = EntityType.Builder.of(
            ::ShipMountingEntity,
            MobCategory.MISC
        ).sized(.3f, .3f)
            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity")))

        // Register VS2's custom ship-chunk TicketType while BuiltInRegistries.TICKET_TYPE is
        // still writable. If left to lazy init, the first ship chunk ticketed mid-tick crashes
        // the server with "Registry is already frozen".
        VSTicketType.init()

        val isClient = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        if (isClient) {
            // Load client render settings (config/valkyrienskies_client.json) -- e.g. ship render distance.
            VSClientConfigLoader.loadOrCreate()
            onInitializeClient()
        }

        ValkyrienSkiesMod.init()

        // 1.21.11 port: register VS2's custom command argument types via Fabric API.
        // The cross-loader MixinArgumentTypeInfos (@Inject into ArgumentTypeInfos.bootstrap)
        // does not weave on 1.21.11 -- ArgumentTypeInfos is class-loaded too early (during
        // BuiltInRegistries static init) for VS2's mixin config to apply, so ShipArgument
        // never landed in ArgumentTypeInfos.BY_CLASS and the server failed to serialize the
        // command tree on player join ("Couldn't place player in world / Invalid player
        // data"). ArgumentTypeRegistry runs during mod init and populates BY_CLASS + the
        // COMMAND_ARGUMENT_TYPE registry. The mixin is removed from the common mixins.json.
        ArgumentTypeRegistry.registerArgumentType(
            Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_argument"),
            ShipArgument::class.java,
            ShipArgumentInfo()
        )
        ArgumentTypeRegistry.registerArgumentType(
            Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "relative_vector3_argument"),
            RelativeVector3Argument::class.java,
            SingletonArgumentInfo.contextFree(::RelativeVector3Argument)
        )

        // Register our four ModConfigSpecs with fcap-fabric's NeoForgeConfigRegistry
        // (the Fabric-side equivalent of NeoForge's ModContainer.registerConfig). Without
        // this, every ConfigValue has no backing config file and operations like /vs
        // backend ... NPE with "Cannot set config value without assigned Config object
        // present". Mirrors the Forge-side registration in ValkyrienSkiesModForge.
        //
        // 1.21.11 port: Forge Config API Port (fcap) is not on the dev runtime
        // classpath, so guard the whole block. Without fcap the TOMLs are simply
        // not backed and config values fall back to their compiled-in defaults,
        // which is fine for the ship-helm milestone. If fcap is restored as a
        // runtime dependency this block resumes working unchanged.
        val modId = ValkyrienSkiesMod.MOD_ID
        try {
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
        } catch (t: Throwable) {
            org.apache.logging.log4j.LogManager.getLogger("ValkyrienSkies").warn(
                "Forge Config API Port unavailable; VS2 config TOMLs disabled, using defaults.", t
            )
        }
        // VSEntityManager.registerContraptionHandler(ContraptionShipyardEntityHandlerFabric)

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

        // Edge-triggered toggle for the ship-terrain GPU render path. Unbound by default, so this
        // consumeClick() never fires until the player binds the key in Controls.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (VSKeyBindings.shipGpuRender.get().consumeClick()) {
                ShipTerrainMeshCache.toggleGpuPath()
                val state = if (ShipTerrainMeshCache.isGpuPath()) {
                    // The GPU path can't render through a shaderpack; it transparently uses the
                    // immediate path while shaders are on (no FPS loss -- the frame is GPU-bound there).
                    if (ShipTerrainMeshCache.isShadersForcingImmediate()) "ON (immediate: shaders on)" else "ON"
                } else {
                    "OFF"
                }
                client.gui.setOverlayMessage(
                    Component.literal("VS ship GPU render: $state"),
                    false
                )
            }
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
    }

    private fun registerBlockAndItem(registryName: String, block: Block): Item {
        Registry.register(
            BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName),
            block
        )
        val item = withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, registryName)) {
            BlockItem(block, itemProps())
        }
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName), item)
        return item
    }
}
