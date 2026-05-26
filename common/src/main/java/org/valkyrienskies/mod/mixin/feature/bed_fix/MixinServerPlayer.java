package org.valkyrienskies.mod.mixin.feature.bed_fix;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Entity {

    public MixinServerPlayer(final EntityType<?> entityType, final Level level) {
        super(entityType, level);
    }

    @Inject(
        at = @At("TAIL"),
        method = "isReachableBedBlock",
        cancellable = true
    )
    private void isReachableBedBlock(final BlockPos blockPos, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            final Vec3 vec3 = Vec3.atBottomCenterOf(blockPos);

            final double origX = vec3.x;
            final double origY = vec3.y;
            final double origZ = vec3.z;

            VSGameUtilsKt.transformToNearbyShipsAndWorld(this.level(), origX, origY, origZ, 1, (x, y, z) -> {
                cir.setReturnValue(Math.abs(this.getX() - x) <= 3.0 && Math.abs(this.getY() - y) <= 2.0
                    && Math.abs(this.getZ() - z) <= 3.0);
            });
        }
    }

    // 1.21.11 ship-bed respawn fix.
    //
    // When a player sleeps in a bed mounted on a ship, vanilla saves the bed's
    // *shipyard* BlockPos (extreme coords, where the ship actually lives in the
    // world; the rendered ship is just a transform). On respawn, vanilla's
    // findRespawnAndUseSpawnBlock reads that shipyard pos, finds the bed
    // (correct -- ship blocks really are at shipyard coords), then calls
    // BedBlock.findStandUpPosition which returns a Vec3 *still in shipyard
    // space*. Vanilla snaps the new ServerPlayer to those millions-of-blocks
    // coordinates and tries to load chunks / apply environmentAttributes /
    // addRespawnedPlayer there. The combination soft-bricks the respawn:
    // ClientboundRespawnPacket is never delivered, and the DeathScreen button
    // (which sets `button.active = false` on click and only re-enables via a
    // fresh init()) stays greyed out until the player exits to title -- where
    // reconnecting finds them still dead, repeating the loop.
    //
    // Fix: on RETURN, if the saved bed pos is in shipyard space, transform the
    // returned TeleportTransition's position from shipyard -> world coords via
    // ship.getShipToWorld(). If the ship has been deleted, swap in
    // TeleportTransition.missingRespawnBlock so vanilla cleanly falls back to
    // world spawn AND clears the dead respawnConfig (PlayerList.respawn skips
    // copyRespawnPosition when missingRespawnBlock = true).
    //
    // Older VS2 had a similar fix targeting Player.findRespawnPositionAndUseSpawnBlock
    // returning Optional<Vec3>; in 1.21.x Mojang moved the method to ServerPlayer
    // with a TeleportTransition return type, so the old mixin was no longer valid
    // (and on this port it wasn't even registered in the mixin config). require=1
    // here so the next Mojang refactor fails loudly at build time instead of
    // silently no-opping.
    @Inject(
        method = "findRespawnPositionAndUseSpawnBlock",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void valkyrienskies$transformShipBedRespawn(
        final boolean keepInventory,
        final TeleportTransition.PostTeleportTransition postTeleportTransition,
        final CallbackInfoReturnable<TeleportTransition> cir) {

        final ServerPlayer self = (ServerPlayer) (Object) this;
        final ServerPlayer.RespawnConfig cfg = self.getRespawnConfig();
        if (cfg == null) {
            return;
        }

        final BlockPos bedPos = cfg.respawnData().pos();
        final ServerLevel level = ((ServerLevel) self.level()).getServer().getLevel(cfg.respawnData().dimension());
        if (level == null) {
            return;
        }

        if (!VSGameUtilsKt.isBlockInShipyard(level, bedPos)) {
            return;
        }

        final ServerShip ship = VSGameUtilsKt.getShipManagingPos(level, bedPos);
        if (ship == null) {
            // Ship deleted -- vanilla would have either teleported the player to
            // stale shipyard coords or returned an in-shipyard standup Vec3.
            // Force clean missing-respawn-block fallback so vanilla retargets to
            // world spawn and drops the dead respawnConfig.
            cir.setReturnValue(TeleportTransition.missingRespawnBlock(self, postTeleportTransition));
            return;
        }

        final TeleportTransition orig = cir.getReturnValue();
        if (orig.missingRespawnBlock()) {
            // No standup spot was found near the bed in shipyard space; vanilla
            // already returned a world-spawn fallback. Leave it alone.
            return;
        }

        final Vec3 shipyardPos = orig.position();
        final Vector3d worldPos = new Vector3d(shipyardPos.x, shipyardPos.y, shipyardPos.z);
        ship.getShipToWorld().transformPosition(worldPos);

        cir.setReturnValue(new TeleportTransition(
            orig.newLevel(),
            new Vec3(worldPos.x, worldPos.y, worldPos.z),
            Vec3.ZERO,
            orig.yRot(),
            orig.xRot(),
            postTeleportTransition));
    }

}
