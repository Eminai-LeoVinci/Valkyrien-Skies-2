package org.valkyrienskies.mod.mixin.client.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;

/**
 * Client-only: keep the baked ship terrain mesh in sync for block changes that arrive through a
 * path which never reaches {@code LevelRenderer.setSectionDirty} for shipyard chunks -- i.e.
 * server-pushed, non-client-predicted updates. Client-PREDICTED edits (placing/breaking blocks)
 * already invalidate via the {@code setSectionDirty(IIIZ)} hook in
 * {@code mixin.client.renderer.MixinLevelRenderer}; this catches everything else: trapdoors / doors
 * / fence gates toggling, redstone, pistons, observers, crops growing, leaves decaying, fire
 * spreading, and so on. Symptom without it: a toggled trapdoor on a ship keeps its old mesh until a
 * neighbouring block in the same section happens to be edited.
 *
 * <p>{@code setBlockState} is the universal block-mutation chokepoint, so a TAIL hook here fires no
 * matter which route the change took (the new state demonstrably lands in the client's shipyard
 * chunk -- a neighbour edit reveals it). Lives in the client mixin set so the client-only
 * {@link ShipTerrainMeshCache} is never loaded on a dedicated server. The integrated server's
 * chunks share this class, so the {@code isClientSide} gate keeps it off the server thread, and the
 * invalidation is marshalled to the client thread because the cache is a plain HashMap the renderer
 * also touches.
 */
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkClientRender {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void valkyrienskies$invalidateShipMeshOnBlockChange(final BlockPos pos, final BlockState state,
        final int flags, final CallbackInfoReturnable<BlockState> cir) {
        if (!level.isClientSide) {
            return;
        }
        final BlockState prevState = cir.getReturnValue();
        if (prevState == null || prevState == state) {
            return;
        }
        if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        final int sx = pos.getX() >> 4;
        final int sy = pos.getY() >> 4;
        final int sz = pos.getZ() >> 4;
        VSGameUtilsKt.executeOrSchedule(level,
            () -> ShipTerrainMeshCache.INSTANCE.invalidateSection(sx, sy, sz));
    }
}
