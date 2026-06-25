package org.valkyrienskies.mod.common.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.slf4j.Logger;

/**
 * Renders the persistent-GPU-buffer ship terrain (see {@link ShipTerrainMeshCache}) through an active
 * Iris shaderpack.
 *
 * <p>Iris exposes a public, version-stable API ({@code net.irisshaders.iris.api.v0.IrisApi
 * .assignPipeline(RenderPipeline, IrisProgram)}): register a Blaze3D {@link RenderPipeline} whose vertex
 * format IS Iris's extended {@code IrisVertexFormats.TERRAIN} object, and Iris's own mixins then
 * substitute the shaderpack's gbuffer program + framebuffer + uniforms/samplers onto every draw of that
 * pipeline. The match inside Iris is by vertex-format REFERENCE equality + program id, so we build the
 * pipeline against the exact {@code TERRAIN} object (not a copy). All Iris access is reflective so there
 * is no compile/runtime hard dependency.
 *
 * <p>We register ONE pipeline, bound to Iris's {@code TERRAIN_CUTOUT} program, and draw all of the hull's
 * opaque + cutout terrain through it. Solid block textures are fully opaque, so the cutout program's 0.5
 * alpha test passes every solid pixel unchanged (and most shaderpacks share one {@code gbuffers_terrain}
 * for solid and cutout anyway), so this is visually correct for the solid hull. The separate
 * {@code TERRAIN_SOLID} program was tried and renders the hull invisible in practice (the shaderpack does
 * not substitute its program for that assignment here), so we do not use it. Translucent geometry stays
 * on the immediate re-emit path (it needs vanilla's per-frame depth sort).
 *
 * <p>The mesh cache bakes sections into vanilla {@code DefaultVertexFormat.BLOCK} (32-byte) vertices.
 * TERRAIN's first 32 bytes are byte-identical to BLOCK (Position@0, Color@12, UV0@16, UV2@24, Normal@28),
 * so {@link #repackBlockToTerrain} copies those verbatim and computes the four Iris extras into the
 * remaining 20 bytes: {@code mc_Entity} (block id, written neutral), {@code mc_midTexCoord} (the quad's
 * sprite-centre UV), {@code at_tangent} (the per-face tangent that drives normal-mapping / POM), and
 * {@code at_midBlock} (left zero -- only used for per-block waving, which a static hull has none of).
 * Element byte offsets are read from the runtime TERRAIN format at init, not hard-coded, so an Iris
 * layout change can't silently corrupt vertices (we bail to the immediate path instead).
 *
 * <p>The pipeline-&gt;program assignment lives in Iris's static {@code coreShaderMap}, which Iris never
 * clears -- it survives every shaderpack reload and dimension change for the JVM session -- so we
 * register exactly once and never touch it again (re-assigning the same pipeline throws). The live
 * gbuffer program behind the assignment is resolved per-draw from the current pipeline, so after a pack
 * reload the hull automatically uses the new pack's terrain program. Note: {@code assignPipeline} writes
 * only the main-pass map, not the shadow map, so ship terrain is not drawn into the shaderpack's shadow
 * buffer (no self/cast shadows under shaders).
 *
 * <p>Client-only, render thread only. Lazily initialises on first use.
 */
public final class ShipTerrainIrisPipeline {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean tried;
    private static boolean ok;
    private static RenderPipeline terrainPipeline;
    private static VertexFormat terrainFormat;
    private static int terrainStride;

    // Byte offsets of the four Iris extras within a TERRAIN vertex, read from the runtime format at init.
    private static int offEntity;
    private static int offMidTex;
    private static int offTangent;
    private static int offMidBlock;

    // Emissive/material id: the shaderpack's block.properties id, written into mc_Entity.x so packs that key
    // emission/material off the block id (glowstone/lanterns/lava, SSS, waving, ...) treat ship blocks like
    // real terrain. Reflective (Iris-internal); degrades to -1 -- byte-identical to the old neutral write --
    // on any failure or when the pack has no block.properties.
    private static boolean blockIdOk;
    private static java.lang.reflect.Field wrsInstanceF;
    private static java.lang.reflect.Method getBlockStateIdsM;

