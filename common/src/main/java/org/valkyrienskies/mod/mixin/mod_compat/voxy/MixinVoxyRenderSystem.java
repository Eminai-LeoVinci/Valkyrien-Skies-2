package org.valkyrienskies.mod.mixin.mod_compat.voxy;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.compat.voxy.VoxyGbufferBridge;

/**
 * Capture Iris's gbuffer framebuffer id for the per-pixel ship/LOD occlusion fix.
 *
 * <p>String-targeted (no compile/runtime dependency on Voxy); only applies when Voxy is installed,
 * and is non-required so its absence is a silent no-op. Voxy's {@code renderOpaque} runs during
 * Sodium's chunk pass with Iris's gbuffer framebuffer bound, so the currently-bound draw framebuffer
 * here is exactly the gbuffer VS2's hull is later drawn into -- the one framebuffer VS2 can't read at
 * any of its own hooks (Blaze3D unbinds between encoder passes). We stash it for {@link VoxyPerPixel}.
 *
 * <p>This may also fire for a shadow-pass invocation (wrong, smaller framebuffer); the main pass runs
 * after the shadow pass each frame, so the last capture before the hull draw is the correct gbuffer.
 * {@code VoxyPerPixel} additionally validates the framebuffer's depth size against the render size, so
 * a stale/shadow capture can never be used.
 */
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class MixinVoxyRenderSystem {

    @Inject(method = "renderOpaque", at = @At("HEAD"), require = 0, remap = false)
    private void valkyrienskies$captureGbufferFbo(final CallbackInfo ci) {
        VoxyGbufferBridge.captureGbufferFbo(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
    }
}
