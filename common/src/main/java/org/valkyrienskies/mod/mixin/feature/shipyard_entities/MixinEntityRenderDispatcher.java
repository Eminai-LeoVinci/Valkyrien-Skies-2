package org.valkyrienskies.mod.mixin.feature.shipyard_entities;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(value = EntityRenderDispatcher.class, priority = 500)
public class MixinEntityRenderDispatcher {

    @Shadow
    public Camera camera;

    @Inject(method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D", at = @At("HEAD"), cancellable = true)
    private void preDistanceToSqr(final Entity entity, final CallbackInfoReturnable<Double> cir) {
        final Vec3 pos = entity.position();
        // entity seems to be null sometimes when the "real camera" mod is used
        if (entity == null) return;
        cir.setReturnValue(VSGameUtilsKt.squaredDistanceToInclShips(entity, pos.x, pos.y, pos.z));
    }

    /**
     * Shipyard entities (item frames, paintings, ...) physically live in their ship's shipyard,
     * millions of blocks from where the ship visually appears. EntityRenderDispatcher.submit
     * translates an entity to (renderState - camera), which for a shipyard entity points at the
     * empty shipyard. Re-apply the ship's render transform here so the entity draws on the ship.
     *
     * <p>1.21.5+ replaced the immediate EntityRenderer.render(...) call with a deferred
     * EntityRenderer.submit(...) into a SubmitNodeCollector. The PoseStack is snapshotted into the
     * submitted nodes (vanilla pops it right after submitting), so transforming it just before the
     * submit call works the same way the old render-path inject did.
     */
    @Inject(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V"
        ),
        require = 1
    )
    private void valkyrienskies$transformShipyardEntity(
        final EntityRenderState renderState, final CameraRenderState cameraRenderState,
        final double camX, final double camY, final double camZ,
        final PoseStack poseStack, final SubmitNodeCollector nodeCollector,
        final CallbackInfo ci, @Local final Vec3 renderOffset
    ) {
        final Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        final BlockPos blockPos = BlockPos.containing(renderState.x, renderState.y, renderState.z);
        final ClientShip ship = (ClientShip) VSGameUtilsKt.getLoadedShipManagingPos(level, blockPos);
        if (ship == null) {
            return;
        }

        // Undo the camera-relative translation EntityRenderDispatcher.submit just applied...
        poseStack.popPose();
        poseStack.pushPose();

        // ...and replace it with the ship's render transform. Mirrors
        // AbstractShipyardEntityHandler.applyRenderTransform.
        final ShipTransform renderTransform = ship.getRenderTransform();
        final Vector3dc transformed = renderTransform.getShipToWorld()
            .transformPosition(new Vector3d(renderState.x, renderState.y, renderState.z), new Vector3d());
        final Vector3dc scale = renderTransform.getShipToWorldScaling();

        poseStack.translate(
            transformed.x() + (camX - renderState.x),
            transformed.y() + (camY - renderState.y),
            transformed.z() + (camZ - renderState.z));
        poseStack.mulPose(new Quaternionf(renderTransform.getShipToWorldRotation()));
        poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());

        // EntityRenderDispatcher.submit already translated the PoseStack by getRenderOffset()
        // before this point, and the entity renderer (e.g. ItemFrameRenderer) translates by
        // -getRenderOffset() right after. We discarded the dispatcher's translation above, so
        // re-apply the offset here in ship space so the renderer's counter-translation still
        // cancels out (otherwise item frames render ~0.3 blocks off the ship surface).
        poseStack.translate(renderOffset.x(), renderOffset.y(), renderOffset.z());
    }

    @ModifyReturnValue(
        method = "shouldRender",
        at = @At("RETURN")
    )
    boolean shouldRender(final boolean returns, final Entity entity, final Frustum frustum,
        final double camX, final double camY, final double camZ) {

        if (!returns) {
            final ClientShip ship =
                (ClientShip) VSGameUtilsKt.getLoadedShipManagingPos(entity.level(), entity.blockPosition());
            if (ship != null) {
                AABB aABB = entity.getBoundingBox().inflate(0.5);
                if (aABB.hasNaN() || aABB.getSize() == 0.0) {
                    aABB = new AABB(entity.getX() - 2.0, entity.getY() - 2.0,
                        entity.getZ() - 2.0, entity.getX() + 2.0,
                        entity.getY() + 2.0, entity.getZ() + 2.0);
                }
                final AABBd aabb = VectorConversionsMCKt.toJOML(aABB);

                // Get the in world position and do it minus what the aabb already has and then add the offset
                aabb.transform(ship.getRenderTransform().getShipToWorld());
                return frustum.isVisible(VectorConversionsMCKt.toMinecraft(aabb));
            }
        }

        return returns;
    }

}
