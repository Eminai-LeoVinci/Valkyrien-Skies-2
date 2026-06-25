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
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
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
import org.valkyrienskies.mod.common.config.VSGameConfig;
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

    // Auto-recovery for the cache + GPU path: after a render/GPU error we skip the failed path for this
    // many frames (~1.7s at 60fps) and then RETRY, instead of disabling it permanently until relog. Repeated
    // failures back the retry off exponentially (capped) so a persistently-broken state doesn't thrash.
    private static final int RETRY_BASE_FRAMES = 100;
    private static final int RETRY_BACKOFF_MAX = 64;

    // Constant writeTransform args (match vanilla RenderType.draw): no colour modulation, no model
    // offset, identity texture matrix. Never mutated -- writeTransform only reads them.
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f NO_MODEL_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY_TEX = new Matrix4f();
    private static final Supplier<String> GPU_BUFFER_LABEL = () -> "vs-ship-section";
    private static final Supplier<String> GPU_PASS_LABEL = () -> "vs_ship_terrain_gpu";

    // The persistent-GPU-buffer path: bake each section's solid/cutout geometry once into a GPU buffer
    // and redraw it with only a per-section transform changing, instead of re-emitting every vertex on
    // the CPU every frame. Always on; a GPU error skips it for a cooldown (gpuPathCooldown) and then
    // retries, gracefully degrading to the immediate re-emit path meanwhile.
    //   * Under a shaderpack it draws through Iris's gbuffer terrain program -- sections are repacked
    //     into Iris's TERRAIN vertex format and drawn via ShipTerrainIrisPipeline's assigned pipelines,
    //     so the hull is shaded exactly like surrounding chunk terrain. This is the large FPS win with a
    //     ship in view (it eliminates the ~7ms/frame CPU re-emit). See frameIrisGpu.
    //   * Without a shaderpack it draws through the vanilla moving-block pipeline.
    //   * The flush is deferred to renderAllFeatures TAIL (MixinFeatureRenderDispatcher) so the draw
    //     lands after the camera/render-target are set, not at submit time.
    // Translucent geometry (glass/water) always stays on the immediate re-emit path -- it needs vanilla's
    // per-frame back-to-front sort to blend correctly.

    // Keyed by SectionPos.asLong-packed shipyard section position (disjoint per ship, so globally
    // unique). Primitive keys: the per-frame walk looks one up per non-air section, and a record
    // key meant one allocation + boxed hashing per lookup.
    private final Long2ObjectOpenHashMap<CachedSection> sections = new Long2ObjectOpenHashMap<>();

    private long frame;
    // Post-failure retry cooldowns (frames) + exponential backoff multipliers, render-thread only. 0 = the
    // path is active. cacheCooldown gates the whole cache (-> per-block immediate fallback); gpuPathCooldown
    // gates only the persistent-GPU path (-> immediate re-emit of cached meshes).
    private int cacheCooldown;
    private int cacheBackoff = 1;
    private int gpuPathCooldown;
    private int gpuBackoff = 1;
    private ClientLevel boundLevel;

    // Reusable scratch (render thread only -- never shared) to avoid per-section/-frame allocation.
    private final Vector3d cullMin = new Vector3d();
    private final Vector3d cullMax = new Vector3d();
    private final PoseStack scratchPose = new PoseStack();

    // Per-frame queue of GPU draws, collected during the section walk and flushed in one render pass
    // afterwards (so buffer uploads during lazy baking never happen inside an open pass).
    private final List<GpuDrawItem> gpuDrawQueue = new ArrayList<>();
    private boolean gpuFormatMismatch;

    // This frame's camera model-view (copied at renderAll); composed onto each queued ship pose at flush.
    private final Matrix4f frameCamModelView = new Matrix4f();
    // Per-pass flush guards so the queue is drawn at most once per pass (the main gbuffer pass + Iris's shadow
    // pass, which also fires the flush hook), reset each renderAll. The queue is RETAINED until the next
    // renderAll (not cleared after the first flush) so both passes can draw it -- the shadow pass runs before
    // renderAll, so it draws the previous frame's queue (a 1-frame shadow lag).
    private boolean flushedMain;
    private boolean flushedShadow;

    // Whether this frame uses the persistent-GPU-buffer path at all (vs the immediate re-emit). True when
    // the GPU path is active (not in a post-error cooldown) and either no shaderpack is active (vanilla
    // pipeline) or the Iris pipelines are registered (frameIrisGpu). Recomputed at the top of each frame; a
    // flip (shaders toggled, or the GPU path recovering after an error) re-bakes every section in the new mode.
    private boolean frameGpuEffective;
    private boolean lastGpuEffective;    // detect flips to re-bake in the new mode
    // This frame the GPU buffers draw through Iris's gbuffer program (sections baked into Iris's TERRAIN
    // format and drawn via ShipTerrainIrisPipeline). False = vanilla pipeline (no shaderpack active).
    private boolean frameIrisGpu;
    private boolean lastIrisGpu;         // detect a shaderpack on/off flip to re-bake in the new format
    // This frame the bake should write each block's shaderpack id into mc_Entity (emissive/material). Gated on
    // the Iris path AND the renderShipBlockIds toggle; the ids are baked into the GPU buffer, so a toggle flip
    // must re-bake -- tracked like the other bake-mode flips.
    private boolean frameBlockIds;
    private boolean lastBlockIds;

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

    /**
     * A queued GPU draw: one section's persistent meshes plus its RAW ship pose (camera-relative). The
     * camera (main pass) or shadow (shadow pass) model-view is composed onto it at flush time, so the same
     * queue can be drawn into both passes.
     */
    private record GpuDrawItem(Matrix4f shipPose, List<GpuMesh> meshes) {
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
        return cacheCooldown > 0;
    }

    /**
     * Whether the renderer should take the cached path (vs the immediate per-block fallback). Also TICKS the
     * post-failure retry cooldown down -- the renderer calls this exactly once per frame to choose its path,
     * so when the cache previously failed we spend [cacheCooldown] frames on the fallback and then retry.
     */
    public boolean canUseCache() {
        if (cacheCooldown > 0) {
            cacheCooldown--;
            return false;
        }
        return true;
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

        // No disabled-guard here: the renderer only calls renderAll when canUseCache() returned true (cooldown
        // already 0), and canUseCache ticks the cooldown. renderAll runs => the cache is active this frame.
        try {
            if (boundLevel != level) {
                clear();
                boundLevel = level;
            }
            frame++;
            lastBaked = 0;
            gpuDrawQueue.clear();
            flushedMain = false;
            flushedShadow = false;

            // Tick the GPU-path retry cooldown (a GPU draw error parks the persistent-GPU path here); when it
            // reaches 0 the path re-activates and the flip below re-bakes into GPU buffers.
            if (gpuPathCooldown > 0) {
                gpuPathCooldown--;
            }
            final boolean gpuActive = gpuPathCooldown == 0;

            // Recompute the render mode each frame; when it flips -- shaders toggled, or the GPU path
            // recovering after an error -- re-bake everything so each section is stored in the correct mode
            // (persistent GPU buffers vs immediate Built meshes).
            final boolean shadersOn = shadersActive();
            // Under a shaderpack the GPU buffers draw through Iris's gbuffer terrain program via
            // ShipTerrainIrisPipeline (sections are repacked into Iris's TERRAIN vertex format); without
            // shaders they draw through the vanilla moving-block pipeline. frameIrisGpu selects the former.
            frameIrisGpu = gpuActive && shadersOn && ShipTerrainIrisPipeline.ready();
            frameGpuEffective = gpuActive && (!shadersOn || frameIrisGpu);
            // Whether to bake shaderpack block ids into mc_Entity (emissive/material) -- Iris path + toggle.
            frameBlockIds = frameIrisGpu && VSGameConfig.CLIENT.getRenderShipBlockIds();
            // Re-bake when the bake mode flips. frameGpuEffective catches GPU <-> immediate; frameIrisGpu
            // catches a shaderpack toggle, which changes the bake FORMAT (vanilla BLOCK 32B <-> Iris
            // TERRAIN 52B) while frameGpuEffective can stay true; frameBlockIds catches the emissive toggle,
            // whose ids are baked into the buffer -- so all three must be tracked, or a section baked before a
            // flip keeps a stale layout / stale (or missing) mc_Entity ids.
            if (frameGpuEffective != lastGpuEffective || frameIrisGpu != lastIrisGpu
                || frameBlockIds != lastBlockIds) {
                lastGpuEffective = frameGpuEffective;
                lastIrisGpu = frameIrisGpu;
                lastBlockIds = frameBlockIds;
                clear();
            }

            // Camera rotation for this frame. Section-local GPU buffers are drawn with
            // (cameraModelView x shipPose) so they land exactly where the immediate path's
            // camera-relative-baked vertices do (vanilla draws immediate meshes with this matrix).
            // Constant for the whole pass; copy it because we multiply per section.
            final Matrix4f camModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            // Remember it for the deferred flush(es) -- composed onto each section's raw ship pose per pass.
            frameCamModelView.set(camModelView);

            final int minSectionY = level.getMinSectionY();

            // When ship shadows are on, also keep sections/ships the SUN's shadow frustum can see -- not just
            // the camera's. Otherwise a caster behind the camera is culled and its shadow vanishes as you turn.
            // The shadow frustum is ~1 frame stale here (it's set during the shadow pass, which ran before this
            // renderAll), but it's a large box around the player so that lag is harmless.
            final Frustum shadowFrustum =
                (ShipTerrainIrisPipeline.shadowReady() && VSGameConfig.CLIENT.getRenderShipShadows())
                    ? ShipTerrainIrisPipeline.shadowFrustum() : null;

            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
                // Skip the whole ship (and all its per-chunk/-section work) when neither the camera nor (for
                // shadows) the sun can see it.
                final var shipAabb = VectorConversionsMCKt.toMinecraft(ship.getRenderAABB());
                final boolean shipVisible = (frustum == null || frustum.isVisible(shipAabb))
                    || (shadowFrustum != null && shadowFrustum.isVisible(shipAabb));
                if (!shipVisible) {
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

                        // Visible to the camera OR (for shadows) to the sun -- so casters behind the camera
                        // still bake + queue, and thus draw into the shadow map. The sun test uses the public
                        // isVisible(AABB) (not the allocation-free cubeInFrustum invoker) so Iris's shadow
                        // frustum override actually runs; the || short-circuits, so it only fires for
                        // off-camera sections when shadows are on.
                        final boolean visible =
                            isShipSectionVisible(frustum, shipToWorld, chunkX, sectionY, chunkZ)
                                || (shadowFrustum != null
                                    && isShipSectionVisibleShadow(shadowFrustum, shipToWorld,
                                        chunkX, sectionY, chunkZ));

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
                        // Solid/cutout: queue a GPU draw with this section's RAW ship pose. Copy it (scratchPose
                        // is mutated next section); the camera/shadow model-view is composed per pass at flush.
                        if (!cached.gpuMeshes.isEmpty()) {
                            gpuDrawQueue.add(new GpuDrawItem(new Matrix4f(pose.pose()), cached.gpuMeshes));
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
            cacheBackoff = 1; // a clean frame clears the backoff so the next failure retries quickly
        } catch (final Throwable t) {
            cacheCooldown = RETRY_BASE_FRAMES * cacheBackoff;
            cacheBackoff = Math.min(cacheBackoff * 2, RETRY_BACKOFF_MAX);
            LOGGER.error("Ship terrain mesh cache failed; using immediate per-block fallback for {} frames before retrying",
                cacheCooldown, t);
            clear();
        }
    }

    /**
     * Flush this frame's queued ship-terrain GPU draws. Called from MixinFeatureRenderDispatcher at
     * {@code renderAllFeatures} TAIL -- the point where vanilla flushes the immediate ship terrain and
     * Iris has its gbuffer target bound. Drawing earlier (at submitBlockEntities time) lands on the wrong
     * target under shaders. The hook fires in BOTH the main gbuffer pass and Iris's shadow pass; per-pass
     * flags draw the queue at most once per pass, and the queue is retained until the next renderAll so both
     * passes can use it. In the shadow pass the queue is only drawn when Ship Shadows is enabled + registered.
     */
    public void flushDeferredGpuDraws() {
        if (gpuDrawQueue.isEmpty()) {
            return;
        }
        final boolean shadow = ShipTerrainIrisPipeline.isShadowPass();
        if (shadow) {
            // Only draw into the shadow map when shadows are enabled AND our shadow pipeline registered;
            // otherwise skip entirely (drawing with the main program/transform would corrupt the shadow map).
            if (!ShipTerrainIrisPipeline.shadowReady() || !VSGameConfig.CLIENT.getRenderShipShadows()
                || flushedShadow) {
                return;
            }
        } else if (flushedMain) {
            return;
        }
        try {
            flushGpuDraws(shadow);
            gpuBackoff = 1; // a clean flush clears the backoff
            if (shadow) {
                flushedShadow = true;
            } else {
                flushedMain = true;
            }
        } catch (final Throwable t) {
            // A GPU failure parks the persistent-GPU path for a cooldown, degrading to the immediate re-emit
            // (still correct), then retries -- instead of disabling it for the rest of the session.
            gpuPathCooldown = RETRY_BASE_FRAMES * gpuBackoff;
            gpuBackoff = Math.min(gpuBackoff * 2, RETRY_BACKOFF_MAX);
            LOGGER.error("VS ship GPU draw failed; using immediate re-emit for {} frames before retrying",
                gpuPathCooldown, t);
            clear();
            gpuDrawQueue.clear(); // the queue's mesh refs are now invalid; the flags reset next renderAll
        }
        if (gpuFormatMismatch) {
            // Shaderpack toggled: baked bytes no longer match the program -- re-bake fresh.
            gpuFormatMismatch = false;
            clear();
            gpuDrawQueue.clear();
        }
    }

    /**
     * Draw all queued sections' persistent GPU meshes in a single render pass, mirroring vanilla
     * {@code RenderType.draw}: same output-target/override selection, pipeline, default uniforms,
     * textures and index buffer. The per-section model-view is supplied through the DynamicTransforms
     * uniform; pipeline + textures are (re)bound only when the pipeline changes.
     */
    private void flushGpuDraws(final boolean shadow) {
        final DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();

        // The base model-view composed onto each section's raw ship pose: the camera (main pass) or the sun
        // POV (shadow pass). For shadows Iris's GL encoder also redirects this draw onto the shadow framebuffer.
        final Matrix4f baseModelView = shadow ? ShipTerrainIrisPipeline.shadowModelView() : frameCamModelView;
        if (baseModelView == null) {
            return; // shadow pass but the shadow model-view wasn't available this frame
        }

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
            final Matrix4f modelView = new Matrix4f(baseModelView).mul(item.shipPose());
            transforms[i] =
                new DynamicUniforms.Transform(modelView, WHITE, NO_MODEL_OFFSET, IDENTITY_TEX);
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

    /**
     * Section visibility against an Iris SHADOW frustum. Unlike {@link #isShipSectionVisible}, this calls the
     * public {@link Frustum#isVisible} so the shadow frustum's overridden cull runs: Iris's shadow frustums
     * override only {@code isVisible(AABB)} and build the base {@code Frustum} from identity matrices, so the
     * allocation-free {@code cubeInFrustum} invoker would test an identity clip cube and wrongly cull
     * everything. Allocates one AABB; only called (via {@code ||} short-circuit) for sections the camera can't
     * see, while ship shadows are enabled, so the cost is bounded by the shadow distance.
     */
    private boolean isShipSectionVisibleShadow(final Frustum shadowFrustum, final Matrix4dc shipToWorld,
        final int sx, final int sy, final int sz) {
        final double x0 = sx * 16.0;
        final double y0 = sy * 16.0;
        final double z0 = sz * 16.0;
        shipToWorld.transformAab(x0, y0, z0, x0 + 16.0, y0 + 16.0, z0 + 16.0, cullMin, cullMax);
        return shadowFrustum.isVisible(new net.minecraft.world.phys.AABB(
            cullMin.x, cullMin.y, cullMin.z, cullMax.x, cullMax.y, cullMax.z));
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

        // Under shaders, capture the shaderpack block id per vertex so repack can fill mc_Entity (emissive/
        // material). currentBlockId is set once per block below; builderFor wraps each builder in a counter that
        // tags every vertex it emits with it. Off the Iris path (counters == null) builderFor returns the raw
        // builder unchanged -- no overhead and identical behaviour to before.
        final boolean captureBlockIds = frameBlockIds;
        final Map<RenderType, CountingVertexConsumer> counters = captureBlockIds ? new HashMap<>(4) : null;
        final short[] currentBlockId = {-1};
        // Resolve the shaderpack block-id map ONCE per bake (instead of a reflective lookup per block); the
        // per-block id is then a plain map.getInt. Null when not capturing / no pack -> every id stays -1.
        final Object blockIdMap = captureBlockIds ? ShipTerrainIrisPipeline.blockIdMap() : null;

        // Render block models through Fabric's terrain-like model renderer (the FRAPI terrain context)
        // so connected-texture mods like Fusion -- which hook that context, NOT the plain block path
        // used by renderBatched -- apply their connections to ship blocks. Quads are routed per
        // chunk-section layer into the matching moving-block render type's capture buffer.
        // Cast via Object: ModelBlockRenderer implements FabricBlockModelRenderer only through a
        // Fabric API mixin, which the compiler can't see, so a direct cast wouldn't compile.
        final FabricBlockModelRenderer fabricModelRenderer =
            (FabricBlockModelRenderer) (Object) dispatcher.getModelRenderer();
        final BlockVertexConsumerProvider ctmConsumers =
            layer -> builderFor(builders, backings, counters, currentBlockId, movingBlockRenderType(layer));

        try {
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        // Tag this block's vertices (both the fluid and model emit below) with its shaderpack
                        // block id; -1 off the Iris path / no pack / unmapped state (neutral mc_Entity).
                        currentBlockId[0] = ShipTerrainIrisPipeline.shaderBlockId(blockIdMap, state);
                        final BlockPos posWorld = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);

                        final FluidState fluidState = state.getFluidState();
                        if (!fluidState.isEmpty()) {
                            // LiquidBlockRenderer emits section-local [0,16] coords (no PoseStack), which
                            // is exactly our cache space -- feed it straight in.
                            final RenderType rt = fluidRenderType(fluidState);
                            dispatcher.renderLiquid(posWorld, level,
                                builderFor(builders, backings, counters, currentBlockId, rt), state, fluidState);
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
                        final short[] ids = blockIdsFor(counters, type, mesh.drawState().vertexCount());
                        final GpuMesh gm = uploadGpuMesh(type, mesh, ids);
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
    private GpuMesh uploadGpuMesh(final RenderType type, final MeshData mesh, final short[] blockIds) {
        final MeshData.DrawState ds = mesh.drawState();
        if (ds.indexCount() <= 0) {
            return null;
        }
        // Under a shaderpack, repack BLOCK -> Iris TERRAIN (computing the shader extras + mc_Entity block id)
        // so the Iris-assigned pipeline shades the hull; otherwise keep the vanilla moving-block layout.
        final ByteBuffer verts;
        final int vertexSize;
        if (frameIrisGpu) {
            verts = ShipTerrainIrisPipeline.repackBlockToTerrain(
                mesh.vertexBuffer(), ds.vertexCount(), ds.format(), blockIds);
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
        final Map<RenderType, ByteBufferBuilder> backings,
        final Map<RenderType, CountingVertexConsumer> counters, final short[] currentBlockId,
        final RenderType rt) {

        BufferBuilder builder = builders.get(rt);
        if (builder == null) {
            final ByteBufferBuilder backing = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
            // Force the vanilla BLOCK layout so the cached bytes have the fixed offsets we decode.
            builder = new BufferBuilder(backing, rt.mode(), DefaultVertexFormat.BLOCK);
            builders.put(rt, builder);
            backings.put(rt, backing);
        }
        if (counters == null) {
            return builder;
        }
        // Iris path: hand out (and reuse) one counter per render type so every vertex of every block that
        // uses this type is tagged with the current block id, in emission order.
        CountingVertexConsumer counter = counters.get(rt);
        if (counter == null) {
            counter = new CountingVertexConsumer(builder, currentBlockId);
            counters.put(rt, counter);
        }
        return counter;
    }

    /** The per-vertex block-id stream captured for {@code type}, or null when unavailable or length-mismatched. */
    private static short[] blockIdsFor(final Map<RenderType, CountingVertexConsumer> counters,
        final RenderType type, final int vertexCount) {
        if (counters == null) {
            return null;
        }
        final CountingVertexConsumer counter = counters.get(type);
        if (counter == null) {
            return null;
        }
        final short[] ids = counter.ids();
        if (ids.length != vertexCount) {
            // A vertex bypassed the counter (e.g. a future FRAPI change): drop to neutral rather than mislabel.
            if (!warnedBlockIdMismatch) {
                warnedBlockIdMismatch = true;
                LOGGER.warn("VS ship terrain: captured block-id count {} != mesh vertex count {} for {}; "
                    + "mc_Entity left neutral (no emissive id) for affected meshes", ids.length, vertexCount, type);
            }
            return null;
        }
        return ids;
    }

    /** One-shot guard so a block-id / vertex-count desync warns once per session, not per section. */
    private static boolean warnedBlockIdMismatch;

    /**
     * Wraps a bake BufferBuilder to record every vertex's shaderpack block id (read from a shared 1-element
     * holder set once per block) so repack can write mc_Entity. Counting hooks ONLY the two vertex-starting
     * addVertex overloads -- the 3-arg chained form (immediate / liquid path) and the 11-arg form (Indigo /
     * FRAPI / CTM path) -- so each vertex is recorded exactly once, in emission order, which equals the order
     * repack walks the built mesh. Every other VertexConsumer method delegates unchanged (the interface
     * defaults decompose into these two), so the vertex DATA is identical; we only observe the id stream.
     */
    private static final class CountingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final short[] currentBlockId;
        private final ShortArrayList ids = new ShortArrayList();

        CountingVertexConsumer(final VertexConsumer delegate, final short[] currentBlockId) {
            this.delegate = delegate;
            this.currentBlockId = currentBlockId;
        }

        short[] ids() {
            return ids.toShortArray();
        }

        @Override
        public VertexConsumer addVertex(final float x, final float y, final float z) {
            ids.add(currentBlockId[0]);
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public void addVertex(final float x, final float y, final float z, final int color, final float u,
            final float v, final int overlay, final int light, final float nx, final float ny, final float nz) {
            ids.add(currentBlockId[0]);
            delegate.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(final int argb) {
            delegate.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(final float width) {
            delegate.setLineWidth(width);
            return this;
        }

        @Override
        public VertexConsumer setUv(final float u, final float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(final int u, final int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(final int u, final int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(final float x, final float y, final float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
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
            // The pending GPU draw queue (retained across frames for the shadow pass) may reference this
            // just-closed section; drop ONLY its item so a deferred flush can't draw the freed buffer, while
            // leaving the rest of the ship's queued sections intact. (Clearing the WHOLE queue here made the
            // shadow pass -- which draws the PREVIOUS frame's queue -- go empty for a frame on every light
            // update, blinking the whole ship's shadow. Per-item removal keeps the freed-buffer guarantee with
            // no whole-ship blink; only the one changed section's shadow drops, for the existing ~1-frame lag.)
            // Identity match is exact: the queue stores cached.gpuMeshes by reference (see the renderAll enqueue).
            gpuDrawQueue.removeIf(item -> item.meshes() == removed.gpuMeshes);
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
