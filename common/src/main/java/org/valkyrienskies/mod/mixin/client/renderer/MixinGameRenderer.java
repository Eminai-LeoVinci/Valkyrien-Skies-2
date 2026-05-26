package org.valkyrienskies.mod.mixin.client.renderer;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.internal.world.VsiClientShipWorld;
import org.valkyrienskies.mod.client.IVSCamera;
import org.valkyrienskies.mod.common.IShipObjectWorldClientProvider;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDragger;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow
    @Final
    private Minecraft minecraft;
    // region Mount the camera to the ship
    @Shadow
    @Final
    private Camera mainCamera;

    @Inject(method = "render", at = @At("HEAD"))
    private void preRender(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        final ClientLevel clientWorld = minecraft.level;
        if (clientWorld != null) {
            // Update ship render transforms
            final VsiClientShipWorld shipWorld =
                IShipObjectWorldClientProvider.class.cast(this.minecraft).getShipObjectWorld();
            if (shipWorld == null) {
                return;
            }

            final float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
            shipWorld.updateRenderTransforms(partialTick);

            // Also update entity last tick positions, so that they interpolate correctly
            for (final Entity entity : clientWorld.entitiesForRendering()) {
                if (!EntityDragger.isDraggable(entity)) {
                    continue;
                }
                // The position we want to render [entity] at for this frame
                // This is set when an entity is mounted to a ship, or an entity is being dragged by a ship
                Vector3dc entityShouldBeHere = null;

                // First, try getting the ship the entity is mounted to, if one exists
                final ShipMountedToData shipMountedToData = VSGameUtilsKt.getShipMountedToData(entity, partialTick);

                if (shipMountedToData != null) {
                    final ClientShip shipMountedTo = (ClientShip) shipMountedToData.getShipMountedTo();
                    // If the entity is mounted to a ship then update their position
                    final Vector3dc passengerPos = shipMountedToData.getMountPosInShip();
                    entityShouldBeHere = shipMountedTo.getRenderTransform().getShipToWorld()
                        .transformPosition(passengerPos, new Vector3d());
                    entity.setPos(entityShouldBeHere.x(), entityShouldBeHere.y(), entityShouldBeHere.z());
                    entity.xo = entityShouldBeHere.x();
                    entity.yo = entityShouldBeHere.y();
                    entity.zo = entityShouldBeHere.z();
                    entity.xOld = entityShouldBeHere.x();
                    entity.yOld = entityShouldBeHere.y();
                    entity.zOld = entityShouldBeHere.z();
                    continue;
                }

                final EntityDraggingInformation entityDraggingInformation =
                    ((IEntityDraggingInformationProvider) entity).getDraggingInformation();
                final Long lastShipStoodOn = entityDraggingInformation.getLastShipStoodOn();
                // Then try getting [entityShouldBeHere] from [entityDraggingInformation]
                if (lastShipStoodOn != null && entityDraggingInformation.isEntityBeingDraggedByAShip()) {
                    final ClientShip shipObject =
                        VSGameUtilsKt.getShipObjectWorld(clientWorld).getLoadedShips().getById(lastShipStoodOn);
                    if (shipObject != null) {
                        entityDraggingInformation.setCachedLastPosition(
                            new Vector3d(entity.xo, entity.yo, entity.zo));
                        entityDraggingInformation.setRestoreCachedLastPosition(true);

                        // The velocity added to the entity by ship dragging
                        final Vector3dc entityAddedVelocity = entityDraggingInformation.getAddedMovementLastTick();

                        // The velocity of the entity before we added ship dragging
                        final double entityMovementX = entity.getX() - entityAddedVelocity.x() - entity.xo;
                        final double entityMovementY = entity.getY() - entityAddedVelocity.y() - entity.yo;
                        final double entityMovementZ = entity.getZ() - entityAddedVelocity.z() - entity.zo;

                        // Without ship dragging, the entity would've been here
                        final Vector3dc entityShouldBeHerePreTransform = new Vector3d(
                            entity.xo + entityMovementX * partialTick,
                            entity.yo + entityMovementY * partialTick,
                            entity.zo + entityMovementZ * partialTick
                        );

                        // Move [entityShouldBeHerePreTransform] with the ship, using the prev transform and the
                        // current render transform
                        entityShouldBeHere = shipObject.getRenderTransform().getShipToWorldMatrix()
                            .transformPosition(
                                shipObject.getPrevTickShipTransform().getWorldToShipMatrix()
                                    .transformPosition(entityShouldBeHerePreTransform, new Vector3d()));
                    }
                }

                // Apply entityShouldBeHere, if its present
                //
                // Also, don't run this if [tickDelta] is too small, getting so close to dividing by 0 could mess
                // something up
                if (entityShouldBeHere != null && partialTick < .99999) {
                    // Update the entity last tick positions such that the entity's render position will be
                    // interpolated to be [entityShouldBeHere]
                    entity.xo = (entityShouldBeHere.x() - (entity.getX() * partialTick)) / (1.0 - partialTick);
                    entity.yo = (entityShouldBeHere.y() - (entity.getY() * partialTick)) / (1.0 - partialTick);
                    entity.zo = (entityShouldBeHere.z() - (entity.getZ() * partialTick)) / (1.0 - partialTick);
                }
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void postRender(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        final ClientLevel clientWorld = minecraft.level;
        if (clientWorld != null) {
            // Restore the entity last tick positions that were replaced during this frame
            for (final Entity entity : clientWorld.entitiesForRendering()) {
                final EntityDraggingInformation vsEntity =
                    ((IEntityDraggingInformationProvider) entity).getDraggingInformation();
                if (vsEntity.getRestoreCachedLastPosition()) {
                    vsEntity.setRestoreCachedLastPosition(false);
                    final Vector3dc cachedLastPosition = vsEntity.getCachedLastPosition();
                    if (cachedLastPosition != null) {
                        entity.xo = cachedLastPosition.x();
                        entity.yo = cachedLastPosition.y();
                        entity.zo = cachedLastPosition.z();
                    } else {
                        System.err.println("How was cachedLastPosition was null?");
                    }
                }
            }
        }
    }

    // Mount the player's camera to the ship they are mounted on.
    //
    // 1.21.11 NOTE: This hook used to be a @WrapOperation on prepareCullFrustum (formerly
    // called from GameRenderer.renderLevel). In 1.21.11, GameRenderer.renderLevel calls
    // extractCamera(f) early -- it snapshots camera.position/rotation/entity into a
    // CameraRenderState that downstream rendering reads from. Any camera mutation that
    // happens AFTER extractCamera (i.e. inside LevelRenderer.renderLevel via the old
    // wrap-op site) is invisible to entity submit, terrain offsets, sky/clouds, etc.
    // The fix is to mutate the camera BEFORE extractCamera runs -- right after the
    // vanilla Camera.setup() call inside updateCamera. Then the snapshot picks up the
    // ship-coupled state and every downstream pass sees a consistent view.
    @Inject(
        method = "updateCamera",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private void valkyrienskies$mountCameraToShip(final DeltaTracker deltaTracker, final CallbackInfo ci) {
        valkyrienskies$applyShipMountCamera(deltaTracker);
    }

    @org.spongepowered.asm.mixin.Unique
    private void valkyrienskies$applyShipMountCamera(final DeltaTracker deltaTracker) {
        ((IVSCamera) this.mainCamera).resetShipMountedRenderTransform();

        final ClientLevel clientLevel = this.minecraft.level;
        final LocalPlayer localPlayer = this.minecraft.player;
        if (clientLevel == null || localPlayer == null) {
            return;
        }

        final float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        final ShipMountedToData shipMountedToData = VSGameUtilsKt.getShipMountedToData(localPlayer, partialTicks);
        if (shipMountedToData == null) {
            return;
        }
        if (localPlayer.getVehicle() == null) {
            return;
        }

        final ClientShip clientShip = (ClientShip) shipMountedToData.getShipMountedTo();
        final Entity cameraEntity =
            this.minecraft.getCameraEntity() == null ? localPlayer : this.minecraft.getCameraEntity();

        // 2.4.80: Standing helm (helm over air -> Eureka standing pose) now uses a
        // custom 3-stage F5 cycle instead of forcing 3rd-person always:
        //   FIRST_PERSON       -> vanilla 1st person (no ship-mount, normal eye view)
        //   THIRD_PERSON_BACK  -> vanilla 3rd person (no ship-mount; player visible
        //                         via the 2.4.77 shouldRender cull bypass)
        //   THIRD_PERSON_FRONT -> ship-mounted 3rd person (pulled-back ship view,
        //                         thirdPersonReverse=false so it's a behind-the-player
        //                         shot, not the mirrored front view)
        // Vanilla F5 then cycles back to FIRST_PERSON.
        //
        // Background: 2.4.73 forced thirdPerson=true here whenever standing, so the
        // user couldn't ever go back to true 1st person at the helm. Now that the
        // 2.4.77 cull bypass makes the player visible in normal vanilla 3rd person,
        // we don't need the force anymore -- we can give the user the full vanilla
        // F5 cycle plus the immersive ship-mounted view as the 3rd option.
        //
        // Sitting helm (chair on a solid block) keeps the original VS2 behavior:
        // always ship-mount, thirdPerson follows the user's F5 state, mirror follows
        // CameraType.isMirrored(). Sitting players never had a visibility problem,
        // and the original behavior preserves ship-rotation coupling in 1st person
        // (useful when the ship rolls/pitches under you).
        final Entity vehicle = localPlayer.getVehicle();
        final boolean standing = vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity
            && vehicle.level().getBlockState(vehicle.blockPosition()).isAir();
        final CameraType cameraType = this.minecraft.options.getCameraType();

        if (standing) {
            // Only the THIRD_PERSON_FRONT slot of the F5 cycle triggers the immersive
            // ship-mounted view. The other two slots fall through to vanilla camera.
            if (cameraType != CameraType.THIRD_PERSON_FRONT) {
                return;
            }
            ((IVSCamera) this.mainCamera).setupWithShipMounted(
                clientLevel,
                cameraEntity,
                true,
                false, // not mirrored: we want a behind-the-player shot in this slot
                partialTicks,
                clientShip,
                shipMountedToData.getMountPosInShip()
            );
            return;
        }

        // Sitting helm (or non-helm passenger): preserve original VS2 behavior.
        final boolean thirdPerson = !cameraType.isFirstPerson();
        ((IVSCamera) this.mainCamera).setupWithShipMounted(
            clientLevel,
            cameraEntity,
            thirdPerson,
            cameraType.isMirrored(),
            partialTicks,
            clientShip,
            shipMountedToData.getMountPosInShip()
        );
    }
    // endregion

    @ModifyReturnValue(method = "getDepthFar", at = @At("RETURN"))
    public float includeShipsIn(final float originalDepth) {
        // 1.21.11 port / Iris: extending the far clip plane to the furthest loaded ship's
        // AABB corner destabilizes the projection matrix and corrupts world terrain under
        // shaders. Skip the extension while we confirm this is the cause.
        if (true) {
            return originalDepth;
        }
        float maxDistance = originalDepth;
        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(Minecraft.getInstance()).getLoadedShips()) {
            Vec3 cameraPos = this.mainCamera.position;
            AABBdc shipAABB = ship.getRenderAABB();
            // find the furthest distance from the camera to the ship AABB corners
            double furthestDistanceSq = 0;
            double dMinX = shipAABB.minX() - cameraPos.x();  
            double dMaxX = shipAABB.maxX() - cameraPos.x();  
            double dMinY = shipAABB.minY() - cameraPos.y();  
            double dMaxY = shipAABB.maxY() - cameraPos.y();  
            double dMinZ = shipAABB.minZ() - cameraPos.z();  
            double dMaxZ = shipAABB.maxZ() - cameraPos.z();  
            double furthestDist = Math.sqrt(Math.max(dMinX * dMinX, dMaxX * dMaxX) + Math.max(dMinY * dMinY, dMaxY * dMaxY) + Math.max(dMinZ * dMinZ, dMaxZ * dMaxZ));  
            maxDistance = Math.max(maxDistance, (float) furthestDist);  
        }

        return maxDistance;
    }
}
