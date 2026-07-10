package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;

/**
 * Teaches the whole A* grid about ship blocks. Every NodeEvaluator reads the world through a
 * {@link PathNavigationRegion} (the PathfindingContext is backed by it), so overlaying ship blocks here -- in
 * ONE place -- makes walk/swim/amphibious/fly evaluators all ship-aware without touching their per-type
 * getPathType logic.
 *
 * <p>When the vanilla world cell is air but a ship's block occupies that world position, report the ship block.
 * The ship's blocks live in the SAME backing Level at the shipyard coordinates, so we map the world cell into
 * each nearby ship's frame and read the ship-local block. Gated on aiOnShips; a ThreadLocal reentry guard
 * prevents recursion (the overlay reads the backing Level's blocks).
 *
 * <p>1.21.1 note: the 1.21.11 branch resolves ships via the {@code mod.api.ValkyrienSkies} helper class, which
 * does not exist on this branch -- {@code getShipsIntersecting} + a manual worldToShip transform is the same
 * query expressed with this branch's API.
 */
@Mixin(PathNavigationRegion.class)
public abstract class MixinPathNavigationRegion {

    @Unique
    private static final ThreadLocal<Boolean> vs$inOverlay = ThreadLocal.withInitial(() -> false);

    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"), cancellable = true)
    private void vs$overlayShipBlock(final BlockPos pos, final CallbackInfoReturnable<BlockState> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips() || vs$inOverlay.get()) {
            return;
        }
        if (!cir.getReturnValue().isAir()) {
            return; // a real world block wins; only fill empty cells with ship blocks
        }
        vs$inOverlay.set(true);
        try {
            final Level level = ((PathNavigationRegionAccessor) (Object) this).getLevel();
            for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(level, new AABB(pos))) {
                final Vector3d shipPos = ship.getTransform().getWorldToShip().transformPosition(
                    new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), new Vector3d());
                final BlockPos shipBlockPos = BlockPos.containing(shipPos.x, shipPos.y, shipPos.z);
                final BlockState shipState = level.getBlockState(shipBlockPos);
                if (!shipState.isAir()) {
                    cir.setReturnValue(shipState);
                    return;
                }
            }
        } finally {
            vs$inOverlay.set(false);
        }
    }
}
