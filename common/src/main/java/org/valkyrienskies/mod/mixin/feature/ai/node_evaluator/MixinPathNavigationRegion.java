package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.config.VSGameConfig;

/**
 * Teaches the whole A* grid about ship blocks. In the 1.21.2+ pathfinder rewrite every NodeEvaluator reads the
 * world through a {@link PathNavigationRegion} (the PathfindingContext is backed by it), so overlaying ship
 * blocks here -- in ONE place -- makes walk/swim/amphibious/fly/frog evaluators all ship-aware without touching
 * their per-type getPathType logic.
 *
 * <p>When the vanilla world cell is air but a ship's block occupies that world position, report the ship block.
 * The ship's blocks live in the SAME backing Level at the shipyard coordinates, so we map the world cell into
 * each nearby ship's frame ({@code positionToNearbyShips}) and read the ship-local block. Gated on aiOnShips;
 * a ThreadLocal reentry guard prevents recursion (the overlay reads the backing Level's blocks).
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
            for (final Vector3d shipPos : ValkyrienSkies.positionToNearbyShips(
                level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                final BlockPos shipBlockPos = BlockPos.containing(ValkyrienSkies.toMinecraft(shipPos));
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
