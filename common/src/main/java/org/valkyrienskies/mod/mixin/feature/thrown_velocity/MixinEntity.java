package org.valkyrienskies.mod.mixin.feature.thrown_velocity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(Entity.class)
public class MixinEntity {
    @Shadow
    private Level level;

    /**
     * Items dropped by entities mounted/dragged on a ship gets dragged too.
     */
    @Inject(
        method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("RETURN")
    )
    private void setItemDragged(final ServerLevel serverLevel, final ItemStack itemStack, final Vec3 vec3,
        final CallbackInfoReturnable<ItemEntity> cir) {
        ItemEntity result = cir.getReturnValue();
        if(result == null) return;
        Ship ship = VSGameUtilsKt.getShipMountedTo(Entity.class.cast(this));
        EntityDraggingInformation info = ((IEntityDraggingInformationProvider)this).getDraggingInformation();
        if (ship == null && info.isEntityBeingDraggedByAShip()) {
            ship = ValkyrienSkies.getShipById(level, info.getLastShipStoodOn());
        }
        ((IEntityDraggingInformationProvider)result).vs$dragImmediately(ship);
    }
}
