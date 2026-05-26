package org.valkyrienskies.mod.fabric.mixin.feature.water_in_ships_entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Unique
    private boolean isModifyingWaterState = false;

    @Shadow
    public Level level;
    @Shadow
    private AABB bb;

    @Shadow
    public abstract double getEyeY();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract boolean updateFluidHeightAndDoFluidPushing(TagKey<Fluid> tagKey, double d);

    @Shadow
    public abstract void lavaIgnite();

    @Shadow
    public abstract void lavaHurt();

    @Shadow
    public abstract void extinguishFire();

    @Unique
    private boolean isShipWater = false;

    /**
     * used to replace updateFluidHeightAndDoFluidPushing aABB in ship context
     * */
    @Unique
    private AABB valkyrienskies$fluidPushAABB = null;

    /**
     * vector apply to the entity when getting pushed by liquid
     * used to combine updateFluidHeightAndDoFluidPushing vec3 of normal and ship context
     * */
    @Unique
    private Vec3 valkyrienskies$fluidPushVec = null;

    /**
     * Number of fluid pushing the entity, o in updateFluidHeightAndDoFluidPushing
     * used to combine updateFluidHeightAndDoFluidPushing o of normal and ship context
     * */
    @Unique
    private int valkyrienskies$fluidPushNumber = 0;

    @Unique
    private boolean valkyrienskies$fluidPushRet = false;

    /**
     * double that rely on other fluid push that is put in the fluidHeight, i don't know its utility but it
     * used to combine updateFluidHeightAndDoFluidPushing e of normal and ship context
     * */
    @Unique
    private double valkyrienskies$fluidPushE = 0;

    // 1.21.11 moved lava burning into InsideBlockEffectApplier (world-space block scan only), so
    // ship lava never triggers it. Set when the ship fluid scan finds lava reaching the entity.
    @Unique
    private boolean valkyrienskies$inShipLava = false;

    // 1.21.11 likewise drives water fire-extinguishing off a world-space block scan, so ship
    // water never puts the entity out. Set when the ship fluid scan finds water reaching it.
    @Unique
    private boolean valkyrienskies$inShipWater = false;

    @Unique
    private boolean inShipContext() {
        return valkyrienskies$fluidPushAABB != null;
    }

    @ModifyVariable(
        method = "updateFluidHeightAndDoFluidPushing",
        at = @At("STORE")
    )
    private AABB setFluidPushInShipContext(AABB original) {
        if (inShipContext())
            return valkyrienskies$fluidPushAABB;

        return original;
    }

    @ModifyConstant(
        method = "updateFluidHeightAndDoFluidPushing",
        constant = @Constant(doubleValue = 0.0)
    )
    private double setFluidPushInShipContext(double constant) {
        // valkyrienskies$fluidPushE = 0 before function end, no need to check inShipContext
        return valkyrienskies$fluidPushE;
    }


    @Inject(
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;length()D", ordinal = 0),
        method = "updateFluidHeightAndDoFluidPushing",
        cancellable = true
    )
    private void shouldProcessPush(TagKey<Fluid> tagKey, double d, CallbackInfoReturnable<Boolean> cir,
        @Local(ordinal = 6) int numberPush, @Local(ordinal = 1) boolean bl2, @Local(ordinal = 0) Vec3 vec3,
        @Local(ordinal = 1) double e) {
        if (inShipContext()) {
            //stop processing is in ship context, processing will be done after collectShipFluidPush in normal context
            valkyrienskies$fluidPushE = e;
            valkyrienskies$fluidPushNumber += numberPush;
            valkyrienskies$fluidPushVec = valkyrienskies$fluidPushVec.add(vec3);
            if (bl2 && FluidTags.LAVA.equals(tagKey)) {
                valkyrienskies$inShipLava = true;
            }
            if (bl2 && FluidTags.WATER.equals(tagKey)) {
                valkyrienskies$inShipWater = true;
            }
            cir.setReturnValue(bl2);
        }
    }

    @Redirect(
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;length()D", ordinal = 0),
        method = "updateFluidHeightAndDoFluidPushing"
    )
    private double collectShipFluidPush(Vec3 instance,
        @Local(ordinal = 0, argsOnly = true) TagKey<Fluid> tagKey, @Local(ordinal = 0, argsOnly = true) double d,
        @Local(ordinal = 0) AABB aabb, @Local(ordinal = 1) boolean bl2, @Local(ordinal = 6) int numberPush,
        @Local(ordinal = 1) double e)
    {
        valkyrienskies$fluidPushE = e;
        valkyrienskies$fluidPushNumber = numberPush;
        valkyrienskies$fluidPushRet = bl2;
        valkyrienskies$fluidPushVec = instance;
        valkyrienskies$inShipLava = false;
        valkyrienskies$inShipWater = false;
        IEntityDraggingInformationProvider provider = (IEntityDraggingInformationProvider) (Object) this;
        boolean sealed = provider.vs$isInSealedArea();
        VSGameUtilsKt.transformFromWorldToNearbyShips(level, aabb, (shipAabb) -> {
            valkyrienskies$fluidPushAABB = shipAabb; // enable ship context
            valkyrienskies$fluidPushRet = valkyrienskies$fluidPushRet || (this.updateFluidHeightAndDoFluidPushing(tagKey, d) && !sealed);
            //recall in the ship context
        });
        valkyrienskies$fluidPushAABB = null; //disable ship context
        if (valkyrienskies$inShipLava && !sealed) {
            // ship lava is missed by InsideBlockEffectApplier; re-apply ignite + damage
            lavaIgnite();
            lavaHurt();
        }
        if (valkyrienskies$inShipWater && !sealed) {
            // ship water is missed by the world-space extinguish scan; put the entity's fire out
            extinguishFire();
        }
        return valkyrienskies$fluidPushVec.length();
    }
    
    @ModifyVariable(
        method = "updateFluidHeightAndDoFluidPushing",
        at = @At("LOAD"),
        ordinal = 6
    )
    private int loadO(int origin) {
        return valkyrienskies$fluidPushNumber;
    }

    @Redirect(
        method = "updateFluidHeightAndDoFluidPushing",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;", ordinal = 1)
    )
    private Vec3 setVec3ToPush(Vec3 instance, double d) {
        return valkyrienskies$fluidPushVec.scale(d);
    }

    @Inject(
        method = "updateFluidHeightAndDoFluidPushing",
        at = @At("TAIL"),
        cancellable = true
    )
    private void setFluidPushingReturnValue(TagKey<Fluid> tagKey, double d, CallbackInfoReturnable<Boolean> cir) {
        valkyrienskies$fluidPushE = 0;
        cir.setReturnValue(valkyrienskies$fluidPushRet);
    }

    // The ship-context recursion accumulates the combined world+ship fluid height into fluidPushE, but
    // vanilla's fluidHeight.put stores only the world-context d -> isInLava() ignores ship lava. Fix the arg.
    @ModifyArg(
        method = "updateFluidHeightAndDoFluidPushing",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Object2DoubleMap;put(Ljava/lang/Object;D)D"
        ),
        index = 1
    )
    private double valkyrienskies$includeShipFluidHeight(double original) {
        return valkyrienskies$fluidPushE;
    }


    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
        method = "updateFluidOnEyes"
    )
    private FluidState getFluidStateRedirect(final Level level, final BlockPos blockPos,
        final Operation<FluidState> getFluidState) {
        final FluidState[] fluidState = {getFluidState.call(level, blockPos)};
        isShipWater = false;
        if (fluidState[0].isEmpty()) {

            final double d = this.getEyeY() - 0.1111111119389534;

            final double origX = this.getX();
            final double origY = d;
            final double origZ = this.getZ();

            VSGameUtilsKt.transformToNearbyShipsAndWorld(this.level, origX, origY, origZ, this.bb.getSize(),
                (x, y, z) -> {
                    fluidState[0] = getFluidState.call(level, BlockPos.containing(x, y, z));
                });
            isShipWater = true;
        }
        return fluidState[0];
    }

    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"),
        method = "updateFluidOnEyes"
    )
    private float fluidHeightOverride(final FluidState instance, final BlockGetter arg, final BlockPos arg2,
        final Operation<Float> getHeight) {
        if (!instance.isEmpty() && this.level instanceof Level) {

            if (isShipWater) {
                if (instance.isSource()) {
                    return 1;
                }
            }

        }
        return getHeight.call(instance, arg, arg2);
    }

}
