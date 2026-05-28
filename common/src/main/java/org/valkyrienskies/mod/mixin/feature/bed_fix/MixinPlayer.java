package org.valkyrienskies.mod.mixin.feature.bed_fix;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(ServerPlayer.class)
public abstract class MixinPlayer {
    /**
     * Ship-bed respawn fix.
     *
     * When a player sleeps in a bed mounted on a ship, vanilla saves the bed's
     * *shipyard* BlockPos (extreme coords -- where the ship actually lives in the
     * world; the rendered ship is just a transform). On respawn,
     * ServerPlayer.findRespawnAndUseSpawnBlock returns Optional<RespawnPosAngle>
     * with the BedBlock standup position still in shipyard space (vanilla doesn't
     * know the bed is on a ship). PlayerList.respawn then snaps the new
     * ServerPlayer to those millions-of-blocks coordinates and tries to load
     * chunks there, which soft-bricks the respawn:
     *  - ClientboundRespawnPacket may never deliver
     *  - the DeathScreen "Respawn" button (which sets button.active=false on
     *    click and only re-enables via fresh init()) stays greyed out until
     *    the player exits to title -- where reconnecting finds them still dead.
     *
     * Two cases handled here:
     *  1. Ship still exists: transform the standup pos AND the bed pos from
     *     shipyard -> world via ship.getShipToWorld() and rebuild a RespawnPosAngle
     *     via the public of(Vec3, BlockPos) factory (which recomputes a yaw that
     *     faces the bed in world space).
     *  2. Ship was deleted (disassembled/exploded): clear the Optional so
     *     vanilla falls back to world spawn cleanly.
     *
     * 1.21.1 note: the method is `findRespawnAndUseSpawnBlock` (not
     * `findRespawnPositionAndUseSpawnBlock`), lives on ServerPlayer (not Player),
     * and returns Optional<RespawnPosAngle> (not Optional<Vec3>) -- all of those
     * changed in 1.21.2+.
     *
     * @author Bunting_chj (ship-deleted case) / VS2 port (shipyard transform)
     * @reason ship beds saved as shipyard pos must be transformed to world coords on respawn
     */
    @Inject(
        method = "findRespawnAndUseSpawnBlock",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private static void valkyrienskies$transformShipBedRespawn(
        ServerLevel serverLevel, BlockPos blockPos, float f, boolean bl, boolean bl2,
        CallbackInfoReturnable<Optional<ServerPlayer.RespawnPosAngle>> cir) {

        if (cir.getReturnValue().isEmpty()) return;

        // Only intervene when the bed itself is in shipyard space.
        if (!VSGameUtilsKt.isBlockInShipyard(serverLevel, blockPos)) return;

        final ServerShip ship = VSGameUtilsKt.getShipManagingPos(serverLevel, blockPos);
        if (ship == null) {
            // Ship deleted -- clear the in-shipyard respawn so vanilla falls back
            // to world spawn and drops the dead respawn config.
            cir.setReturnValue(Optional.empty());
            return;
        }

        final ServerPlayer.RespawnPosAngle current = cir.getReturnValue().get();

        // Transform standup position from shipyard -> world coordinates.
        final Vec3 shipyardPos = current.position();
        final Vector3d worldPosVec = new Vector3d(shipyardPos.x, shipyardPos.y, shipyardPos.z);
        ship.getShipToWorld().transformPosition(worldPosVec);
        final Vec3 worldPos = new Vec3(worldPosVec.x, worldPosVec.y, worldPosVec.z);

        // Transform the bed BlockPos so RespawnPosAngle.of() computes a world-space
        // yaw that faces the bed where it's actually rendered.
        final Vector3d worldBedVec = new Vector3d(
            blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        ship.getShipToWorld().transformPosition(worldBedVec);
        final BlockPos worldBedPos = BlockPos.containing(worldBedVec.x, worldBedVec.y, worldBedVec.z);

        cir.setReturnValue(Optional.of(ServerPlayer.RespawnPosAngle.of(worldPos, worldBedPos)));
    }
}
