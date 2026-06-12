package org.valkyrienskies.mod.compat.voxy;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.LevelRenderer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;

/**
 * Per-pixel ship&lt;-&gt;Voxy-LOD depth occlusion (the real fix, replacing the whole-ship dilation cull).
 *
 * <p>WHY THE CULL WASN'T ENOUGH: a whole-ship heuristic either shows a ship that's mostly hidden or
 * hides one that's partly visible -- it can't be per-pixel correct. This makes the hull depth-test
 * against Voxy's LOD terrain exactly like it already does against real chunks.
 *
 * <p>HOW: Voxy keeps its LOD out of the gbuffer depth under this shaderpack ({@code
 * excludeLodsFromVanillaDepth}), so VS2 does its own SCOPED merge: at the main pass's
 * renderAllFeatures HEAD we SAVE the gbuffer depth, then MERGE Voxy's LOD depth into it (min); the
 * hull (and entities) then draw occluded by both real terrain AND LOD; at TAIL we RESTORE -- but
 * selectively, putting back the saved real depth ONLY at pixels that are still pure LOD (not covered
 * by the hull or an entity), so the shaderpack's later shadow/SSR passes never see LOD (zero shadow
 * side-effects) while entities keep their depth.
 *
 * <p>REPROJECTION: Voxy's LOD depth and the gbuffer are the SAME resolution and SAME camera, so we
 * don't reproject positions -- we sample LOD at the same texel and remap the depth VALUE from Voxy's
 * projection (near 16 / far 48000) to MC's (near ~0.05 / far ~2045) with four scalars.
 *
 * <p>The gbuffer framebuffer id is captured by {@link VoxyGbufferBridge} (Blaze3D unbinds it between
 * encoder passes, so it's unreachable at VS2's own hooks). Everything here is wrapped: on ANY failure
 * it disables itself and the dilation cull in {@link VoxyOcclusion} takes back over -- never a crash.
 */
public final class VoxyPerPixel {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    private static final int GL_FB_ATT_OBJECT_NAME = 0x8CD1;
    private static final int GL_FB_ATT_OBJECT_TYPE = 0x8CD0;
    private static final int GL_TEXTURE = 0x1702;
    private static final int GL_TEXTURE_INTERNAL_FORMAT = 0x1003;
    private static final int GL_TEXTURE_WIDTH = 0x1000;
    private static final int GL_TEXTURE_HEIGHT = 0x1001;
    private static final float SKY = 0.99990f;   // Voxy clears LOD depth to far (1.0); >= this == no LOD
    private static final float EPS = 0.00002f;   // "current still equals the merged LOD" tolerance
    // Bind our depth samplers to HIGH texture units nothing else samples (the GUI/font use unit 0),
    // so a leaked binding can't tint items/HUD red; unbound again after each pass for tidiness.
    private static final int U0 = 13;
    private static final int U1 = 14;
    private static final int U2 = 15;

    private static boolean disabled = false;
    private static boolean glReady = false;
    private static boolean operational = false;  // true once a merge has run; tells the cull to stand down
    private static boolean frameActive = false;  // a merge happened this frame -> afterHull must restore
    // Voxy merge params resolved by beforeHull, consumed by afterHull in the same frame.
    private static VoxyOcclusion.MergeParams frameParams;

    private static int mergeProg;
    private static int restoreProg;
    private static int vao;
    private static int savedTex;
    private static int curTex;
    private static int scratchW;
    private static int scratchH;
    private static int scratchFmt;

    // cached uniform locations
    private static int mU_voxy;
    private static int mU_sky;
    private static int mU_pm22;
    private static int mU_pm32;
    private static int mU_vm22;
    private static int mU_vm32;
    private static int rU_cur;
    private static int rU_saved;
    private static int rU_voxy;
    private static int rU_sky;
    private static int rU_pm22;
    private static int rU_pm32;
    private static int rU_vm22;
    private static int rU_vm32;
    private static int rU_eps;

    private VoxyPerPixel() {
    }

    /** True once per-pixel is live; {@link VoxyOcclusion#isOccludedByLod} returns false so ships draw. */
    public static boolean isReplacingCull() {
        return operational && !disabled;
    }

    private static final String VERT = """
        #version 330 core
        void main() {
            vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
            gl_Position = vec4(p, 0.0, 1.0);
        }
        """;

