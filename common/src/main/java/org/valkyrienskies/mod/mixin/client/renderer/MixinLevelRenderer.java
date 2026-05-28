package org.valkyrienskies.mod.mixin.client.renderer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.client.IVSCamera;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private ShipTransform valkyrienskies$prevShipMountedToTransform = null;

    // When the ship-mount camera pulls the camera ~50 blocks back from the player, vanilla's
    // EntityRenderDispatcher.shouldRender returns false for the mounted LocalPlayer because
    // Entity.shouldRender(d,e,f) does a distance cull: dist < bb.getSize() * 64 * viewScale.
    // With default settings that threshold is ~60 blocks; with entityDistanceScaling < 1.0
    // it drops below the 50-block pullback and the standing-mounted player gets culled.
    // Bypass the distance cull while still consulting the frustum directly, so off-screen
    // is still off-screen.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceShouldRenderForMountedPlayer(
        final net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
        final Entity entity, final net.minecraft.client.renderer.culling.Frustum frustum,
        final double d, final double e, final double f,
        final Operation<Boolean> original) {
        if (!(entity instanceof net.minecraft.client.player.LocalPlayer)) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        final Entity vehicle = entity.getVehicle();
        if (vehicle == null
            || !(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)
            || !vehicle.level().getBlockState(vehicle.blockPosition()).isAir()) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        return frustum.isVisible(entity.getBoundingBox().inflate(0.5));
    }

    // Force camera.isDetached() to true for the standing-mounted LocalPlayer so they survive
    // the 1st-person skip in renderLevel. This is a backstop in case another mod (Iris shadow
    // pass, etc.) resets the live camera between updateCamera and the cull loop.
    //
    // 2.4.81: bail out when the user is actually in 1st person at a standing helm. Before
    // 2.4.80 the helm always forced 3rd person, so the backstop's lie was harmless. Now that
    // FIRST_PERSON is a legit slot in the F5 cycle, lying here makes vanilla skip the
    // "don't render own entity in 1st person" branch -- and the player's body renders at
    // the camera's eye position, so we look INSIDE our own head model. Respecting the
    // user's cameraType here lets vanilla's own-entity-skip work in 1st person while still
    // backstopping the 3rd-person slots.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;isDetached()Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceDetachedForShipMount(final Camera camera,
        final Operation<Boolean> original, @Local final Entity entity) {

        if (original.call(camera)) {
            return true;
        }
        // 2.4.81 1st-person fix: don't override when the user is actually in 1st person.
        if (this.minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (entity != camera.getEntity()) {
            return false;
        }
        final Entity vehicle = entity.getVehicle();
        if (vehicle == null) {
            return false;
        }
        if (!(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)) {
            return false;
        }
        if (!vehicle.level().getBlockState(vehicle.blockPosition()).isAir()) {
            return false;
        }
        return true;
    }

    /**
     * @reason This mixin forces the game to always render block damage.
     */
    @ModifyExpressionValue(
        method = "renderLevel",
        at = @At(value = "CONSTANT", args = "doubleValue=1024", ordinal = 0)
    )
    private double disableBlockDamageDistanceCheck(final double originalBlockDamageDistanceConstant) {
        return Double.MAX_VALUE;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void preRenderLevel(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        final ShipTransform shipMountedRenderTransform = ((IVSCamera) camera).getShipMountedRenderTransform();
        if (valkyrienskies$prevShipMountedToTransform != shipMountedRenderTransform) {
            if (valkyrienskies$prevShipMountedToTransform != null && shipMountedRenderTransform != null) {
                // Compute the angle between rotations
                double rotDot = Math.abs(valkyrienskies$prevShipMountedToTransform.getShipToWorldRotation().dot(shipMountedRenderTransform.getShipToWorldRotation()));
                rotDot = Math.min(rotDot, 1.0);
                double angle = 2.0 * Math.acos(rotDot);
                if (Math.toDegrees(angle) > 1.0) {
                    valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                    sectionOcclusionGraph.invalidate();
                }
            } else {
                valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                sectionOcclusionGraph.invalidate();
            }
        }
    }

    /**
     * This mixin makes block damage render on ships.
     */
    /*
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void renderBlockDamage(final BlockRenderDispatcher blockRenderManager, final BlockState state,
        final BlockPos blockPos, final BlockAndTintGetter blockRenderWorld, final PoseStack matrix,
        final VertexConsumer vertexConsumer, final Operation<Void> renderBreakingTexture) {


        final ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, blockPos);
        if (ship != null) {
            // Remove the vanilla render transform
            matrixStack.popPose();

            // Add the VS render transform
            matrixStack.pushPose();

            final ShipTransform renderTransform = ship.getRenderTransform();
            final Vec3 cameraPos = methodCamera.getPosition();

            transformRenderWithShip(renderTransform, matrixStack, blockPos, cameraPos.x, cameraPos.y, cameraPos.z);

            final Matrix3f newNormalMatrix = matrixStack.last().normal().copy();
            final Matrix4f newModelMatrix = matrixStack.last().pose().copy();

            // Then update the matrices in vertexConsumer (I'm guessing vertexConsumer is responsible for mapping
            // textures, so we need to update its matrices otherwise the block damage texture looks wrong)
            final SheetedDecalTextureGenerator newVertexConsumer =
                new SheetedDecalTextureGenerator(((OverlayVertexConsumerAccessor) vertexConsumer).getDelegate(),
                    newModelMatrix, newNormalMatrix);

            // Finally, invoke the render damage function.
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix,
                newVertexConsumer);
        } else {
            // Vanilla behavior
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix, vertexConsumer);
        }
    }

     */

}
