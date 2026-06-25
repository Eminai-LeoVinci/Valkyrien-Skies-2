package org.valkyrienskies.mod.mixin.feature.drag_standing_mobs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * D2 moving-ship desync fix (server-side): keep a STILL / enclosed non-player mob that is genuinely STANDING
 * on a ship registered as on-ship every server tick, so the existing VS sync + carry pipeline stays engaged.
 *
 * <p>See the project notes for the full root-cause chain; in short, the only vanilla stamp of
 * {@code lastShipStoodOn} for a mob is COLLISION-driven (EntityShipCollisionUtils), so a motionless mob never
 * re-stamps, the server's drag gate expires after 25 ticks, the VS ship-motion packet stops, and the client
 * treats the mob as a plain remote entity (vanilla ~3-tick lerp = ~3-block slide in render AND hitbox).
 *
 * <p>This stamps {@code lastShipStoodOn} each server tick for a non-player {@link LivingEntity} genuinely
 * standing on a ship (foot-AABB intersect AND a non-air ship block directly below the feet in ship space), so
 * the server keeps emitting the VS packet and the existing client lerp + carry re-engage. Server authority only;
 * non-player mobs only (the local player's carry is the influence feature in EntityDragger). Release is the
 * existing 25-tick expiry (we simply stop refreshing when off the hull).
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void vs$keepStandingMobShipStamped(final CallbackInfo ci) {
        final LivingEntity self = (LivingEntity) (Object) this;
        final Level level = self.level();
        // Server authority only -- the client receives the result via the VS packet this stamp unlocks.
        if (level.isClientSide()) {
            return;
        }
        // Mobs only. Players are handled by EntityDragger's influence carry (local) / separate path (remote).
        if (self instanceof Player) {
            return;
        }
        if (!(self instanceof IEntityDraggingInformationProvider provider)) {
            return;
        }
        // Fast-bail before any per-entity allocation when the world has no ships at all.
        if (VSGameUtilsKt.getShipObjectWorld(level).getAllShips().size() == 0) {
            return;
        }
        final Ship ship = vs$findShipStandingOn(self, level);

        if (ship == null) {
            // Not standing on a ship -> do NOT refresh; the existing 25-tick expiry releases the mob naturally.
            return;
        }
        final EntityDraggingInformation info = provider.getDraggingInformation();
        final Long current = info.getLastShipStoodOn();
        if (current == null || !current.equals(ship.getId())) {
            // Acquire / change ship. The setter resets ticksSinceStoodOnShip = 0.
            info.setLastShipStoodOn(ship.getId());
        }
        // Pin the drag-gate alive every tick (the ticksSinceStoodOnShip setter also clears shouldImpulseMovement
        // so a freshly-stamped still mob is carried by the position re-anchor without a one-time velocity jolt).
        info.setTicksSinceStoodOnShip(0);
    }

    /**
     * Returns the ship the entity is genuinely STANDING on (a non-air ship block directly below its feet in ship
     * space), or {@code null}. Mirrors {@code entity_collision/MixinEntity.getPosStandingOnFromShips} but returns
     * the {@link Ship}.
     */
    @Unique
    private Ship vs$findShipStandingOn(final LivingEntity self, final Level level) {
        final double radius = 0.5;
        final double gx = self.getX();
        final double gy = self.getBoundingBox().minY - 0.5;
        final double gz = self.getZ();
        final Vector3dc global = new Vector3d(gx, gy, gz);
        final AABBdc testAABB = new AABBd(
            gx - radius, gy - radius, gz - radius,
            gx + radius, gy + radius, gz + radius
        );
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(level, testAABB)) {
            final Vector3dc local =
                ship.getTransform().getWorldToShip().transformPosition(global, new Vector3d());
            final BlockPos blockPos = BlockPos.containing(
                Math.floor(local.x()), Math.floor(local.y()), Math.floor(local.z())
            );
            if (!level.getBlockState(blockPos).isAir()) {
                return ship;
            }
            // Fence/edge case: also check one block lower (matches getPosStandingOnFromShips).
            final Vector3dc localBelow = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(gx, gy - 1.0, gz));
            final BlockPos blockPosBelow = BlockPos.containing(
                Math.round(localBelow.x()), Math.round(localBelow.y()), Math.round(localBelow.z())
            );
            if (!level.getBlockState(blockPosBelow).isAir()) {
                return ship;
            }
        }
        return null;
    }
}
