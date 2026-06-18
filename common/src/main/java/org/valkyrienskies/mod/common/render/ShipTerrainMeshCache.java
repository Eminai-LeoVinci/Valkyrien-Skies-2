package org.valkyrienskies.mod.common.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.renderer.v1.render.BlockVertexConsumerProvider;
import net.fabricmc.fabric.api.renderer.v1.render.FabricBlockModelRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.mixin.accessors.client.render.FrustumInvoker;
import org.valkyrienskies.mod.mixin.accessors.client.render.RenderTypeAccessor;

/**
 * Caches ship terrain geometry so it is baked once and cheaply redrawn each frame, instead of
 * re-baking every block model (with ambient occlusion + light sampling) every frame -- the latter
 * dropped large ships to single-digit FPS.
 * <p>
 * Each shipyard section is baked once into the vanilla {@code BLOCK} vertex layout (in SECTION-LOCAL
 * [0,16] coordinates) and, by default, re-emitted each frame into the frame's
 * {@link MultiBufferSource.BufferSource} -- the IMMEDIATE path. That re-emit goes through the
 * moving-block render types (the same path the per-block ship renderer uses), which Iris's gbuffers
 * shade (so shaders work and the see-through holes can't return; it never touches Sodium's terrain
 * render-lists), and vanilla flushes it at the correct point in the frame.
 * <p>
 * The GPU path (always on; it self-disables only on a GPU error) instead uploads solid/cutout/tripwire
 * geometry once into a persistent {@link GpuBuffer} and redraws it each frame with only a per-section
 * model-view uniform changing -- no per-vertex CPU work. Under a shaderpack it draws through Iris's
 * gbuffer terrain program (geometry repacked into Iris's TERRAIN vertex format and drawn via
 * {@link ShipTerrainIrisPipeline}'s assigned pipelines), which eliminates the per-frame CPU re-emit that
 * otherwise costs ~7 ms/frame with a hull in view; without shaders it draws through the vanilla
 * moving-block pipeline. The flush is deferred to renderAllFeatures TAIL (MixinFeatureRenderDispatcher)
 * so the draw lands after the camera/render-target are set, not at submit time. Translucent (glass,
 * water) always uses the immediate re-emit path, since it needs vanilla's per-frame back-to-front sort
 * to blend correctly.
 * <p>
 * The GPU path self-disables to the immediate path on any GPU error, and re-bakes automatically if the
 * vertex format changes (a shaderpack toggle). Sections outside the camera frustum are skipped entirely
 * (and not even baked until first seen), so cost scales with what is on screen, not the whole ship.
 * <p>
 * We deliberately decode/upload the bytes out at bake time and never retain a {@link MeshData}: its
 * backing {@code ByteBufferBuilder.Result} is invalidated after its first upload, so a held mesh throws
 * "Buffer is no longer valid" on the second frame.
 * <p>
 * The cache is invalidated per-section on block changes via {@code LevelRenderer.setSectionDirty}, and
 * stale sections are evicted over time (closing their GPU buffers). Client-only: referenced only from
 * client mixins, on the render thread.
 */
public final class ShipTerrainMeshCache {

    public static final ShipTerrainMeshCache INSTANCE = new ShipTerrainMeshCache();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int INITIAL_BUFFER_BYTES = 1 << 12;
    // Off-screen ships are skipped wholesale, so a section can go untouched for a while without the
    // ship being gone; keep cached sections alive long enough to survive normal look-aways.
    private static final long EVICT_AFTER_FRAMES = 3600L;
    // Cap how many sections may be baked in a single frame so a ship coming into view doesn't bake
    // its whole hull at once (a visible hitch). Over-budget sections bake on following frames.
    private static final int MAX_BAKES_PER_FRAME = 8;

    // Constant writeTransform args (match vanilla RenderType.draw): no colour modulation, no model
    // offset, identity texture matrix. Never mutated -- writeTransform only reads them.
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f NO_MODEL_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY_TEX = new Matrix4f();
    private static final Supplier<String> GPU_BUFFER_LABEL = () -> "vs-ship-section";
    private static final Supplier<String> GPU_PASS_LABEL = () -> "vs_ship_terrain_gpu";

