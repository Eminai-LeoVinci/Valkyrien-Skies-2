package org.valkyrienskies.mod.mixin.world.chunk;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.BlockStateInfo;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VSLevelChunk;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk extends ChunkAccess implements VSLevelChunk {
    @Shadow
    @Final
    Level level;

    @Unique
    private static final Set<Types> ALL_HEIGHT_MAP_TYPES = new HashSet<>(Arrays.asList((Heightmap.Types.values())));

    /**
     * Allow block entity ticking in shipyard chunks at FULL status (level 33).
     *
     * MC's isTicking checks getFullStatus().isOrAfter(BLOCK_TICKING), which requires
     * level ≤ 32. Ship chunks use level 33 (FULL) to minimize neighbor loading.
     * Without this, furnaces/hoppers/etc won't tick on ships.
     *
     * Note: On Forge 1.20.1 the ticking gate is shouldTickBlocksAt in MixinServerLevel,
     * so this may not be invoked. Kept as a safety net for other code paths.
     */
    @Inject(method = "isTicking", at = @At("HEAD"), cancellable = true)
    private void vs$allowShipyardBlockEntityTicking(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(
                pos.getX() >> 4, pos.getZ() >> 4)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Report shipyard chunks as BLOCK_TICKING status so that Level.markAndNotifyBlock()
     * calls sendBlockUpdated(), which triggers ServerChunkCache.blockChanged() and
     * ultimately ChunkHolder.broadcastChanges() to send block update packets to clients.
     *
     * Without this, ship chunks at level 33 (FULL status) fail the
     * getFullStatus().isOrAfter(BLOCK_TICKING) check and block updates are silently dropped.
     */
    @Inject(method = "getFullStatus", at = @At("HEAD"), cancellable = true)
    private void vs$upgradeShipyardChunkStatus(CallbackInfoReturnable<FullChunkStatus> cir) {
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(
                this.chunkPos.x, this.chunkPos.z)) {
            cir.setReturnValue(FullChunkStatus.BLOCK_TICKING);
        }
    }

    // Dummy constructor
    public MixinLevelChunk(final Ship ship) {
        super(null, null, null, null, 0, null, null);
        throw new IllegalStateException("This should never be called!");
    }

    @Inject(method = "setBlockState", at = @At("TAIL"))
    public void postSetBlockState(final BlockPos pos, final BlockState state, final int flags,
        final CallbackInfoReturnable<BlockState> cir) {
        final BlockState prevState = cir.getReturnValue();
        // This function is getting invoked by non-game threads for some reason. So use executeOrSchedule() to schedule
        // onSetBlock() to be run on the next tick when this function is invoked by a non-game thread.
        // See https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/913 for more info.
        VSGameUtilsKt.executeOrSchedule(level, () -> BlockStateInfo.INSTANCE.onSetBlock(level, pos, prevState, state));
    }

    @Shadow
    public abstract void clearAllBlockEntities();

    @Shadow
    public abstract void registerTickContainerInLevel(ServerLevel serverLevel);

    @Shadow
    public abstract void unregisterTickContainerFromLevel(ServerLevel serverLevel);

    /**
     * Clears this chunk to empty terrain. Used by cross-dimension ship transfer to wipe the SOURCE chunk
     * once its blocks have been copied into the destination dimension.
     *
     * <p>1.21.11 port: the old {@code new LevelChunkSection(Registry<Biome>)} ctor and the public
     * {@code unsaved} field are gone -- empty sections now come from the level's {@link
     * PalettedContainerFactory} ({@link Level#palettedContainerFactory()}) and the dirty flag is set via
     * {@link #markUnsaved()}.
     */
    @Override
    public void clearChunk() {
        final ServerLevel serverLevel = (ServerLevel) level;
        final PalettedContainerFactory pcf = level.palettedContainerFactory();

        clearAllBlockEntities();
        unregisterTickContainerFromLevel(serverLevel);

        heightmaps.clear();
        Arrays.fill(sections, null);
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(pcf);
        }
        this.setLightCorrect(false);

        registerTickContainerInLevel(serverLevel);
        markUnsaved();
    }

    /**
     * Copies another dimension's chunk into this one (cross-dimension ship transfer -- e.g. sailing a ship
     * through a Nether portal).
     *
     * <p>1.21.11 port: the old {@code ChunkSerializer.write/read} path is replaced by {@link
     * SerializableChunkData}. {@code copyOf().write()} snapshots the source chunk to NBT and {@code
     * parse().read()} rebuilds a STANDALONE chunk in this dimension -- the NBT round-trip is what makes
     * this a true deep copy, so the two dimensions never share section / block-entity objects. A FULL
     * source chunk deserializes as an {@link ImposterProtoChunk} wrapping a real {@link LevelChunk}; we
     * unwrap it and transplant its sections + block entities into this chunk, then re-prime heightmaps.
     *
     * <p>Pending scheduled block/fluid ticks and post-processing are intentionally NOT carried across: they
     * are transient sub-tick state that re-settles on the next interaction, so the destination keeps its own
     * (empty) tick lists rather than transplanting the source's.
     */
    @Override
    public void copyChunkFromOtherDimension(@NotNull final VSLevelChunk srcChunkVS) {
        final ServerLevel destLevel = (ServerLevel) level;
        final PalettedContainerFactory pcf = level.palettedContainerFactory();

        clearAllBlockEntities();
        unregisterTickContainerFromLevel(destLevel);
        heightmaps.clear();
        Arrays.fill(sections, null);

        final LevelChunk srcChunk = (LevelChunk) srcChunkVS;
        final CompoundTag chunkNbt = SerializableChunkData
            .copyOf((ServerLevel) srcChunk.getLevel(), srcChunk)
            .write();
        // RegionStorageInfo is only used by read() for error labelling (we feed the NBT directly, not a
        // region file on disk), so a descriptive label is sufficient.
        final RegionStorageInfo storageInfo =
            new RegionStorageInfo(destLevel.dimension().toString(), destLevel.dimension(), "chunk");
        final ProtoChunk loaded = SerializableChunkData
            .parse(destLevel, pcf, chunkNbt)
            .read(destLevel, destLevel.getPoiManager(), storageInfo, chunkPos);

        // A full chunk reads back as an ImposterProtoChunk wrapping a standalone LevelChunk; unwrap it.
        final ChunkAccess loadedChunk =
            loaded instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : loaded;

        final LevelChunkSection[] loadedSections = loadedChunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            sections[i] = i < loadedSections.length && loadedSections[i] != null
                ? loadedSections[i] : new LevelChunkSection(pcf);
        }
        this.blendingData = loadedChunk.getBlendingData();

        // Re-home block entities into this chunk.
        if (loadedChunk instanceof LevelChunk loadedLevelChunk) {
            for (final BlockEntity blockEntity : loadedLevelChunk.getBlockEntities().values()) {
                this.setBlockEntity(blockEntity);
            }
        } else if (loaded instanceof ProtoChunk loadedProto) {
            for (final BlockEntity blockEntity : loadedProto.getBlockEntities().values()) {
                this.setBlockEntity(blockEntity);
            }
            this.pendingBlockEntities.putAll(loadedProto.getBlockEntityNbts());
        }

        // Recompute heightmaps (avoids crashes from missing maps), reset lighting, re-register ticks.
        Heightmap.primeHeightmaps(this, ALL_HEIGHT_MAP_TYPES);
        this.setLightCorrect(false);
        registerTickContainerInLevel(destLevel);
        markUnsaved();
    }
}
