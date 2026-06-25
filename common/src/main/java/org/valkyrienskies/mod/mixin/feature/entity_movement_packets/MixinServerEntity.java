package org.valkyrienskies.mod.mixin.feature.entity_movement_packets;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.impl.networking.simple.SimplePacket;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.networking.PacketEntityShipMotion;
import org.valkyrienskies.mod.common.networking.PacketMobShipRotation;
import org.valkyrienskies.mod.common.util.EntityDragger;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.EntityLerper;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;
import org.valkyrienskies.mod.mixinducks.world.entity.PlayerDuck;

@Mixin(ServerEntity.class)
public class MixinServerEntity {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ServerLevel level;

    /**
     * @author Tomato
     * @reason Intercept entity motion packets to send our own data, then cancel the original packet.
     *
     * <p>1.21.11 port fix: {@code ServerEntity.sendChanges} no longer broadcasts move/teleport/motion/rotate
     * packets via {@code Consumer.accept} (that path now only exists in {@code sendPairingData} for spawn data).
     * In 1.21.11 every position/motion/rotation packet is dispatched through
     * {@code ServerEntity$Synchronizer.sendToTrackingPlayers(Packet)}. The old {@code Consumer.accept} target
     * matched nothing here and, with {@code defaultRequire=0}, silently no-opped — so the VS ship-motion packet
     * was NEVER sent for any entity and every ship-borne mob / remote player interpolated as a plain remote
     * entity (the ~3-block slide at speed). Retargeting to {@code sendToTrackingPlayers} restores the sync.
     * {@code require = 1} makes any future mapping/refactor break fail loudly instead of silently no-opping again.
     */
    @WrapOperation(
        method = "sendChanges",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerEntity$Synchronizer;"
                + "sendToTrackingPlayers(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 1
    )
    private void wrapBroadcastAccept(ServerEntity.Synchronizer instance, Packet<?> t, Operation<Void> original) {
        if (t instanceof ClientboundSetEntityMotionPacket || t instanceof ClientboundTeleportEntityPacket || t instanceof ClientboundMoveEntityPacket || t instanceof ClientboundRotateHeadPacket || t instanceof ClientboundEntityPositionSyncPacket) {
            // ClientboundEntityPositionSyncPacket is the 1.21.x FORCED full position-sync (sent ~every 400 ticks
            // and on large deltas / onGround flips), built from the entity's WORLD position. It was NOT in this
            // guard, so for a dragged mob it fell through to the raw vanilla send -> the client hard-SNAPS via
            // Entity.snapTo to a stale world position (the "enderman teleport", no particles, any time). Routing it
            // into the existing dragged-mob branch converts it to a ship-frame PacketEntityShipMotion like the
            // other position packets, so it lerps instead of snapping.
            if (EntityDragger.isDraggable(entity)) {
                IEntityDraggingInformationProvider draggedEntity = (IEntityDraggingInformationProvider) entity;
                EntityDraggingInformation dragInfo = draggedEntity.getDraggingInformation();

                if (dragInfo != null && dragInfo.isEntityBeingDraggedByAShip() && dragInfo.getLastShipStoodOn() != null) {
                    ServerShip ship = VSGameUtilsKt.getShipObjectWorld(level).getAllShips().getById(dragInfo.getLastShipStoodOn());
                    if (ship != null) {

                        Vector3d position = ship.getWorldToShip().transformPosition(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
                        if (dragInfo.getServerRelativePlayerPosition() != null) {
                            position = new Vector3d(dragInfo.getServerRelativePlayerPosition());
                        }
                        Vector3d motion = ship.getTransform().getWorldToShip().transformDirection(new Vector3d(entity.getDeltaMovement().x(), entity.getDeltaMovement().y(), entity.getDeltaMovement().z()), new Vector3d());
                        double yaw;
                        if (!(t instanceof ClientboundRotateHeadPacket)) {
                            yaw = EntityLerper.INSTANCE.yawToShip(ship, entity.getYRot());
                        } else {
                            yaw = EntityLerper.INSTANCE.yawToShip(ship, entity.getYHeadRot());
                        }
                        double pitch = entity.getXRot();
                        SimplePacket vsPacket;
                        if (!(t instanceof ClientboundRotateHeadPacket)) {
                            vsPacket = new PacketEntityShipMotion(entity.getId(), ship.getId(),
                                position.x, position.y, position.z,
                                motion.x, motion.y, motion.z,
                                yaw, pitch);
                        } else {
                            vsPacket = new PacketMobShipRotation(entity.getId(), ship.getId(), yaw, pitch);
                        }

                        List<ServerPlayer> players = level.getPlayers(player -> player.shouldRender(entity.getX(), entity.getY(), entity.getZ()));
                        players.forEach(
                            player -> ValkyrienSkiesMod.getVsCore().getSimplePacketNetworking().sendToClients(vsPacket, ((PlayerDuck)player).vs_getPlayer())
                        );

                        return;
                    }


                }
            }
        }
        original.call(instance, t);
    }
}
