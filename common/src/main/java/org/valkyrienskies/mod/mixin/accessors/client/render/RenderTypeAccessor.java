package org.valkyrienskies.mod.mixin.accessors.client.render;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a {@link RenderType}'s private {@link RenderSetup} so the persistent-GPU-buffer ship
 * terrain path can read the render type's texture map ({@code RenderSetup.getTextures()} -> the
 * block-atlas / lightmap samplers) and bind them onto its own {@code RenderPass}, exactly as vanilla
 * {@code RenderType.draw(MeshData)} does. Client-only.
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {

    @Accessor("state")
    RenderSetup valkyrienskies$getState();
}
