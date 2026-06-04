package org.valkyrienskies.mod.mixinducks.client.render;

/**
 * Implemented by Model via mixin. Carries whether THIS model instance is currently rendering a
 * player standing at a ship helm. Set on the shared PlayerModel during setupAnim (which has the
 * render state) and read at renderToBuffer HEAD to apply the standing pose.
 *
 * <p>The pose must be applied at renderToBuffer rather than setupAnim because the deferred render
 * pipeline (ModelFeatureRenderer.renderModel) calls model.setupAnim again right before
 * renderToBuffer, and setupAnim starts with resetPose() which wipes every part's rotation. Writes
 * made in setupAnim's TAIL therefore survive only until the next resetPose; writes made at
 * renderToBuffer HEAD are the last thing before geometry is built and cannot be wiped.
 */
public interface ShipMountPoseModel {

    boolean vs$isShipMountStanding();

    void vs$setShipMountStanding(boolean standing);

}
