package org.valkyrienskies.mod.mixin.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Unit;
import com.mojang.serialization.Codec;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.BlockUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.util.AerodynamicUtils;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.properties.IShipActiveChunksSet;
import org.valkyrienskies.core.internal.VsiGameServer;
import org.valkyrienskies.core.internal.world.VsiPlayer;
import org.valkyrienskies.core.internal.world.VsiPipeline;
import org.valkyrienskies.core.internal.world.VsiServerShipWorld;
import org.valkyrienskies.mod.common.IShipObjectWorldServerProvider;
import org.valkyrienskies.mod.common.ShipSavedData;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.config.MassDatapackResolver;
import org.valkyrienskies.mod.common.hooks.VSGameEvents;
import org.valkyrienskies.mod.common.util.EntityDragger;
import org.valkyrienskies.mod.common.util.VSLevelChunk;
import org.valkyrienskies.mod.common.util.VSServerLevel;
import org.valkyrienskies.mod.common.world.ChunkManagement;
import org.valkyrienskies.mod.common.world.ShipActivationManager;
import org.valkyrienskies.mod.common.world.ShipPlantMower;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.Weather2Compat;
import org.valkyrienskies.mod.util.KrunchSupport;
import org.valkyrienskies.mod.util.McMathUtilKt;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements IShipObjectWorldServerProvider, VsiGameServer {
    @Shadow
    private PlayerList playerList;

    @Shadow
    public abstract ServerLevel overworld();

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Unique
    private VsiServerShipWorld shipWorld;

    @Unique
    private VsiPipeline vsPipeline;

    @Unique
    private Set<String> loadedLevels = new HashSet<>();

    @Unique
    private final Map<String, ServerLevel> dimensionToLevelMap = new HashMap<>();

    @Inject(
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"),
        method = "runServer"
    )
    private void beforeInitServer(final CallbackInfo info) {
        ValkyrienSkiesMod.setCurrentServer(MinecraftServer.class.cast(this));
    }

    @Inject(at = @At("TAIL"), method = "stopServer")
    private void afterStopServer(final CallbackInfo ci) {
        ValkyrienSkiesMod.setCurrentServer(null);
    }

    @Nullable
    @Override
    public VsiServerShipWorld getShipObjectWorld() {
        return shipWorld;
    }

    @Nullable
    @Override
    public VsiPipeline getVsPipeline() {
        return vsPipeline;
    }

    /**
     * Create the ship world immediately after the levels are created, so that nothing can try to access the ship world
     * before it has been initialized.
     */
    @Inject(
        method = "createLevels",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getDataStorage()Lnet/minecraft/world/level/storage/DimensionDataStorage;"
        )
    )
    private void postCreateLevels(final CallbackInfo ci) {
        // Register blocks
        if (!MassDatapackResolver.INSTANCE.getRegisteredBlocks()) {
            final List<BlockState> blockStateList = new ArrayList<>(Block.BLOCK_STATE_REGISTRY.size());
            Block.BLOCK_STATE_REGISTRY.forEach((blockStateList::add));
            MassDatapackResolver.INSTANCE.registerAllBlockStates(blockStateList);
            ValkyrienSkiesMod.getVsCore().registerBlockStates(MassDatapackResolver.INSTANCE.getBlockStateData());
        }

        // 1.21.11: SavedData persistence is codec-driven via SavedDataType. ShipSavedData
        // round-trips through a CompoundTag (see ShipSavedData.load / saveToTag).
        final Codec<ShipSavedData> shipSavedDataCodec = CompoundTag.CODEC.xmap(
            ShipSavedData::load,
            data -> data.saveToTag(new CompoundTag()));
        final SavedDataType<ShipSavedData> savedDataType = new SavedDataType<>(
            ShipSavedData.SAVED_DATA_ID,
            ShipSavedData.Companion::createEmpty,
            shipSavedDataCodec,
            DataFixTypes.LEVEL);
        // Load ship data from the world storage
        final ShipSavedData shipSavedData = overworld().getDataStorage()
            .computeIfAbsent(savedDataType);

        // If there was an error deserializing, re-throw it here so that the game actually crashes.
        // We would prefer to crash the game here than allow the player keep playing with everything corrupted.
        final Throwable ex = shipSavedData.getLoadingException();
        if (ex != null) {
            System.err.println("VALKYRIEN SKIES ERROR WHILE LOADING SHIP DATA");
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }

        // Create ship world and VS Pipeline
        vsPipeline = shipSavedData.getPipeline();

        KrunchSupport.INSTANCE.setKrunchSupported(!vsPipeline.isUsingDummyPhysics());

        shipWorld = vsPipeline.getShipWorld();
        shipWorld.setGameServer(this);

        VSGameEvents.INSTANCE.getRegistriesCompleted().emit(Unit.INSTANCE);

        getShipObjectWorld().addDimension(
            VSGameUtilsKt.getDimensionId(overworld()),
            VSGameUtilsKt.getYRange(overworld()),
            McMathUtilKt.getDEFAULT_WORLD_GRAVITY(),
            AerodynamicUtils.DEFAULT_SEA_LEVEL,
            AerodynamicUtils.DEFAULT_MAX
        );
    }

    @Inject(
        method = "tickServer",
        at = @At("HEAD")
    )
    private void preTick(final CallbackInfo ci) {
        final Set<VsiPlayer> vsPlayers = new HashSet<>();
        for (final ServerPlayer player : playerList.getPlayers()) {
            vsPlayers.add(VSGameUtilsKt.getPlayerWrapper(player));
        }
        // Pin a synthetic observer (at the ship centre, distance 0) onto every "active" ship --
        // keepActive, piloted, or one a player is aboard -- so vs-core's player-proximity load/physics
        // gate keeps it simulating: with no real player nearby (keepActive), and from the centre even
        // when the only real player stands at the far end of a big craft (the walk-to-the-back freeze).
        // vs-core gates physics on its player set + proximity, NOT on vanilla sim distance or chunk
        // tickets, and its per-ship forceWatchingShips override is dead code in this build -- so making
        // it think a player sits on the ship is the only lever. The observer feeds only that gate;
        // nothing is networked to it (VSFabricNetworking.sendToClient drops non-MinecraftPlayer).
        vsPlayers.addAll(ShipActivationManager.activeShipObservers(shipWorld, MinecraftServer.class.cast(this)));
        shipWorld.setPlayers(vsPlayers);

        // region Tell the VS world to load new levels, and unload deleted ones
        final Set<String> newLoadedLevels = new HashSet<>();
        for (final ServerLevel level : getAllLevels()) {
            final String dimensionId = VSGameUtilsKt.getDimensionId(level);
            newLoadedLevels.add(dimensionId);
            dimensionToLevelMap.put(dimensionId, level);
        }

        if (!newLoadedLevels.equals(loadedLevels)) {
            for (final String oldLoadedLevelId : loadedLevels) {
                if (!newLoadedLevels.contains(oldLoadedLevelId)) {
                    shipWorld.removeDimension(oldLoadedLevelId);
                    dimensionToLevelMap.remove(oldLoadedLevelId);
                }
            }
        }
        loadedLevels = newLoadedLevels;
        // endregion

        vsPipeline.preTickGame();
    }

    /**
     * Tick the [shipWorld], then send voxel terrain updates for each level
     */
    @Inject(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            // 1.21.11: tickChildren no longer calls ServerConnectionListener.tick() inline -
            // the connection tick was extracted into MinecraftServer.tickConnection(). This
            // injection drives ChunkManagement.tickChunkLoading, which calls
            // setExecutedChunkWatchTasks; that is the pipeline's SET_EXECUTED stage. If this
            // injection fails to bind (e.g. wrong target), postTickGame crashes vs-core with
            // "Constraints failed. Stages since last reset: [PRE_TICK, POST_TICK]".
            target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V",
            shift = Shift.AFTER
        )
    )
    private void preConnectionTick(final CallbackInfo ci) {
        ChunkManagement.tickChunkLoading(shipWorld, MinecraftServer.class.cast(this));
        // Keep "active" ships (keepActive flag / cruising) simulating regardless of the vanilla
        // simulation-distance setting by force-ticking the world chunks under them.
        ShipActivationManager.tick(shipWorld, MinecraftServer.class.cast(this));
        // Moving ships silently cut away the kelp their hull physically touches (no drops, no felling).
        ShipPlantMower.tick(shipWorld, MinecraftServer.class.cast(this));
    }

    @Shadow
    public abstract ServerLevel getLevel(ResourceKey<Level> resourceKey);

    @Inject(
        method = "tickServer",
        at = @At("TAIL")
    )
    private void postTick(final CallbackInfo ci) {
        vsPipeline.postTickGame();
        // Only drag entities after we have updated the ship positions. The drag sweep visits
        // every entity in every dimension, so skip it entirely when the world has no ships.
        final boolean anyShips = shipWorld != null && shipWorld.getAllShips().size() > 0;
        for (final ServerLevel level : getAllLevels()) {
            if (anyShips) {
                EntityDragger.INSTANCE.dragEntitiesWithShips(level.getAllEntities(), false);
            }
            if (LoadedMods.getWeather2())
                Weather2Compat.INSTANCE.tick(level);
        }
    }

    @Inject(
        method = "stopServer",
        at = @At("HEAD")
    )
    private void preStopServer(final CallbackInfo ci) {
        if (vsPipeline != null) {
            vsPipeline.setDeleteResources(true);
            vsPipeline.setArePhysicsRunning(true);
        }

        // Remove all ship chunk tickets before Minecraft's shutdown loop runs; otherwise
        // the while(hasWork()) drain in stopServer() hangs forever because shipyard
        // ChunkHolders stay in updatingChunkMap and are never scheduled for dropping.
        // Was 1.20.1's primary fix for the "100+ ships, closing world hangs" case in
        // commit 076dd115afcf920a9db472527d8a41786b465863; MixinChunkMapClose is the
        // defense-in-depth safety net that assumes this ran.
        if (shipWorld != null) {
            // Release any world-chunk tickets we placed for active ships (mirror of the SHIP_CHUNK
            // cleanup below — clear before MC's shutdown chunk-drain loop runs).
            ShipActivationManager.clearAll(MinecraftServer.class.cast(this));
            for (final LoadedServerShip ship : shipWorld.getLoadedShips()) {
                final ServerLevel level = dimensionToLevelMap.get(ship.getChunkClaimDimension());
                if (level != null) {
                    ship.getActiveChunksSet().forEach((final int x, final int z) -> {
                        final ChunkPos cp = new ChunkPos(x, z);
                        // Remove the SHIP_CHUNK ticket (radius-0, level 33)
                        level.getChunkSource().removeTicketWithRadius(
                            org.valkyrienskies.mod.common.world.VSTicketType.SHIP_CHUNK, cp, 0);
                        // Also remove any legacy FORCED tickets in case they exist
                        level.getChunkSource().updateChunkForced(cp, false);
                    });
                }
            }
        }
    }

    // Only clear these after stopping the server so preStopServer and the
    // stopServer drain loop can still reach shipWorld / dimensionToLevelMap.
    @Inject(
        method = "stopServer",
        at = @At("RETURN")
    )
    private void postStopServer(final CallbackInfo ci) {
        dimensionToLevelMap.clear();
        if (shipWorld != null) {
            shipWorld.setGameServer(null);
            shipWorld = null;
        }
    }

    @NotNull
    private ServerLevel getLevelFromDimensionId(@NotNull final String dimensionId) {
        return dimensionToLevelMap.get(dimensionId);
    }

    @Unique
    private static final org.slf4j.Logger VS$CROSSDIM_LOGGER =
        org.slf4j.LoggerFactory.getLogger("ValkyrienSkies-CrossDimTransfer");

    @Override
    public void moveTerrainAcrossDimensions(
        @NotNull final IShipActiveChunksSet shipChunks,
        @NotNull final String srcDimension,
        @NotNull final String destDimension
    ) {
        final ServerLevel srcLevel = getLevelFromDimensionId(srcDimension);
        final ServerLevel destLevel = getLevelFromDimensionId(destDimension);

        // Cross-dimension transfer was a hard UnsupportedOperationException stub until the 1.21.11 chunk
        // re-port (SerializableChunkData). This SERVER-side terrain move works, but cross-dimension SHIP
        // transfer is blocked one layer down in vs-core: teleporting a ship into a dimension the client
        // already tracks makes core's client ship-sync throw "Received ship create packet for already loaded
        // ship" (ShipObjectClientWorld -> obfuscated coroutine internals), crashing the render thread. So
        // there is intentionally NO in-game trigger wired to this hook until that core limitation is fixed
        // upstream; the implementation is kept (correct + upstream parity) for when it is. Guard the whole
        // transfer anyway so a chunk-copy failure aborts with a logged error instead of crashing the server
        // tick. NOTE: a failure mid-loop can leave the terrain partially moved.
        try {
            // Copy ship chunks from srcLevel to destLevel
            shipChunks.forEach((final int x, final int z) -> {
                final LevelChunk srcChunk = srcLevel.getChunk(x, z);

                // This is a hack, but it fixes destLevel being in the wrong state
                ((VSServerLevel) destLevel).removeChunk(x, z);

                final LevelChunk destChunk = destLevel.getChunk(x, z);
                ((VSLevelChunk) destChunk).copyChunkFromOtherDimension((VSLevelChunk) srcChunk);
            });

            // Delete ship chunks from srcLevel
            shipChunks.forEach((final int x, final int z) -> {
                final LevelChunk srcChunk = srcLevel.getChunk(x, z);
                ((VSLevelChunk) srcChunk).clearChunk();

                final ChunkPos chunkPos = srcChunk.getPos();
                srcLevel.getChunkSource().updateChunkForced(chunkPos, false);
                ((VSServerLevel) srcLevel).removeChunk(x, z);
            });
        } catch (final Throwable t) {
            VS$CROSSDIM_LOGGER.error(
                "Cross-dimension ship terrain transfer {} -> {} failed; aborting (terrain may be partially moved)",
                srcDimension, destDimension, t);
        }
    }
}
