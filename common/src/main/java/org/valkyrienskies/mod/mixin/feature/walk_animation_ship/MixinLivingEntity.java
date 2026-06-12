package org.valkyrienskies.mod.mixin.feature.walk_animation_ship;

import net.minecraft.world.entity.LivingEntity;
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

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    /**
     * Stop the walk animation from firing on a stationary entity that's just being carried by a ship.
     *
     * 2.4.99: Use SHIP-RELATIVE position delta as the walk-anim speed signal while on a ship.
     * 2.4.100: Gate on getLastShipStoodOn() != null instead of isEntityBeingDraggedByAShip().
     * 2.4.101: Drop reliance on getLastShipStoodOn() entirely — it returns null for REMOTE
     * entities on the observing client. Instead use VSGameUtilsKt.getShipsIntersecting() with
     * an AABB around the entity's feet, which is the same primitive used by
     * entity_collision/MixinEntity.getPosStandingOnFromShips. Works on both sides for both
     * local AND remote entities.
     *
     * Why the previous gates failed:
     * - 2.4.100 logs proved class_745 (RemotePlayer on observer) has shipId=null AND vanilla
     *   f=0.18+ rising. So both isEntityBeingDraggedByAShip() (requires ticksSinceStoodOnShip<25)
     *   AND lastShipStoodOn (set only by collide() which doesn't run for remote entities on
     *   observers) fail. The position-intersection lookup doesn't depend on any per-entity
     *   ticksSinceStoodOnShip counter or packet-synced field — it just asks "is this entity's
     *   foot AABB inside any ship's AABB right now".
     *
     * Why position-intersection is correct here:
     * - getShipsIntersecting is computed from the ship's current world-space AABB, which moves
     *   with the ship every tick on both server and observing client (the ship transform IS
     *   sync'd).
     * - When updateWalkAnimation runs for a remote player on the observer, the entity has
     *   already been interpolated to the server's latest world position (with ship-drag baked
     *   in). That post-interp position is inside the ship's current AABB envelope.
     * - When updateWalkAnimation runs for the local player, the entity's position is pre-
     *   EntityDragger (drag runs at Minecraft.tick @RETURN), so getX() reflects the previous
     *   tick's drag end. That position is still inside the ship's AABB if the player is on it.
     *
     * 2.4.97's deltaMovement approach failed because vanilla Entity.move() ends up either
     * zeroing deltaMovement on collided axes OR leaving residual ship-drag motion in it.
     * 2.4.99/2.4.100's lastShipStoodOn gate failed for remote entities because that field
     * isn't populated for them on observers.
     *
     * The ONLY truly clean signal for "did this entity try to move on the ship" is the delta
     * of its position expressed in SHIP-LOCAL coordinates. Ship motion translates and rotates
     * the world frame around the player; ship-local coordinates are invariant under that, so a
     * stationary-on-ship player has zero ship-local delta regardless of how the ship moves.
     */
    @Unique
    private Vector3dc vs$prevShipRelPos = null;
    @Unique
    private Long vs$prevShipRelId = null;

    @Inject(method = "updateWalkAnimation", at = @At("HEAD"), cancellable = true, require = 1)
    private void vs$shipRelativeWalkAnim(float f, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // Runs for every LivingEntity every tick: bail to vanilla before any per-entity work
        // (AABB allocation + intersection query below) when the world has no ships at all.
        if (VSGameUtilsKt.getShipObjectWorld(self.level()).getAllShips().size() == 0) {
            if (vs$prevShipRelPos != null) {
                vs$prevShipRelPos = null;
                vs$prevShipRelId = null;
            }
            return;
        }

        // Fast path: trust lastShipStoodOn if set (local player / server-side path).
        Ship ship = null;
        Long shipId = null;
        boolean fromDragInfo = false;
        if (((Object) this) instanceof IEntityDraggingInformationProvider provider) {
            EntityDraggingInformation info = provider.getDraggingInformation();
            if (info != null) {
                Long candidate = info.getLastShipStoodOn();
                if (candidate != null) {
                    Ship s = VSGameUtilsKt.getShipObjectWorld(self.level())
                        .getAllShips().getById(candidate);
                    if (s != null) {
                        ship = s;
                        shipId = candidate;
                        fromDragInfo = true;
                    }
                }
            }
        }

        // Fallback: position-intersect ship lookup. Works for REMOTE entities on the observing
        // client, where lastShipStoodOn is null but the ship transform IS sync'd, so its AABB
        // moves with it and the entity's post-interp position is inside it.
        if (ship == null) {
            AABBdc footAABB = new AABBd(
                self.getX() - 0.4, self.getY() - 1.0, self.getZ() - 0.4,
                self.getX() + 0.4, self.getY() + 0.1, self.getZ() + 0.4);
            for (Ship s : VSGameUtilsKt.getShipsIntersecting(self.level(), footAABB)) {
                ship = s;
                shipId = s.getId();
                break;
            }
        }

        if (ship == null) {
            if (vs$prevShipRelPos != null) {
                vs$prevShipRelPos = null;
                vs$prevShipRelId = null;
            }
            return;
        }

        Vector3dc currentShipRel = ship.getWorldToShip().transformPosition(
            new Vector3d(self.getX(), self.getY(), self.getZ()));

        float speedSignal = 0.0F;
        if (vs$prevShipRelPos != null && shipId.equals(vs$prevShipRelId)) {
            double dx = currentShipRel.x() - vs$prevShipRelPos.x();
            double dz = currentShipRel.z() - vs$prevShipRelPos.z();
            speedSignal = (float) Math.sqrt(dx * dx + dz * dz);
        }
        vs$prevShipRelPos = currentShipRel;
        vs$prevShipRelId = shipId;

        self.walkAnimation.update(Math.min(speedSignal * 4.0F, 1.0F), 0.4F, self.isBaby() ? 3.0F : 1.0F);
        ci.cancel();
    }
}
