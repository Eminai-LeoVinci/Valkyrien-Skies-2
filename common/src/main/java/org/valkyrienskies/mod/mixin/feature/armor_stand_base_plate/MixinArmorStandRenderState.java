package org.valkyrienskies.mod.mixin.feature.armor_stand_base_plate;

import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.mod.mixinducks.client.render.ArmorStandBasePlateRenderState;

@Mixin(ArmorStandRenderState.class)
public class MixinArmorStandRenderState implements ArmorStandBasePlateRenderState {

    @Unique
    private boolean vs$deckCarried;

    @Override
    public boolean vs$isDeckCarried() {
        return this.vs$deckCarried;
    }

    @Override
    public void vs$setDeckCarried(final boolean carried) {
        this.vs$deckCarried = carried;
    }
}