    // The persistent-GPU-buffer path: bake each section's solid/cutout geometry once into a GPU buffer
    // and redraw it with only a per-section transform changing, instead of re-emitting every vertex on
    // the CPU every frame. Always on; a GPU error flips it off for the rest of the session (a graceful
    // fallback to the immediate re-emit path).
    //   * Under a shaderpack it draws through Iris's gbuffer terrain program -- sections are repacked
    //     into Iris's TERRAIN vertex format and drawn via ShipTerrainIrisPipeline's assigned pipelines,
    //     so the hull is shaded exactly like surrounding chunk terrain. This is the large FPS win with a
    //     ship in view (it eliminates the ~7ms/frame CPU re-emit). See frameIrisGpu.
    //   * Without a shaderpack it draws through the vanilla moving-block pipeline.
    //   * The flush is deferred to renderAllFeatures TAIL (MixinFeatureRenderDispatcher) so the draw
    //     lands after the camera/render-target are set, not at submit time.
    // Translucent geometry (glass/water) always stays on the immediate re-emit path -- it needs vanilla's
    // per-frame back-to-front sort to blend correctly.
    private static volatile boolean gpuPath = true;

    // Keyed by SectionPos.asLong-packed shipyard section position (disjoint per ship, so globally
    // unique). Primitive keys: the per-frame walk looks one up per non-air section, and a record
    // key meant one allocation + boxed hashing per lookup.
    private final Long2ObjectOpenHashMap<CachedSection> sections = new Long2ObjectOpenHashMap<>();

    private long frame;
    private boolean disabled;
    private ClientLevel boundLevel;

    // Reusable scratch (render thread only -- never shared) to avoid per-section/-frame allocation.
    private final Vector3d cullMin = new Vector3d();
    private final Vector3d cullMax = new Vector3d();
    private final PoseStack scratchPose = new PoseStack();

    // Per-frame queue of GPU draws, collected during the section walk and flushed in one render pass
    // afterwards (so buffer uploads during lazy baking never happen inside an open pass).
    private final List<GpuDrawItem> gpuDrawQueue = new ArrayList<>();
    private boolean gpuFormatMismatch;

    // Whether this frame uses the persistent-GPU-buffer path at all (vs the immediate re-emit). True when
    // the GPU path is enabled and either no shaderpack is active (vanilla pipeline) or the Iris pipelines
    // are registered (frameIrisGpu). Recomputed at the top of each frame; a flip (shaders or the keybind
    // toggled) re-bakes every section in the new mode.
    private boolean frameGpuEffective;
    private boolean lastGpuEffective;    // detect flips to re-bake in the new mode
    // This frame the GPU buffers draw through Iris's gbuffer program (sections baked into Iris's TERRAIN
    // format and drawn via ShipTerrainIrisPipeline). False = vanilla pipeline (no shaderpack active).
    private boolean frameIrisGpu;
    private boolean lastIrisGpu;         // detect a shaderpack on/off flip to re-bake in the new format

    // Cached reflective handle to IrisApi.getInstance().isShaderPackInUse() -- resolved once, no hard dep.
    private static boolean irisResolved;
    private static Object irisApiInstance;
    private static Method irisIsShaderPackInUse;

    // Per-frame bake budget counter (reset each frame); throttles first-time section bakes.
    private int lastBaked;

    private ShipTerrainMeshCache() {
    }

    /** One render type's worth of a baked section, decoded into flat primitive arrays (immediate path). */
    private static final class Built {
        final RenderType type;
        final int vertexCount;
        final float[] pos;     // 3 per vertex: x, y, z
        final float[] uv;      // 2 per vertex: u, v
        final byte[] color;    // 4 per vertex: r, g, b, a
        final int[] light;     // 1 per vertex: (uv2.u & 0xFFFF) | (uv2.v << 16)
        final float[] normal;  // 3 per vertex: nx, ny, nz (already /127 at decode -- emit is per frame)

        Built(final RenderType type, final int vertexCount, final float[] pos, final float[] uv,
            final byte[] color, final int[] light, final float[] normal) {
            this.type = type;
            this.vertexCount = vertexCount;
            this.pos = pos;
            this.uv = uv;
            this.color = color;
            this.light = light;
            this.normal = normal;
        }
    }

    /**
     * One render type's worth of a baked section living in a persistent GPU vertex buffer (GPU path).
     * Drawn each frame through the render type's pipeline -- which Iris shades like any moving-block
     * terrain -- with only a per-section transform uniform changing.
     */
    private static final class GpuMesh {
        final RenderType type;
        final GpuBuffer vertexBuffer;
        final int indexCount;
        // Bake-time vertex stride. If the pipeline's current format size diverges (shaderpack toggled),
        // the baked bytes no longer match the program and the section must be re-baked.
        final int vertexSize;

        GpuMesh(final RenderType type, final GpuBuffer vertexBuffer, final int indexCount,
            final int vertexSize) {
            this.type = type;
            this.vertexBuffer = vertexBuffer;
            this.indexCount = indexCount;
            this.vertexSize = vertexSize;
        }

        void close() {
            vertexBuffer.close();
        }
    }

    /** A queued GPU draw: one section's persistent meshes plus its model-view matrix for this frame. */
    private record GpuDrawItem(Matrix4f modelView, List<GpuMesh> meshes) {
    }

