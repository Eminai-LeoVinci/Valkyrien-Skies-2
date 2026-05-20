package org.valkyrienskies.mod.mixin.client.world;

import static org.valkyrienskies.mod.common.BlockStateInfo.isSortedRegistryInitialized;
import static org.valkyrienskies.mod.common.ValkyrienSkiesMod.getApi;
import static org.valkyrienskies.mod.common.ValkyrienSkiesMod.getVsCore;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector3i;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ChunkClaim;
import org.valkyrienskies.core.internal.world.VsiClientShipWorld;
import org.valkyrienskies.core.internal.world.chunks.VsiTerrainUpdate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.compat.SodiumCompat;
import org.valkyrienskies.mod.compat.VSRenderer;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;
import org.valkyrienskies.mod.mixin.accessors.client.multiplayer.ClientLevelAccessor;
import org.valkyrienskies.mod.mixin.accessors.client.render.LevelRendererAccessor;
import org.valkyrienskies.mod.mixin.accessors.client.world.ClientChunkCacheStorageAccessor;
import org.valkyrienskies.mod.mixinducks.client.render.IVSViewAreaMethods;
import org.valkyrienskies.mod.mixinducks.client.world.ClientChunkCacheDuck;
import org.valkyrienskies.mod.mixinducks.mod_compat.vanilla_renderer.LevelRendererDuck;
import org.valkyrienskies.mod.util.ClientConnectivityUpdateQueue;

