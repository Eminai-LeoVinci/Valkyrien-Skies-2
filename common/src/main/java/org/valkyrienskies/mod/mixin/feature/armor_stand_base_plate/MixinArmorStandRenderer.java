package org.valkyrienskies.mod.mixin.feature.armor_stand_base_plate;

import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;
import org.valkyrienskies.mod.mixinducks.client.render.ArmorStandBasePlateRenderState;

/**
 * Stamps the "being dragged by a ship" flag onto the armor stand's render state during extraction,
 * so {@code ArmorStandModel.setupAnim} (which only receives the render state, not the entity) can
 * deck-lock the base plate. Mirrors {@code ship_mount_pose/MixinAvatarRenderer}.
 */
@Mixin(ArmorStandRenderer.class)
public class MixinArmorStandRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ArmorStand;Lnet/minecraft/client/renderer/entity/state/ArmorStandRenderState;F)V",
        at = @At("TAIL"),
        require = 1
    )
    private void vs$markDeckCarried(final ArmorStand armorStand, final ArmorStandRenderState state,
                                    final float partialTick, final CallbackInfo ci) {
        // Gate on lastShipStoodOn (has-a-ship), NOT isEntityBeingDraggedByAShip() (a 25-tick countdown
        // that only stays alive while ship packets arrive). A PARKED stand gets no packets, so the
        // countdown lapses every ~2s and the base plate reverts to its vanilla compass rotation (the
        // "snap to south" the user saw) until a forced sync re-arms it. The body is unaffected (it uses
        // the carry's persisted yaw). lastShipStoodOn stays set while on the ship, so the base stays
        // deck-locked when parked too. (Trade-off: a stand carried OFF the ship onto land keeps a
        // deck-aligned base until lastShipStoodOn clears -- cosmetic and rare.)
        final boolean carried = armorStand instanceof IEntityDraggingInformationProvider p
            && p.getDraggingInformation().getLastShipStoodOn() != null;
        ((ArmorStandBasePlateRenderState) state).vs$setDeckCarried(carried);
    }
}
