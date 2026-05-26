package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

@Mixin(AvatarRenderer.class)
public class MixinAvatarRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void vs$markShipMountPose(final Avatar avatar, final AvatarRenderState state,
                                      final float partialTick, final CallbackInfo ci) {
        final Entity vehicle = avatar.getVehicle();
        // A ShipMountingEntity seat over an air block is a helm steered standing up; a solid
        // block under the seat is a chair the player sits on (see ShipHelmBlockEntity.spawnSeat).
        final boolean standing = vehicle instanceof ShipMountingEntity
            && vehicle.level().getBlockState(vehicle.blockPosition()).isAir();
        ((ShipMountPoseRenderState) state).vs$setShipMountStanding(standing);
    }
}
