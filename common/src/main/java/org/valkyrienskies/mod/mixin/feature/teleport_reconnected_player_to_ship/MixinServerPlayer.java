package org.valkyrienskies.mod.mixin.feature.teleport_reconnected_player_to_ship;

import com.mojang.authlib.GameProfile;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player {

    // 1.21.11: ServerPlayer has no serverLevel() method -- it's a covariant level() returning ServerLevel.
    // The mixin extends Player (whose level() returns the base Level), so cast level() at the call sites.

    // RECONNECT AUTO-SEAT. A player who logs out standing on a ship is, on rejoin, mounted onto an invisible
    // passenger seat (ShipMountingEntity) at their exact ship-relative logout spot, shown a "Press SPACE to stand"
    // prompt, and only stands when they choose -- instead of free-falling while the client streams in the
    // (possibly far-stern) deck chunks. A mounted passenger has no gravity and is carried by the ship, so it
    // CANNOT fall through a not-yet-collidable client deck. The seat is spawned in WORLD space so it actually
    // ticks, and it self-drives + handles standing in its own tick (ShipMountingEntity); this mixin only triggers
    // the seating (while the player is still un-mounted and ticking) and persists the ship anchor for relogs.

    // Pending reconnect (queued at save-read; consumed once the ship loads).
    @Unique private Long vs$reconnectShipId = null;
    @Unique private Vector3d vs$reconnectPosInShip = null;
    @Unique private int vs$reconnectTicksWaited = 0;

    // ~5s grace so the unloaded-ship movement guard never snaps the seat/rider while the ship's chunks load.
    @Unique private static final long RECONNECT_SHIP_GRACE_NANOS = 10_000_000_000L;

    public MixinServerPlayer(final Level level, final GameProfile gameProfile) {
        super(level, gameProfile);
        throw new IllegalStateException("Unreachable");
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    void vs$queueReconnectToShip(final ValueInput compoundTag, final CallbackInfo ci) {
        if (!VSGameConfig.SERVER.getTeleportReconnectedPlayers())
            return;
        final Optional<Long> lastShipIdOpt = compoundTag.getLong("LastShipId");
        if (lastShipIdOpt.isEmpty())
            return; // Player did not disconnect off of any ship
        vs$reconnectShipId = lastShipIdOpt.get();
        vs$reconnectPosInShip = new Vector3d(
            compoundTag.getDoubleOr("RelativeShipX", 0.0),
            compoundTag.getDoubleOr("RelativeShipY", 0.0),
            compoundTag.getDoubleOr("RelativeShipZ", 0.0)
        );
        vs$reconnectTicksWaited = 0;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    void vs$reconnectSeatTick(final CallbackInfo ci) {
        if (vs$reconnectShipId == null)
            return;
        final Ship ship = VSGameUtilsKt.getShipObjectWorld((ServerLevel) level())
            .getAllShips().getById(vs$reconnectShipId);
        if (ship != null) {
            vs$spawnAndMountSeat(ship);
            vs$reconnectShipId = null;
            vs$reconnectPosInShip = null;
        } else if (++vs$reconnectTicksWaited > 100) {
            // Ship never loaded within ~5s (deleted / too far): give up; the player stays at their login spot.
            vs$reconnectShipId = null;
            vs$reconnectPosInShip = null;
        }
    }

    @Unique
    private void vs$spawnAndMountSeat(final Ship ship) {
        final ServerLevel slevel = (ServerLevel) level();
        // The saved RelativeShipX/Y/Z is a SHIP-SPACE position; convert it to where the ship is NOW for the seat's
        // initial world spot, and hand the seat the ship anchor so it self-drives there every tick.
        final Vector3d worldPos = ship.getShipToWorld().transformPosition(new Vector3d(vs$reconnectPosInShip));
        final ShipMountingEntity seat = ShipMountingEntity.spawnPassengerSeat(
            slevel, worldPos.x, worldPos.y, worldPos.z, getYRot(), getXRot(),
            ship.getId(), vs$reconnectPosInShip.x, vs$reconnectPosInShip.y, vs$reconnectPosInShip.z);
        if (seat == null)
            return;
        // 3-arg (force) startRiding is the VS variant Eureka's helm uses.
        if (startRiding(seat, true, true)) {
            // Insurance: the unloaded-ship movement guard must not snap the seat/rider during the load grace.
            EntityShipCollisionUtils.markShipAsRecentlySpawned(ship.getId(), RECONNECT_SHIP_GRACE_NANOS);
        } else {
            seat.kill(slevel);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    void vs$rememberLastShip(final ValueOutput compoundTag, final CallbackInfo ci) {
        // Case 1: logging out while still auto-seated (never stood). A mounted passenger is NOT "being dragged",
        // so the normal path below would skip it -- read the seat's ship anchor and save it so the next login
        // re-seats them. (The seat entity itself does not persist; see ShipMountingEntity.shouldBeSaved.)
        if (getVehicle() instanceof ShipMountingEntity seat
            && seat.vs$isPassengerSeat()
            && seat.getDriveShipId() != null
            && seat.getDriveRelPos() != null) {
            compoundTag.putLong("LastShipId", seat.getDriveShipId());
            compoundTag.putDouble("RelativeShipX", seat.getDriveRelPos().x);
            compoundTag.putDouble("RelativeShipY", seat.getDriveRelPos().y);
            compoundTag.putDouble("RelativeShipZ", seat.getDriveRelPos().z);
            return;
        }

        // Case 2: normal -- logging out while standing on / carried by a ship.
        final EntityDraggingInformation draggingInformation =
            ((IEntityDraggingInformationProvider) this).getDraggingInformation();
        if (!draggingInformation.isEntityBeingDraggedByAShip())
            return;
        @Nullable final Long lastShipId = draggingInformation.getLastShipStoodOn();
        if (lastShipId == null)
            return;
        final Ship ship = VSGameUtilsKt.getShipObjectWorld((ServerLevel) level()).getAllShips().getById(lastShipId);
        if (ship == null)
            return;
        compoundTag.putLong("LastShipId", lastShipId);
        // Prefer the carry's authoritative ship-space position; fall back to a worldToShip recompute.
        final Vector3dc best = draggingInformation.bestRelativeEntityPosition();
        final Vector3d playerShipPosition = (best != null)
            ? new Vector3d(best)
            : ship.getWorldToShip().transformPosition(new Vector3d(getX(), getY(), getZ()));
        compoundTag.putDouble("RelativeShipX", playerShipPosition.x);
        compoundTag.putDouble("RelativeShipY", playerShipPosition.y);
        compoundTag.putDouble("RelativeShipZ", playerShipPosition.z);
    }
}
