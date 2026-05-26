package org.valkyrienskies.mod.mixin.feature.save_mob_location_on_ship;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.entity.ShipyardPosSavable;

@Mixin(Mob.class)
public class MixinMob implements ShipyardPosSavable {

    @Unique
    public Vector3d valkyrienskies$unloadedShipyardPos = null;

    @Override
    public Vector3d valkyrienskies$getUnloadedShipyardPos() {
        return valkyrienskies$unloadedShipyardPos;
    }

    @Override
    public void valkyrienskies$setUnloadedShipyardPos(Vector3d vector3d) {
        this.valkyrienskies$unloadedShipyardPos = vector3d;
    }


    /**
     * Save mob's shipyard position to nbt, or clear it if null
     *
     * @author G_Mungus
     */
    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    public void addAdditionalSaveDataMixin(ValueOutput nbt, CallbackInfo info) {
        Vector3d position = this.valkyrienskies$getUnloadedShipyardPos();
        if (position != null && VSGameConfig.SERVER.getSaveMobsPositionOnShip()) {
            nbt.putDouble("valkyrienskies$unloadedX", position.x);
            nbt.putDouble("valkyrienskies$unloadedY", position.y);
            nbt.putDouble("valkyrienskies$unloadedZ", position.z);
        } else {
            nbt.discard("valkyrienskies$unloadedX");
            nbt.discard("valkyrienskies$unloadedY");
            nbt.discard("valkyrienskies$unloadedZ");
        }
    }


    /**
     * Read mob's shipyard position from nbt
     *
     * @author G_Mungus
     */
    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    public void readAdditionalSaveData(ValueInput nbt, CallbackInfo info) {
        double x = nbt.getDoubleOr("valkyrienskies$unloadedX", Double.NaN);
        double y = nbt.getDoubleOr("valkyrienskies$unloadedY", Double.NaN);
        double z = nbt.getDoubleOr("valkyrienskies$unloadedZ", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
            this.valkyrienskies$setUnloadedShipyardPos(new Vector3d(x, y, z));
        }
    }



}

