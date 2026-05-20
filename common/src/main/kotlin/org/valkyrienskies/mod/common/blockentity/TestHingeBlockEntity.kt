package org.valkyrienskies.mod.common.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.internal.joints.VSJointId
import org.valkyrienskies.core.internal.joints.VSRevoluteJoint
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId

@OptIn(PhysTickOnly::class)
class TestHingeBlockEntity(blockPos: BlockPos, blockState: BlockState) : BlockEntity(
    ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE, blockPos, blockState
) {
    var otherHingePos: BlockPos? = null
    @Volatile
    var constraintId: VSJointId? = null

    var hingeConstraint: VSRevoluteJoint? = null

    private var makeConstraint = false

    // 1.21.11: BlockEntity save/load switched to ValueOutput/ValueInput, and VS2's CompoundTag
    // helpers (putVector3d/getQuatd/etc.) have no ValueInput/Output variants yet. TestHinge is a
    // debug block — its joint state is no longer persisted across world save/load in this port.
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
    }

    fun tick() {
        if (!makeConstraint) return
        if (level !is ServerLevel) return
        makeConstraint = false

        val level = level as ServerLevel
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).addJoint(hingeConstraint!!, 4) {
            constraintId = it
        }
    }
}

