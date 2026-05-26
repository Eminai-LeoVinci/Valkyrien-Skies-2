package org.valkyrienskies.mod.mixin.feature.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.command.arguments.RelativeVector3Argument;
import org.valkyrienskies.mod.common.command.arguments.ShipArgument;
import org.valkyrienskies.mod.common.command.arguments.ShipArgumentInfo;

// 1.21.11 port: DISABLED -- removed from valkyrienskies-common.mixins.json.
// This @Inject into ArgumentTypeInfos.bootstrap does not weave on 1.21.11 because
// ArgumentTypeInfos is class-loaded too early (during BuiltInRegistries static init)
// for the mod mixin config to apply -- no apply error is logged, the mixin simply
// has no effect, ShipArgument never lands in BY_CLASS, and the server fails to
// serialize the command tree on player join ("Invalid player data").
// VS2's two custom argument types are now registered from
// ValkyrienSkiesModFabric.onInitialize() via Fabric API's ArgumentTypeRegistry.
@Mixin(ArgumentTypeInfos.class)
public class MixinArgumentTypeInfos {
    @Shadow
    private static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> ArgumentTypeInfo<A, T> register(
        Registry<ArgumentTypeInfo<?, ?>> arg, String string, Class<? extends A> class_, ArgumentTypeInfo<A, T> arg2) {
        throw new IllegalStateException();
    }

    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void postBootstrap(final Registry<ArgumentTypeInfo<?, ?>> registry,
        final CallbackInfoReturnable<ArgumentTypeInfo<?, ?>> ci) {
        register(registry, "valkyrienskies:ship_argument", ShipArgument.class, new ShipArgumentInfo());
        register(registry, "valkyrienskies:relative_vector3_argument", RelativeVector3Argument.class,
            SingletonArgumentInfo.contextFree(RelativeVector3Argument::new));
    }
}
