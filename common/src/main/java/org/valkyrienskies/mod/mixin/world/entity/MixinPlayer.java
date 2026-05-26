package org.valkyrienskies.mod.mixin.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.MinecraftPlayer;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.mixinducks.world.entity.PlayerDuck;

@Mixin(Player.class)
public abstract class MixinPlayer extends LivingEntity implements PlayerDuck {

    @Unique
    private final MinecraftPlayer vsPlayer = new MinecraftPlayer(Player.class.cast(this));
    @Unique
    private Vec3 queuedPositionUpdate = null;
    @Unique
    private boolean handledMovePacket = false;

    protected MixinPlayer(EntityType<? extends LivingEntity> entityType,
        Level level) {
        super(entityType, level);
    }

    @Override
    public MinecraftPlayer vs_getPlayer() {
        return vsPlayer;
    }

    @Override
    public Vec3 vs_getQueuedPositionUpdate() {
        return this.queuedPositionUpdate;
    }

    @Override
    public void vs_setQueuedPositionUpdate(final Vec3 queuedPositionUpdate) {
        this.queuedPositionUpdate = queuedPositionUpdate;
    }

    @Override
    public boolean vs_handledMovePacket() {
        return this.handledMovePacket;
    }

    @Override
    public void vs_setHandledMovePacket(final boolean handledMovePacket) {
        this.handledMovePacket = handledMovePacket;
    }

    @Shadow
    public abstract double blockInteractionRange();

    @Shadow
    public abstract double entityInteractionRange();

    // 1.21.11 port: Player.canInteractWithBlock(BlockPos,double) was renamed to
    // isWithinBlockInteractionRange(BlockPos,double). Without this the server reach check
    // always rejects ship blocks, since their real coordinates are in the far-away shipyard.
    @Inject(
        method = "isWithinBlockInteractionRange",
        at = @At("RETURN"),
        cancellable = true
    )
    private void includeShipsInDistanceCheck(BlockPos blockPos, double reachDistance, CallbackInfoReturnable<Boolean> cir) {
        // If the player can already interact then just return
        if (cir.getReturnValueZ()) {
            return;
        }
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level(), blockPos);
        if (ship != null) {
            final double e = this.blockInteractionRange() + reachDistance;
            final Vec3 eyePosInShip = VectorConversionsMCKt.toMinecraft(ship.getWorldToShip().transformPosition(VectorConversionsMCKt.toJOML(getEyePosition())));
            // Handle scaling
            final double distanceSq = (new AABB(blockPos)).distanceToSqr(eyePosInShip) * ship.getTransform().getShipToWorldScaling().x() * ship.getTransform().getShipToWorldScaling().x();
            cir.setReturnValue(distanceSq < e * e);
        }
    }

    // 1.21.11: isWithinEntityInteractionRange reach-checks an entity by its raw bounding box,
    // which for a shipyard entity (item frame, ...) sits millions of blocks away in the shipyard
    // -- so the client drops the use-on-entity interaction in Minecraft.startUseItem before any
    // packet is sent. Mirrors includeShipsInDistanceCheck (the block-reach fix) but for entities.
    @Inject(
        method = "isWithinEntityInteractionRange(Lnet/minecraft/world/entity/Entity;D)Z",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void valkyrienskies$includeShipsInEntityInteractionRange(final Entity entity,
        final double extraRange, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level(), entity.blockPosition());
        if (ship == null) {
            return;
        }
        final double e = this.entityInteractionRange() + extraRange;
        final Vec3 eyePosInShip = VectorConversionsMCKt.toMinecraft(
            ship.getWorldToShip().transformPosition(VectorConversionsMCKt.toJOML(getEyePosition())));
        final double scale = ship.getTransform().getShipToWorldScaling().x();
        final double distanceSq = entity.getBoundingBox().distanceToSqr(eyePosInShip) * scale * scale;
        cir.setReturnValue(distanceSq < e * e);
    }
}
