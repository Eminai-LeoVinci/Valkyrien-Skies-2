package org.valkyrienskies.mod.mixinducks.client.render;

/**
 * Implemented by AvatarRenderState via mixin. Carries whether the rendered player is steering
 * a ship mount standing up (a Eureka helm) rather than seated. Set during render-state
 * extraction and read by PlayerModel.setupAnim to swap the seated pose for a standing one.
 */
public interface ShipMountPoseRenderState {

    boolean vs$isShipMountStanding();

    void vs$setShipMountStanding(boolean standing);

}
