package org.valkyrienskies.mod.mixinducks.client.render;

/**
 * Implemented by {@code ArmorStandRenderState} via mixin. Carries whether the rendered armor stand
 * is currently being dragged/carried by a ship, so {@code ArmorStandModel.setupAnim} can deck-lock
 * the base plate (zero its world-locking {@code -yRot} term) instead of letting it spin relative to
 * the deck. Mirrors {@link ShipMountPoseRenderState}.
 */
public interface ArmorStandBasePlateRenderState {

    boolean vs$isDeckCarried();

    void vs$setDeckCarried(boolean carried);

}