/**
 * The purpose of this mixin is to allow {@link ClientChunkCache} to store ship chunks.
 */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache implements ClientChunkCacheDuck {

    @Shadow
    volatile ClientChunkCache.Storage storage;
    @Shadow
    @Final
    ClientLevel level;

    @Unique
    private final Long2ObjectMap<LevelChunk> emptyShipChunks = new Long2ObjectOpenHashMap<>();

    public Long2ObjectMap<LevelChunk> vs$getShipChunks() {
        return vs$shipChunks;
    }

    @Unique
    private final Long2ObjectMap<LevelChunk> vs$shipChunks = new Long2ObjectOpenHashMap<>();

    @Override
    public void vs$removeShip(final ClientShip ship) {
        final ChunkClaim chunks = ship.getChunkClaim();
        for (int x = chunks.getXStart(); x <= chunks.getXEnd(); x++) {
            for (int z = chunks.getZStart(); z <= chunks.getZEnd(); z++) {
                removeShipChunk(x, z);
            }
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void preLoadChunkFromPacket(final int x, final int z,
        final FriendlyByteBuf buf,
        final Map<Heightmap.Types, long[]> tag,
        final Consumer<BlockEntityTagOutput> consumer, final CallbackInfoReturnable<LevelChunk> cir) {
        final ClientChunkCacheStorageAccessor clientChunkMapAccessor =
            ClientChunkCacheStorageAccessor.class.cast(storage);
        if (!clientChunkMapAccessor.callInRange(x, z)) {
            if (VSGameUtilsKt.isChunkInShipyard(level, x, z)) {
                if (Minecraft.getInstance().levelRenderer instanceof final LevelRendererDuck levelRenderer) {
                    levelRenderer.vs$setNeedsFrustumUpdate();
                }
                final long chunkPosLong = ChunkPos.asLong(x, z);
                final ChunkPos pos = new ChunkPos(x, z);
                final LevelChunk oldChunk = vs$shipChunks.get(chunkPosLong);
                final LevelChunk oldEmptyChunk = this.emptyShipChunks.remove(chunkPosLong);
                final LevelChunk worldChunk;
                boolean shouldForce = false;
                if (oldChunk != null) {
                    worldChunk = oldChunk;
                    oldChunk.replaceWithPacketData(buf, tag, consumer);
                } else {
                    worldChunk = oldEmptyChunk == null ? new LevelChunk(this.level, pos) : oldEmptyChunk;
                    worldChunk.replaceWithPacketData(buf, tag, consumer);
                    vs$shipChunks.put(chunkPosLong, worldChunk);
                    clientChunkMapAccessor.setChunkCount(clientChunkMapAccessor.getChunkCount() + 1);
                }

                if (oldChunk != null) {
                    shouldForce = true;
                }

                final boolean shouldDefer = !isSortedRegistryInitialized();
                if (shouldDefer) {
                    ClientConnectivityUpdateQueue.queueChunkForInitialization(pos, shouldForce);
                } else {
                    final VsiClientShipWorld clientShipWorld = VSGameUtilsKt.getShipObjectWorld(level);
                    if (clientShipWorld != null && VSGameConfig.CLIENT.getConnectivity().getEnableClientConnectivity()) {
                        final LevelChunkSection[] chunkSections = worldChunk.getSections();
                        final ArrayList<VsiTerrainUpdate> voxelShapeUpdates = new ArrayList<>(chunkSections.length);
                        for (int i = 0; i < chunkSections.length; i++) {
                            final LevelChunkSection chunkSection = chunkSections[i];
                            final int sectionY = worldChunk.getSectionYFromSectionIndex(i);
                            voxelShapeUpdates.add(
                                chunkSection != null && !chunkSection.hasOnlyAir()
                                    ? VSGameUtilsKt.toDenseVoxelUpdate(chunkSection, new Vector3i(pos.x, sectionY, pos.z))
                                    : getVsCore().newEmptyVoxelShapeUpdate(pos.x, sectionY, pos.z, true)
                            );
                        }
                        final String dimensionId = getApi().getDimensionId(level);
                        if (shouldForce) {
                            for (final VsiTerrainUpdate update : voxelShapeUpdates) {
                                clientShipWorld.forceUpdateConnectivityChunk(
                                    dimensionId,
                                    update.getChunkX(),
                                    update.getChunkY(),
                                    update.getChunkZ(),
                                    update
                                );
                            }
                        } else {
                            clientShipWorld.addTerrainUpdates(dimensionId, voxelShapeUpdates);
                        }
                    }
                }

                relightChunk(worldChunk);
                while (this.level.getLightEngine().runLightUpdates() > 0) {
                    // Keep flushing until light propagation settles for this ship chunk.
                }

                if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
                    final IVSViewAreaMethods viewArea = (IVSViewAreaMethods)
                        ((LevelRendererAccessor) ((ClientLevelAccessor) level).getLevelRenderer()).getViewArea();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
                                final SectionRenderDispatcher.RenderSection renderSection =
                                    viewArea.vs$getShipRenderSection(x + dx, sy, z + dz);
                                if (renderSection != null) {
                                    renderSection.setDirty(true);
                                }
                            }
                        }
                    }
                }

                this.level.onChunkLoaded(pos);
                if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
                    SodiumCompat.onChunkAdded(this.level, x, z);
                }
                cir.setReturnValue(worldChunk);
            }
        }
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    public void preUnload(final ChunkPos chunkPos, final CallbackInfo ci) {
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;
        if (VSGameUtilsKt.isChunkInShipyard(level, chunkX, chunkZ)) {
            vs$shipChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
            if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
                ((IVSViewAreaMethods) ((LevelRendererAccessor) ((ClientLevelAccessor) level).getLevelRenderer()).getViewArea())
                    .valkyrienskies$unloadChunk(chunkX, chunkZ);
            }
            SodiumCompat.onChunkRemoved(this.level, chunkX, chunkZ);
            ci.cancel();
        }
    }

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), cancellable = true)
    public void preGetChunk(final int chunkX, final int chunkZ, final ChunkStatus chunkStatus, final boolean bl,
        final CallbackInfoReturnable<LevelChunk> cir) {
        if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(chunkX, chunkZ)) {
            return;
        }
        final LevelChunk shipChunk = vs$shipChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        if (shipChunk != null) {
            cir.setReturnValue(shipChunk);
            return;
        }
        cir.setReturnValue(getOrCreateEmptyChunk(chunkX, chunkZ));
    }

    @Unique
    private void removeShipChunk(final int chunkX, final int chunkZ) {
        final LevelChunk chunk = vs$shipChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
        emptyShipChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
        if (chunk == null) {
            return;
        }
        final ClientChunkCacheStorageAccessor clientChunkMapAccessor = ClientChunkCacheStorageAccessor.class.cast(storage);
        clientChunkMapAccessor.setChunkCount(clientChunkMapAccessor.getChunkCount() - 1);
        level.unload(chunk);
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
            ((IVSViewAreaMethods) ((LevelRendererAccessor) ((ClientLevelAccessor) level).getLevelRenderer()).getViewArea())
                .valkyrienskies$unloadChunk(chunkX, chunkZ);
        }
        SodiumCompat.onChunkRemoved(level, chunkX, chunkZ);
    }

    @Unique
    private void relightChunk(final LevelChunk chunk) {
        try {
            final LevelLightEngine lightEngine = this.level.getLightEngine();
            final ChunkPos cp = chunk.getPos();
            final int baseX = cp.getMinBlockX();
            final int baseZ = cp.getMinBlockZ();

            final LevelChunkSection[] sections = chunk.getSections();
            for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                final LevelChunkSection section = sections[sIdx];
                if (section != null && !section.hasOnlyAir()) {
                    final int sectionY = chunk.getSectionYFromSectionIndex(sIdx);
                    lightEngine.updateSectionStatus(SectionPos.of(cp.x, sectionY, cp.z), false);
                }
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    lightEngine.propagateLightSources(new ChunkPos(cp.x + dx, cp.z + dz));
                }
            }

            for (int sIdx = 0; sIdx < sections.length; sIdx++) {
                final LevelChunkSection section = sections[sIdx];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                final int sectionY = chunk.getSectionYFromSectionIndex(sIdx);
                final int baseY = sectionY << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            if (!section.getBlockState(lx, ly, lz).is(Blocks.AIR)) {
                                lightEngine.checkBlock(new BlockPos(baseX + lx, baseY + ly, baseZ + lz));
                            }
                        }
                    }
                }
            }
        } catch (final Exception ignored) {
            // Do not fail chunk application if relight bookkeeping changes across versions.
        }
    }

    @Unique
    private LevelChunk getOrCreateEmptyChunk(final int x, final int z) {
        final long posLong = ChunkPos.asLong(x, z);
        final LevelChunk cached = this.emptyShipChunks.get(posLong);
        if (cached != null) {
            return cached;
        }
        final LevelChunk chunk = new LevelChunk(this.level, new ChunkPos(x, z));
        this.emptyShipChunks.put(posLong, chunk);
        return chunk;
    }
}
