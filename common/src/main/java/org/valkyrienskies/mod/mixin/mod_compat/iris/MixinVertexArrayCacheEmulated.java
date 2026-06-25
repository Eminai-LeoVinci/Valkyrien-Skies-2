package org.valkyrienskies.mod.mixin.mod_compat.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.render.ShipTerrainIrisPipeline;

/**
 * Legacy (no ARB_vertex_attrib_binding) twin of {@link MixinVertexArrayCacheSeparate}: on GPUs that fall back to
 * {@code VertexArrayCache$Emulated}, route GENERIC + non-FLOAT attributes through {@code glVertexAttribPointer}
 * (float input, non-normalized) instead of {@code glVertexAttribIPointer} (integer input), so Iris's mc_Entity /
 * at_midBlock reach the shaderpack's float inputs. Same {@code usage()==GENERIC} scope as the ARB mixin. Rarely
 * hit on modern desktop GPUs, but keeps the fix correct there too.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.VertexArrayCache$Emulated")
public class MixinVertexArrayCacheEmulated {

    @WrapOperation(
        method = "setupCombinedAttributes(Lcom/mojang/blaze3d/vertex/VertexFormat;Z)V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_vertexAttribIPointer(IIIIJ)V"))
    private static void vs$genericAttrAsFloatEmu(final int index, final int size, final int type, final int stride,
        final long offset, final Operation<Void> original, @Local(argsOnly = true) final VertexFormat format,
        @Local(ordinal = 0) final VertexFormatElement element) {
        if (format == ShipTerrainIrisPipeline.terrainFormat()
            && element.usage() == VertexFormatElement.Usage.GENERIC
            && element.type() != VertexFormatElement.Type.FLOAT) {
            GlStateManager._vertexAttribPointer(index, size, type, false, stride, offset);
        } else {
            original.call(index, size, type, stride, offset);
        }
    }
}
