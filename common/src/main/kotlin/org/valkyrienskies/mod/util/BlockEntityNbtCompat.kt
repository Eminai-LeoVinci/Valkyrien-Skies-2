package org.valkyrienskies.mod.util

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

// 1.21.11 compat: BlockEntity NBT save/load moved from CompoundTag to ValueOutput/ValueInput.
// VS2's block-relocation/assembly code does CompoundTag round trips; these helpers bridge
// the old shape (saveWithId -> CompoundTag, loadWithComponents <- CompoundTag) onto the new API.

fun BlockEntity.saveToTag(registries: HolderLookup.Provider): CompoundTag {
    val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)
    saveWithId(output)
    return output.buildResult()
}

fun BlockEntity.loadFromTag(tag: CompoundTag, registries: HolderLookup.Provider) {
    loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag))
}
