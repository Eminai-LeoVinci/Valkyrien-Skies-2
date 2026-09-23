package org.valkyrienskies.mod.common.entity

import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.setAttachment
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.networking.PacketPlayerDriving
import org.valkyrienskies.mod.common.vsCore

open class ShipMountingEntity(type: EntityType<ShipMountingEntity>, level: Level) : Entity(type, level) {
    // Decides if this entity controls the ship it is in.
    // Only needs to be set serverside
    var isController = false

    init {
        // Don't prevent blocks colliding with this entity from being placed
        blocksBuilding = false
        // Don't collide with terrain
        noPhysics = true
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && passengers.isEmpty()) {
            // Kill this entity if nothing is riding it
            kill()
            return
        }

        if (level().getLoadedShipManagingPos(blockPosition()) != null)
            sendDrivingPacket()
    }

    // This is a partial fix for mounting ships that have been deleted
    // TODO: Make a full fix eventually
    /*
    override fun positionRider(entity: Entity) {
        if (level().isBlockInShipyard(position()) && level().getShipManagingPos(position()) == null) {
            // Stop rider positioning if we can't find the ship
            entity.removeVehicle()
            return
        }
        super.positionRider(entity)
    }

     */

    // This is a partial fix for mounting ships that have been deleted
    // TODO: Make a full fix eventually
    override fun getDismountLocationForPassenger(livingEntity: LivingEntity): Vec3 {
        if (level().isBlockInShipyard(position()) && level().getShipManagingPos(position()) == null) {
            // Don't teleport to the ship if we can't find the ship
            return livingEntity.position()
        }
        return super.getDismountLocationForPassenger(livingEntity)
    }

    /**
     * Tell everyone tracking this seat that its riders changed.
     *
     * After spawn, [ClientboundSetPassengersPacket] only goes out from `ServerEntity.sendChanges`, which
     * `ChunkMap.tick()` reaches only when the entity changed section or its chunk is in entity-ticking
     * range. A seat parked in the shipyard never changes section, and it is spawned before its rider
     * mounts, so if that tracker does not run on the tick of the mount then the pairing burst everyone
     * already holds says the seat is empty. Nobody watching is told about the mount, their client has no
     * vehicle for that player, and the ship-mount render path cannot engage.
     *
     * Saying it here, where the passenger list actually changes, means the announcement never depends on
     * the tracker ticking, and it covers every seat and every mount path. Seats whose tracker does run
     * already worked; they now get one duplicate packet per mount, which the client applies idempotently.
     */
    private fun announceRiders() {
        val lvl = level() as? ServerLevel ?: return
        lvl.chunkSource.chunkMap.broadcast(this, ClientboundSetPassengersPacket(this))
    }

    override fun addPassenger(passenger: Entity) {
        super.addPassenger(passenger)
        announceRiders()
    }

    override fun removePassenger(passenger: Entity) {
        super.removePassenger(passenger)
        announceRiders()
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {}

    override fun addAdditionalSaveData(compound: CompoundTag) {}

    override fun defineSynchedData() {}

    override fun remove(removalReason: RemovalReason) {
        if (this.isController && !level().isClientSide)
            (level().getLoadedShipManagingPos(blockPosition()) as LoadedServerShip?)
                ?.setAttachment<SeatedControllingPlayer>(null)
        super.remove(removalReason)
    }

    private fun sendDrivingPacket() {
        if (!level().isClientSide) return
        // todo: custom keybinds for going up down and all around but for now lets just use the mc defaults
        val opts = Minecraft.getInstance().options
        val forward = opts.keyUp.isDown
        val backward = opts.keyDown.isDown
        val left = opts.keyLeft.isDown
        val right = opts.keyRight.isDown
        val up = opts.keyJump.isDown
        val sprint = this.controllingPassenger?.isSprinting == true
        val down = VSKeyBindings.shipDown.get().isDown
        val cruise = VSKeyBindings.shipCruise.get().isDown

        val impulse = Vector3f()
        impulse.z = if (forward == backward) 0.0f else if (forward) 1.0f else -1.0f
        impulse.x = if (left == right) 0.0f else if (left) 1.0f else -1.0f
        impulse.y = if (up == down) 0.0f else if (up) 1.0f else -1.0f

        with(vsCore.simplePacketNetworking) {
            PacketPlayerDriving(impulse, sprint, cruise).sendToServer()
        }
    }

    override fun getControllingPassenger(): LivingEntity? {
        return if (isController) {
            this.passengers.getOrNull(0) as? LivingEntity
        } else {
            null
        }
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }
}
