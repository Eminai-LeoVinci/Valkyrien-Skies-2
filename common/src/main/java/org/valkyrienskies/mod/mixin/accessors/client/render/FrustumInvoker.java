package org.valkyrienskies.mod.mixin.accessors.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link Frustum}'s private {@code cubeInFrustum(double...)} so hot render paths can
 * frustum-test a box without allocating an {@code AABB} per test. The public
 * {@code isVisible(AABB)} is a plain pass-through to this method (verified against the 1.21.11
 * bytecode: the camera offset is applied inside {@code cubeInFrustum}, not by the caller), so the
 * arguments are the same world-space coords.
 */
@Mixin(Frustum.class)
public interface FrustumInvoker {

    @Invoker("cubeInFrustum")
    boolean valkyrienskies$cubeInFrustum(double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ);
}