    private static final class CachedSection {
        // Translucent geometry: re-emitted immediately each frame (keeps per-frame depth sorting).
        final List<Built> built = new ArrayList<>(1);
        // Solid/cutout/tripwire geometry: persistent GPU buffers, redrawn with a transform each frame.
        final List<GpuMesh> gpuMeshes = new ArrayList<>(2);
        long lastUsedFrame;

        boolean isEmpty() {
            return built.isEmpty() && gpuMeshes.isEmpty();
        }

        void close() {
            for (final GpuMesh m : gpuMeshes) {
                m.close();
            }
            gpuMeshes.clear();
        }
    }

    public boolean isDisabled() {
        return disabled;
    }

    /** Whether the renderer should take the cached path (vs the immediate fallback). */
    public boolean canUseCache() {
        return !disabled;
    }

    /**
     * True when a shaderpack is active (Iris). Resolved reflectively against the stable Iris v0 API so
     * there is no compile/runtime dependency on Iris; if Iris is absent or anything fails we report
     * "no shaders" and keep the GPU path available.
     */
    private static boolean shadersActive() {
        if (!irisResolved) {
            irisResolved = true;
            try {
                final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApiInstance = api.getMethod("getInstance").invoke(null);
                irisIsShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (final Throwable ignored) {
                irisApiInstance = null;
                irisIsShaderPackInUse = null;
            }
        }
        if (irisApiInstance == null || irisIsShaderPackInUse == null) {
            return false;
        }
        try {
            return (Boolean) irisIsShaderPackInUse.invoke(irisApiInstance);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * Draw every loaded ship's visible cached terrain, baking missing sections on demand. Translucent
     * geometry is re-emitted into {@code bufferSource} (vanilla flushes it); solid/cutout geometry is
     * drawn from persistent GPU buffers in one render pass at the end. Call at the gbuffer-bound submit
     * point in the main pass (where the immediate path draws). Catches its own failures and disables
     * the cache (caller falls back to immediate) rather than propagating.
     *
     * @param frustum the main camera frustum (world space); sections outside it are skipped. May be
     *                null, in which case nothing is culled.
     */
    public void renderAll(final ClientLevel level, final BlockRenderDispatcher dispatcher,
        final RandomSource random, final MultiBufferSource.BufferSource bufferSource,
        final Frustum frustum, final LongSet occludedShipIds,
        final double camX, final double camY, final double camZ) {

        if (disabled) {
            return;
        }
        try {
            if (boundLevel != level) {
                clear();
                boundLevel = level;
            }
            frame++;
            lastBaked = 0;
            gpuDrawQueue.clear();

            // Recompute the render mode each frame; when it flips -- the user toggles shaders or the GPU
            // keybind -- re-bake everything so each section is stored in the correct mode (persistent GPU
            // buffers vs immediate Built meshes).
            final boolean shadersOn = shadersActive();
            // Under a shaderpack the GPU buffers draw through Iris's gbuffer terrain program via
            // ShipTerrainIrisPipeline (sections are repacked into Iris's TERRAIN vertex format); without
            // shaders they draw through the vanilla moving-block pipeline. frameIrisGpu selects the former.
            frameIrisGpu = gpuPath && shadersOn && ShipTerrainIrisPipeline.ready();
            frameGpuEffective = gpuPath && (!shadersOn || frameIrisGpu);
            // Re-bake when the bake mode flips. frameGpuEffective catches GPU <-> immediate; frameIrisGpu
            // catches a shaderpack toggle, which changes the bake FORMAT (vanilla BLOCK 32B <-> Iris
            // TERRAIN 52B) while frameGpuEffective can stay true -- so both must be tracked, or a section
            // baked before the toggle keeps a stale layout drawn through the wrong pipeline.
            if (frameGpuEffective != lastGpuEffective || frameIrisGpu != lastIrisGpu) {
                lastGpuEffective = frameGpuEffective;
                lastIrisGpu = frameIrisGpu;
                clear();
            }

            // Camera rotation for this frame. Section-local GPU buffers are drawn with
            // (cameraModelView x shipPose) so they land exactly where the immediate path's
            // camera-relative-baked vertices do (vanilla draws immediate meshes with this matrix).
            // Constant for the whole pass; copy it because we multiply per section.
            final Matrix4f camModelView = new Matrix4f(RenderSystem.getModelViewMatrix());

            final int minSectionY = level.getMinSectionY();

            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
                // Skip the whole ship (and all its per-chunk/-section work) when it can't be on screen.
                if (frustum != null && !frustum.isVisible(VectorConversionsMCKt.toMinecraft(ship.getRenderAABB()))) {
                    continue;
                }
                // VS-VOXY-OCCLUSION: ship is fully behind Voxy LOD terrain (sampled from Voxy's own
                // depth buffer in MixinLevelRenderer). Keep its baked sections warm so re-emerging is
                // instant, but suppress the draw below -- this is also what saves the per-frame
                // immediate re-emit under shaders for hidden ships.
                final boolean shipOccluded = occludedShipIds != null && occludedShipIds.contains(ship.getId());
                final ShipTransform renderTransform = ship.getRenderTransform();
                final Matrix4dc shipToWorld = renderTransform.getShipToWorld();
                ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                    final LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    final LevelChunkSection[] arr = chunk.getSections();
                    for (int sectionIndex = 0; sectionIndex < arr.length; sectionIndex++) {
                        final LevelChunkSection section = arr[sectionIndex];
                        if (section == null || section.hasOnlyAir()) {
                            continue;
                        }
                        final int sectionY = minSectionY + sectionIndex;
                        final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);

                        final boolean visible = isShipSectionVisible(frustum, shipToWorld, chunkX, sectionY, chunkZ);

                        CachedSection cached = sections.get(key);
                        if (cached == null) {
                            // Defer the (expensive) bake until the section is first actually visible,
                            // and cap bakes per frame so a ship appearing all at once doesn't hitch.
                            if (!visible || lastBaked >= MAX_BAKES_PER_FRAME) {
                                continue;
                            }
                            cached = bake(level, chunkX, sectionY, chunkZ, dispatcher, random);
                            sections.put(key, cached);
                            lastBaked++;
                        }
                        // Keep loaded-ship sections alive even while culled, so turning back toward the
                        // ship doesn't trigger a re-bake stutter.
                        cached.lastUsedFrame = frame;
                        if (!visible) {
                            continue;
                        }
                        if (cached.isEmpty()) {
                            continue;
                        }

                        // One ship pose per section; section-local [0,16] vertices map to render space
                        // exactly as the per-block immediate path does (offset by the section origin).
                        // Reuse a single scratch PoseStack to avoid a per-section allocation each frame.
                        scratchPose.setIdentity();
                        VSClientGameUtils.transformRenderWithShip(renderTransform, scratchPose,
                            chunkX * 16.0, sectionY * 16.0, chunkZ * 16.0, camX, camY, camZ);
                        final PoseStack.Pose pose = scratchPose.last();

                        // Ship fully behind LOD terrain: sections stay baked + kept-alive (above), but
                        // emit nothing this frame. (Cheaper than drawing, and the only thing that
                        // actually hides the hull under shaders -- the immediate re-emit path.)
                        if (shipOccluded) {
                            continue;
                        }
                        // Translucent: immediate re-emit (keeps vanilla's per-frame back-to-front sort).
                        for (final Built b : cached.built) {
                            emit(bufferSource.getBuffer(b.type), pose, b);
                        }
                        // Solid/cutout: queue a GPU draw with this section's full model-view. Copy the
                        // pose (scratchPose is mutated next section) and pre-multiply the camera matrix.
                        if (!cached.gpuMeshes.isEmpty()) {
                            gpuDrawQueue.add(new GpuDrawItem(
                                new Matrix4f(camModelView).mul(pose.pose()), cached.gpuMeshes));
                        }
                    }
                });
            }

            // The GPU draw is intentionally NOT flushed here. submitBlockEntities runs before Iris has
            // bound its gbuffer target, so a draw at this point lands on the wrong framebuffer and gets
            // composited away -- the ship only renders here when no shaderpack is active. The queue is
            // instead flushed from MixinFeatureRenderDispatcher at renderAllFeatures TAIL: the same
            // point where vanilla flushes this frame's immediate ship terrain (solidMovingBlock), where
            // Iris's target override IS live. flushDeferredGpuDraws() consumes + clears the queue there.
            // (The per-section transforms are already baked into gpuDrawQueue above, so deferring only
            // the draw is correct -- the camera model-view doesn't change between here and that point.)

            evictStale();
        } catch (final Throwable t) {
            disabled = true;
            LOGGER.error("Ship terrain mesh cache failed; falling back to immediate-mode ship rendering", t);
            clear();
        }
    }

    /**
     * Flush this frame's queued ship-terrain GPU draws. Called from MixinFeatureRenderDispatcher at
     * {@code renderAllFeatures} TAIL -- the point where vanilla flushes the immediate ship terrain and
     * Iris has its gbuffer target bound. Drawing earlier (at submitBlockEntities time) lands on the
     * wrong target under shaders. No-op when the queue is empty: GPU path off, no ships, or already
     * flushed this frame. Clears the queue afterward so a second renderAllFeatures call in the same
     * frame (there are two) can't redraw it; self-gating on emptiness keeps it order-independent.
     */
    public void flushDeferredGpuDraws() {
        if (disabled || gpuDrawQueue.isEmpty()) {
            return;
        }
        try {
            flushGpuDraws();
        } catch (final Throwable t) {
            // A GPU failure degrades to the immediate path (still correct), not a full disable.
            LOGGER.error("VS ship GPU draw failed; switching ship terrain to immediate re-emit", t);
            gpuPath = false;
            clear();
        } finally {
            gpuDrawQueue.clear();
        }
        if (gpuFormatMismatch) {
            // Shaderpack toggled: baked bytes no longer match the program -- re-bake fresh.
            gpuFormatMismatch = false;
            clear();
        }
    }

    /**
     * Draw all queued sections' persistent GPU meshes in a single render pass, mirroring vanilla
     * {@code RenderType.draw}: same output-target/override selection, pipeline, default uniforms,
     * textures and index buffer. The per-section model-view is supplied through the DynamicTransforms
     * uniform; pipeline + textures are (re)bound only when the pipeline changes.
     */
    private void flushGpuDraws() {
        final DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();

        // PHASE 1 -- everything that MAPS a GPU buffer (the transforms + sizing the sequential index
        // buffer) MUST happen before a render pass is opened; mapping inside an open pass throws
        // "Close the existing render pass before performing additional commands".
        //
        // Build every section's transform and write them in ONE batched call: writeTransforms pre-sizes
        // the dynamic-uniform UBO for all of them and hands back slices that are valid together. N
        // separate writeTransform calls do NOT work here -- when the UBO hits its capacity mid-batch it
        // reallocates, so any slice handed out before that point references a freed buffer, which
        // transformed whole sections off-screen and made the ship vanish.
        final int n = gpuDrawQueue.size();
        final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[n];
        int maxIndexCount = 0;
        for (int i = 0; i < n; i++) {
            final GpuDrawItem item = gpuDrawQueue.get(i);
            transforms[i] =
                new DynamicUniforms.Transform(item.modelView(), WHITE, NO_MODEL_OFFSET, IDENTITY_TEX);
            for (final GpuMesh gm : item.meshes()) {
                if (gm.indexCount > maxIndexCount) {
                    maxIndexCount = gm.indexCount;
                }
            }
        }
        if (maxIndexCount == 0) {
            return;
        }
        final GpuBufferSlice[] slices = uniforms.writeTransforms(transforms);
        // Ship terrain is always QUADS (guaranteed at bake), so one shared sequential index buffer,
        // sized to the largest mesh, serves every draw. getBuffer() may grow/map it -- hence pre-pass.
        final RenderSystem.AutoStorageIndexBuffer seq =
            RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer indexBuffer = seq.getBuffer(maxIndexCount);
        final VertexFormat.IndexType indexType = seq.type();

        // PHASE 2 -- the render pass does only pipeline/uniform/buffer binds + draws (no mapping).
        final RenderTarget target = OutputTarget.MAIN_TARGET.getRenderTarget();
        // Match vanilla exactly: honour Iris's (or anyone's) target overrides, else the main target.
        final GpuTextureView color = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride
            : target.getColorTextureView();
        final GpuTextureView depth = !target.useDepth ? null
            : (RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : target.getDepthTextureView());
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        try (RenderPass pass = encoder.createRenderPass(GPU_PASS_LABEL, color, OptionalInt.empty(),
            depth, OptionalDouble.empty())) {
            RenderPipeline boundPipeline = null;
            for (int i = 0; i < n; i++) {
                final GpuBufferSlice transform = slices[i];
                for (final GpuMesh gm : gpuDrawQueue.get(i).meshes()) {
                    // Iris-format meshes (TERRAIN stride) draw through the Iris-assigned terrain pipeline
                    // so the shaderpack's gbuffer program shades them. Vanilla-format meshes (no shaderpack)
                    // use the render type's own pipeline.
                    final int terrainStride = ShipTerrainIrisPipeline.terrainStride();
                    final RenderPipeline pipeline = pipelineFor(gm, terrainStride);
                    if (gm.vertexSize != pipeline.getVertexFormat().getVertexSize()) {
                        // Shaderpack toggled since bake: the program now wants a different layout.
                        gpuFormatMismatch = true;
                        continue;
                    }
                    if (pipeline != boundPipeline) {
                        pass.setPipeline(pipeline);
                        RenderSystem.bindDefaultUniforms(pass);
                        bindRenderTypeTextures(pass, gm.type);
                        boundPipeline = pipeline;
                    }
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, gm.vertexBuffer);
                    pass.setIndexBuffer(indexBuffer, indexType);
                    pass.drawIndexed(0, 0, gm.indexCount, 1);
                }
            }
        }
    }

    /**
     * Pick the draw pipeline for a baked mesh: the Iris-assigned solid/cutout TERRAIN pipeline when the
     * mesh was baked in Iris format and that pipeline is registered, else the render type's own vanilla
     * pipeline (no shaderpack active, or Iris registration unavailable).
     */
    private static RenderPipeline pipelineFor(final GpuMesh gm, final int terrainStride) {
        if (terrainStride > 0 && gm.vertexSize == terrainStride) {
            final RenderPipeline p = ShipTerrainIrisPipeline.terrainPipeline();
            if (p != null) {
                return p;
            }
        }
        return gm.type.pipeline();
    }

    /** Bind a render type's textures (block atlas + lightmap) onto the pass, exactly as vanilla draw. */
    private static void bindRenderTypeTextures(final RenderPass pass, final RenderType type) {
        final RenderSetup setup = ((RenderTypeAccessor) type).valkyrienskies$getState();
        for (final Map.Entry<String, RenderSetup.TextureAndSampler> e : setup.getTextures().entrySet()) {
            pass.bindTexture(e.getKey(), e.getValue().textureView(), e.getValue().sampler());
        }
    }

    /**
     * Is the shipyard section [sx,sy,sz] visible? Transforms the section's [0,16]^3 box from shipyard
     * space into rendered world space with {@code shipToWorld}, then frustum-tests the enclosing AABB.
     * Public so the block-entity renderer can cull ship BEs by the same section visibility.
     */
    public boolean isShipSectionVisible(final Frustum frustum, final Matrix4dc shipToWorld,
        final int sx, final int sy, final int sz) {
        final double x0 = sx * 16.0;
        final double y0 = sy * 16.0;
        final double z0 = sz * 16.0;
        return isShipBoxVisible(frustum, shipToWorld, x0, y0, z0, x0 + 16.0, y0 + 16.0, z0 + 16.0);
    }

    /** Frustum-test a shipyard-space box transformed into rendered world space. Null frustum = visible. */
    private boolean isShipBoxVisible(final Frustum frustum, final Matrix4dc shipToWorld,
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ) {
        if (frustum == null) {
            return true;
        }
        shipToWorld.transformAab(minX, minY, minZ, maxX, maxY, maxZ, cullMin, cullMax);
        // Direct cubeInFrustum call -- this runs once per non-air ship section per frame, so skip
        // the per-test AABB allocation. Same INSIDE/INTERSECT check as vanilla isVisible(AABB).
        final int result = ((FrustumInvoker) frustum).valkyrienskies$cubeInFrustum(
            cullMin.x, cullMin.y, cullMin.z, cullMax.x, cullMax.y, cullMax.z);
        return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT;
    }

    private static void emit(final VertexConsumer consumer, final PoseStack.Pose pose, final Built mesh) {
        final int count = mesh.vertexCount;
        final float[] pos = mesh.pos;
        final float[] uv = mesh.uv;
        final byte[] color = mesh.color;
        final int[] light = mesh.light;
        final float[] normal = mesh.normal;

        for (int i = 0; i < count; i++) {
            final int p3 = i * 3;
            final int p2 = i * 2;
            final int c4 = i * 4;
            final int l = light[i];

            consumer.addVertex(pose, pos[p3], pos[p3 + 1], pos[p3 + 2])
                .setColor(color[c4] & 0xFF, color[c4 + 1] & 0xFF, color[c4 + 2] & 0xFF, color[c4 + 3] & 0xFF)
                .setUv(uv[p2], uv[p2 + 1])
                .setUv2(l & 0xFFFF, l >>> 16)
                .setNormal(pose, normal[p3], normal[p3 + 1], normal[p3 + 2]);
        }
    }

    /** Bake one shipyard section into per-RenderType meshes (section-local coords). */
    private CachedSection bake(final ClientLevel level, final int sectionX, final int sectionY,
        final int sectionZ, final BlockRenderDispatcher dispatcher, final RandomSource random) {

        final CachedSection result = new CachedSection();
        result.lastUsedFrame = frame;

        final LevelChunk chunk = level.getChunk(sectionX, sectionZ);
        final int sectionIndex = sectionY - level.getMinSectionY();
        final LevelChunkSection[] arr = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= arr.length) {
            return result;
        }
        final LevelChunkSection section = arr[sectionIndex];
        if (section == null || section.hasOnlyAir()) {
            return result;
        }

        final int baseX = sectionX << 4;
        final int baseY = sectionY << 4;
        final int baseZ = sectionZ << 4;

        final Map<RenderType, BufferBuilder> builders = new HashMap<>(4);
        final Map<RenderType, ByteBufferBuilder> backings = new HashMap<>(4);
        final PoseStack pose = new PoseStack();

        // Render block models through Fabric's terrain-like model renderer (the FRAPI terrain context)
        // so connected-texture mods like Fusion -- which hook that context, NOT the plain block path
        // used by renderBatched -- apply their connections to ship blocks. Quads are routed per
        // chunk-section layer into the matching moving-block render type's capture buffer.
        // Cast via Object: ModelBlockRenderer implements FabricBlockModelRenderer only through a
        // Fabric API mixin, which the compiler can't see, so a direct cast wouldn't compile.
        final FabricBlockModelRenderer fabricModelRenderer =
            (FabricBlockModelRenderer) (Object) dispatcher.getModelRenderer();
        final BlockVertexConsumerProvider ctmConsumers =
            layer -> builderFor(builders, backings, movingBlockRenderType(layer));

        try {
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        final BlockPos posWorld = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);

                        final FluidState fluidState = state.getFluidState();
                        if (!fluidState.isEmpty()) {
                            // LiquidBlockRenderer emits section-local [0,16] coords (no PoseStack), which
                            // is exactly our cache space -- feed it straight in.
                            final RenderType rt = fluidRenderType(fluidState);
                            dispatcher.renderLiquid(posWorld, level, builderFor(builders, backings, rt),
                                state, fluidState);
                        }

                        if (state.getRenderShape() == RenderShape.MODEL) {
                            final BlockStateModel model = dispatcher.getBlockModel(state);
                            pose.pushPose();
                            pose.translate(lx, ly, lz);
                            // Section-local position from the pose; posWorld (shipyard coords) gives the
                            // renderer the neighbours it needs for face culling, ambient occlusion and
                            // connected-texture matching -- which all resolve against the ship's own blocks.
                            fabricModelRenderer.render(level, model, state, posWorld, pose, ctmConsumers,
                                true, state.getSeed(posWorld), OverlayTexture.NO_OVERLAY);
                            pose.popPose();
                        }
                    }
                }
            }

            for (final Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                try (MeshData mesh = entry.getValue().build()) {
                    if (mesh == null) {
                        continue;
                    }
                    final RenderType type = entry.getKey();
                    // Translucent needs per-frame depth sorting -> immediate path. Everything else
                    // (solid/cutout/tripwire, and opaque fluids like lava) -> persistent GPU buffer,
                    // provided it draws QUADS (it always does) so the one shared sequential index buffer
                    // applies to every mesh; anything else falls back to the immediate path.
                    if (frameGpuEffective && type != RenderTypes.translucentMovingBlock()
                        && mesh.drawState().mode() == VertexFormat.Mode.QUADS) {
                        final GpuMesh gm = uploadGpuMesh(type, mesh);
                        if (gm != null) {
                            result.gpuMeshes.add(gm);
                        }
                    } else {
                        final Built b = decode(type, mesh);
                        if (b != null) {
                            result.built.add(b);
                        }
                    }
                }
            }
        } finally {
            for (final ByteBufferBuilder backing : backings.values()) {
                backing.close();
            }
        }
        return result;
    }

    /**
     * Copy a freshly-built mesh's vertex bytes into a persistent GPU buffer. The MeshData (and its
     * backing Result) is still valid here inside bake, so this synchronous upload is safe -- we never
     * retain the MeshData past this method. Only reached when no shaderpack is active (the bake routing
     * gates on frameGpuEffective), so the bytes are always the vanilla moving-block layout.
     */
    private GpuMesh uploadGpuMesh(final RenderType type, final MeshData mesh) {
        final MeshData.DrawState ds = mesh.drawState();
        if (ds.indexCount() <= 0) {
            return null;
        }
        // Under a shaderpack, repack BLOCK -> Iris TERRAIN (computing the shader extras) so the
        // Iris-assigned pipeline shades the hull; otherwise keep the vanilla moving-block layout.
        final ByteBuffer verts;
        final int vertexSize;
        if (frameIrisGpu) {
            verts = ShipTerrainIrisPipeline.repackBlockToTerrain(mesh.vertexBuffer(), ds.vertexCount(), ds.format());
            vertexSize = ShipTerrainIrisPipeline.terrainStride();
        } else {
            verts = mesh.vertexBuffer();
            vertexSize = ds.format().getVertexSize();
        }
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            GPU_BUFFER_LABEL, GpuBuffer.USAGE_VERTEX, verts);
        return new GpuMesh(type, buffer, ds.indexCount(), vertexSize);
    }

    /**
     * Decode a freshly-built mesh into flat primitive arrays (immediate path, translucent). Iris extends
     * the bake buffer to its TERRAIN layout during the level pass, so read the vanilla fields by their
     * actual offsets (and stride) -- correct whether the format is vanilla BLOCK (32B) or an extended
     * layout (52B+). The appended shader extras are ignored: re-emitting through bufferSource regenerates
     * them.
     */
    private static Built decode(final RenderType type, final MeshData mesh) {
        final MeshData.DrawState ds = mesh.drawState();
        final VertexFormat fmt = ds.format();
        final int stride = fmt.getVertexSize();
        final int offPos = fmt.getOffset(VertexFormatElement.POSITION);
        final int offColor = fmt.getOffset(VertexFormatElement.COLOR);
        final int offUv0 = fmt.getOffset(VertexFormatElement.UV0);
        final int offUv2 = fmt.getOffset(VertexFormatElement.UV2);
        final int offNormal = fmt.getOffset(VertexFormatElement.NORMAL);
        if (offPos < 0 || offColor < 0 || offUv0 < 0 || offUv2 < 0 || offNormal < 0) {
            throw new IllegalStateException("ship bake format missing a required element: " + fmt);
        }

        final int count = ds.vertexCount();
        // GPU vertex bytes are native (little-endian) order; ByteBuffer.duplicate() resets to
        // BIG_ENDIAN, which would silently byte-swap the float/short reads. Read with native order.
        final ByteBuffer src = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());

        final float[] pos = new float[count * 3];
        final float[] uv = new float[count * 2];
        final byte[] color = new byte[count * 4];
        final int[] light = new int[count];
        final float[] normal = new float[count * 3];

        for (int i = 0; i < count; i++) {
            final int base = i * stride;
            final int pp = base + offPos;
            final int cc = base + offColor;
            final int tt = base + offUv0;
            final int ll = base + offUv2;
            final int nn = base + offNormal;

            final int p3 = i * 3;
            pos[p3] = src.getFloat(pp);
            pos[p3 + 1] = src.getFloat(pp + 4);
            pos[p3 + 2] = src.getFloat(pp + 8);

            final int c4 = i * 4;
            color[c4] = src.get(cc);
            color[c4 + 1] = src.get(cc + 1);
            color[c4 + 2] = src.get(cc + 2);
            color[c4 + 3] = src.get(cc + 3);

            final int p2 = i * 2;
            uv[p2] = src.getFloat(tt);
            uv[p2 + 1] = src.getFloat(tt + 4);

            final int u2 = src.getShort(ll) & 0xFFFF;
            final int v2 = src.getShort(ll + 2) & 0xFFFF;
            light[i] = u2 | (v2 << 16);

            // Pre-divide at bake: emit() replays these every frame for translucents.
            normal[p3] = src.get(nn) / 127.0f;
            normal[p3 + 1] = src.get(nn + 1) / 127.0f;
            normal[p3 + 2] = src.get(nn + 2) / 127.0f;
        }

        return new Built(type, count, pos, uv, color, light, normal);
    }

    private static VertexConsumer builderFor(final Map<RenderType, BufferBuilder> builders,
        final Map<RenderType, ByteBufferBuilder> backings, final RenderType rt) {

        BufferBuilder builder = builders.get(rt);
        if (builder == null) {
            final ByteBufferBuilder backing = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
            // Force the vanilla BLOCK layout so the cached bytes have the fixed offsets we decode.
            builder = new BufferBuilder(backing, rt.mode(), DefaultVertexFormat.BLOCK);
            builders.put(rt, builder);
            backings.put(rt, backing);
        }
        return builder;
    }

    /** Map a chunk-section render layer to the matching MOVING-block render type used by ship terrain. */
    private static RenderType movingBlockRenderType(final ChunkSectionLayer layer) {
        return switch (layer) {
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            case TRIPWIRE -> RenderTypes.tripwireMovingBlock();
            default -> RenderTypes.solidMovingBlock();
        };
    }

    private static RenderType fluidRenderType(final FluidState fluidState) {
        return movingBlockRenderType(ItemBlockRenderTypes.getRenderLayer(fluidState));
    }

    /**
     * Drop the cached mesh for a section (called when its blocks change) so it re-bakes next render,
     * closing any GPU buffers it held. Cheap no-op when nothing is cached (the common, no-ships case).
     */
    public void invalidateSection(final int sectionX, final int sectionY, final int sectionZ) {
        if (sections.isEmpty()) {
            return;
        }
        final CachedSection removed = sections.remove(SectionPos.asLong(sectionX, sectionY, sectionZ));
        if (removed != null) {
            removed.close();
        }
    }

    private void evictStale() {
        if ((frame & 0xFF) != 0L) {
            return;
        }
        final long cutoff = frame - EVICT_AFTER_FRAMES;
        final Iterator<CachedSection> it = sections.values().iterator();
        while (it.hasNext()) {
            final CachedSection cs = it.next();
            if (cs.lastUsedFrame < cutoff) {
                cs.close();
                it.remove();
            }
        }
    }

    public void clear() {
        for (final CachedSection cs : sections.values()) {
            cs.close();
        }
        sections.clear();
    }
}
