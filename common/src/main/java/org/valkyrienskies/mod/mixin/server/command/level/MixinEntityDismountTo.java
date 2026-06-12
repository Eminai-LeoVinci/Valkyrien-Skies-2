package org.valkyrienskies.mod.mixin.server.command.level;

import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

/**
 * 1.21.11: ServerPlayer no longer overrides dismountTo, so the ship-aware dismount handling
 * (previously in {@link MixinServerPlayer}) injects into Entity#dismountTo with a
 * ServerPlayer guard.
 */
@Mixin(Entity.class)
public abstract class MixinEntityDismountTo {

    @Inject(at = @At("HEAD"), method = "dismountTo", cancellable = true, require = 1)
    private void beforeDismountTo(final double x, final double y, final double z, final CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) {
            return;
        }
        final ServerLevel level = self.level();
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, x, y, z);
        if (ship != null) {
            ci.cancel();

            final Vector3d lookVector = VectorConversionsMCKt.toJOML(self.getLookAngle());
            final Vector3d transformedLook = ship.getTransform().getShipToWorld().transformDirection(lookVector);
            final double yaw = Math.atan2(-transformedLook.x, transformedLook.z) * 180.0 / Math.PI;
            final double pitch = Math.atan2(-transformedLook.y, Math.sqrt((transformedLook.x * transformedLook.x) + (transformedLook.z * transformedLook.z))) * 180.0 / Math.PI;
            self.setYRot((float) yaw);
            self.setXRot((float) pitch);

            //Predict the position 2 ticks ahead for dismount
            final Vector3d inWorld = ship.getTransform().getShipToWorld().transformPosition(x, y, z, new Vector3d());
            final Vector3d inWorldPrev = ship.getPrevTickTransform().getShipToWorld().transformPosition(x, y, z, new Vector3d());
            final Vector3d inWorldNext = inWorld.mul(3, new Vector3d()).sub(inWorldPrev.mul(2, new Vector3d()));
            self.teleportTo(level, inWorldNext.x, inWorldNext.y, inWorldNext.z, Set.of(), self.getYRot(), self.getXRot(), true);
            ((IEntityDraggingInformationProvider) self).vs$dragImmediately(ship);
        }
    }
}
