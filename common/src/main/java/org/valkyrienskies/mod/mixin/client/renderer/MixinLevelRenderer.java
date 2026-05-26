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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
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
import org.valkyrienskies.mod.common.assembly.SeamlessChunksManager;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;

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
        final ShipTransform shipMountedRenderTransform = ((IVSCamera) camera).getShipMountedRenderTransform();
        if (valkyrienskies$prevShipMountedToTransform != shipMountedRenderTransform) {
            if (valkyrienskies$prevShipMountedToTransform != null && shipMountedRenderTransform != null) {
                // Compute the angle between rotations
                double rotDot = Math.abs(valkyrienskies$prevShipMountedToTransform.getShipToWorldRotation().dot(shipMountedRenderTransform.getShipToWorldRotation()));
                rotDot = Math.min(rotDot, 1.0);
                double angle = 2.0 * Math.acos(rotDot);
                if (Math.toDegrees(angle) > 1.0) {
                    valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                    sectionOcclusionGraph.invalidate();
                }
            } else {
                valkyrienskies$prevShipMountedToTransform = shipMountedRenderTransform;
                sectionOcclusionGraph.invalidate();
            }
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

    // Ship blocks live in far-away shipyard chunks and must be re-drawn each frame at the ship's
    // render transform. The 1.21.11 section renderer is GPU-driven and cannot be given a per-ship
    // transform, so ship blocks are drawn in immediate mode in a dedicated frame-graph pass
    // inserted right after the main terrain pass.
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/culling/Frustum;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;ZLnet/minecraft/client/renderer/state/LevelRenderState;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private void valkyrienskies$addShipRenderPass(final GraphicsResourceAllocator graphicsResourceAllocator,
        final DeltaTracker deltaTracker, final boolean bl, final Camera camera, final Matrix4f matrix4f,
        final Matrix4f matrix4f2, final Matrix4f matrix4f3, final GpuBufferSlice gpuBufferSlice,
        final Vector4f vector4f, final boolean bl2, final CallbackInfo ci,
        @Local final FrameGraphBuilder frameGraphBuilder) {

        // 1.21.11 port / Iris diagnostic: the vs_ships frame-graph pass is the prime suspect for
        // the world-terrain corruption under shaders -- it perturbs the frame graph that Iris
        // rewrites. Skip adding the pass entirely to confirm. Ship BLOCKS will not render while
        // this is active (ship block entities still will).
        if (true) {
            return;
        }

        final FramePass framePass = frameGraphBuilder.addPass("vs_ships");
        this.targets.main = framePass.readsAndWrites(this.targets.main);
        framePass.executes(() -> valkyrienskies$renderShipBlocks(camera, gpuBufferSlice));
    }

    @Unique
    private void valkyrienskies$renderShipBlocks(final Camera camera, final GpuBufferSlice shaderFog) {
        final ClientLevel clientLevel = this.level;
        if (clientLevel == null) {
            return;
        }
        try {
            RenderSystem.setShaderFog(shaderFog);
            final Vec3 camPos = camera.position();
            final BlockRenderDispatcher dispatcher = this.minecraft.getBlockRenderer();
            final MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();
            final PoseStack poseStack = new PoseStack();
            final RandomSource random = RandomSource.create();

            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(clientLevel).getLoadedShips()) {
                valkyrienskies$renderShip(ship, clientLevel, dispatcher, bufferSource, poseStack, random,
                    camPos.x, camPos.y, camPos.z);
            }
            bufferSource.endBatch();
        } catch (final Throwable t) {
            if (!this.valkyrienskies$loggedShipRenderError) {
                this.valkyrienskies$loggedShipRenderError = true;
                t.printStackTrace();
            }
        }
    }

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
            final CameraRenderState cameraRenderState = levelRenderState.cameraRenderState;
            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(clientLevel).getLoadedShips()) {
                final ShipTransform renderTransform = ship.getRenderTransform();
                ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                    final LevelChunk chunk = clientLevel.getChunk(chunkX, chunkZ);
                    for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        valkyrienskies$submitShipBlockEntity(blockEntity, renderTransform, poseStack,
                            submitNodeStorage, cameraRenderState);
                    }
                });
            }
        } catch (final Throwable t) {
            if (!this.valkyrienskies$loggedShipRenderError) {
                this.valkyrienskies$loggedShipRenderError = true;
                t.printStackTrace();
            }
        }
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
        if (vehicle == null
            || !(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)
            || !vehicle.level().getBlockState(vehicle.blockPosition()).isAir()) {
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
        if (!vehicle.level().getBlockState(vehicle.blockPosition()).isAir()) {
            return false;
        }
        return true;
    }

    /**
     * This mixin makes block damage render on ships.
     */
    /*
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void renderBlockDamage(final BlockRenderDispatcher blockRenderManager, final BlockState state,
        final BlockPos blockPos, final BlockAndTintGetter blockRenderWorld, final PoseStack matrix,
        final VertexConsumer vertexConsumer, final Operation<Void> renderBreakingTexture) {


        final ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, blockPos);
        if (ship != null) {
            // Remove the vanilla render transform
            matrixStack.popPose();

            // Add the VS render transform
            matrixStack.pushPose();

            final ShipTransform renderTransform = ship.getRenderTransform();
            final Vec3 cameraPos = methodCamera.getPosition();

            transformRenderWithShip(renderTransform, matrixStack, blockPos, cameraPos.x, cameraPos.y, cameraPos.z);

            final Matrix3f newNormalMatrix = matrixStack.last().normal().copy();
            final Matrix4f newModelMatrix = matrixStack.last().pose().copy();

            // Then update the matrices in vertexConsumer (I'm guessing vertexConsumer is responsible for mapping
            // textures, so we need to update its matrices otherwise the block damage texture looks wrong)
            final SheetedDecalTextureGenerator newVertexConsumer =
                new SheetedDecalTextureGenerator(((OverlayVertexConsumerAccessor) vertexConsumer).getDelegate(),
                    newModelMatrix, newNormalMatrix);

            // Finally, invoke the render damage function.
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix,
                newVertexConsumer);
        } else {
            // Vanilla behavior
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix, vertexConsumer);
        }
    }

     */

}
