package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseModel;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

@Mixin(PlayerModel.class)
public abstract class MixinPlayerModel extends HumanoidModel<AvatarRenderState> {

    public MixinPlayerModel(final ModelPart modelPart) {
        super(modelPart);
    }

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
        at = @At("TAIL"),
        require = 1
    )
    private void vs$standAtShipMount(final AvatarRenderState state, final CallbackInfo ci) {
        final boolean flag = ((ShipMountPoseRenderState) state).vs$isShipMountStanding();
        // Only RECORD the flag here. The pose itself is applied at renderToBuffer HEAD (MixinModel),
        // because setupAnim starts with resetPose() and the deferred pipeline re-runs setupAnim
        // right before the draw -- any rotation set here is wiped before geometry is built.
        ((ShipMountPoseModel) (Object) this).vs$setShipMountStanding(flag);
    }
}
