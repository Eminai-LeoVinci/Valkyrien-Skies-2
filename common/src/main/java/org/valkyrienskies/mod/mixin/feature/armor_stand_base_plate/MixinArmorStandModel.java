package org.valkyrienskies.mod.mixin.feature.armor_stand_base_plate;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.armorstand.ArmorStandModel;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.render.ArmorStandBasePlateRenderState;

/**
 * Deck-lock the armor-stand BASE PLATE for ship-carried stands.
 *
 * <p>Vanilla {@code setupAnim} sets {@code basePlate.yRot = -(pi/180) * state.yRot} -- a deliberate
 * world-locking ("compass-needle") term: it cancels the {@code yRot} the shared body PoseStack
 * rotation already contains, so a hand-rotated stand keeps its base pointing a fixed world
 * direction. But for a SHIP-CARRIED stand the EntityDragger carry rotates the entity {@code yRot}
 * by the ship's yaw delta every tick, and the base plate then counts that delta TWICE -- once via
 * the shared body rotation (which correctly deck-locks the body) and once via this {@code -yRot}
 * term -- so the base over-rotates by one ship-delta per tick and spins relative to the deck while
 * the body stays put.
 *
 * <p>For a carried stand we zero this base-plate-only term, so the base plate inherits ONLY the
 * shared (already deck-locked) body rotation and turns WITH the ship exactly like the body.
 * Non-carried stands are untouched (vanilla compass behavior preserved). Only {@code basePlate.yRot}
 * is written -- head/body/arms (which already deck-lock correctly) are not touched. Marker stands
 * hide the base plate, so they are unaffected regardless.
 */
@Mixin(ArmorStandModel.class)
public class MixinArmorStandModel {

    @Shadow
    @Final
    private ModelPart basePlate;

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/ArmorStandRenderState;)V",
        at = @At("TAIL"),
        require = 1
    )
    private void vs$deckLockBasePlate(final ArmorStandRenderState state, final CallbackInfo ci) {
        if (((ArmorStandBasePlateRenderState) state).vs$isDeckCarried()) {
            this.basePlate.yRot = 0.0F;
        }
    }
}
