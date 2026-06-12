package org.valkyrienskies.mod.fabric.mixin.compat.emf;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

/**
 * Entity Model Features (EMF) compat for the standing helm pose.
 *
 * <p>EMF renders OptiFine/CEM custom player models -- e.g. Fresh Animations' "FA+Player" pack,
 * which replaces the vanilla player model with its own animated one. FA gates its seated pose
 * entirely on the CEM variable {@code is_riding} ({@code var.sit = if(is_riding,1,0)}), which EMF
 * resolves through {@code EMFAnimationEntityContext.isRiding() -> emfState.hasVehicle()} -- read
 * from the LIVE entity's vehicle. That ignores the vanilla render-state flags VS2 clears, so the
 * helmsman renders seated even though VS2 already poses the vanilla model standing. (This is why
 * every vanilla-side fix failed against FA: EMF draws its own geometry and never reads our writes.)
 *
 * <p>Here we force {@code is_riding} to false for exactly the helm rider -- the player whose
 * AvatarRenderState carries the {@link ShipMountPoseRenderState} standing flag (set in
 * MixinAvatarRenderer) -- so FA plays its normal standing/idle animation at the wheel. Every other
 * rider (boats, mounts, other players) is untouched.
 *
 * <p>String target + shadow stub on purpose: VS2's EMF compile dependency is a stale 1.20.1 build
 * that predates this render-state API, but the installed runtime (3.2.x for 1.21.11) has it. A
 * string target also makes this a soft mixin that simply no-ops when EMF is not installed.
 */
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
public abstract class MixinEMFStandAtHelm {

    @Shadow
    static EntityRenderState getEntityRenderState() {
        throw new AssertionError();
    }

    @Inject(method = "isRiding", at = @At("HEAD"), cancellable = true)
    private static void vs$standAtShipHelm(final CallbackInfoReturnable<Boolean> cir) {
        final EntityRenderState state = getEntityRenderState();
        if (state instanceof ShipMountPoseRenderState
            && ((ShipMountPoseRenderState) state).vs$isShipMountStanding()) {
            cir.setReturnValue(false);
        }
    }
}