    private ShipTerrainIrisPipeline() {
    }

    /** True once the TERRAIN pipeline is built + assigned. Lazily initialises on the render thread. */
    public static boolean ready() {
        if (!tried) {
            tried = true;
            init();
        }
        return ok;
    }

    /** TERRAIN vertex stride (52) once resolved, else 0. Used to tag/select Iris-format meshes. */
    public static int terrainStride() {
        return terrainStride;
    }

    /** The Iris-assigned terrain pipeline (TERRAIN format, cutout program), or null if unavailable. */
    public static RenderPipeline terrainPipeline() {
        return terrainPipeline;
    }

    /**
     * The exact Iris TERRAIN {@link VertexFormat} our ship buffers are drawn with, or null if unavailable.
     * Used by the Blaze3D VAO mixins to scope their GENERIC-attribute fix to ONLY our ship draw (reference
     * identity) so no other (Iris/vanilla) draw is touched.
     */
    public static VertexFormat terrainFormat() {
        return terrainFormat;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void init() {
        try {
            // The exact IrisVertexFormats.TERRAIN object + the four extra elements (reference identity
            // matters: Iris's findBestMatch compares vertex formats with ==, and getOffset() keys on the
            // same element objects used to build the format).
            final Class<?> ivf = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
            final VertexFormat terrain = (VertexFormat) ivf.getField("TERRAIN").get(null);
            terrainFormat = terrain;
            terrainStride = terrain.getVertexSize();

            offEntity = terrain.getOffset((VertexFormatElement) ivf.getField("ENTITY_ELEMENT").get(null));
            offMidTex = terrain.getOffset((VertexFormatElement) ivf.getField("MID_TEXTURE_ELEMENT").get(null));
            offTangent = terrain.getOffset((VertexFormatElement) ivf.getField("TANGENT_ELEMENT").get(null));
            offMidBlock = terrain.getOffset((VertexFormatElement) ivf.getField("MID_BLOCK_ELEMENT").get(null));

            // Expected layout for iris-fabric-1.10.7 (stride 52). If a future Iris build moves these, the
            // repack would corrupt vertices, so refuse the Iris path and fall back to immediate re-emit.
            if (terrainStride != 52 || offEntity != 32 || offMidTex != 36 || offTangent != 44
                || offMidBlock != 48) {
                ok = false;
                LOGGER.warn("VS ship terrain: unexpected Iris TERRAIN layout (stride={} entity={} midTex={} "
                    + "tangent={} midBlock={}); staying on the immediate path under shaders",
                    terrainStride, offEntity, offMidTex, offTangent, offMidBlock);
                return;
            }

            // One pipeline bound to the SAME TERRAIN object by reference, mirroring the vanilla cutout
            // moving-block GL state. Iris substitutes its gbuffer terrain program at draw time (the
            // core/block shaders are just the placeholder the pipeline is built against).
            terrainPipeline = RenderPipeline.builder()
                .withLocation("valkyrienskies/iris_terrain")
                .withVertexShader("core/block")
                .withFragmentShader("core/block")
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withShaderDefine("ALPHA_CUTOUT", 0.5f)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withCull(true)
                .withColorWrite(true)
                .withDepthWrite(true)
                .withVertexFormat(terrain, VertexFormat.Mode.QUADS)
                .build();

            final Class<?> apiCls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            final Class<?> progCls = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            final Object api = apiCls.getMethod("getInstance").invoke(null);
            apiCls.getMethod("assignPipeline", RenderPipeline.class, progCls)
                .invoke(api, terrainPipeline, Enum.valueOf((Class) progCls, "TERRAIN_CUTOUT"));

            ok = true;
            LOGGER.info("VS ship terrain: registered Iris TERRAIN pipeline (cutout program, stride {})",
                terrainStride);

            // Also register into Iris's shadow program map so ships can cast shadows (gated at draw time by the
            // Ship Shadows config). Separate try/catch inside: a failure here leaves the proven main path intact.
            registerShadow();
            // Resolve the shaderpack block-id map so repack can fill mc_Entity (emissive/material). Own
            // try/catch: a failure just leaves blockIdOk=false -> neutral mc_Entity, main path intact.
            registerBlockIds();
        } catch (final Throwable t) {
            ok = false;
            final Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
            LOGGER.warn("VS ship terrain: Iris pipeline registration failed ({}: {}); staying on the "
                + "immediate path under shaders", c.getClass().getSimpleName(), c.getMessage());
        }
    }

    // ===== Shadow pass (ships cast/receive shadows under shaders) =====
    // Iris exposes no public route into its shadow program map, so this reaches a PRIVATE static map
    // (net.irisshaders.iris.pipeline.IrisPipelines.coreShaderMapShadow) -- the one fragile, non-v0 dependency.
    // Everything else (shadow-pass detection, shadow modelview) is public/v0. All wrapped so a failure just
    // means "no ship shadows" with the main pass untouched.
    private static boolean shadowOk;
    private static Object irisApiInst;
    private static java.lang.reflect.Method irisIsRenderingShadowPass;
    private static java.lang.reflect.Field shadowModelViewField;
    private static java.lang.reflect.Field shadowFrustumField;

    /** True once our pipeline is registered into Iris's shadow map (so ships CAN cast shadows). */
    public static boolean shadowReady() {
        return shadowOk;
    }

    /** True while Iris is rendering its shadow pass (sun POV into the shadow map). Public v0 API, reflective. */
    public static boolean isShadowPass() {
        if (irisIsRenderingShadowPass == null) {
            return false;
        }
        try {
            return (Boolean) irisIsRenderingShadowPass.invoke(irisApiInst);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** The shadow pass's camera model-view (sun POV), or null if unavailable. */
    public static org.joml.Matrix4f shadowModelView() {
        if (shadowModelViewField == null) {
            return null;
        }
        try {
            return (org.joml.Matrix4f) shadowModelViewField.get(null);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /** The sun's shadow culling frustum for the current frame, or null if unavailable. */
    public static net.minecraft.client.renderer.culling.Frustum shadowFrustum() {
        if (shadowFrustumField == null) {
            return null;
        }
        try {
            return (net.minecraft.client.renderer.culling.Frustum) shadowFrustumField.get(null);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerShadow() {
        try {
            // Put our terrain pipeline -> SHADOW_TERRAIN_CUTOUT into Iris's private shadow program map (keyed by
            // RenderPipeline -> it.unimi.dsi.fastutil.Function<IrisRenderingPipeline, ShaderKey>). The value is a
            // constant Function (get() is its only abstract method) ignoring the pipeline arg.
            final java.lang.reflect.Field shadowMapField =
                Class.forName("net.irisshaders.iris.pipeline.IrisPipelines").getDeclaredField("coreShaderMapShadow");
            shadowMapField.setAccessible(true);
            final java.util.Map<Object, Object> shadowMap = (java.util.Map<Object, Object>) shadowMapField.get(null);
            final Object shadowKey = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey")
                .getField("SHADOW_TERRAIN_CUTOUT").get(null);
            shadowMap.put(terrainPipeline, new ConstantFunction(shadowKey));

            // Public/v0 shadow-pass detection + the shadow camera modelview (public static field).
            final Class<?> apiCls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApiInst = apiCls.getMethod("getInstance").invoke(null);
            irisIsRenderingShadowPass = apiCls.getMethod("isRenderingShadowPass");
            final Class<?> shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            shadowModelViewField = shadowRenderer.getField("MODELVIEW");
            // The sun's shadow culling frustum -- used to keep ship sections the camera can't see but the sun
            // can, so casters behind the camera still draw into the shadow map (otherwise shadows pop out as
            // you turn). Same net.minecraft Frustum class as the main camera frustum.
            shadowFrustumField = shadowRenderer.getField("FRUSTUM");

            shadowOk = true;
            LOGGER.info("VS ship terrain: registered Iris shadow pipeline (ships can cast shadows under shaders)");
        } catch (final Throwable t) {
            shadowOk = false;
            final Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
            LOGGER.warn("VS ship terrain: Iris shadow registration unavailable ({}: {}); ships won't cast shadows "
                + "under shaders (main pass unaffected)", c.getClass().getSimpleName(), c.getMessage());
        }
    }

    /** Constant fastutil Function returning a fixed ShaderKey regardless of the pipeline argument. */
    private static final class ConstantFunction implements it.unimi.dsi.fastutil.Function<Object, Object> {
        private final Object value;

        ConstantFunction(final Object value) {
            this.value = value;
        }

        @Override
        public Object get(final Object key) {
            return value;
        }
    }

    // ===== Block id (mc_Entity) for emissive/material under shaders =====
    // Iris keeps the shaderpack's block.properties ids in WorldRenderingSettings.INSTANCE.getBlockStateIds()
    // (an Object2IntMap<BlockState> whose defaultReturnValue is -1, so unmapped states already read neutral).
    // The map is null until a pack with block.properties loads. All reflective to keep the no-Iris-dep contract.
    private static void registerBlockIds() {
        try {
            final Class<?> wrs = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            wrsInstanceF = wrs.getField("INSTANCE");
            getBlockStateIdsM = wrs.getMethod("getBlockStateIds");
            blockIdOk = true;
            LOGGER.info("VS ship terrain: shaderpack block-id map available (ship blocks can emit/match material)");
        } catch (final Throwable t) {
            blockIdOk = false;
            final Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
            LOGGER.warn("VS ship terrain: shaderpack block-id map unavailable ({}: {}); ship blocks render with a "
                + "neutral mc_Entity (no emissive/material id)", c.getClass().getSimpleName(), c.getMessage());
        }
    }

    /**
     * The shaderpack block-id map (Object2IntMap&lt;BlockState&gt;), resolved once via reflection so callers can
     * do many plain {@code getInt} lookups without per-call reflection. Null when block ids are unavailable or
     * no pack with block.properties is loaded. Pass the result to {@link #shaderBlockId}.
     */
    public static Object blockIdMap() {
        if (!blockIdOk) {
            return null;
        }
        try {
            return getBlockStateIdsM.invoke(wrsInstanceF.get(null));
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * The shaderpack block.properties id for a BlockState (what the shader compares {@code mc_Entity.x} against),
     * given a map from {@link #blockIdMap()}. Returns -1 when the map is null or the state is unmapped (the
     * neutral sentinel, identical to the pre-emissive behaviour). No reflection -- cheap to call per block.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static short shaderBlockId(final Object blockIdMap,
        final net.minecraft.world.level.block.state.BlockState state) {
        if (blockIdMap == null || state == null) {
            return -1;
        }
        try {
            return (short) ((it.unimi.dsi.fastutil.objects.Object2IntMap) blockIdMap).getInt(state);
        } catch (final Throwable ignored) {
            return -1;
        }
    }

    /**
     * Repack a BLOCK-format (32B/vertex) QUADS buffer into TERRAIN-format (52B/vertex), computing the
     * Iris extra attributes. The shared first 32 bytes are copied verbatim; per quad of 4 vertices we
     * compute {@code mc_midTexCoord} (mean UV) and {@code at_tangent} (per-face TBN, the normal-mapping/POM
     * driver) and write {@code mc_Entity} = (block id, BLOCK_RENDER_TYPE). {@code blockIds} is the per-vertex
     * shaderpack block id captured at bake (length must equal {@code vertexCount}); when null/mismatched every
     * vertex gets the neutral -1 (byte-identical to the pre-emissive behaviour). {@code at_midBlock} is filled
     * with the per-vertex block-centre offset (foliage waving); the trailing pad stays zero. Returns a fresh
     * direct buffer positioned at 0, ready for {@code createBuffer}.
     */
    public static ByteBuffer repackBlockToTerrain(final ByteBuffer block, final int vertexCount,
        final VertexFormat blockFmt, final short[] blockIds) {
        final int srcStride = blockFmt.getVertexSize();
        final int dstStride = terrainStride;
        final int offPos = blockFmt.getOffset(VertexFormatElement.POSITION);
        final int offUv0 = blockFmt.getOffset(VertexFormatElement.UV0);
        // Per-vertex block id available only when the bake captured a matching-length stream.
        final boolean haveIds = blockIds != null && blockIds.length == vertexCount;

        final ByteBuffer src = block.duplicate().order(ByteOrder.nativeOrder());
        final ByteBuffer dst = ByteBuffer.allocateDirect(vertexCount * dstStride).order(ByteOrder.nativeOrder());
        final byte[] shared = new byte[32];

        // Terrain meshes are always QUADS, but guard: a stray non-quad count just gets the shared bytes +
        // neutral entity, leaving tangent/midTex zero (degraded, never corrupt).
        final boolean quads = (vertexCount & 3) == 0 && offPos >= 0 && offUv0 >= 0;

        for (int q = 0; quads && q < vertexCount; q += 4) {
            final int b0 = q * srcStride;
            final int b1 = b0 + srcStride;
            final int b2 = b1 + srcStride;
            final int b3 = b2 + srcStride;

            final float p0x = src.getFloat(b0 + offPos), p0y = src.getFloat(b0 + offPos + 4), p0z = src.getFloat(b0 + offPos + 8);
            final float p1x = src.getFloat(b1 + offPos), p1y = src.getFloat(b1 + offPos + 4), p1z = src.getFloat(b1 + offPos + 8);
            final float p2x = src.getFloat(b2 + offPos), p2y = src.getFloat(b2 + offPos + 4), p2z = src.getFloat(b2 + offPos + 8);
            final float p3x = src.getFloat(b3 + offPos), p3y = src.getFloat(b3 + offPos + 4), p3z = src.getFloat(b3 + offPos + 8);

            final float u0 = src.getFloat(b0 + offUv0), v0 = src.getFloat(b0 + offUv0 + 4);
            final float u1 = src.getFloat(b1 + offUv0), v1 = src.getFloat(b1 + offUv0 + 4);
            final float u2 = src.getFloat(b2 + offUv0), v2 = src.getFloat(b2 + offUv0 + 4);
            final float u3 = src.getFloat(b3 + offUv0), v3 = src.getFloat(b3 + offUv0 + 4);

            final float midU = (u0 + u1 + u2 + u3) * 0.25f;
            final float midV = (v0 + v1 + v2 + v3) * 0.25f;

            // Face normal = normalize(cross(p2 - p0, p3 - p1)) (Iris computeFaceNormal, QUADS path).
            final float d0x = p2x - p0x, d0y = p2y - p0y, d0z = p2z - p0z;
            final float d1x = p3x - p1x, d1y = p3y - p1y, d1z = p3z - p1z;
            float nx = d0y * d1z - d0z * d1y;
            float ny = d0z * d1x - d0x * d1z;
            float nz = d0x * d1y - d0y * d1x;
            final float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen != 0.0f) {
                nx /= nlen; ny /= nlen; nz /= nlen;
            }

            // Tangent from verts 0,1,2 (Iris computeTangent TriView overload).
            final float e1x = p1x - p0x, e1y = p1y - p0y, e1z = p1z - p0z;
            final float e2x = p2x - p0x, e2y = p2y - p0y, e2z = p2z - p0z;
            final float dU1 = u1 - u0, dV1 = v1 - v0, dU2 = u2 - u0, dV2 = v2 - v0;
            final float denom = dU1 * dV2 - dU2 * dV1;
            final float f = (denom == 0.0f) ? 1.0f : 1.0f / denom;
            float tx = f * (dV2 * e1x - dV1 * e2x);
            float ty = f * (dV2 * e1y - dV1 * e2y);
            float tz = f * (dV2 * e1z - dV1 * e2z);
            final float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tlen != 0.0f) {
                tx /= tlen; ty /= tlen; tz /= tlen;
            }
            float bx = f * (-dU2 * e1x + dU1 * e2x);
            float by = f * (-dU2 * e1y + dU1 * e2y);
            float bz = f * (-dU2 * e1z + dU1 * e2z);
            final float blen = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (blen != 0.0f) {
                bx /= blen; by /= blen; bz /= blen;
            }
            // Handedness: w = sign(dot(B, cross(T, N))). Match Iris: < 0 -> -1, else +1.
            final float pbx = ty * nz - tz * ny;
            final float pby = tz * nx - tx * nz;
            final float pbz = tx * ny - ty * nx;
            final float w = (bx * pbx + by * pby + bz * pbz) < 0.0f ? -1.0f : 1.0f;

            // NormI8.pack: truncate toward zero (NOT round), mask each component to a byte.
            final int packedTangent =
                  ((int) (tx * 127.0f) & 0xFF)
                | (((int) (ty * 127.0f) & 0xFF) << 8)
                | (((int) (tz * 127.0f) & 0xFF) << 16)
                | (((int) (w * 127.0f) & 0xFF) << 24);

            for (int k = 0; k < 4; k++) {
                final int s = (q + k) * srcStride;
                final int d = (q + k) * dstStride;
                src.position(s);
                src.get(shared, 0, 32);
                dst.position(d);
                dst.put(shared, 0, 32);
                // mc_Entity = (block id, render type). x = shaderpack block.properties id (-1 neutral when
                // unmapped/no pack); y = BLOCK_RENDER_TYPE (-1), the value Iris writes for solid blocks.
                dst.putShort(d + offEntity, haveIds ? blockIds[q + k] : (short) -1);
                dst.putShort(d + offEntity + 2, (short) -1);
                // mc_midTexCoord + at_tangent are face-constant (same for all 4 verts).
                dst.putFloat(d + offMidTex, midU);
                dst.putFloat(d + offMidTex + 4, midV);
                dst.putInt(d + offTangent, packedTangent);
                // at_midBlock: signed offset (block centre - vertex) * 64, one byte per axis (Iris
                // ExtendedDataHelper.packMidBlock). Drives foliage/leaf waving (the per-block pivot). The
                // integer part of the position cancels, so it's recoverable purely from the section-local
                // vertex position; the 4th (pad) byte stays zero. (Inert until the GENERIC attributes upload
                // via the float path -- see MixinVertexArrayCacheSeparate.)
                final float vx = src.getFloat(s + offPos);
                final float vy = src.getFloat(s + offPos + 4);
                final float vz = src.getFloat(s + offPos + 8);
                dst.put(d + offMidBlock, (byte) ((int) ((((float) Math.floor(vx) + 0.5f) - vx) * 64.0f)));
                dst.put(d + offMidBlock + 1, (byte) ((int) ((((float) Math.floor(vy) + 0.5f) - vy) * 64.0f)));
                dst.put(d + offMidBlock + 2, (byte) ((int) ((((float) Math.floor(vz) + 0.5f) - vz) * 64.0f)));
            }
        }

        if (!quads) {
            // Defensive fallback: copy shared bytes + entity id only.
            for (int i = 0; i < vertexCount; i++) {
                final int s = i * srcStride;
                final int d = i * dstStride;
                src.position(s);
                src.get(shared, 0, 32);
                dst.position(d);
                dst.put(shared, 0, 32);
                dst.putShort(d + offEntity, haveIds ? blockIds[i] : (short) -1);
                dst.putShort(d + offEntity + 2, (short) -1);
            }
        }

        dst.position(0);
        return dst;
    }
}
