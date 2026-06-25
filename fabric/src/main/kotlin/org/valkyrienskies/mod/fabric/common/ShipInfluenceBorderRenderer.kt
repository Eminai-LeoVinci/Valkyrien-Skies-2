package org.valkyrienskies.mod.fabric.common

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShapeRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import org.valkyrienskies.mod.client.ShipDebugRender
import org.valkyrienskies.mod.common.VSClientGameUtils
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Draws each loaded ship's "influence border" as a thin blue 12-edge wireframe when
 * `/vs influence-border true` is active.
 *
 * The border is the ship-space voxel AABB ([org.valkyrienskies.core.api.ships.ClientShip.getShipAABB]) inflated
 * per-axis by [VSClientConfig.Client.influenceExtendWidth]/Height/Length -- i.e. the EXACT region
 * [org.valkyrienskies.mod.common.util.EntityDragger] tests to decide whether an airborne player (who jumped or
 * walked off a moving ship) is still carried. It is drawn in ship-space and pushed out through the ship's
 * interpolated render transform, so the box is ORIENTED: it rotates/tilts with the hull and always reflects the
 * live influenceExtend values.
 *
 * ## Why a Fabric [WorldRenderEvents] listener (and not the old DebugRenderer mixin)
 * The 1.21.5+ render-pipeline rewrite removed `DebugRenderer.render(PoseStack, ...)`,
 * `LevelRenderer.renderLineBox(...)` and `RenderType.lines()` (the line layer is now
 * [RenderTypes.lines], the box helper is [ShapeRenderer.renderShape]). It also moved the new debug geometry onto
 * a separate "gizmo" collector system ([net.minecraft.gizmos.Gizmos]) whose cuboid primitive is world-AABB only
 * (no rotation), so an oriented hull box would need 12 hand-transformed line gizmos.
 *
 * [WorldRenderEvents.AFTER_ENTITIES] is the least-code, most-robust hook: it fires inside the main level pass with
 * the camera-relative [WorldRenderContext.matrices] already set and [WorldRenderContext.consumers] wired to the
 * level renderer's own `renderBuffers.bufferSource()` (which the renderer flushes), exactly where vanilla entity
 * hitboxes used to draw. That makes it the same Iris-captured immediate pass the previous approach relied on.
 *
 * Iris caveat: under some shaderpacks thin debug lines may draw without depth occlusion against the hull, or look
 * hairline-thin; that is an Iris/line-layer quirk of immediate `lines` geometry, not a bug in this code.
 */
object ShipInfluenceBorderRenderer {

    // ARGB. Slightly-cyan blue (A=FF, R=00, G=73, B=FF) -- matches the old renderLineBox(0f, 0.45f, 1f, 1f) tint.
    private const val BORDER_COLOR_ARGB = 0xFF0073FF.toInt()
    private const val LINE_WIDTH = 1.0f

    /** Register the per-frame draw. Call once from the Fabric client initializer. */
    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register(WorldRenderEvents.AfterEntities { context -> render(context) })
    }

    private fun render(context: WorldRenderContext) {
        if (!ShipDebugRender.influenceBorder) return

        val mc = Minecraft.getInstance()
        val world = mc.level ?: return

        val poseStack: PoseStack = context.matrices() ?: return
        val consumer = context.consumers().getBuffer(RenderTypes.lines())

        // Camera position the world renderer is rendering relative to (matrices() are camera-relative).
        val cam = mc.gameRenderer.mainCamera.position()

        // Per-FACE outward inflation, read LIVE each frame so config edits show immediately. Same face mapping as
        // EntityDragger's carry test: -X=Left/+X=Right, -Y=Bottom/+Y=Top, -Z=Back/+Z=Front.
        val extLeft = VSClientConfig.CLIENT.influenceExtendLeft
        val extRight = VSClientConfig.CLIENT.influenceExtendRight
        val extBottom = VSClientConfig.CLIENT.influenceExtendBottom
        val extTop = VSClientConfig.CLIENT.influenceExtendTop
        val extBack = VSClientConfig.CLIENT.influenceExtendBack
        val extFront = VSClientConfig.CLIENT.influenceExtendFront

        for (ship in world.shipObjectWorld.loadedShips) {
            val shipAABB = ship.shipAABB ?: continue

            // Inflated ship-space influence box (matches EntityDragger per-face; can be ASYMMETRIC, so the box center
            // below is the midpoint of these inflated bounds, not the ship-AABB center).
            val minX = shipAABB.minX() - extLeft
            val maxX = shipAABB.maxX() + extRight
            val minY = shipAABB.minY() - extBottom
            val maxY = shipAABB.maxY() + extTop
            val minZ = shipAABB.minZ() - extBack
            val maxZ = shipAABB.maxZ() + extFront

            // Offset the box by -center so the wireframe is built near the origin (avoids float error far from
            // ship-space 0), then put the center back via the render transform below.
            val cx = (minX + maxX) * 0.5
            val cy = (minY + maxY) * 0.5
            val cz = (minZ + maxZ) * 0.5
            val box = Shapes.create(AABB(minX - cx, minY - cy, minZ - cz, maxX - cx, maxY - cy, maxZ - cz))

            poseStack.pushPose()
            // Last = Last * translate(-cam) * shipToWorld * translate(center): orients the box with the hull and
            // restores the center we subtracted above.
            VSClientGameUtils.transformRenderWithShip(
                ship.renderTransform, poseStack, cx, cy, cz, cam.x, cam.y, cam.z
            )
            ShapeRenderer.renderShape(poseStack, consumer, box, 0.0, 0.0, 0.0, BORDER_COLOR_ARGB, LINE_WIDTH)
            poseStack.popPose()
        }
    }
}
