package org.valkyrienskies.mod.fabric.mixin.compat.shouldersurfing;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

/**
 * Shoulder Surfing Reloaded (SSR) compat: drop the shoulder-surfing perspective from the F5
 * cycle while the player is riding a VS2 ship mount (the helm seat).
 *
 * <p>SSR always forces SHOULDER_SURFING into its perspective cycle -- there is no config toggle to
 * remove it -- so a helmsman gets four F5 stages: first person, vanilla third-person back,
 * third-person front (which VS2 repurposes as the immersive ship view), and shoulder surfing.
 * At the wheel the shoulder stage is redundant; we want exactly first -> player back -> ship view
 * -> (loop). SSR computes the cycle in {@code Perspective.next(IClientConfig)}; when that lands on
 * SHOULDER_SURFING and the player is on a ship mount we hand back FIRST_PERSON instead, so the ship
 * (third-person front) view is the last stage before the cycle loops to first person. Shoulder
 * surfing while walking around is untouched.
 *
 * <p>Soft string-target mixin (remap=false, defaultRequire=0): no SSR compile dependency, and it
 * simply no-ops when SSR is absent. SSR's enum is matched by name and FIRST_PERSON is recovered as
 * enum ordinal 0, so no SSR type is referenced directly.
 */
@Mixin(targets = "com.github.exopandora.shouldersurfing.api.model.Perspective", remap = false)
public abstract class MixinSSRPerspectiveCycle {

    @Inject(method = "next", at = @At("RETURN"), cancellable = true)
    private void vs$skipShoulderOnShipMount(final CallbackInfoReturnable<Object> cir) {
        final Object next = cir.getReturnValue();
        if (!(next instanceof Enum<?>) || !"SHOULDER_SURFING".equals(((Enum<?>) next).name())) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null
            || !(mc.player.getVehicle() instanceof ShipMountingEntity)) {
            return;
        }
        // Skip shoulder: hand back FIRST_PERSON (enum ordinal 0) so the immersive ship view is the
        // last F5 stage and the next press returns to first person.
        cir.setReturnValue(((Enum<?>) next).getDeclaringClass().getEnumConstants()[0]);
    }
}
