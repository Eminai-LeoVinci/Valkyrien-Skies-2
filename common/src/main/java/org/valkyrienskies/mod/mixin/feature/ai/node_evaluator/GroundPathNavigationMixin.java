package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * D1 "down" fix, TARGET side: stop villager goals/POIs from descending the requested target to the world
 * floor before the pathfinder even runs.
 *
 * <p>Villagers (and other land mobs) navigate via {@link GroundPathNavigation}. Its
 * {@code createPath(BlockPos, int)} override runs a target pre-pass: when {@code canPathToTargetsBelowSurface}
 * is {@code false} (the private default), it REPLACES the requested target with
 * {@code findSurfacePosition(chunk, target, accuracy)}, which walks {@code Direction.DOWN} reading the RAW
 * {@link LevelChunk} (fetched straight from the chunk source) until it hits a non-air block or
 * {@code level.getMinY()}, then returns {@code above()} of it. Those raw-chunk reads do NOT go through
 * {@code PathNavigationRegion}, so the VS ship overlay ({@code MixinPathNavigationRegion}) never fires here:
 * over a ship deck the raw world cell is sky-air, the DOWN-loop falls to the world floor, and that becomes
 * the path target — exactly "villagers make their way down to the lower floor".
 *
 * <p>Surgical fix: {@code @WrapOperation} the single {@code findSurfacePosition} INVOKE inside
 * {@code createPath(BlockPos, int)}. For a ship-dragged mob we SKIP the descent and return the original
 * floored target unchanged (the overlay-aware A* exploration then resolves it on the deck); for every other
 * mob we {@code original.call(...)} so non-ship behavior is 100% untouched. Preferred over the documented
 * alternative ({@code setCanPathToTargetsBelowSurface(true)}) precisely because the wrap leaves the non-ship
 * path bit-identical and needs no lifecycle bookkeeping to clear the flag when a mob leaves a ship.
 *
 * <p>The mob is reached via {@link PathNavigationMobAccessor} (an {@code @Accessor} on the declaring
 * {@code PathNavigation}) rather than {@code @Shadow}, because {@code @Shadow} on the inherited {@code mob}
 * field crashes at apply in this build. Server-side only; gated on {@code aiOnShips}.
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {

    @WrapOperation(
        method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/GroundPathNavigation;findSurfacePosition"
                + "(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;I)"
                + "Lnet/minecraft/core/BlockPos;"))
    private BlockPos vs$skipSurfaceDescentForShipMobs(final GroundPathNavigation instance, final LevelChunk chunk,
        final BlockPos target, final int accuracy, final Operation<BlockPos> original) {
        if (VSGameConfig.SERVER.getAiOnShips()) {
            final Mob mob = ((PathNavigationMobAccessor) instance).getMob();
            if (mob instanceof IEntityDraggingInformationProvider dragProvider
                && dragProvider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
                // Ship-dragged mob: keep the requested (floored) deck target; skip the raw-chunk DOWN descent
                // that bypasses the ship overlay and would drop the target to the world floor.
                return target;
            }
        }
        return original.call(instance, chunk, target, accuracy);
    }
}
