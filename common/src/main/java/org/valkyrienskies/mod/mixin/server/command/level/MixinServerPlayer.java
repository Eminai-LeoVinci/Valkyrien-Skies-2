package org.valkyrienskies.mod.mixin.server.command.level;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player {

    public MixinServerPlayer(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @Shadow
    public abstract void teleportTo(double d, double e, double f);

    @Inject(
        at = @At("HEAD"),
        method = "teleportTo(DDD)V",
        cancellable = true,
        require = 1
    )
    private void beforeTeleportTo(final double x, final double y, final double z, final CallbackInfo ci) {
        ServerLevel level = ((ServerPlayer) (Object) this).level();
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, x, y, z);
        if (ship != null) {
            ci.cancel();
            final Vector3d inWorld = VSGameUtilsKt.toWorldCoordinates(ship, x, y, z);
            this.teleportTo(inWorld.x, inWorld.y, inWorld.z);
        }
    }

    // 1.21.11: ServerPlayer no longer overrides dismountTo; the ship-aware dismount handling
    // moved to MixinEntityDismountTo (Entity#dismountTo with a ServerPlayer guard).
}
