package org.valkyrienskies.mod.mixin.feature.fluid_escaping_ship_config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3d;
import org.joml.primitives.AABBic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;

@Mixin(FlowingFluid.class)
public class MixinFlowingFluid {

    @Inject(at = @At("HEAD"), method = "canSpreadTo", cancellable = true)
    private void beforeCanSpreadTo(final BlockGetter level, final BlockPos fromPos, final BlockState fromBlockState,
        final Direction direction, final BlockPos toPos, final BlockState toBlockState, final FluidState toFluidState,
        final Fluid fluid, final CallbackInfoReturnable<Boolean> cir) {

        if (VSGameConfig.SERVER.getPreventFluidEscapingShip() && level instanceof Level) {
            final Ship ship = VSGameUtilsKt.getShipManagingPos((Level) level, toPos);
            if (ship != null && ship.getShipAABB() != null) {
                final AABBic a = ship.getShipAABB();
                final int x = toPos.getX();
                final int y = toPos.getY();
                final int z = toPos.getZ();

                if (x < a.minX() || y < a.minY() || z < a.minZ() || x >= a.maxX() || y >= a.maxY() || z >= a.maxZ()) {
                    cir.setReturnValue(false);
                }
            }
        }
    }

    // Fluid spilling off a ship falls through empty shipyard space and would otherwise keep
    // generating flow down to the bottom of the world. Cut it off once a spilled-off block's
    // real-world height passes below y=64, so the waterfall ends cleanly near sea level.
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void valkyrienskies$capFluidFalloff(final LevelAccessor level, final BlockPos pos,
        final BlockState blockState, final Direction direction, final FluidState fluidState,
        final CallbackInfo ci) {

        if (level instanceof Level && valkyrienskies$isBelowFluidFloor((Level) level, pos)) {
            ci.cancel();
        }
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
