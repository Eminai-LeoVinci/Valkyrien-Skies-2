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
    private static int terrainStride;

    // Byte offsets of the four Iris extras within a TERRAIN vertex, read from the runtime format at init.
    private static int offEntity;
    private static int offMidTex;
    private static int offTangent;
    private static int offMidBlock;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void init() {
        try {
            // The exact IrisVertexFormats.TERRAIN object + the four extra elements (reference identity
            // matters: Iris's findBestMatch compares vertex formats with ==, and getOffset() keys on the
            // same element objects used to build the format).
            final Class<?> ivf = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
            final VertexFormat terrain = (VertexFormat) ivf.getField("TERRAIN").get(null);
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
        } catch (final Throwable t) {
            ok = false;
            final Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
            LOGGER.warn("VS ship terrain: Iris pipeline registration failed ({}: {}); staying on the "
                + "immediate path under shaders", c.getClass().getSimpleName(), c.getMessage());
        }
    }

    /**
     * Repack a BLOCK-format (32B/vertex) QUADS buffer into TERRAIN-format (52B/vertex), computing the
     * Iris extra attributes. The shared first 32 bytes are copied verbatim; per quad of 4 vertices we
     * compute {@code mc_midTexCoord} (mean UV) and {@code at_tangent} (per-face TBN, the normal-mapping/POM
     * driver) and write a neutral {@code mc_Entity}. {@code at_midBlock} and the trailing pad are left
     * zero (the direct buffer is zero-initialised) -- midBlock only affects per-block waving, which a
     * static hull has none of. Returns a fresh direct buffer positioned at 0, ready for {@code createBuffer}.
     */
    public static ByteBuffer repackBlockToTerrain(final ByteBuffer block, final int vertexCount,
        final VertexFormat blockFmt) {
        final int srcStride = blockFmt.getVertexSize();
        final int dstStride = terrainStride;
        final int offPos = blockFmt.getOffset(VertexFormatElement.POSITION);
        final int offUv0 = blockFmt.getOffset(VertexFormatElement.UV0);

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
                // mc_Entity: (block id, render type) = (-1, -1) -- the documented "no id" / BLOCK sentinel
                // every pack treats as neutral (0 would be a valid block.properties id).
                dst.putShort(d + offEntity, (short) -1);
                dst.putShort(d + offEntity + 2, (short) -1);
                // mc_midTexCoord + at_tangent are face-constant (same for all 4 verts).
                dst.putFloat(d + offMidTex, midU);
                dst.putFloat(d + offMidTex + 4, midV);
                dst.putInt(d + offTangent, packedTangent);
                // at_midBlock (offMidBlock..+2) and the trailing pad stay zero (allocateDirect zero-init).
            }
        }

        if (!quads) {
            // Defensive fallback: copy shared bytes + neutral entity only.
            for (int i = 0; i < vertexCount; i++) {
                final int s = i * srcStride;
                final int d = i * dstStride;
                src.position(s);
                src.get(shared, 0, 32);
                dst.position(d);
                dst.put(shared, 0, 32);
                dst.putShort(d + offEntity, (short) -1);
                dst.putShort(d + offEntity + 2, (short) -1);
            }
        }

        dst.position(0);
        return dst;
    }
}
