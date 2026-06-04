package org.valkyrienskies.mod.common.config

import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import java.util.function.Consumer
import java.util.function.Supplier

object VSKeyBindings {
    // TODO when making the addon utils for registering... this too
    private val toBeRegistered = mutableListOf<Consumer<Consumer<KeyMapping>>>()

    // 1.21.11: KeyMapping.Category.register(name) throws if the same name is
    // registered twice. Cache one Category per unique name and reuse it so
    // multiple keybindings can share a category.
    private val categories = mutableMapOf<String, KeyMapping.Category>()

    // 2.4.80: shipUp added as a dedicated "ascend while at a helm" keybind so
    // controller users (Controlify) can map a controller button to ship-ascend
    // without also mapping their normal jump. Left UNBOUND by default so it never
    // conflicts with vanilla jump on keyboard -- keyboard players keep using SPACE
    // (vanilla jump) for ascend via the keyPresses.jump() path in ShipMountingEntity.
    val shipUp = register("key.valkyrienskies.ship_up", GLFW.GLFW_KEY_UNKNOWN, "category.valkyrienskies.driving")
    val shipDown = register("key.valkyrienskies.ship_down", GLFW.GLFW_KEY_V, "category.valkyrienskies.driving")
    val shipCruise = register("key.valkyrienskies.ship_cruise", GLFW.GLFW_KEY_C, "category.valkyrienskies.driving")

    // 2.4.116: toggle the persistent-GPU-buffer ship-terrain render path vs the immediate re-emit
    // path (ShipTerrainMeshCache.toggleGpuPath). UNBOUND by default -- bind it in Controls -- so a
    // shaderpack/visual regression on the GPU path can be reverted instantly without a restart.
    val shipGpuRender = register("key.valkyrienskies.ship_gpu_render", GLFW.GLFW_KEY_UNKNOWN, "category.valkyrienskies.rendering")

    // val shipForward = register("key.valkyrienskies.ship_forward", 87, "category.valkyrienskies.driving")
    // val shipBack = register("key.valkyrienskies.ship_back", 83, "category.valkyrienskies.driving")
    // val shipLeft = register("key.valkyrienskies.ship_left", 65, "category.valkyrienskies.driving")
    // val shipRight = register("key.valkyrienskies.ship_right", 68, "category.valkyrienskies.driving")

    private fun register(name: String, keyCode: Int, category: String): Supplier<KeyMapping> =
        object : Supplier<KeyMapping>, Consumer<Consumer<KeyMapping>> {
            lateinit var registered: KeyMapping

            // If this throws error ur on server
            override fun get(): KeyMapping = registered
            override fun accept(t: Consumer<KeyMapping>) {
                // 1.21.11: KeyMapping's 3rd arg is KeyMapping.Category, not String.
                // Register each unique category name exactly once and reuse it.
                val cat = categories.getOrPut(category) { KeyMapping.Category.register(category) }
                registered = KeyMapping(name, keyCode, cat)
                t.accept(registered)
            }
        }.apply { toBeRegistered.add(this) }

    fun clientSetup(registerar: Consumer<KeyMapping>) {
        toBeRegistered.forEach { it.accept(registerar) }
    }

    fun isKeyMappingFromVS2(keyMapping: KeyMapping): Boolean {
        return keyMapping.name.startsWith("key.valkyrienskies")
    }
}
