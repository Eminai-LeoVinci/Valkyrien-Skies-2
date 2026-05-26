package org.valkyrienskies.mod.mixin.server.network;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.internal.world.VsiServerShipWorld;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.mixinducks.world.entity.PlayerDuck;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
    public MixinServerGamePacketListenerImpl(MinecraftServer minecraftServer, Connection connection,
        CommonListenerCookie commonListenerCookie) {
        super(minecraftServer, connection, commonListenerCookie);
    }

    @Shadow
    public ServerPlayer player;

    @Shadow
    private int awaitingTeleport;

    @Shadow
    private int tickCount;

    @Shadow
    private Vec3 awaitingPositionFromClient;

    @Shadow
    private int awaitingTeleportTime;

    @ModifyExpressionValue(
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;aboveGroundTickCount:I", ordinal = 0),
        method = "tick"
    )
    private int noFlyKick(final int original) {
        if (VSGameConfig.SERVER.getEnableMovementChecks()) {
            return original;
        } else {
            return 0;
        }
    }

    @WrapOperation(
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        ),
        method = "handleUseItemOn"
    )
    private Vec3 skipDistanceCheck2(final Vec3 instance, final Vec3 vec3, final Operation<Vec3> subtract) {
        return VSGameUtilsKt.toWorldCoordinates(player.level(), subtract.call(instance, vec3));
    }

    /**
     * A shipyard entity (item frame, painting, minecart, ...) physically lives in its ship's
     * shipyard millions of blocks from where the ship visually appears. handleInteract gates
     * every attack/use-on-entity packet behind ServerboundInteractPacket.isWithinRange, which
     * reach-checks the player against entity.getBoundingBox() -- the raw shipyard-space box,
     * always hopelessly out of range, so the interaction is silently dropped and the entity
     * can't be broken or have an item placed in it.
     *
     * <p>Transform that box into world space, where the entity visually sits on the ship right
     * next to the player, so the vanilla reach check passes. Non-shipyard entities resolve no
     * ship and keep their box unchanged. Mirrors VS2's Forge-only isCloseEnough overwrite.
     */
    @WrapOperation(
        method = "handleInteract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
        ),
        require = 1
    )
    private AABB valkyrienskies$worldSpaceInteractBox(final Entity entity, final Operation<AABB> original) {
        final AABB box = original.call(entity);
        final ServerShip ship =
            VSGameUtilsKt.getShipManagingPos((ServerLevel) player.level(), entity.blockPosition());
        if (ship == null) {
            return box;
        }
        final AABBd worldBox = VectorConversionsMCKt.toJOML(box);
        worldBox.transform(ship.getShipToWorld());
        return VectorConversionsMCKt.toMinecraft(worldBox);
    }

    /*
    @WrapOperation(
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/ChunkPos;getChessboardDistance(Lnet/minecraft/world/level/ChunkPos;)I"
        ),
        method = "handleUseItemOn"
    )
    private int skipDistanceCheck(final ChunkPos instance, final ChunkPos chunkPos, final Operation<Integer> getChessboardDistance) {
        return 0;
    }

     */

    @WrapOperation(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"
        ),
        require = 0
    )
    private boolean shouldSkipMoveCheck1(final ServerGamePacketListenerImpl instance,
        final Operation<Boolean> isSinglePlayerOwner) {
        return !VSGameConfig.SERVER.getEnableMovementChecks();
    }

    @WrapOperation(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"
        ),
        require = 0
    )
    private boolean shouldSkipMoveCheck2(final ServerGamePacketListenerImpl instance,
        final Operation<Boolean> isSinglePlayerOwner) {
        return !VSGameConfig.SERVER.getEnableMovementChecks();
    }

    @WrapOperation(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;isCreative()Z"
        ),
        require = 0
    )
    private boolean shouldSkipMoveCheck(final ServerPlayerGameMode instance,
        final Operation<Boolean> isSinglePlayerOwner) {
        return !VSGameConfig.SERVER.getEnableMovementChecks();
    }

    // 1.21.11: the "moved wrongly" rubber-band gate now calls `ServerPlayer.isCreative()`
    // directly inside handleMovePlayer (older VS2 wrap above targets ServerPlayerGameMode
    // which is the old call path — present here for back-compat, silently no-ops on 1.21.11
    // because the call site moved). Without this, a *survival* player standing on a moving
    // ship gets rubber-banded every tick: server position is dragged forward by
    // EntityDragger but the player's client→server move packet trips the bl4
    // ("moved wrongly!") delta check, the server teleports them back, drag pushes them
    // forward, repeat → visible jitter. Creative bypasses it naturally (isCreative=true);
    // mounted bypasses via handleMoveVehicle's own gate.
    @WrapOperation(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;isCreative()Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$skipMovedWronglyCheck(final ServerPlayer instance,
        final Operation<Boolean> original) {
        if (VSGameConfig.SERVER.getEnableMovementChecks()) {
            return original.call(instance);
        }
        return true;
    }

    // 1.21.11: the "moved too quickly" rubber-band is now gated by the new
    // shouldCheckPlayerMovement(boolean) helper (which itself consults isSingleplayerOwner,
    // dimension-change state, and the PLAYER_MOVEMENT_CHECK gamerule). The legacy
    // shouldSkipMoveCheck1 above targets a direct isSingleplayerOwner() call from
    // handleMovePlayer that no longer exists — silent no-op. Skipping the whole helper
    // is equivalent to "don't run the speed check" — the same outcome
    // enableMovementChecks=false has always intended.
    @WrapOperation(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;shouldCheckPlayerMovement(Z)Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$skipMovedTooQuicklyCheck(
        final ServerGamePacketListenerImpl instance, final boolean isFallFlying,
        final Operation<Boolean> original) {
        if (VSGameConfig.SERVER.getEnableMovementChecks()) {
            return original.call(instance, isFallFlying);
        }
        return false;
    }

    // Fixes:
    // https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/87
    // Bed Bug
    @Inject(
        method = "teleport(DDDFF)V",
        at = @At(value = "HEAD"),
        cancellable = true
    )
    private void transformTeleport(final double x, final double y, final double z, final float yaw, final float pitch,
        final CallbackInfo ci) {

        if (!VSGameConfig.SERVER.getTransformTeleports()) {
            return;
        }

        final BlockPos blockPos = BlockPos.containing(x, y, z);
        final ServerShip ship = VSGameUtilsKt.getShipManagingPos((ServerLevel) player.level(), blockPos);

        // TODO add flag to disable this https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues/30
        if (ship != null) {
            final Vector3d pos = new Vector3d(x, y, z);
            ship.getShipToWorld().transformPosition(pos);

            this.awaitingPositionFromClient = VectorConversionsMCKt.toMinecraft(pos);
            if (++this.awaitingTeleport == Integer.MAX_VALUE) {
                this.awaitingTeleport = 0;
            }
            this.awaitingTeleportTime = this.tickCount;
            this.player.absSnapTo(pos.x, pos.y, pos.z, yaw, pitch);

            this.send(
                new ClientboundPlayerPositionPacket(awaitingTeleport,
                    new net.minecraft.world.entity.PositionMoveRotation(
                        new Vec3(pos.x, pos.y, pos.z), Vec3.ZERO, yaw, pitch),
                    Collections.emptySet()));
            ci.cancel();
        }
    }

    @Inject(
        method = "onDisconnect",
        at = @At("HEAD")
    )
    void onDisconnect(DisconnectionDetails disconnectionDetails, CallbackInfo ci) {
        final VsiServerShipWorld world = VSGameUtilsKt.getShipObjectWorld(this.server);
        if (world != null) {
            world.onDisconnect(VSGameUtilsKt.getPlayerWrapper(this.player));
        }
    }

    @Inject(
        method = "handleMovePlayer",
        at = @At("TAIL")
    )
    void afterHandleMovePlayer(final ServerboundMovePlayerPacket packet, final CallbackInfo ci) {
        if (this.player instanceof final PlayerDuck duck) {
            duck.vs_setHandledMovePacket(true);
            if (duck.vs_getQueuedPositionUpdate() != null) {
                this.player.setPos(duck.vs_getQueuedPositionUpdate());
                duck.vs_setQueuedPositionUpdate(null);
            }
        }
    }

}
