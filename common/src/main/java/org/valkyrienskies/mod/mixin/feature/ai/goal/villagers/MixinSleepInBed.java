package org.valkyrienskies.mod.mixin.feature.ai.goal.villagers;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.SleepInBed;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

/**
 * Villager bed-sleep on ships -- the SLEEP-side world-conversion (the WALK side is
 * {@link MixinSetClosestHomeAsWalkTarget}).
 *
 * <p>A villager's HOME memory stores the bed's {@link net.minecraft.core.GlobalPos} whose
 * {@code pos()} is the bed's SHIPYARD {@link BlockPos} (the extreme coords where the ship's
 * blocks actually live; the rendered ship is just a transform). {@link SleepInBed} gates
 * starting / keeping the behavior on three geometric comparisons between that shipyard bedPos
 * and {@code entity.position()} (the villager's RENDERED WORLD coord). Compared raw across the
 * two frames the villager is "millions of blocks away" from its own bed, so it never sleeps.
 *
 * <p>Fix (matching the review's preferred consistent-ship-frame approach): convert the ENTITY
 * world position INTO the bed's SHIP space and compare it against the RAW shipyard bedPos. This
 * is robust on tilted ships and avoids corner/floor skew that world-converting the bedPos would
 * introduce. The bedPos itself is left untouched (the {@code BlockTags.BEDS} / {@code OCCUPIED}
 * BlockState reads in this method correctly use the shipyard pos -- ship blocks genuinely live
 * there).
 *
 * <p>Every conversion is gated on a non-null managing ship: an off-ship (vanilla) bed has no
 * managing ship, so the entity position is passed through unchanged and vanilla behavior is
 * byte-identical.
 */
@Mixin(SleepInBed.class)
public class MixinSleepInBed {

    /**
     * {@code checkExtraStartConditions}: {@code bedPos.closerToCenterThan(entity.position(), 2.0)}.
     * The {@code instance} receiver IS the shipyard bedPos; convert {@code entityPos} into that
     * bed's ship space before the distance test.
     */
    @WrapOperation(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        ),
        require = 1
    )
    private boolean vs$startConditionsCloserToCenter(
        final BlockPos bedPos, final Position entityPos, final double dist,
        final Operation<Boolean> original,
        @Local(argsOnly = true) final ServerLevel level) {
        return original.call(bedPos, vs$entityPosInBedShipSpace(level, bedPos, entityPos), dist);
    }

    /**
     * {@code canStillUse}: {@code bedPos.closerToCenterThan(entity.position(), 1.14)}. Same
     * conversion as above.
     */
    @WrapOperation(
        method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        ),
        require = 1
    )
    private boolean vs$canStillUseCloserToCenter(
        final BlockPos bedPos, final Position entityPos, final double dist,
        final Operation<Boolean> original,
        @Local(argsOnly = true) final ServerLevel level) {
        return original.call(bedPos, vs$entityPosInBedShipSpace(level, bedPos, entityPos), dist);
    }

    /**
     * {@code canStillUse} Y check: {@code entity.getY() > bedPos.getY() + 0.4}. Convert the
     * villager's Y into the bed's ship space so both operands live in the same (ship) frame.
     * The bedPos local captured here is the same shipyard {@link BlockPos} used by the distance
     * test above (the only {@link BlockPos} local in this method).
     */
    @WrapOperation(
        method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getY()D"
        ),
        require = 1
    )
    private double vs$canStillUseEntityY(
        final LivingEntity entity, final Operation<Double> original,
        @Local(argsOnly = true) final ServerLevel level,
        @Local final BlockPos bedPos) {
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, bedPos);
        if (ship == null) {
            return original.call(entity);
        }
        final Vector3dc shipPos = ship.getTransform().getWorldToShip()
            .transformPosition(VectorConversionsMCKt.toJOML(entity.position()), new Vector3d());
        return shipPos.y();
    }

    /**
     * Converts a WORLD {@link Position} into the SHIP space of the ship managing {@code bedPos}.
     * If no ship manages the bed (off-ship / vanilla bed) the position is returned unchanged so
     * vanilla behavior is byte-identical.
     */
    private static Position vs$entityPosInBedShipSpace(
        final ServerLevel level, final BlockPos bedPos, final Position entityPos) {
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, bedPos);
        if (ship == null) {
            return entityPos;
        }
        final Vector3dc shipPos = ship.getTransform().getWorldToShip().transformPosition(
            new Vector3d(entityPos.x(), entityPos.y(), entityPos.z()), new Vector3d());
        return new Vec3(shipPos.x(), shipPos.y(), shipPos.z());
    }
}
