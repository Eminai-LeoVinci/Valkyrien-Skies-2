package org.valkyrienskies.mod.mixin.accessors.resource;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ResourceKey.class)
public interface ResourceKeyAccessor {
/*
    @Accessor("VALUES")
    static Map<String, ResourceKey<?>> getValues() {
        throw new AssertionError();
    }

 */

    @Accessor
    Identifier getRegistryName();

    @Invoker
    static <T> ResourceKey<T> callCreate(final Identifier parent, final Identifier location) {
        throw new AssertionError();
    }
}
