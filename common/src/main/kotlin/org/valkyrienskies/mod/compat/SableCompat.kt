package org.valkyrienskies.mod.compat

import net.minecraft.core.Position
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc

// Stub: real Sable dep has no 1.21.11 build. LoadedMods.sable is always false,
// so these are never invoked at runtime — but call sites still need to compile.
object SableCompat {
    fun sublevelToWorld(level: Level?, pos: Vector3dc, dest: Vector3d): Vector3d =
        dest.set(pos)

    fun sublevelToWorld(level: Level?, pos: Position): Vec3 =
        Vec3(pos.x(), pos.y(), pos.z())

    fun isChunkInSublevel(level: Level?, x: Int, y: Int) = false

    fun isBlockInSublevel(level: Level?, x: Int, y: Int, z: Int) = false
}
