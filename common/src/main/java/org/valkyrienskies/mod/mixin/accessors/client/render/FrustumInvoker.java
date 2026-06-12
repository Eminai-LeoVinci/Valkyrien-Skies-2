package org.valkyrienskies.mod.mixin.accessors.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link Frustum}'s private {@code cubeInFrustum(double...)} so hot render paths can
 * frustum-test a box without allocating an {@code AABB} per test. The camera offset is applied
 * inside {@code cubeInFrustum}, so the arguments are the same world-space coords callers would
 * put in an {@code AABB}. Returns the raw {@link org.joml.FrustumIntersection#intersectAab}
 * result: {@code INSIDE} (-2), {@code INTERSECT} (-1), or the index of a culling plane;
 * {@code isVisible(AABB)} is exactly {@code result == INSIDE || result == INTERSECT}.
 */
@Mixin(Frustum.class)
public interface FrustumInvoker {

    @Invoker("cubeInFrustum")
    int valkyrienskies$cubeInFrustum(double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ);
}
