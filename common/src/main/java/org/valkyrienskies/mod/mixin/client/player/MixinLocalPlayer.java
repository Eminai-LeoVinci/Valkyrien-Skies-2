package org.valkyrienskies.mod.mixin.client.player;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Position;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.common.world.RaycastUtilsKt;
import org.valkyrienskies.mod.mixinducks.client.MinecraftDuck;
import org.valkyrienskies.mod.mixinducks.client.player.LocalPlayerDuck;

@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer extends LivingEntity implements LocalPlayerDuck {
    @Shadow
    private float yRotLast;
    @Shadow
    private float xRotLast;
    @Unique
    private Vec3 lastPosition = null;
    @Unique
    private Vector3dc velocity = new Vector3d();

    protected MixinLocalPlayer() {
        super(null, null);
    }

    /**
     * @reason We need to overwrite this method to force Minecraft to smoothly interpolate the Y rotation of the player
     * during rendering. Why it wasn't like this originally is beyond me \(>.<)/
     * @author StewStrong
     */
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void preGetViewYRot(final float partialTick, final CallbackInfoReturnable<Float> cir) {
        if (this.isPassenger()) {
            cir.setReturnValue(super.getViewYRot(partialTick));
        } else {
            cir.setReturnValue(Mth.lerp(partialTick, this.yRotO, this.getYRot()));
        }
    }

    @Override
    public Vector3dc vs$getVelocity() {
        return this.velocity;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tick(final CallbackInfo ci) {
        final Vec3 pos = this.position();
        if (this.lastPosition != null) {
            this.velocity = new Vector3d(pos.x - this.lastPosition.x, pos.y - this.lastPosition.y, pos.z - this.lastPosition.z);
        }
        this.lastPosition = pos;
    }

    @WrapMethod(
        method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z"
    )
    private boolean adjustLookOnMount(Entity entity, boolean bl, boolean bl2, Operation<Boolean> original) {
        Vector3d lookVector = VectorConversionsMCKt.toJOML(this.getLookAngle());
        if(original.call(entity, bl, bl2)) {
            Ship ship = VSGameUtilsKt.getShipMountedTo(Entity.class.cast(this));
            if (ship != null) {
                final Vector3d transformedLook = ship.getTransform().getWorldToShip().transformDirection(lookVector);
                final double yaw = Math.atan2(-transformedLook.x, transformedLook.z) * 180.0 / Math.PI;
                final double pitch = Math.atan2(-transformedLook.y, Math.sqrt((transformedLook.x * transformedLook.x) + (transformedLook.z * transformedLook.z))) * 180.0 / Math.PI;
                this.setYRot((float) yaw);
                this.setXRot((float) pitch);
                this.yRotO = this.getYRot();
                this.yRotLast = this.getYRot();
                this.yHeadRot = this.getYRot();
                this.yHeadRotO = this.getYRot();
                this.xRotO = this.getXRot();
                this.xRotLast = this.getXRot();
            }
            return true;
        }
        return false;
    }

    // 1.21.11 port: the crosshair raycast moved from GameRenderer.pick(Entity,DDF) to the new
    // LocalPlayer.pick(Entity,DDF). These two wraps were ported here from MixinGameRenderer so
    // that ships are included in the crosshair target and block placement on ships stays consistent.
    @Unique
    private static HitResult vs$entityRaycastNoTransform(final Entity entity, final double maxDistance,
        final float tickDelta, final boolean includeFluids) {
        final Vec3 eye = entity.getEyePosition(tickDelta);
        final Vec3 view = entity.getViewVector(tickDelta);
        final Vec3 end = eye.add(view.x * maxDistance, view.y * maxDistance, view.z * maxDistance);
        return RaycastUtilsKt.clipIncludeShips(
            entity.level(),
            new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, entity),
            false
        );
    }

    @WrapOperation(
        method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"
        )
    )
    private static HitResult vs$modifyCrosshairTargetBlocks(final Entity receiver, final double maxDistance,
        final float tickDelta, final boolean includeFluids, final Operation<HitResult> pick) {
        final HitResult original = vs$entityRaycastNoTransform(receiver, maxDistance, tickDelta, includeFluids);
        ((MinecraftDuck) Minecraft.getInstance()).vs$setOriginalCrosshairTarget(original);
        return pick.call(receiver, maxDistance, tickDelta, includeFluids);
    }

    @WrapOperation(
        method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"
        )
    )
    private static double vs$correctDistanceChecks(final Vec3 instance, final Vec3 other,
        final Operation<Double> original) {
        return VSGameUtilsKt.squaredDistanceBetweenInclShips(
            Minecraft.getInstance().level, instance, other, original);
    }

    // 1.21.11: LocalPlayer.filterHitResult (new in the picking refactor) drops a crosshair hit
    // whose location fails Vec3.closerThan against the eye. A shipyard entity's EntityHitResult
    // carries a ship-space location millions of blocks away (raytraceEntities never transforms it
    // back to world space), so item frames silently become a MISS and can never be targeted. The
    // pick() distance wraps above do not cover this separate method -- make its check ship-aware.
    @WrapOperation(
        method = "filterHitResult(Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/phys/Vec3;D)Lnet/minecraft/world/phys/HitResult;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;closerThan(Lnet/minecraft/core/Position;D)Z"
        ),
        require = 1
    )
    private static boolean vs$shipAwareHitResultRange(final Vec3 instance, final Position other,
        final double range, final Operation<Boolean> original) {
        if (original.call(instance, other, range)) {
            return true;
        }
        return VSGameUtilsKt.squaredDistanceBetweenInclShips(
            Minecraft.getInstance().level,
            instance.x, instance.y, instance.z, other.x(), other.y(), other.z()) < range * range;
    }
}
