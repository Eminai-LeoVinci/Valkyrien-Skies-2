package org.valkyrienskies.mod.mixin.mod_compat.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.render.ShipTerrainIrisPipeline;

/**
 * Makes Iris's GENERIC integer vertex attributes (mc_Entity, at_midBlock) survive when an Iris TERRAIN-format
 * buffer is drawn through a vanilla Mojang {@code RenderPipeline} -- the VS persistent ship-terrain path.
 *
 * <p>Vanilla Blaze3D ({@code VertexArrayCache$Separate}, the modern ARB_vertex_attrib_binding path) sets up a
 * GENERIC + non-FLOAT attribute with {@code glVertexAttribIFormat} (integer input). But the shaderpack declares
 * these as float inputs ({@code in vec4 mc_Entity}, {@code in vec3 at_midBlock}); feeding an integer-format
 * attribute into a float input is undefined per the GL spec and reads 0, so ship block ids (emission/material)
 * and wave pivots never reach the shader. Sodium's own terrain binds them via the float path
 * ({@code glVertexAttribPointer}, normalized=false); this makes Blaze3D match Sodium for GENERIC elements.
 *
 * <p>Scope: vanilla registers NO GENERIC elements, so only modded (Iris) formats are affected; in practice the
 * only such format reaching Blaze3D's VAO cache is our ship pipeline (Sodium uses its own VAO setup). The guard
 * is {@code usage()==GENERIC} (NOT merely non-FLOAT), so UV2 (lightmap: UV usage, SHORT) stays on the integer
 * path. When Iris is absent the guard is never true, so this mixin is a no-op.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.VertexArrayCache$Separate")
public class MixinVertexArrayCacheSeparate {

    private static final Logger VS$LOGGER = LogUtils.getLogger();
    private static boolean vs$logged;

    @WrapOperation(
        method = "bindVertexArray(Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/opengl/GlBuffer;)V",
        at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/ARBVertexAttribBinding;glVertexAttribIFormat(IIII)V"))
    private void vs$genericAttrAsFloat(final int index, final int size, final int type, final int relativeOffset,
        final Operation<Void> original, @Local(argsOnly = true) final VertexFormat format,
        @Local(ordinal = 0) final VertexFormatElement element) {
        // Only our ship TERRAIN draw (reference identity); never any other Iris/vanilla format. Within TERRAIN,
        // still restrict to GENERIC non-FLOAT so UV2 (lightmap, UV usage + SHORT) keeps its integer path.
        if (format == ShipTerrainIrisPipeline.terrainFormat()
            && element.usage() == VertexFormatElement.Usage.GENERIC
            && element.type() != VertexFormatElement.Type.FLOAT) {
            // Float-input path, no normalization -> the short/byte arrives as its raw value (195 -> 195.0),
            // which is what the shaderpack's float `in` expects (matches Sodium's non-int, non-normalized bind).
            ARBVertexAttribBinding.glVertexAttribFormat(index, size, type, false, relativeOffset);
            if (!vs$logged) {
                vs$logged = true;
                VS$LOGGER.info("VS ship terrain: Iris GENERIC vertex attributes now upload via the float path "
                    + "(mc_Entity / at_midBlock reach shaders)");
            }
        } else {
            original.call(index, size, type, relativeOffset);
        }
    }
}