    // Merge: write Voxy LOD depth (remapped to MC's projection) into the gbuffer, kept where nearer.
    private static final String MERGE_FRAG = """
        #version 330 core
        uniform sampler2D uVoxy;
        uniform float uSky, uPm22, uPm32, uVm22, uVm32;
        void main() {
            float dv = texelFetch(uVoxy, ivec2(gl_FragCoord.xy), 0).r;
            if (dv >= uSky) discard;
            float ndcV = dv * 2.0 - 1.0;
            float viewZ = -uPm32 / (ndcV + uPm22);
            float ndcM = -uVm22 - uVm32 / viewZ;
            gl_FragDepth = ndcM * 0.5 + 0.5;
        }
        """;

    // Restore: at pixels still showing the merged LOD (not overdrawn), put back the saved real depth.
    private static final String RESTORE_FRAG = """
        #version 330 core
        uniform sampler2D uCur, uSaved, uVoxy;
        uniform float uSky, uPm22, uPm32, uVm22, uVm32, uEps;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            float cur = texelFetch(uCur, p, 0).r;
            float dv = texelFetch(uVoxy, p, 0).r;
            if (dv >= uSky) { gl_FragDepth = cur; return; }
            float sav = texelFetch(uSaved, p, 0).r;
            float ndcV = dv * 2.0 - 1.0;
            float viewZ = -uPm32 / (ndcV + uPm22);
            float ndcM = -uVm22 - uVm32 / viewZ;
            float lod = ndcM * 0.5 + 0.5;
            gl_FragDepth = (abs(cur - lod) < uEps && lod < sav) ? sav : cur;
        }
        """;

