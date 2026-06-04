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
import org.valkyrienskies.mod.common.util.PoseDebug;
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
        // Every ShipMountingEntity a player rides is a ship's helm (Eureka's spawnSeat always
        // marks the seat as the controller), and the helmsman stands at the wheel. Decide this
        // purely from the client-side vehicle type: the seat lives in a shipyard chunk the
        // client never loads, so it is never entity-tracked and server-synced flags don't
        // reliably reach the client -- but the seat entity itself is delivered (the player is
        // mounted on it), so this instanceof check is reliable. (The previous air-block probe
        // read shipyard-space coordinates and regressed the helm rider to a seated pose.)
        final boolean standing = vehicle instanceof ShipMountingEntity;
        ((ShipMountPoseRenderState) state).vs$setShipMountStanding(standing);
        if (standing) {
            // Kill the seated pose at its SOURCE. HumanoidModel.setupAnim only bends the legs
            // into the sit when state.isPassenger is true; clearing it here means the bend is
            // never created in the first place. Straightening the legs afterward in
            // PlayerModel.setupAnim's TAIL provably ran (see VS-TD-POSE logs) yet the rider
            // still rendered seated -- something re-applies the riding pose between setupAnim
            // and the draw, and whatever that is keys off isPassenger. Removing the trigger
            // defeats it regardless of source; the standing flag (above) still drives the
            // arms-on-wheel pose in MixinPlayerModel.
            state.isPassenger = false;
        }
        if (vehicle != null) {
            PoseDebug.change("extract.vehicle",
                vehicle.getClass().getName() + " standing=" + standing + " isPassenger=" + state.isPassenger);
        }
    }
}
