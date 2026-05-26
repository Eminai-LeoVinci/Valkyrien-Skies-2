package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

@Mixin(AvatarRenderState.class)
public class MixinAvatarRenderState implements ShipMountPoseRenderState {

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
}