    /**
     * renderAllFeatures HEAD (main pass): save gbuffer depth, merge Voxy LOD into it. No-op + fallback
     * when per-pixel can't run this frame.
     */
    public static void beforeHull(final LevelRenderer lr) {
        frameActive = false;
        if (disabled) {
            return;
        }
        try {
            final VoxyOcclusion.MergeParams mp = VoxyOcclusion.mainPassMergeParams(lr);
            if (mp == null) {
                return; // not the main pass / Voxy not ready -> nothing to do
            }
            final int gbufferFbo = VoxyGbufferBridge.gbufferFbo;
            if (gbufferFbo <= 0) {
                return; // gbuffer not captured yet
            }
            final int objType = GL45C.glGetNamedFramebufferAttachmentParameteri(
                gbufferFbo, GL_DEPTH_ATTACHMENT, GL_FB_ATT_OBJECT_TYPE);
            if (objType != GL_TEXTURE) {
                return; // depth isn't a sampleable texture (can't reliably operate)
            }
            final int gbDepth = GL45C.glGetNamedFramebufferAttachmentParameteri(
                gbufferFbo, GL_DEPTH_ATTACHMENT, GL_FB_ATT_OBJECT_NAME);
            if (gbDepth <= 0) {
                return;
            }
            final int w = GL45C.glGetTextureLevelParameteri(gbDepth, 0, GL_TEXTURE_WIDTH);
            final int h = GL45C.glGetTextureLevelParameteri(gbDepth, 0, GL_TEXTURE_HEIGHT);
            final int fmt = GL45C.glGetTextureLevelParameteri(gbDepth, 0, GL_TEXTURE_INTERNAL_FORMAT);
            // Reject a stale/shadow capture: the gbuffer must match Voxy's render size.
            if (w != mp.viewportW || h != mp.viewportH) {
                return;
            }

            if (!glReady) {
                initGl();
                if (!glReady) {
                    return;
                }
            }
            ensureScratch(w, h, fmt);

            final int prevFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            final int prevProg = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            final int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            final int[] vp = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
            // Save the EXACT enable/depth state we're about to change, so we can put GL back
            // byte-for-byte. Restoring the real prior state (not a hardcoded default) keeps the
            // Blaze3D GL-state cache valid -- otherwise we leave blend disabled and the GUI's
            // additive enchant glint draws OPAQUE: a solid purple smear over every glinted item.
            final boolean wasBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
            final boolean wasCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
            final boolean wasStencil = GL11C.glIsEnabled(0x0B90); // GL_STENCIL_TEST
            final boolean wasDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
            final int prevDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
            final boolean prevDepthMask = GL11C.glGetInteger(0x0B72) != 0; // GL_DEPTH_WRITEMASK

            // SAVE the clean (real-terrain) gbuffer depth.
            GL43C.glCopyImageSubData(gbDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                savedTex, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);

            // MERGE Voxy LOD into the gbuffer depth (min via GL_LESS).
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, gbufferFbo);
            GL11C.glViewport(0, 0, w, h);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(0x0B90); // GL_STENCIL_TEST
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDepthMask(true);
            GL11C.glColorMask(false, false, false, false);
            GL20C.glUseProgram(mergeProg);
            GL30C.glBindVertexArray(vao);
            GL45C.glBindTextureUnit(U0, mp.voxyDepthTex);
            GL20C.glUniform1i(mU_voxy, U0);
            GL20C.glUniform1f(mU_sky, SKY);
            GL20C.glUniform1f(mU_pm22, mp.projM22);
            GL20C.glUniform1f(mU_pm32, mp.projM32);
            GL20C.glUniform1f(mU_vm22, mp.vanM22);
            GL20C.glUniform1f(mU_vm32, mp.vanM32);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

            // Restore the GL state MC's encoder relies on.
            GL45C.glBindTextureUnit(U0, 0);
            // Put GL back EXACTLY as we found it (see the save note above) so Blaze3D's state cache
            // stays valid and the GUI enchant glint keeps its additive blend.
            GL11C.glColorMask(true, true, true, true);
            GL11C.glDepthMask(prevDepthMask);
            GL11C.glDepthFunc(prevDepthFunc);
            setEnabled(GL11C.GL_BLEND, wasBlend);
            setEnabled(GL11C.GL_CULL_FACE, wasCull);
            setEnabled(0x0B90, wasStencil); // GL_STENCIL_TEST
            setEnabled(GL11C.GL_DEPTH_TEST, wasDepthTest);
            GL20C.glUseProgram(prevProg);
            GL30C.glBindVertexArray(prevVao);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glViewport(vp[0], vp[1], vp[2], vp[3]);

            // Stash for afterHull: it runs later in this same frame (frameActive handshake) and
            // needs only these values, so it must not repeat the whole reflective resolve chain.
            frameParams = mp;
            frameActive = true;
            if (!operational) {
                operational = true;
                LOGGER.info("[vs perpixel] per-pixel LOD occlusion ACTIVE ({}x{}, depthFmt=0x{})",
                    w, h, Integer.toHexString(fmt));
            }
        } catch (final Throwable t) {
            disableSelf();
            LOGGER.warn("[vs perpixel] beforeHull failed; falling back to the cull", t);
        }
    }

    /** renderAllFeatures TAIL (main pass): selectively restore the saved depth at pure-LOD pixels. */
    public static void afterHull(final LevelRenderer lr) {
        if (disabled || !frameActive) {
            return;
        }
        frameActive = false;
        try {
            // Resolved once in beforeHull this frame (frameActive guarantees it ran and succeeded);
            // re-resolving here repeated the whole reflective Voxy chain every frame for nothing.
            final VoxyOcclusion.MergeParams mp = frameParams;
            final int gbufferFbo = VoxyGbufferBridge.gbufferFbo;
            if (mp == null || gbufferFbo <= 0) {
                return;
            }
            final int gbDepth = GL45C.glGetNamedFramebufferAttachmentParameteri(
                gbufferFbo, GL_DEPTH_ATTACHMENT, GL_FB_ATT_OBJECT_NAME);
            if (gbDepth <= 0 || scratchW <= 0) {
                return;
            }

            final int prevFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            final int prevProg = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            final int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            final int[] vp = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
            final boolean wasBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
            final boolean wasCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
            final boolean wasStencil = GL11C.glIsEnabled(0x0B90); // GL_STENCIL_TEST
            final boolean wasDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
            final int prevDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
            final boolean prevDepthMask = GL11C.glGetInteger(0x0B72) != 0; // GL_DEPTH_WRITEMASK

            // Copy the post-draw gbuffer depth so the restore shader can read it without feedback.
            GL43C.glCopyImageSubData(gbDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                curTex, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, scratchW, scratchH, 1);

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, gbufferFbo);
            GL11C.glViewport(0, 0, scratchW, scratchH);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(0x0B90); // GL_STENCIL_TEST
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);
            GL11C.glColorMask(false, false, false, false);
            GL20C.glUseProgram(restoreProg);
            GL30C.glBindVertexArray(vao);
            GL45C.glBindTextureUnit(U0, curTex);
            GL45C.glBindTextureUnit(U1, savedTex);
            GL45C.glBindTextureUnit(U2, mp.voxyDepthTex);
            GL20C.glUniform1i(rU_cur, U0);
            GL20C.glUniform1i(rU_saved, U1);
            GL20C.glUniform1i(rU_voxy, U2);
            GL20C.glUniform1f(rU_sky, SKY);
            GL20C.glUniform1f(rU_pm22, mp.projM22);
            GL20C.glUniform1f(rU_pm32, mp.projM32);
            GL20C.glUniform1f(rU_vm22, mp.vanM22);
            GL20C.glUniform1f(rU_vm32, mp.vanM32);
            GL20C.glUniform1f(rU_eps, EPS);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

            GL45C.glBindTextureUnit(U0, 0);
            GL45C.glBindTextureUnit(U1, 0);
            GL45C.glBindTextureUnit(U2, 0);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glDepthMask(prevDepthMask);
            GL11C.glDepthFunc(prevDepthFunc);
            setEnabled(GL11C.GL_BLEND, wasBlend);
            setEnabled(GL11C.GL_CULL_FACE, wasCull);
            setEnabled(0x0B90, wasStencil); // GL_STENCIL_TEST
            setEnabled(GL11C.GL_DEPTH_TEST, wasDepthTest);
            GL20C.glUseProgram(prevProg);
            GL30C.glBindVertexArray(prevVao);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo);
            GL11C.glViewport(vp[0], vp[1], vp[2], vp[3]);
        } catch (final Throwable t) {
            disableSelf();
            LOGGER.warn("[vs perpixel] afterHull failed; falling back to the cull", t);
        }
    }

    private static void setEnabled(final int cap, final boolean on) {
        if (on) {
            GL11C.glEnable(cap);
        } else {
            GL11C.glDisable(cap);
        }
    }

    private static void initGl() {
        try {
            mergeProg = makeProgram(VERT, MERGE_FRAG);
            restoreProg = makeProgram(VERT, RESTORE_FRAG);
            if (mergeProg == 0 || restoreProg == 0) {
                disableSelf();
                return;
            }
            mU_voxy = GL20C.glGetUniformLocation(mergeProg, "uVoxy");
            mU_sky = GL20C.glGetUniformLocation(mergeProg, "uSky");
            mU_pm22 = GL20C.glGetUniformLocation(mergeProg, "uPm22");
            mU_pm32 = GL20C.glGetUniformLocation(mergeProg, "uPm32");
            mU_vm22 = GL20C.glGetUniformLocation(mergeProg, "uVm22");
            mU_vm32 = GL20C.glGetUniformLocation(mergeProg, "uVm32");
            rU_cur = GL20C.glGetUniformLocation(restoreProg, "uCur");
            rU_saved = GL20C.glGetUniformLocation(restoreProg, "uSaved");
            rU_voxy = GL20C.glGetUniformLocation(restoreProg, "uVoxy");
            rU_sky = GL20C.glGetUniformLocation(restoreProg, "uSky");
            rU_pm22 = GL20C.glGetUniformLocation(restoreProg, "uPm22");
            rU_pm32 = GL20C.glGetUniformLocation(restoreProg, "uPm32");
            rU_vm22 = GL20C.glGetUniformLocation(restoreProg, "uVm22");
            rU_vm32 = GL20C.glGetUniformLocation(restoreProg, "uVm32");
            rU_eps = GL20C.glGetUniformLocation(restoreProg, "uEps");
            vao = GL30C.glGenVertexArrays();
            glReady = !glError("initGl");
            if (!glReady) {
                disableSelf();
            }
        } catch (final Throwable t) {
            disableSelf();
            LOGGER.warn("[vs perpixel] GL init failed", t);
        }
    }

    private static void ensureScratch(final int w, final int h, final int fmt) {
        if (savedTex != 0 && w == scratchW && h == scratchH && fmt == scratchFmt) {
            return;
        }
        if (savedTex != 0) {
            GL11C.glDeleteTextures(savedTex);
        }
        if (curTex != 0) {
            GL11C.glDeleteTextures(curTex);
        }
        savedTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        curTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(savedTex, 1, fmt, w, h);
        GL45C.glTextureStorage2D(curTex, 1, fmt, w, h);
        scratchW = w;
        scratchH = h;
        scratchFmt = fmt;
    }

    private static int makeProgram(final String vert, final String frag) {
        final int vs = compile(GL20C.GL_VERTEX_SHADER, vert);
        final int fs = compile(GL20C.GL_FRAGMENT_SHADER, frag);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        final int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            LOGGER.warn("[vs perpixel] program link failed: {}", GL20C.glGetProgramInfoLog(prog));
            GL20C.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compile(final int type, final String src) {
        final int sh = GL20C.glCreateShader(type);
        GL20C.glShaderSource(sh, src);
        GL20C.glCompileShader(sh);
        if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            LOGGER.warn("[vs perpixel] shader compile failed: {}", GL20C.glGetShaderInfoLog(sh));
            GL20C.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }

    private static boolean glError(final String where) {
        final int e = GL11C.glGetError();
        if (e != 0) {
            LOGGER.warn("[vs perpixel] GL error 0x{} at {}", Integer.toHexString(e), where);
            return true;
        }
        return false;
    }

    private static void disableSelf() {
        disabled = true;
        operational = false;
        frameActive = false;
    }
}
