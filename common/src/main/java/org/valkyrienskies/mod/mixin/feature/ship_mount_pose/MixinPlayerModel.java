package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

@Mixin(PlayerModel.class)
public abstract class MixinPlayerModel extends HumanoidModel<AvatarRenderState> {

    public MixinPlayerModel(final ModelPart modelPart) {
        super(modelPart);
    }

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
        at = @At("TAIL")
    )
    private void vs$standAtShipMount(final AvatarRenderState state, final CallbackInfo ci) {
        if (!((ShipMountPoseRenderState) state).vs$isShipMountStanding()) {
            return;
        }
        // Override the vanilla seated pose: stand upright, both arms reaching forward
        // as if hands are resting on the helm wheel.
        this.rightLeg.xRot = 0.0F;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;
        this.leftLeg.xRot = 0.0F;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;
        this.rightArm.xRot = -1.4F;
        this.rightArm.yRot = 0.0F;
        this.rightArm.zRot = 0.0F;
        this.leftArm.xRot = -1.4F;
        this.leftArm.yRot = 0.0F;
        this.leftArm.zRot = 0.0F;
    }
}
