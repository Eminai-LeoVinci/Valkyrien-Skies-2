package org.valkyrienskies.mod.client

import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity

// 1.21.11: EntityRenderer is now EntityRenderer<T, S extends EntityRenderState> and renders
// off an extracted render state. EmptyRenderer draws nothing, so a bare EntityRenderState
// and a no-op createRenderState() is all that's needed.
class EmptyRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<Entity, EntityRenderState>(context) {

    override fun createRenderState(): EntityRenderState = EntityRenderState()
}
