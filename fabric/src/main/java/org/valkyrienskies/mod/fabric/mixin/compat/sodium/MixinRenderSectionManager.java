package org.valkyrienskies.mod.fabric.mixin.compat.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {

    @Shadow
    @Final
    private ChunkRenderer chunkRenderer;

    @Shadow
    @Final
    private SortBehavior sortBehavior;

    // FIX (chunk see-through holes, shaders only): the bisection proved THIS per-frame ship draw
    // corrupts streaming terrain, and ONLY under Iris. It is NOT a GPU race (forcing glFinish before
    // the ship draw did nothing) and NOT leftover transform state (a post-draw state reset did
    // nothing). What remains: terrain used to draw FIRST, then the ship drew into the same Iris
    // terrain pass -- and the ship draw disturbs the terrain Iris has already written to its
    // G-buffer, so those sections later read as sky (the holes).
    //
    // Fix: draw ships at the HEAD of renderLayer, BEFORE Sodium's own terrain render runs. Terrain
    // is then the last thing written to the G-buffer each pass, so nothing the ship draw does can
    // corrupt it. Depth testing keeps opaque geometry correct regardless of draw order. We fetch the
    // immediate command list the same way Sodium's renderLayer does (RenderDevice.INSTANCE).
    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void redirectRenderLayer(final ChunkRenderMatrices matrices, final TerrainRenderPass pass,
        final double camX, final double camY, final double camZ, final FogParameters fogParameters,
        final GpuSampler gpuSampler, final CallbackInfo ci) {

        final var shipRenderLists = ((RenderSectionManagerDuck) this).vs_getShipRenderLists();
        if (shipRenderLists.isEmpty()) {
            return;
        }

        final CommandList commandList = RenderDevice.INSTANCE.createCommandList();
        // Sodium's renderLayer passes (sortBehavior != OFF) as render()'s translucency-sort flag — mirror it.
        final boolean sortTranslucent = sortBehavior != SortBehavior.OFF;

        shipRenderLists.forEach((ship, renderList) -> {
            final Matrix4f newModelView = new Matrix4f(matrices.modelView());
            final Vector3dc center = ship.getRenderTransform().getPositionInShip();
            VSClientGameUtils.transformRenderWithShip(ship.getRenderTransform(), newModelView, center.x(), center.y(),
                center.z(), camX, camY, camZ);

            final ChunkRenderMatrices newMatrices = new ChunkRenderMatrices(matrices.projection(), newModelView);
            chunkRenderer.render(newMatrices, commandList, renderList, pass,
                new CameraTransform(center.x(), center.y(), center.z()), fogParameters, sortTranslucent, gpuSampler);
            commandList.close();
        });
    }
}
