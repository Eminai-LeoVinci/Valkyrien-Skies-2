package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * D1 "down" fix, TARGET side: stop villager goals/POIs from descending the requested target to the world
 * floor before the pathfinder even runs.
 *
 * <p>Villagers (and other land mobs) navigate via {@link GroundPathNavigation}. Its
 * {@code createPath(BlockPos, int)} override runs a target pre-pass that walks DOWN from an air target
 * reading the RAW {@code LevelChunk} (fetched straight from the chunk source) until it hits a non-air block
 * or the min build height. Those raw-chunk reads do NOT go through {@code PathNavigationRegion}, so the VS
 * ship overlay ({@code MixinPathNavigationRegion}) never fires here: over a ship deck the raw world cell is
 * sky-air, the DOWN-loop falls to the world floor, and that becomes the path target -- exactly "villagers
 * make their way down to the lower floor".
 *
 * <p>1.21.1 re-expression of the 1.21.11 fix: 1.21.11 extracts that pre-pass into a
 * {@code findSurfacePosition} helper (a single wrappable INVOKE); on 1.21.1 the descent/ascent loops are
 * INLINED in {@code createPath(BlockPos, int)}, so there is nothing to wrap. Instead we cancel at HEAD for a
 * ship-dragged mob and route the UNCHANGED target straight to {@code PathNavigation.createPath(Set, int)} --
 * which is exactly where the vanilla method lands after its (here skipped) surface adjustment -- preserving
 * vanilla's target-chunk-loaded null guard on the way. For every other mob the method runs untouched.
 *
 * <p>The mob is reached via {@link PathNavigationMobAccessor} (an {@code @Accessor} on the declaring
 * {@code PathNavigation}) rather than {@code @Shadow}, because {@code @Shadow} on the inherited {@code mob}
 * field crashes at apply in this build. Server-side only; gated on {@code aiOnShips}.
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {

    @Inject(
        method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void vs$skipSurfaceDescentForShipMobs(final BlockPos target, final int accuracy,
        final CallbackInfoReturnable<Path> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return;
        }
        final Mob mob = ((PathNavigationMobAccessor) (Object) this).getMob();
        if (!(mob instanceof IEntityDraggingInformationProvider dragProvider)
            || !dragProvider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return; // non-ship mob -> vanilla surface descent runs unchanged
        }
        // Preserve vanilla's guard: an unloaded target chunk means no path.
        if (mob.level().getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(target.getX()),
            SectionPos.blockToSectionCoord(target.getZ())) == null) {
            cir.setReturnValue(null);
            return;
        }
        // Ship-dragged mob: keep the requested (floored) deck target; skip the raw-chunk DOWN descent that
        // bypasses the ship overlay and would drop the target to the world floor. The overlay-aware A*
        // exploration then resolves it on the deck.
        cir.setReturnValue(((PathNavigation) (Object) this).createPath(ImmutableSet.of(target), accuracy));
    }
}
