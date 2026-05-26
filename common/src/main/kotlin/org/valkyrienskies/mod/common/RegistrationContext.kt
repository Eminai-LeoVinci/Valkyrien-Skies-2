package org.valkyrienskies.mod.common

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * 1.21.2+ registration shim, shared by VS2 and Eureka.
 *
 * Since MC 1.21.2, [BlockBehaviour.Properties] / [Item.Properties] must carry their registry
 * id (a [ResourceKey], applied via setId) BEFORE the Block / Item is constructed:
 * BlockBehaviour's constructor calls effectiveDrops(), which fails with "Block id not set"
 * when the id is missing.
 *
 * Both mods build their Properties deep inside block-class constructors, where the registry
 * id is not yet known. This shim threads the id through a [ThreadLocal] that the registration
 * code installs immediately before construction; [blockProps] / [itemProps] read it back.
 *
 * When no id is in scope (e.g. a stray construction such as a codec decode), [blockProps]
 * falls back to noLootTable() so construction still succeeds.
 */
object RegistrationContext {
    /** The registry id of the block/item currently being constructed, if any. */
    @JvmField
    val currentId: ThreadLocal<ResourceKey<*>> = ThreadLocal()
}

/** Registry key for a block named [name] in mod [modId]. */
fun blockKey(modId: String, name: String): ResourceKey<Block> =
    ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(modId, name))

/** Registry key for an item named [name] in mod [modId]. */
fun itemKey(modId: String, name: String): ResourceKey<Item> =
    ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(modId, name))

/**
 * Runs [builder] with [id] installed as the current registration id, so [blockProps] /
 * [itemProps] calls inside it pick the id up. Restores the previous value afterwards.
 */
fun <T> withRegistryId(id: ResourceKey<*>, builder: () -> T): T {
    val ctx = RegistrationContext.currentId
    val prev = ctx.get()
    ctx.set(id)
    try {
        return builder()
    } finally {
        if (prev != null) ctx.set(prev) else ctx.remove()
    }
}

/**
 * Fresh [BlockBehaviour.Properties] carrying the id from the current [RegistrationContext].
 * Falls back to noLootTable() when no id is in scope.
 */
@Suppress("UNCHECKED_CAST")
fun blockProps(): BlockBehaviour.Properties {
    val key = RegistrationContext.currentId.get()
    val props = BlockBehaviour.Properties.of()
    if (key != null) {
        props.setId(key as ResourceKey<Block>)
    } else {
        props.noLootTable()
    }
    return props
}

/** Fresh [Item.Properties] carrying the id from the current [RegistrationContext], if any. */
@Suppress("UNCHECKED_CAST")
fun itemProps(): Item.Properties {
    val key = RegistrationContext.currentId.get()
    val props = Item.Properties()
    if (key != null) {
        props.setId(key as ResourceKey<Item>)
    }
    return props
}
