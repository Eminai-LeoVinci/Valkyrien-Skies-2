package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseModel;

/**
 * Applies the standing helm pose at the LAST possible moment -- renderToBuffer HEAD, after every
 * setupAnim/resetPose has run. See ShipMountPoseModel for why setupAnim's TAIL is too early.
 */
@Mixin(Model.class)
public abstract class MixinModel implements ShipMountPoseModel {

    @Unique
    private boolean vs$shipMountStanding;

    @Override
    public boolean vs$isShipMountStanding() {
        return this.vs$shipMountStanding;
    }

    @Override
    public void vs$setShipMountStanding(final boolean standing) {
        this.vs$shipMountStanding = standing;
    }

    @Inject(
        method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
        at = @At("HEAD")
    )
    private void vs$standAtShipMount(final PoseStack poseStack, final VertexConsumer buffer,
        final int light, final int overlay, final int color, final CallbackInfo ci) {
        if (!this.vs$shipMountStanding) {
            return;
        }
        if (!(((Object) this) instanceof HumanoidModel)) {
            return;
        }
        final HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        // Standing helm pose for the VANILLA player model (no EMF/CEM pack). Legs straight and
        // visible (undo any seated bend), arms reaching forward onto the wheel. When an EMF custom
        // model pack like FA+Player is active these writes never reach the drawn figure -- EMF draws
        // its own geometry -- so the standing pose for that case is handled by forcing is_riding=false
        // in MixinEMFStandAtHelm instead.
        model.rightLeg.visible = true;
        model.leftLeg.visible = true;
        model.rightLeg.xRot = 0.0F;
        model.rightLeg.yRot = 0.0F;
        model.rightLeg.zRot = 0.0F;
        model.leftLeg.xRot = 0.0F;
        model.leftLeg.yRot = 0.0F;
        model.leftLeg.zRot = 0.0F;
        model.rightArm.xRot = -1.4F;
        model.rightArm.yRot = 0.0F;
        model.rightArm.zRot = 0.0F;
        model.leftArm.xRot = -1.4F;
        model.leftArm.yRot = 0.0F;
        model.leftArm.zRot = 0.0F;
    }
}
