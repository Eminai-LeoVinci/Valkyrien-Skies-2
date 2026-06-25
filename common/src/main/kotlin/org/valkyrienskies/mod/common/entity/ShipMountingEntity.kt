package org.valkyrienskies.mod.common.entity

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
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
        val lvl = level()
        if (lvl is ServerLevel && passengers.isEmpty()) {
            // Kill this entity if nothing is riding it (1.21.11: kill() now takes a ServerLevel)
            kill(lvl)
            return
        }

        // sendDrivingPacket only does anything client-side; don't pay the ship lookup on the server
        if (lvl.isClientSide && level().getLoadedShipManagingPos(blockPosition()) != null)
            sendDrivingPacket()
    }

    // This is a partial fix for mounting ships that have been deleted
    // TODO: Make a full fix eventually
    override fun getDismountLocationForPassenger(livingEntity: LivingEntity): Vec3 {
        if (level().isBlockInShipyard(position()) && level().getShipManagingPos(position()) == null) {
            // Don't teleport to the ship if we can't find the ship
            return livingEntity.position()
        }
        return super.getDismountLocationForPassenger(livingEntity)
    }

    // Helm seats are the only ShipMountingEntity players ride (Eureka's spawnSeat always marks
    // the seat as the controller), and the rider stands at the wheel -- so place them exactly
    // at the seat origin (deck level) for flush footing rather than the default raised
    // passenger height. This used to gate on an air-block probe, but the seat lives in shipyard
    // space where blockPosition() doesn't map to the helm cleanly, so the probe regressed the
    // rider to a raised, hovering seat. Positioning is unconditional here so it stays identical
    // on client and server (the controller flag is never synced to clients).
    override fun getPassengerRidingPosition(entity: Entity): Vec3 {
        return position()
    }

    // 1.21.11: these now take ValueInput/ValueOutput instead of CompoundTag. Bodies stay empty —
    // ShipMountingEntity has no persistent state worth saving (it's recreated on ship mount).
    override fun readAdditionalSaveData(input: ValueInput) {}

    override fun addAdditionalSaveData(output: ValueOutput) {}

    // 1.21.11: Entity declares abstract hurtServer. ShipMountingEntity is an invisible mount
    // point — it can't be damaged.
    override fun hurtServer(serverLevel: ServerLevel, damageSource: net.minecraft.world.damagesource.DamageSource, f: Float): Boolean = false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}

    override fun remove(removalReason: RemovalReason) {
        if (this.isController && !level().isClientSide)
            (level().getLoadedShipManagingPos(blockPosition()) as LoadedServerShip?)
                ?.setAttachment<SeatedControllingPlayer>(null)
        super.remove(removalReason)
    }

    private fun sendDrivingPacket() {
        if (!level().isClientSide) return

        // Only the seated LOCAL player should report driving input. Every client in render
        // distance ticks this seat entity too, and without this gate each of them would send
        // its own (server-ignored) PacketPlayerDriving every tick for every visible seat.
        if (Minecraft.getInstance().player?.vehicle !== this) return

        // Read movement intent from the local player's ClientInput rather than the
        // W/A/S/D KeyMappings directly. Controlify's joystick mode (and other controller
        // / accessibility mods) writes to ClientInput.moveVector and .keyPresses; it
        // typically does NOT synthesize KeyMapping.isDown events for stick input, so the
        // old `opts.keyUp.isDown` path missed controller stick movement entirely.
        // Keyboard still works unchanged because vanilla KeyboardInput.tick() computes
        // the same moveVector / keyPresses from the W/A/S/D KeyMappings.
        // shipDown / shipCruise stay as KeyMappings so they can be bound to controller
        // buttons via Controlify's normal keybind UI.
        val mc = Minecraft.getInstance()
        val playerInput = mc.player?.input
        val moveVec = playerInput?.moveVector
        val forwardImpulse = moveVec?.y ?: 0f
        val leftImpulse = moveVec?.x ?: 0f
        val jumping = playerInput?.keyPresses?.jump() ?: mc.options.keyJump.isDown
        val sprint = this.controllingPassenger?.isSprinting == true
        // 2.4.80: "up" intent now also accepts the dedicated shipUp keybind so
        // controller users can map a controller button to ascend without
        // overloading vanilla jump. Keyboard players keep SPACE (jump) as before.
        val up = jumping || VSKeyBindings.shipUp.get().isDown
        val down = VSKeyBindings.shipDown.get().isDown
        val cruise = VSKeyBindings.shipCruise.get().isDown

        // Quantize to -1/0/+1 to preserve the binary thrust step the physics is tuned
        // for. Small deadzone guards against analog stick noise near center.
        val deadzone = 0.1f
        val impulse = Vector3f()
        impulse.z = if (forwardImpulse > deadzone) 1.0f else if (forwardImpulse < -deadzone) -1.0f else 0.0f
        impulse.x = if (leftImpulse > deadzone) 1.0f else if (leftImpulse < -deadzone) -1.0f else 0.0f
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

    override fun getAddEntityPacket(serverEntity: ServerEntity): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this, serverEntity)
    }
}
