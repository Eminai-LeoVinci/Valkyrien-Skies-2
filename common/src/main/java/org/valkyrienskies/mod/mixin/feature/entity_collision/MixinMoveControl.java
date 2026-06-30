package org.valkyrienskies.mod.mixin.feature.entity_collision;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(MoveControl.class)
public class MixinMoveControl {

    @Shadow
    @Final
    protected Mob mob;

    // A ship block only counts as a climbable STEP (allowing the auto-step jump) when its top is more than this
    // many blocks ABOVE the mob's feet, measured in SHIP frame. The flat deck a mob stands on -- or one the
    // no-collision carry has nudged it slightly into on a moving ship -- reads ~0..+0.1 and is suppressed; a
    // genuine 1-block deck step reads ~+1.0 and is allowed. (Tunable.)
    @Unique
    private static final double VS$STEP_THRESHOLD = 0.5;

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private VoxelShape vs$insertShipCollisions(final BlockState instance, final BlockGetter blockGetter,
        final BlockPos blockPos, final Operation<VoxelShape> original) {
        final VoxelShape originalShape = original.call(instance, this.mob.level(), blockPos);
        if (!originalShape.isEmpty()) {
            return originalShape;
        }
        // Vanilla MoveControl.tick probes the mob's OWN blockPosition() here for its step-up jump decision. Over a
        // ship the deck lives in the shipyard (~ -28e6) so the mob's world cell is air -> originalShape empty and
        // we look for the ship block under it. Get the Ship object (NOT positionToNearbyShips, which yields only a
        // Vector3d, NOR getShipManagingBlock, which floors world X/Z and mis-resolves a shipyard sample) so we can
        // transform BOTH the sampled cell AND the mob feet into the SAME ship frame for the height test below.
        final double cx = blockPos.getX() + 0.5;
        final double cy = blockPos.getY() + 0.5;
        final double cz = blockPos.getZ() + 0.5;
        final AABBdc cellAABB = new AABBd(cx - 0.5, cy - 0.5, cz - 0.5, cx + 0.5, cy + 0.5, cz + 0.5);
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(this.mob.level(), cellAABB)) {
            // Cell CENTER -> ship frame, floored to the ship block (the +0.5 keeps the standing air-cell from
            // rounding into the deck, exactly like MixinPathNavigationRegion; load-bearing for the stationary case).
            final Vector3dc cellShip = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(cx, cy, cz), new Vector3d());
            final BlockPos shipPos = BlockPos.containing(cellShip.x(), cellShip.y(), cellShip.z());
            final BlockState shipState = this.mob.level().getBlockState(shipPos);
            if (shipState.isAir()) {
                continue;
            }
            final VoxelShape shipShape = shipState.getCollisionShape(this.mob.level(), shipPos);
            if (shipShape.isEmpty()) {
                continue;
            }
            // HEIGHT DISCRIMINATOR (ship frame, gravity-aligned for upright ships): only return the ship shape --
            // letting vanilla fire the auto-step jump -- when the block top is meaningfully ABOVE the mob's feet
            // (a real step). The flat deck the mob stands on / has been carry-penetrated into reads ~0 and is
            // suppressed, which kills the moving-ship spam-jump (and the airborne-lag drift it drives) WITHOUT
            // disabling legit step-up onto raised ship blocks.
            final double deckTopShipY = shipPos.getY() + shipShape.max(Direction.Axis.Y);
            final Vector3dc feetShip = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(this.mob.getX(), this.mob.getY(), this.mob.getZ()), new Vector3d());
            final double delta = deckTopShipY - feetShip.y();

            if (delta > VS$STEP_THRESHOLD) {
                return shipShape;
            }
        }
        return originalShape;
    }
}
