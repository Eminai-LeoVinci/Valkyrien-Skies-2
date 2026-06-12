package org.valkyrienskies.mod.compat.voxy;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Read-only Voxy LOD-depth occlusion for VS2 assembled ships.
 *
 * <p>WHY: assembled ships render far past the vanilla render distance, out where terrain is only
 * drawn as Voxy LOD. VS2 ships depth-test against the gbuffer depth, which does NOT contain LOD
 * depth, so a ship behind a distant monument still paints in front of it. The shaderpack-side fix
 * (forcing Voxy's LOD into the gbuffer depth) breaks the shaderpack's depth-based shadow passes. So
 * instead we READ Voxy's own LOD depth buffer to decide whether a ship is fully hidden, and skip
 * drawing it -- never writing anything the shaderpack reads. Zero shadow side-effects, works on any
 * shaderpack.
 *
 * <p>NO COMPILE DEPENDENCY ON VOXY: everything is reflection, resolved by name at runtime and
 * cached. Voxy is auto-detected via the {@code voxy$getRenderSystem} method its mixin adds to
 * LevelRenderer. If Voxy is absent / a different version / anything fails, this disables itself and
 * VS2 renders ships exactly as before.
 *
 * <p>CALIBRATION (from the 2.4.167 diagnostic, Voxy 0.2.15-beta): Voxy depth is standard
 * zero-to-one (0 = near, 1 = far). A point is BEHIND LOD terrain when
 * {@code sampledLodDepth < pointNdcZ}. Sky reads ~1.0 (far), so points over sky never count as
 * behind. Points are projected with Voxy's own viewport MVP (camera-relative), so their NDC depth
 * and the sampled LOD depth live in the same clip space -- no near/far linearisation.
 *
 * <p>COVERAGE (2.4.170 -> 2.4.172): we sample a {@code GRID^3} lattice over the render AABB (the
 * ship's on-screen bbox is read in one block -- ~8x fewer GPU syncs than per-corner reads; a very
 * large bbox from a close/zoomed ship falls back to per-texel reads) and classify each on-screen
 * point as SKY (no LOD terrain there: open sky, a window, the gap between monuments, or a near
 * non-LOD object), BEHIND (LOD in front of it) or FRONT (the point sticks out in front of LOD). The
 * ship is hidden when BEHIND is the majority of the terrain-overlapping points ({@code >= BEHIND_FRAC})
 * and at least {@code MIN_COVER} of its on-screen points overlap terrain. Treating gaps as SKY
 * (ignored) stops a second occluder / window / pillar / palm frond from dragging a mostly-hidden
 * ship back into view; tolerating a MINORITY of FRONT samples handles complex monuments whose
 * receding upper steps / window openings / ledges show terrain farther than the ship. Granularity is
 * still whole-ship: a mostly-hidden ship pops out entirely rather than clipping per-pixel (the
 * shaderpack's depth is never touched, so this stays side-effect-free on any shaderpack).
 */
public final class VoxyOcclusion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Depth margin to avoid culling on coincident / z-fighting depths. */
    private static final float EPS = 0.0005f;

    /**
     * Minimum fraction of a ship's on-screen sample points that must overlap LOD terrain (the rest
     * being open sky / gaps, which are ignored) before it is even eligible to be hidden. Keeps a
     * mostly-open-sky ship that only clips a little terrain visible.
     */
    private static final float MIN_COVER = 0.5f;

    /**
     * Of the sample points that overlap LOD terrain, the fraction that must be BEHIND it (vs poking in
     * FRONT through a window / ledge / receding step) to hide the whole ship. 0.5 = a simple majority.
     * Lower hides more aggressively through holey monuments; higher is more cautious about over-hiding.
     */
    private static final float BEHIND_FRAC = 0.5f;

    /** LOD depth at/above which a pixel is treated as open sky / no LOD terrain (standard 0..1, far = 1). */
    private static final float SKY_DEPTH = 0.999f;

    /** Samples per axis over the render AABB ({@code GRID^3} points total). */
    private static final int GRID = 5;

    /**
     * Occluder-dilation radius in texels: each sample takes the NEAREST LOD depth in a
     * {@code (2*DILATE+1)^2} screen neighbourhood. This fills the thin holes a coarse LOD leaves in
     * carved/recessed facades (the "concrete 1-2 blocks behind the design reveals the ship" case) and
     * covers silhouette edges of overlapping layers, both of which otherwise leak the ship.
     */
    private static final int DILATE = 2;

    /** Max width/height (texels) of the one-shot depth-region readback; larger bboxes use a per-sample read. */
    private static final int MAXDIM = 96;

    /** Reusable scratch buffer for the per-ship depth-region readback (render thread only; never freed). */
    private static FloatBuffer regionBuf;

    // Voxy presence is detected by reflection (its mixin adds voxy$getRenderSystem to LevelRenderer),
    // so there is no compile/runtime dependency on Voxy or any loader API. Flipped true once we learn
    // Voxy is absent or its internals can't be reached, after which this is a no-op.
    private static boolean failed = false;
    private static boolean resolved = false;

    /** One-shot guard for the per-pixel recon dump (2.4.174); only consumed on the main pass. */
    private static boolean perPixelDiagLogged = false;
    /** Frame attempts for the recon; bounded so we don't retry forever if the main pass never matches. */
    private static int perPixelDiagAttempts = 0;

    private static Method mGetRenderSystem; // LevelRenderer.voxy$getRenderSystem()
    private static Method mGetViewport;     // VoxyRenderSystem.getViewport()
    private static Field fPipeline;         // VoxyRenderSystem.pipeline (private)
    private static Field fFb;               // AbstractRenderPipeline.fb (public)
    private static Method mGetDepthTex;     // DepthFramebuffer.getDepthTex()
    private static Field fTexId;            // GlTexture.id
    private static Field fVpWidth;
    private static Field fVpHeight;
    private static Field fVpMvp;            // Viewport.MVP (joml Matrix4f, camera-relative clip)
    private static Field fVpProjection;        // Viewport.projection (Voxy perspective: near 16 / far 48000)
    private static Field fVpVanillaProjection; // Viewport.vanillaProjection (MC projection)
    private static Field fVpCamX;
    private static Field fVpCamY;
    private static Field fVpCamZ;

    private VoxyOcclusion() {
    }

    public static boolean isPresent() {
        return !failed;
    }

    public static void logCulledCount(final int n) {
        LOGGER.info("[vs voxy-occlusion] hiding {} ship(s) fully behind LOD terrain", n);
    }

    /**
     * @return true if BEHIND is the majority ({@code >= BEHIND_FRAC}) of the ship's terrain-overlapping
     *     sample points and at least {@code MIN_COVER} of its on-screen points overlap terrain, so the
     *     whole ship should be skipped this frame. Returns false (render the ship) when it is near the
     *     camera (a corner behind the near plane), fully off-screen, mostly in front of terrain, or
     *     mostly over open sky / gaps.
     */
    public static boolean isOccludedByLod(final LevelRenderer levelRenderer,
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ) {
        if (failed || levelRenderer == null) {
            return false;
        }
        if (VoxyPerPixel.isReplacingCull()) {
            return false; // per-pixel occlusion is live -> draw the hull and let it depth-test vs LOD
        }
        try {
            if (mGetRenderSystem == null) {
                try {
                    mGetRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
                } catch (final NoSuchMethodException notVoxy) {
                    failed = true;
                    LOGGER.info("[vs voxy-occlusion] Voxy not present; ship LOD-occlusion disabled");
                    return false;
                }
            }
            final Object vrs = mGetRenderSystem.invoke(levelRenderer);
            if (vrs == null) {
                return false; // renderer not created (Voxy disabled / world loading)
            }
            if (!resolved) {
                resolveHandles(vrs);
                if (!resolved) {
                    return false; // viewport not ready yet
                }
            }
            final Object viewport = mGetViewport.invoke(vrs);
            if (viewport == null) {
                return false; // iris shadow pass etc.
            }
            final Object pipeline = fPipeline.get(vrs);
            if (pipeline == null) {
                return false;
            }
            final Object fb = fFb.get(pipeline);
            if (fb == null) {
                return false;
            }
            final Object depthTex = mGetDepthTex.invoke(fb);
            if (depthTex == null) {
                return false;
            }
            final int texId = fTexId.getInt(depthTex);
            final int w = fVpWidth.getInt(viewport);
            final int h = fVpHeight.getInt(viewport);
            if (texId <= 0 || w <= 0 || h <= 0) {
                return false;
            }
            final Matrix4f mvp = (Matrix4f) fVpMvp.get(viewport);
            final double camX = fVpCamX.getDouble(viewport);
            final double camY = fVpCamY.getDouble(viewport);
            final double camZ = fVpCamZ.getDouble(viewport);

            final double[] xs = {minX, maxX};
            final double[] ys = {minY, maxY};
            final double[] zs = {minZ, maxZ};
            final Vector4f clip = new Vector4f();

            // Project the 8 AABB corners to find the ship's on-screen bounding rectangle. Bail (render
            // the ship) if any corner is behind the camera: that means the ship is very close, where it
            // is drawn from real chunks and occludes naturally -- LOD-culling it there would be wrong.
            float bbMinX = Float.MAX_VALUE;
            float bbMinY = Float.MAX_VALUE;
            float bbMaxX = -Float.MAX_VALUE;
            float bbMaxY = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                clip.set((float) (xs[i & 1] - camX), (float) (ys[(i >> 1) & 1] - camY),
                    (float) (zs[(i >> 2) & 1] - camZ), 1.0f);
                mvp.transform(clip);
                if (clip.w <= 0.0f) {
                    return false; // a corner is behind the camera -> near ship, don't LOD-cull
                }
                final float invW = 1.0f / clip.w;
                final float sx = (clip.x * invW * 0.5f + 0.5f) * w;
                final float sy = (clip.y * invW * 0.5f + 0.5f) * h;
                bbMinX = Math.min(bbMinX, sx);
                bbMaxX = Math.max(bbMaxX, sx);
                bbMinY = Math.min(bbMinY, sy);
                bbMaxY = Math.max(bbMaxY, sy);
            }
            // Integer screen rectangle, clamped to the viewport.
            int x0 = (int) Math.floor(bbMinX);
            int y0 = (int) Math.floor(bbMinY);
            int x1 = (int) Math.ceil(bbMaxX);
            int y1 = (int) Math.ceil(bbMaxY);
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            x1 = Math.min(w - 1, x1);
            y1 = Math.min(h - 1, y1);
            if (x1 < x0 || y1 < y0) {
                return false; // fully off-screen
            }
            final int bw = x1 - x0 + 1;
            final int bh = y1 - y0 + 1;

            // Common case (far / normal-zoom ship, small bbox): read the whole bbox in ONE call -- one
            // GPU sync, vs one per corner before. For a very large bbox (a close or heavily zoomed ship)
            // reading it whole would be huge, so fall back to a per-sample texel read. This keeps EVERY
            // grid sample correct instead of centre-cropping to the middle slice, which previously
            // sampled an unrepresentative region and revealed zoomed ships near max zoom.
            final boolean useRegion = bw <= MAXDIM && bh <= MAXDIM;
            final FloatBuffer region = useRegion ? readDepthRegion(texId, x0, y0, bw, bh) : null;

            // Sample a GRID^3 lattice over the AABB and classify each on-screen point by the LOD depth
            // at its pixel:
            //   * SKY    - no LOD terrain there (open sky, a window, the gap between two monuments, or a
            //              near real-chunk object like palm fronds that Voxy doesn't draw as LOD). The
            //              ship may be visible there, but this is not evidence of occlusion -> ignore.
            //   * BEHIND - LOD terrain is in front of the point (the ship is hidden there).
            //   * FRONT  - the point is in front of the LOD (the ship genuinely sticks out past terrain).
            // Hide the whole ship when BEHIND is the majority of the terrain-overlapping samples and a
            // real chunk of it overlaps terrain (>= MIN_COVER). Treating gaps as SKY (ignore) instead of
            // "not occluded" stops a 2nd monument / window / pillar / palm frond from dragging a
            // mostly-hidden ship back into view; tolerating a MINORITY of FRONT samples (rather than
            // requiring zero) handles complex monuments whose receding upper steps / window openings /
            // ledges show terrain FARTHER than the ship without un-hiding a ship that is, on balance,
            // behind the monument.
            int counted = 0;
            int behind = 0;
            int front = 0;
            for (int gz = 0; gz < GRID; gz++) {
                final double wz = minZ + (maxZ - minZ) * gz / (GRID - 1);
                for (int gy = 0; gy < GRID; gy++) {
                    final double wy = minY + (maxY - minY) * gy / (GRID - 1);
                    for (int gx = 0; gx < GRID; gx++) {
                        final double wx = minX + (maxX - minX) * gx / (GRID - 1);
                        clip.set((float) (wx - camX), (float) (wy - camY), (float) (wz - camZ), 1.0f);
                        mvp.transform(clip);
                        if (clip.w <= 0.0f) {
                            continue;
                        }
                        final float invW = 1.0f / clip.w;
                        final int px = Math.round((clip.x * invW * 0.5f + 0.5f) * w);
                        final int py = Math.round((clip.y * invW * 0.5f + 0.5f) * h);
                        if (px < x0 || px > x1 || py < y0 || py > y1) {
                            continue; // outside the read region
                        }
                        counted++;
                        // Nearest LOD depth in a small neighbourhood (occluder dilation) -- fills coarse-LOD
                        // holes in carved facades and covers silhouette edges, which otherwise leak the ship.
                        final float lod = useRegion
                            ? minLodInRegion(region, x0, y0, x1, y1, bw, px, py)
                            : minLodBlock(texId, px, py, w, h);
                        if (lod >= SKY_DEPTH) {
                            continue; // open sky / gap / non-LOD object -> ignore
                        }
                        final float ndcZ = clip.z * invW;
                        if (lod < ndcZ - EPS) {
                            behind++;
                        } else {
                            front++; // ship is in front of LOD terrain here -> genuinely visible
                        }
                    }
                }
            }
            if (counted == 0) {
                return false;
            }
            final int nonSky = behind + front;
            if (nonSky < counted * MIN_COVER) {
                return false; // ship is mostly over open sky / gaps -> visible
            }
            return behind >= nonSky * BEHIND_FRAC;
        } catch (final Throwable t) {
            failed = true;
            LOGGER.warn("[vs voxy-occlusion] reflective access failed; disabling Voxy occlusion", t);
            return false;
        }
    }

    /** Lightweight carrier for the per-pixel merge: Voxy LOD depth tex + projection scalars. */
    public static final class MergeParams {
        public int voxyDepthTex;
        public int viewportW;
        public int viewportH;
        public float projM22;
        public float projM32;
        public float vanM22;
        public float vanM32;
    }

    private static final MergeParams MERGE = new MergeParams();

    /**
     * Resolve the data {@link VoxyPerPixel} needs to merge Voxy LOD depth into the gbuffer, but ONLY on
     * the main pass (Voxy viewport non-null; it's null in the Iris shadow pass). Returns a shared,
     * reused instance (render thread only) or null when not applicable / not ready / Voxy absent.
     */
    public static MergeParams mainPassMergeParams(final LevelRenderer levelRenderer) {
        if (failed || levelRenderer == null) {
            return null;
        }
        try {
            if (mGetRenderSystem == null) {
                try {
                    mGetRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
                } catch (final NoSuchMethodException notVoxy) {
                    failed = true;
                    return null;
                }
            }
            final Object vrs = mGetRenderSystem.invoke(levelRenderer);
            if (vrs == null) {
                return null;
            }
            if (!resolved) {
                resolveHandles(vrs);
                if (!resolved) {
                    return null;
                }
            }
            final Object viewport = mGetViewport.invoke(vrs);
            if (viewport == null) {
                return null; // shadow pass / not the main pass
            }
            final Object pipeline = fPipeline.get(vrs);
            if (pipeline == null) {
                return null;
            }
            final Object fb = fFb.get(pipeline);
            if (fb == null) {
                return null;
            }
            final Object depthTex = mGetDepthTex.invoke(fb);
            if (depthTex == null) {
                return null;
            }
            final Matrix4f proj = (Matrix4f) fVpProjection.get(viewport);
            final Matrix4f van = (Matrix4f) fVpVanillaProjection.get(viewport);
            MERGE.voxyDepthTex = fTexId.getInt(depthTex);
            MERGE.viewportW = fVpWidth.getInt(viewport);
            MERGE.viewportH = fVpHeight.getInt(viewport);
            MERGE.projM22 = proj.m22();
            MERGE.projM32 = proj.m32();
            MERGE.vanM22 = van.m22();
            MERGE.vanM32 = van.m32();
            if (MERGE.voxyDepthTex <= 0 || MERGE.viewportW <= 0 || MERGE.viewportH <= 0) {
                return null;
            }
            return MERGE;
        } catch (final Throwable t) {
            return null;
        }
    }

    private static void resolveHandles(final Object vrs) throws Exception {
        mGetViewport = vrs.getClass().getMethod("getViewport");
        fPipeline = vrs.getClass().getDeclaredField("pipeline");
        fPipeline.setAccessible(true);
        final Object pipeline = fPipeline.get(vrs);
        if (pipeline == null) {
            return; // pipeline not built yet
        }
        fFb = pipeline.getClass().getField("fb"); // public, declared in AbstractRenderPipeline
        final Object fb = fFb.get(pipeline);
        if (fb == null) {
            return; // pipeline framebuffer not built yet (e.g. the Iris shadow pass) -- retry later
        }
        mGetDepthTex = fb.getClass().getMethod("getDepthTex");
        final Object depthTex = mGetDepthTex.invoke(fb);
        fTexId = depthTex.getClass().getDeclaredField("id");
        fTexId.setAccessible(true);
        final Object viewport = mGetViewport.invoke(vrs);
        if (viewport == null) {
            return; // resolve viewport fields once a viewport exists
        }
        fVpWidth = viewport.getClass().getField("width");
        fVpHeight = viewport.getClass().getField("height");
        fVpMvp = viewport.getClass().getField("MVP");
        fVpProjection = viewport.getClass().getField("projection");
        fVpVanillaProjection = viewport.getClass().getField("vanillaProjection");
        fVpCamX = viewport.getClass().getField("cameraX");
        fVpCamY = viewport.getClass().getField("cameraY");
        fVpCamZ = viewport.getClass().getField("cameraZ");
        resolved = true;
        LOGGER.info("[vs voxy-occlusion] resolved Voxy reflective handles (depthTexClass={}, viewportClass={})",
            depthTex.getClass().getName(), viewport.getClass().getName());
    }

    /**
     * Read a {@code bw x bh} block of Voxy's LOD depth texture starting at (x, y) in ONE call into a
     * reusable scratch buffer. GL texture origin is bottom-left, so the value at screen (px, py) is at
     * index {@code (py - y) * bw + (px - x)}. One readback/ship is a single GPU sync (vs one per corner).
     */
    private static FloatBuffer readDepthRegion(final int texId, final int x, final int y,
        final int bw, final int bh) {
        if (regionBuf == null) {
            regionBuf = MemoryUtil.memAllocFloat(MAXDIM * MAXDIM);
        }
        final FloatBuffer buf = regionBuf;
        buf.clear();
        buf.limit(bw * bh);
        GL45C.glGetTextureSubImage(texId, 0, x, y, 0, bw, bh, 1,
            GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        return buf;
    }

    /**
     * Nearest (smallest) LOD depth in a {@code (2*DILATE+1)^2} screen neighbourhood of (px, py), read
     * from the already-fetched region buffer and clamped to the region bounds. Dilating occluders this
     * way fills the thin holes a coarse LOD leaves in carved/recessed facades and at silhouette edges,
     * which otherwise leak the ship through.
     */
    private static float minLodInRegion(final FloatBuffer region, final int x0, final int y0,
        final int x1, final int y1, final int bw, final int px, final int py) {
        float m = Float.MAX_VALUE;
        for (int dy = -DILATE; dy <= DILATE; dy++) {
            final int sy = py + dy;
            if (sy < y0 || sy > y1) {
                continue;
            }
            for (int dx = -DILATE; dx <= DILATE; dx++) {
                final int sx = px + dx;
                if (sx < x0 || sx > x1) {
                    continue;
                }
                final float v = region.get((sy - y0) * bw + (sx - x0));
                if (v < m) {
                    m = v;
                }
            }
        }
        return m;
    }

    /**
     * Same neighbourhood-min as {@link #minLodInRegion}, but for the per-sample fallback path used when
     * a ship's bbox is larger than {@link #MAXDIM} (a close / zoomed ship): reads a
     * {@code (2*DILATE+1)^2} block around (x, y) in one call and returns the nearest depth.
     */
    private static float minLodBlock(final int texId, final int x, final int y, final int w, final int h) {
        final int x0 = Math.max(0, x - DILATE);
        final int y0 = Math.max(0, y - DILATE);
        final int x1 = Math.min(w - 1, x + DILATE);
        final int y1 = Math.min(h - 1, y + DILATE);
        final int bw = x1 - x0 + 1;
        final int bh = y1 - y0 + 1;
        if (regionBuf == null) {
            regionBuf = MemoryUtil.memAllocFloat(MAXDIM * MAXDIM);
        }
        final FloatBuffer buf = regionBuf;
        buf.clear();
        buf.limit(bw * bh);
        GL45C.glGetTextureSubImage(texId, 0, x0, y0, 0, bw, bh, 1,
            GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        float m = Float.MAX_VALUE;
        for (int i = 0; i < bw * bh; i++) {
            final float v = buf.get(i);
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------
    // PER-PIXEL RECON (2.4.174): one-shot dump of everything the real per-pixel fix needs to know about
    // the depth buffer the hull is drawn into under Iris, plus Voxy's LOD depth + reprojection matrices.
    // Read-only; fully guarded. Called from MixinFeatureRenderDispatcher#renderAllFeatures TAIL, where
    // Iris's gbuffer target is live and the immediate ship terrain flushes.
    // ------------------------------------------------------------------------------------------------

    /**
     * Dump the MAIN-pass ship-draw depth target + Voxy LOD depth + matrices, once. The first
     * renderAllFeatures of a frame is the Iris SHADOW pass (2048^2 depth, Voxy viewport null); we skip
     * those and only consume the one-shot on the main pass, identified by a non-null Voxy viewport.
     */
    public static void logPerPixelDiag(final LevelRenderer lr) {
        if (perPixelDiagLogged) {
            return;
        }
        if (++perPixelDiagAttempts > 1200) {
            perPixelDiagLogged = true; // ~ a few seconds of frames; stop trying
            LOGGER.info("[vs perpixel-diag] gave up (no main pass with a Voxy viewport seen)");
            return;
        }
        try {
            if (lr != null && mGetRenderSystem == null) {
                try {
                    mGetRenderSystem = lr.getClass().getMethod("voxy$getRenderSystem");
                } catch (final NoSuchMethodException notVoxy) {
                    perPixelDiagLogged = true;
                    LOGGER.info("[vs perpixel-diag] Voxy not present");
                    return;
                }
            }
            if (lr == null || mGetRenderSystem == null) {
                return; // retry
            }
            final Object vrs = mGetRenderSystem.invoke(lr);
            if (vrs == null) {
                return; // retry
            }
            if (!resolved) {
                resolveHandles(vrs);
            }
            if (!resolved) {
                return; // framebuffer not built yet (shadow pass / loading) -- retry
            }
            final Object viewport = mGetViewport.invoke(vrs);
            if (viewport == null) {
                return; // Iris shadow pass -- not the main pass; retry next frame
            }

            // --- MAIN PASS: consume the one-shot and dump everything. ---
            perPixelDiagLogged = true;

            final Object override = RenderSystem.outputDepthTextureOverride;
            LOGGER.info("[vs perpixel-diag] outputDepthTextureOverride={}",
                override == null ? "null" : override.getClass().getName());

            // The GL depth attachment bound for drawing at this hook (the main gbuffer depth).
            final int fbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            final int dType = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,
                GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            final int dId = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,
                GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            final int sId = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,
                GL30C.GL_STENCIL_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int fmt = -1;
            int tw = -1;
            int th = -1;
            if (dType == GL11C.GL_TEXTURE && dId > 0) {
                fmt = GL45C.glGetTextureLevelParameteri(dId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                tw = GL45C.glGetTextureLevelParameteri(dId, 0, GL11C.GL_TEXTURE_WIDTH);
                th = GL45C.glGetTextureLevelParameteri(dId, 0, GL11C.GL_TEXTURE_HEIGHT);
            }
            LOGGER.info("[vs perpixel-diag] MAIN drawFBO={} depthAtt(type=0x{} id={} fmt=0x{} {}x{}) stencilId={}",
                fbo, Integer.toHexString(dType), dId, Integer.toHexString(fmt), tw, th, sId);

            final Object pipeline = fPipeline.get(vrs);
            final Object fb = pipeline == null ? null : fFb.get(pipeline);
            final Object depthTex = fb == null ? null : mGetDepthTex.invoke(fb);
            final int voxId = depthTex == null ? -1 : fTexId.getInt(depthTex);
            final int vw = fVpWidth.getInt(viewport);
            final int vh = fVpHeight.getInt(viewport);
            int voxFmt = -1;
            if (voxId > 0) {
                voxFmt = GL45C.glGetTextureLevelParameteri(voxId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            }
            LOGGER.info("[vs perpixel-diag] voxyDepth(id={} fmt=0x{} viewport={}x{})",
                voxId, Integer.toHexString(voxFmt), vw, vh);
            logMat(viewport, "projection");
            logMat(viewport, "vanillaProjection");
            logMat(viewport, "modelView");
        } catch (final Throwable t) {
            // Don't consume the one-shot on a transient failure; the attempt cap stops any spam.
            if (perPixelDiagAttempts % 240 == 1) {
                LOGGER.warn("[vs perpixel-diag] attempt failed (will retry)", t);
            }
        }
    }

    /** Log a viewport matrix's depth-relevant entries (or that it's absent / a different type). */
    private static void logMat(final Object viewport, final String name) {
        if (viewport == null) {
            return;
        }
        try {
            final Field f = viewport.getClass().getField(name);
            final Object m = f.get(viewport);
            if (m instanceof Matrix4f mat) {
                LOGGER.info("[vs perpixel-diag] {}: m22={} m23={} m32={} m33={}",
                    name, mat.m22(), mat.m23(), mat.m32(), mat.m33());
            } else {
                LOGGER.info("[vs perpixel-diag] {}: type={}", name, m == null ? "null" : m.getClass().getName());
            }
        } catch (final Throwable t) {
            LOGGER.info("[vs perpixel-diag] {}: NOT PRESENT ({})", name, t.getClass().getSimpleName());
        }
    }
}
