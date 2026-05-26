package org.valkyrienskies.mod.mixin.client.renderer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.mixinducks.client.render.IVSViewAreaMethods;

/**
 * The purpose of this mixin is to allow {@link ViewArea} to render ship chunks.
 */
@Mixin(ViewArea.class)
public class MixinViewAreaVanilla implements IVSViewAreaMethods {

    @Shadow
    @Final
    protected Level level;

    @Shadow
    protected int sectionGridSizeY;

    // Maps chunk position to an array of BuiltChunk, indexed by the y value.
    @Unique
    private final Long2ObjectMap<SectionRenderDispatcher.RenderSection[]> vs$shipRenderChunks =
        new Long2ObjectOpenHashMap<>();
    // This creates render chunks
    @Unique
    private SectionRenderDispatcher vs$sectionRenderDispatcher;

    /**
     * This mixin stores the [chunkBuilder] object from the constructor. It is used to create new render chunks.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void postInit(SectionRenderDispatcher sectionRenderDispatcher, final Level world, final int viewDistance,
        final LevelRenderer worldRenderer, final CallbackInfo callbackInfo) {

        this.vs$sectionRenderDispatcher = sectionRenderDispatcher;
    }

    /**
     * This mixin creates render chunks for ship chunks.
     */
    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void preScheduleRebuild(final int x, final int y, final int z, final boolean important,
        final CallbackInfo callbackInfo) {

        final int yIndex = y - level.getMinSectionY();

        if (yIndex < 0 || yIndex >= sectionGridSizeY) {
            return; // Weird, but just ignore it
        }

        if (VSGameUtilsKt.isChunkInShipyard(level, x, z)) {
            final long chunkPosAsLong = ChunkPos.asLong(x, z);
            final SectionRenderDispatcher.RenderSection[] renderChunksArray = vs$shipRenderChunks.get(chunkPosAsLong);
            if (renderChunksArray != null && renderChunksArray[yIndex] != null) {
                renderChunksArray[yIndex].setDirty(important);
            }

            callbackInfo.cancel();
        }
    }

    /**
     * This mixin allows {@link ViewArea} to return the render chunks for ships.
     */
    @Inject(method = "getRenderSectionAt", at = @At("HEAD"), cancellable = true)
    private void preGetRenderedChunk(final BlockPos pos,
        final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> callbackInfoReturnable) {
        final int chunkX = Mth.floorDiv(pos.getX(), 16);
        final int chunkY = Mth.floorDiv(pos.getY() - level.getMinY(), 16);
        final int chunkZ = Mth.floorDiv(pos.getZ(), 16);

        if (chunkY < 0 || chunkY >= sectionGridSizeY) {
            return; // Weird, but ignore it
        }

        if (VSGameUtilsKt.isChunkInShipyard(level, chunkX, chunkZ)) {
            final long chunkPosAsLong = ChunkPos.asLong(chunkX, chunkZ);
            final SectionRenderDispatcher.RenderSection[] renderChunksArray = vs$shipRenderChunks.get(chunkPosAsLong);
            if (renderChunksArray == null) {
                callbackInfoReturnable.setReturnValue(null);
                return;
            }
            final SectionRenderDispatcher.RenderSection renderChunk = renderChunksArray[chunkY];
            callbackInfoReturnable.setReturnValue(renderChunk);
        }
    }

    @Override
    public SectionRenderDispatcher.RenderSection vs$getShipRenderSection(final int chunkX, final int sectionY, final int chunkZ) {
        final int yIndex = sectionY - level.getMinSectionY();
        if (yIndex < 0 || yIndex >= sectionGridSizeY) {
            return null;
        }
        final SectionRenderDispatcher.RenderSection[] arr = vs$shipRenderChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        return arr != null ? arr[yIndex] : null;
    }

    @Override
    public SectionRenderDispatcher.RenderSection vs$getOrCreateShipRenderSection(
        final int chunkX, final int sectionY, final int chunkZ
    ) {
        final int yIndex = sectionY - level.getMinSectionY();
        if (yIndex < 0 || yIndex >= sectionGridSizeY) {
            return null;
        }
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final SectionRenderDispatcher.RenderSection[] arr =
            vs$shipRenderChunks.computeIfAbsent(key, k -> new SectionRenderDispatcher.RenderSection[sectionGridSizeY]);
        if (arr[yIndex] == null) {
            arr[yIndex] = vs$sectionRenderDispatcher.new RenderSection(0, SectionPos.asLong(chunkX, sectionY, chunkZ));
        }
        arr[yIndex].setDirty(true);
        return arr[yIndex];
    }

    @Override
    public void valkyrienskies$unloadChunk(final int chunkX, final int chunkZ) {
        if (VSGameUtilsKt.isChunkInShipyard(level, chunkX, chunkZ)) {
            final SectionRenderDispatcher.RenderSection[] chunks =
                vs$shipRenderChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
            if (chunks != null) {
                for (final SectionRenderDispatcher.RenderSection chunk : chunks) {
                    if (chunk != null) {
                        chunk.reset();
                    }
                }
            }
        }
    }

    /**
     * Clear VS ship render chunks so that we don't leak memory
     */
    @Inject(method = "releaseAllBuffers", at = @At("HEAD"))
    private void postReleaseAllBuffers(final CallbackInfo ci) {
        for (final Entry<SectionRenderDispatcher.RenderSection[]> entry : vs$shipRenderChunks.long2ObjectEntrySet()) {
            for (final SectionRenderDispatcher.RenderSection renderChunk : entry.getValue()) {
                if (renderChunk != null) {
                    renderChunk.reset();
                }
            }
        }
        vs$shipRenderChunks.clear();
    }
}
