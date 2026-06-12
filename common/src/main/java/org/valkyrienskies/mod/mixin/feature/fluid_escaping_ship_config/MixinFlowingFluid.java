package org.valkyrienskies.mod.mixin.feature.fluid_escaping_ship_config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3d;
import org.joml.primitives.AABBic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(FlowingFluid.class)
public class MixinFlowingFluid {

    // 1.21.11: canSpreadTo no longer exists (split into canPassThrough/canHoldSpecificFluid),
    // but every spread path — sideways and down — still funnels through spreadTo, so both the
    // escape-prevention check and the falloff cap live in this one HEAD inject.
    //
    // Falloff cap: fluid spilling off a ship falls through empty shipyard space and would
    // otherwise keep generating flow down to the bottom of the world. Cut it off once a
    // spilled-off block's real-world height passes below y=64, so the waterfall ends cleanly
    // near sea level.
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true, require = 1)
    private void valkyrienskies$capFluidFalloff(final LevelAccessor level, final BlockPos pos,
        final BlockState blockState, final Direction direction, final FluidState fluidState,
        final CallbackInfo ci) {

        if (!(level instanceof Level realLevel)) {
            return;
        }
        if (VSGameConfig.SERVER.getPreventFluidEscapingShip()
            && valkyrienskies$isOutsideShipAABB(realLevel, pos)) {
            ci.cancel();
            return;
        }
        if (valkyrienskies$isBelowFluidFloor(realLevel, pos)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean valkyrienskies$isOutsideShipAABB(final Level level, final BlockPos pos) {
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null || ship.getShipAABB() == null) {
            return false;
        }
        final AABBic a = ship.getShipAABB();
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        return x < a.minX() || y < a.minY() || z < a.minZ() || x >= a.maxX() || y >= a.maxY() || z >= a.maxZ();
    }

    @Unique
    private boolean valkyrienskies$isBelowFluidFloor(final Level level, final BlockPos pos) {
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null || ship.getShipAABB() == null) {
            return false;
        }
        // Only fluid that has spilled below the hull — never the fluid sitting on the ship
        // itself, since a ship may legitimately float with its hull below y=64.
        if (pos.getY() >= ship.getShipAABB().minY()) {
            return false;
        }
        final Vector3d worldPos =
            ship.getShipToWorld().transformPosition(new Vector3d(pos.getX(), pos.getY(), pos.getZ()));
        return worldPos.y < 64.0;
    }
}
