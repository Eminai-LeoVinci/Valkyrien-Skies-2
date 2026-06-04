package org.valkyrienskies.mod.mixin.client.renderer;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;

/**
 * Flush the ship-terrain GPU-buffer draw queue at the correct point in the frame.
 *
 * <p>The GPU render path bakes each ship section once into a persistent vertex buffer and redraws it
 * every frame with a per-section model-view, instead of re-emitting every block's vertices. The queue
 * is BUILT at submitBlockEntities TAIL (see MixinLevelRenderer), but it must not be DRAWN there: at
 * submit time Iris has not yet bound its gbuffer target, so the draw lands on the wrong framebuffer
 * and is composited away (the ship renders correctly from submit time only when no shaderpack is
 * active). renderAllFeatures is called immediately after submitBlockEntities and is where vanilla then
 * flushes this frame's immediate ship terrain (solidMovingBlock) -- Iris's target override is live
 * here. Flushing at TAIL puts the GPU draws at the same point, so they render under shaders too.
 *
 * <p>renderAllFeatures is invoked more than once per frame; flushDeferredGpuDraws self-gates on an
 * empty queue and clears it after drawing, so exactly the first call after the queue is built flushes
 * it -- order-independent of the shadow pass. No-op entirely when the GPU path is toggled off.
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class MixinFeatureRenderDispatcher {

    @Inject(method = "renderAllFeatures", at = @At("TAIL"), require = 1)
    private void valkyrienskies$flushShipGpuDraws(final CallbackInfo ci) {
        ShipTerrainMeshCache.INSTANCE.flushDeferredGpuDraws();
    }
}
