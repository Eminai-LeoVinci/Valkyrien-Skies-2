package org.valkyrienskies.mod.compat.voxy;

/**
 * Tiny bridge for the per-pixel LOD-occlusion fix.
 *
 * <p>Under the 1.21.11 Blaze3D render system the gbuffer framebuffer is unbound between encoder
 * RenderPasses, so VS2 can't read it at any of its own mixin hooks (they all see draw-FBO 0). Voxy,
 * however, runs its LOD render WHILE Iris's gbuffer is bound (during Sodium's cutout pass) and reads
 * that framebuffer id locally. A mixin on Voxy's render entry captures the bound gbuffer FBO id into
 * here each frame; {@link VoxyPerPixel} then uses it to merge/restore LOD depth around the hull draw.
 *
 * <p>Plain static state, written on the render thread by the Voxy mixin and read on the render thread
 * by VoxyPerPixel in the same frame -- no synchronization needed. Stays 0 when Voxy is absent.
 */
public final class VoxyGbufferBridge {

    /** GL id of the framebuffer Iris had bound when Voxy last rendered (the gbuffer). 0 = unknown. */
    public static int gbufferFbo = 0;

    private VoxyGbufferBridge() {
    }

    /** Called from the Voxy render mixin with the currently-bound draw framebuffer. */
    public static void captureGbufferFbo(final int fbo) {
        if (fbo > 0) {
            gbufferFbo = fbo;
        }
    }
}
