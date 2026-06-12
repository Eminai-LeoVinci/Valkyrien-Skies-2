package org.valkyrienskies.mod.mixin.client.renderer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.client.IVSCamera;
import org.valkyrienskies.mod.client.TransformingVertexConsumer;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.common.assembly.SeamlessChunksManager;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;
import org.valkyrienskies.mod.compat.voxy.VoxyOcclusion;
import org.valkyrienskies.mod.compat.voxy.VoxyPerPixel;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Shadow
    private ClientLevel level;

    @Shadow
    @Final
    private BlockEntityRenderDispatcher blockEntityRenderDispatcher;

    @Unique
    private ShipTransform valkyrienskies$prevShipMountedToTransform = null;

    @Unique
    private boolean valkyrienskies$loggedShipRenderError = false;

    @Unique
    private float valkyrienskies$partialTick = 1.0f;

    // The main camera-pass frustum, captured during pass setup (addMainPass) so it is available when
    // the pass execute lambda later calls submitBlockEntities, where we frustum-cull ship terrain.
    @Unique
    private Frustum valkyrienskies$mainPassFrustum = null;

    // Throttle for the costly full section-occlusion rebuild we trigger while a mounted ship turns:
    // coalesce it to at most once every few frames instead of (often) every frame during rotation.
    @Unique
    private static final long VS_OCCLUSION_INVALIDATE_MIN_GAP_FRAMES = 5L;
    @Unique
    private long valkyrienskies$frameCounter = 0L;
    @Unique
    private long valkyrienskies$lastOcclusionInvalidateFrame = Long.MIN_VALUE;
    @Unique
    private boolean valkyrienskies$occlusionRebuildPending = false;

    /**
     * @reason This mixin forces the game to always render block damage.
     */
    @ModifyExpressionValue(
        method = "renderLevel",
        at = @At(value = "CONSTANT", args = "doubleValue=1024", ordinal = 0)
    )
    private double disableBlockDamageDistanceCheck(final double originalBlockDamageDistanceConstant) {
        return Double.MAX_VALUE;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void preRenderLevel(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker,
        boolean bl, Camera camera, Matrix4f matrix4f, Matrix4f matrix4f2, Matrix4f matrix4f3,
        GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
        this.valkyrienskies$partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        this.valkyrienskies$frameCounter++;
        final ShipTransform shipMountedRenderTransform = ((IVSCamera) camera).getShipMountedRenderTransform();
        if (valkyrienskies$prevShipMountedToTransform != shipMountedRenderTransform) {
            if (valkyrienskies$prevShipMountedToTransform != null && shipMountedRenderTransform != null) {
                // Compute the angle between rotations
                double rotDot = Math.abs(valkyrienskies$prevShipMountedToTransform.getShipToWorldRotation().dot(shipMountedRenderTransform.getShipToWorldRotation()));
                rotDot = Math.min(rotDot, 1.0);
                double angle = 2.0 * Math.acos(rotDot);
                if (Math.toDegrees(angle) > 1.0) {
                    valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                    // Don't rebuild the (whole-world) occlusion graph every frame the ship turns; mark
                    // it pending and let the throttle below coalesce the rebuilds.
                    valkyrienskies$occlusionRebuildPending = true;
                }
            } else {
                // Mounting or dismounting a ship changes visibility drastically and is rare -- rebuild now.
                valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                valkyrienskies$occlusionRebuildPending = false;
                valkyrienskies$lastOcclusionInvalidateFrame = valkyrienskies$frameCounter;
                sectionOcclusionGraph.invalidate();
            }
        }
        // Throttled flush of pending rotation rebuilds: at most one full invalidate every few frames.
        // Each invalidate is a full rebuild reflecting the current camera, so coalescing only makes the
        // occlusion set up to a few frames stale -- imperceptible, but it avoids a per-frame full BFS.
        if (valkyrienskies$occlusionRebuildPending
            && valkyrienskies$frameCounter - valkyrienskies$lastOcclusionInvalidateFrame
                >= VS_OCCLUSION_INVALIDATE_MIN_GAP_FRAMES) {
            valkyrienskies$occlusionRebuildPending = false;
            valkyrienskies$lastOcclusionInvalidateFrame = valkyrienskies$frameCounter;
            sectionOcclusionGraph.invalidate();
        }
    }

    // 1.21.11: ship-coupled camera setup moved from this file to MixinGameRenderer
    // (@Inject after Camera.setup in updateCamera). The renderLevel.prepareCullFrustum
    // site is too late -- GameRenderer.extractCamera has already snapshotted vanilla
    // camera state into CameraRenderState by then, and downstream rendering reads from
    // that snapshot. See MixinGameRenderer.valkyrienskies$mountCameraToShip.

    // Assembly defers client chunk updates while relocating blocks; this drains the resumed
    // queue each frame, otherwise relocated blocks never disappear/appear on the client.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void valkyrienskies$drainSeamlessChunks(final CallbackInfo ci) {
        final SeamlessChunksManager manager = SeamlessChunksManager.get();
        if (manager != null) {
            manager.drainDeferredBatch();
        }
    }

    // Capture the main-pass frustum so ship terrain can be frustum-culled in submitBlockEntities.
    // addMainPass runs during frame-graph setup, before the pass execute lambda (which calls
    // submitBlockEntities) runs, so the field is populated in time. World space; shared with no one.
    @Inject(method = "addMainPass", at = @At("HEAD"), require = 1)
    private void valkyrienskies$captureMainPassFrustum(final FrameGraphBuilder frameGraphBuilder,
        final Frustum frustum, final Matrix4f matrix4f, final GpuBufferSlice gpuBufferSlice,
        final boolean bl, final LevelRenderState levelRenderState, final DeltaTracker deltaTracker,
        final ProfilerFiller profilerFiller, final CallbackInfo ci) {
        this.valkyrienskies$mainPassFrustum = frustum;
    }

    // NOTE (1.21.11): ship terrain is NOT drawn in a dedicated frame-graph pass. A "vs_ships"
    // FramePass was tried and corrupted world terrain under Iris (it perturbs the frame graph
    // Iris rewrites); ship terrain instead draws from submitBlockEntities below, through MC's
    // normal geometry path. valkyrienskies$renderShip is the immediate-mode fallback used there
    // when the mesh cache can't run.

    @Unique
    private void valkyrienskies$renderShip(final ClientShip ship, final ClientLevel clientLevel,
        final BlockRenderDispatcher dispatcher, final MultiBufferSource.BufferSource bufferSource,
        final PoseStack poseStack, final RandomSource random,
        final double camX, final double camY, final double camZ) {

        final ShipTransform renderTransform = ship.getRenderTransform();
        final int minSectionY = clientLevel.getMinSectionY();

        ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
            final LevelChunk chunk = clientLevel.getChunk(chunkX, chunkZ);
            final LevelChunkSection[] sections = chunk.getSections();
            final int baseX = chunkX << 4;
            final int baseZ = chunkZ << 4;
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                final LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }
                final int baseY = (minSectionY + sectionIndex) << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            final BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir()) {
                                continue;
                            }
                            final BlockPos pos = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                            final FluidState fluidState = state.getFluidState();
                            if (!fluidState.isEmpty()) {
                                valkyrienskies$renderShipFluid(state, fluidState, pos,
                                    baseX, baseY, baseZ, clientLevel, renderTransform, dispatcher,
                                    bufferSource, camX, camY, camZ);
                            }
                            if (state.getRenderShape() == RenderShape.MODEL) {
                                valkyrienskies$renderShipBlock(state, pos,
                                    clientLevel, renderTransform, dispatcher, bufferSource, poseStack, random,
                                    camX, camY, camZ);
                            }
                        }
                    }
                }
            }
        });
    }

    @Unique
    private void valkyrienskies$renderShipBlock(final BlockState state, final BlockPos pos,
        final ClientLevel clientLevel, final ShipTransform renderTransform,
        final BlockRenderDispatcher dispatcher, final MultiBufferSource.BufferSource bufferSource,
        final PoseStack poseStack, final RandomSource random,
        final double camX, final double camY, final double camZ) {

        final BlockStateModel model = dispatcher.getBlockModel(state);
        random.setSeed(state.getSeed(pos));
        final List<BlockModelPart> parts = model.collectParts(random);
        if (parts.isEmpty()) {
            return;
        }
        final VertexConsumer consumer = bufferSource.getBuffer(ItemBlockRenderTypes.getMovingBlockRenderType(state));
        poseStack.pushPose();
        VSClientGameUtils.transformRenderWithShip(renderTransform, poseStack,
            pos.getX(), pos.getY(), pos.getZ(), camX, camY, camZ);
        dispatcher.renderBatched(state, pos, clientLevel, poseStack, consumer, true, parts);
        poseStack.popPose();
    }

    // Water/lava blocks (and the fluid in waterlogged blocks) are RenderShape.INVISIBLE, so the
    // block-model path above skips them. Draw them here with the vanilla fluid renderer.
    @Unique
    private void valkyrienskies$renderShipFluid(final BlockState state, final FluidState fluidState,
        final BlockPos pos, final int sectionBaseX, final int sectionBaseY, final int sectionBaseZ,
        final ClientLevel clientLevel, final ShipTransform renderTransform,
        final BlockRenderDispatcher dispatcher, final MultiBufferSource.BufferSource bufferSource,
        final double camX, final double camY, final double camZ) {

        final RenderType renderType = switch (ItemBlockRenderTypes.getRenderLayer(fluidState)) {
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            case TRIPWIRE -> RenderTypes.tripwireMovingBlock();
            default -> RenderTypes.solidMovingBlock();
        };

        // LiquidBlockRenderer writes vertices in section-local space [0,16] and never consults a
        // PoseStack, so feed it through a consumer that maps section-local -> ship render space.
        final Matrix4f fluidMatrix = new Matrix4f();
        VSClientGameUtils.transformRenderWithShip(renderTransform, fluidMatrix,
            sectionBaseX, sectionBaseY, sectionBaseZ, camX, camY, camZ);

        final VertexConsumer consumer =
            new TransformingVertexConsumer(bufferSource.getBuffer(renderType), fluidMatrix);
        dispatcher.renderLiquid(pos, clientLevel, consumer, state, fluidState);
    }

    // Ship block entities (chests, beds, signs, ...) live in shipyard chunks that vanilla never
    // collects for rendering: extractVisibleBlockEntities only walks camera-visible sections, and
    // the shipyard is always far outside the frustum. Extract + submit them here at the tail of
    // submitBlockEntities so they land in the same SubmitNodeStorage that the immediately
    // following renderAllFeatures() call flushes, with the ship's render transform applied.
    @Inject(method = "submitBlockEntities", at = @At("TAIL"), require = 1)
    private void valkyrienskies$submitShipBlockEntities(final PoseStack poseStack,
        final LevelRenderState levelRenderState, final SubmitNodeStorage submitNodeStorage,
        final CallbackInfo ci) {

        final ClientLevel clientLevel = this.level;
        if (clientLevel == null) {
            return;
        }
        try {
            // VS-VOXY-OCCLUSION (2.4.168): read-only whole-ship LOD cull -- now a FALLBACK only. When the
            // per-pixel depth merge (VoxyPerPixel) is live it occludes ships per-pixel, so the cull stands
            // down entirely (skip building the set; ships draw and depth-test against the merged LOD).
            // The cull resumes automatically if per-pixel ever disables itself. Fail-safe: empty set.
            final java.util.Set<Long> vsLodOccluded;
            if (VoxyOcclusion.isPresent() && !VoxyPerPixel.isReplacingCull()) {
                final java.util.Set<Long> occ = new java.util.HashSet<>();
                final LevelRenderer vsSelf = (LevelRenderer) (Object) this;
                for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(clientLevel).getLoadedShips()) {
                    final net.minecraft.world.phys.AABB vsBox =
                        VectorConversionsMCKt.toMinecraft(ship.getRenderAABB());
                    if (VoxyOcclusion.isOccludedByLod(vsSelf, vsBox.minX, vsBox.minY, vsBox.minZ,
                            vsBox.maxX, vsBox.maxY, vsBox.maxZ)) {
                        occ.add(ship.getId());
                    }
                }
                vsLodOccluded = occ;
            } else {
                vsLodOccluded = java.util.Collections.emptySet();
            }

            final CameraRenderState cameraRenderState = levelRenderState.cameraRenderState;
            final Frustum frustum = this.valkyrienskies$mainPassFrustum;
            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(clientLevel).getLoadedShips()) {
                if (vsLodOccluded.contains(ship.getId())) {
                    continue; // fully behind LOD terrain
                }
                // Skip a whole ship's block entities when the ship can't be on screen.
                if (frustum != null && !frustum.isVisible(VectorConversionsMCKt.toMinecraft(ship.getRenderAABB()))) {
                    continue;
                }
                final ShipTransform renderTransform = ship.getRenderTransform();
                final Matrix4dc shipToWorld = renderTransform.getShipToWorld();
                ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                    final LevelChunk chunk = clientLevel.getChunk(chunkX, chunkZ);
                    for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        final BlockPos bePos = blockEntity.getBlockPos();
                        // Cull each BE by its section visibility (the same test the cached terrain uses),
                        // so off-screen ship block entities aren't extracted + submitted every frame.
                        if (!ShipTerrainMeshCache.INSTANCE.isShipSectionVisible(frustum, shipToWorld,
                                bePos.getX() >> 4, bePos.getY() >> 4, bePos.getZ() >> 4)) {
                            continue;
                        }
                        valkyrienskies$submitShipBlockEntity(blockEntity, renderTransform, poseStack,
                            submitNodeStorage, cameraRenderState);
                    }
                });
            }

            // Ship TERRAIN blocks. Two paths:
            //  * Cached fast path (vanilla vertex format / no shaders): bake each ship section once
            //    into reusable meshes and redraw them via RenderType.draw() with the ship transform
            //    on the modelview stack. Avoids re-baking every block model (with ambient occlusion +
            //    light sampling) every frame, which dropped large ships to single-digit FPS.
            //  * Immediate fallback (shaders extend the vertex format, or the cache disabled itself):
            //    the original per-frame bake into the main buffer source.
            // Both draw at this execute-time point (gbuffer bound, right after opaque terrain) through
            // MC's normal geometry path that Iris's gbuffers handle -- NOT Sodium's terrain
            // render-lists, so the see-through holes can't return. Do NOT call endBatch(); the
            // immediate path is flushed by vanilla's following renderAllFeatures()/endBatch().
            final BlockRenderDispatcher dispatcher = this.minecraft.getBlockRenderer();
            final RandomSource random = RandomSource.create();
            final MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();
            final boolean useShipMeshCache = ShipTerrainMeshCache.INSTANCE.canUseCache();
            if (useShipMeshCache) {
                ShipTerrainMeshCache.INSTANCE.renderAll(clientLevel, dispatcher, random, bufferSource,
                    this.valkyrienskies$mainPassFrustum, vsLodOccluded,
                    cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
            } else {
                final PoseStack shipPoseStack = new PoseStack();
                for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(clientLevel).getLoadedShips()) {
                    if (vsLodOccluded.contains(ship.getId())) {
                        continue; // fully behind LOD terrain
                    }
                    valkyrienskies$renderShip(ship, clientLevel, dispatcher, bufferSource, shipPoseStack, random,
                        cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
                }
            }
        } catch (final Throwable t) {
            if (!this.valkyrienskies$loggedShipRenderError) {
                this.valkyrienskies$loggedShipRenderError = true;
                org.slf4j.LoggerFactory.getLogger("valkyrienskies")
                    .error("Ship render failed (logged once)", t);
            }
        }
    }

    // Invalidate a ship section's cached mesh when its blocks change. We hook the PRIVATE
    // setSectionDirty(IIIZ) funnel, not the public setSectionDirty(III): a block edit goes
    // blockChanged -> setBlockDirty -> setSectionDirty(IIIZ) and never touches the public overload
    // (that one is mainly called by light updates). Hooking the public overload meant a broken ship
    // block only re-baked when a neighbour edit happened to trigger a relight -- the "doesn't
    // disappear until I update a block around it" bug. The private overload catches every dirty path
    // (public overload, setBlockDirty, neighbours) and fires after the new state is committed, so the
    // re-bake reads fresh blocks. No-op for normal (non-ship) terrain -- the lookup misses cheaply.
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), require = 1)
    private void valkyrienskies$invalidateShipSectionMesh(final int x, final int y, final int z,
        final boolean reRenderOnMainThread, final CallbackInfo ci) {
        ShipTerrainMeshCache.INSTANCE.invalidateSection(x, y, z);
    }

    @Unique
    private void valkyrienskies$submitShipBlockEntity(final BlockEntity blockEntity,
        final ShipTransform renderTransform, final PoseStack poseStack,
        final SubmitNodeStorage submitNodeStorage, final CameraRenderState cameraRenderState) {

        if (blockEntity.isRemoved() || !blockEntity.hasLevel()
            || !blockEntity.getType().isValid(blockEntity.getBlockState())) {
            return;
        }
        final BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer =
            this.blockEntityRenderDispatcher.getRenderer(blockEntity);
        if (renderer == null) {
            return;
        }
        final BlockEntityRenderState renderState = renderer.createRenderState();
        renderer.extractRenderState(blockEntity, renderState, this.valkyrienskies$partialTick,
            cameraRenderState.pos, null);

        final BlockPos pos = blockEntity.getBlockPos();
        poseStack.pushPose();
        VSClientGameUtils.transformRenderWithShip(renderTransform, poseStack,
            pos.getX(), pos.getY(), pos.getZ(),
            cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
        this.blockEntityRenderDispatcher.submit(renderState, poseStack, submitNodeStorage, cameraRenderState);
        poseStack.popPose();
    }

    // 1.21.5+ split entity rendering into extractVisibleEntities (build the render state) and
    // submitEntities (draw it). extractVisibleEntities drops any entity whose chunk section is
    // not compiled-and-visible -- and a shipyard entity (item frame, painting, ...) physically
    // lives millions of blocks from the camera, so its section never qualifies and its render
    // state is never built. Force shipyard entities past that gate so they reach submitEntities
    // -> EntityRenderDispatcher.submit, where MixinEntityRenderDispatcher re-applies the ship's
    // render transform and draws them on the ship.
    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$keepShipyardEntities(final LevelRenderer self, final BlockPos blockPos,
        final Operation<Boolean> original) {

        if (original.call(self, blockPos)) {
            return true;
        }
        final ClientLevel clientLevel = this.level;
        if (clientLevel == null) {
            return false;
        }
        return VSGameUtilsKt.getLoadedShipManagingPos(clientLevel, blockPos) != null;
    }

    // When the ship-mount camera pulls the camera ~50 blocks back from the player, vanilla's
    // EntityRenderDispatcher.shouldRender returns false for the mounted LocalPlayer because
    // Entity.shouldRender(d,e,f) does a distance cull: dist < bb.getSize() * 64 * viewScale.
    // With default settings that threshold is ~60 blocks; with entityDistanceScaling < 1.0
    // it drops below the 50-block pullback and the standing-mounted player gets culled.
    // Bypass the distance cull while still consulting the frustum directly, so off-screen
    // is still off-screen.
    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceShouldRenderForMountedPlayer(
        final net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
        final Entity entity, final net.minecraft.client.renderer.culling.Frustum frustum,
        final double d, final double e, final double f,
        final Operation<Boolean> original) {
        if (!(entity instanceof net.minecraft.client.player.LocalPlayer)) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        final Entity vehicle = entity.getVehicle();
        // Every ShipMountingEntity rider renders standing. The old isAir(blockPosition()) probe
        // mis-classified a half-slab-floored helm as seated (Eureka drops the seat onto the slab
        // block) and skipped this bypass, so the pulled-back standing player got distance-culled
        // and rendered invisible.
        if (vehicle == null
            || !(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        return frustum.isVisible(entity.getBoundingBox().inflate(0.5));
    }

    // Force camera.isDetached() to true for the standing-mounted LocalPlayer so they survive
    // the 1st-person skip in extractVisibleEntities. This is a backstop in case another mod
    // (Iris shadow pass, etc.) resets the live camera between updateCamera and the cull loop.
    //
    // 2.4.81: bail out when the user is actually in 1st person at a standing helm. Before
    // 2.4.80 the helm always forced 3rd person, so the backstop's lie was harmless. Now that
    // FIRST_PERSON is a legit slot in the F5 cycle, lying here makes vanilla skip the
    // "don't render own entity in 1st person" branch -- and the player's body renders at
    // the camera's eye position, so we look INSIDE our own head model. Respecting the
    // user's cameraType here lets vanilla's own-entity-skip work in 1st person while still
    // backstopping the 3rd-person slots.
    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;isDetached()Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceDetachedForShipMount(final Camera camera,
        final Operation<Boolean> original, @Local final Entity entity) {

        if (original.call(camera)) {
            return true;
        }
        // 2.4.81 1st-person fix: don't override when the user is actually in 1st person.
        if (this.minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (entity != camera.entity()) {
            return false;
        }
        final Entity vehicle = entity.getVehicle();
        if (vehicle == null) {
            return false;
        }
        if (!(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)) {
            return false;
        }
        // Every ShipMountingEntity rider renders standing; the old isAir(blockPosition()) probe
        // mis-classified a half-slab-floored helm as seated and skipped this detached backstop.
        return true;
    }

}
