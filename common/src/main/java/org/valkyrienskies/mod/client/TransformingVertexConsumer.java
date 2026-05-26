package org.valkyrienskies.mod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A {@link VertexConsumer} that applies a {@link Matrix4f} to every position and normal fed
 * through the raw {@code addVertex(float,float,float)} / {@code setNormal(float,float,float)}
 * calls before forwarding to a delegate.
 *
 * <p>{@link net.minecraft.client.renderer.block.LiquidBlockRenderer} writes fluid vertices in
 * section-local coordinates and never consults a {@link com.mojang.blaze3d.vertex.PoseStack},
 * so this is the only way to draw ship fluids at the ship's render transform.
 */
public class TransformingVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final Matrix4f matrix;
    private final Vector3f scratch = new Vector3f();

    public TransformingVertexConsumer(final VertexConsumer delegate, final Matrix4f matrix) {
        this.delegate = delegate;
        this.matrix = matrix;
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        this.matrix.transformPosition(this.scratch.set(x, y, z));
        this.delegate.addVertex(this.scratch.x, this.scratch.y, this.scratch.z);
        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        this.matrix.transformDirection(this.scratch.set(x, y, z));
        this.delegate.setNormal(this.scratch.x, this.scratch.y, this.scratch.z);
        return this;
    }

    @Override
    public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha) {
        this.delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(final int argb) {
        this.delegate.setColor(argb);
        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(final float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}
