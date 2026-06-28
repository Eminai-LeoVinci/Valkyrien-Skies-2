package org.valkyrienskies.mod.common.entity

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3f
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.LoadedShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.setAttachment
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.networking.PacketPlayerDriving
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.vsCore

open class ShipMountingEntity(type: EntityType<ShipMountingEntity>, level: Level) :
    Entity(type, level), ShipMountedToDataProvider {
    // Decides if this entity controls the ship it is in.
    // Only needs to be set serverside
    var isController = false

    // Reconnect passenger-seat anchor (server-side). When set, this seat snaps ITSELF to ship.shipToWorld(rel)
    // every tick so it -- and its rider, via positionRider -- is carried with the ship in WORLD space. Spawning
    // in world space (not the shipyard) is what makes the seat actually tick: a shipyard seat is in
    // block-ticking-only chunks and would never tick (which is why the helm drives its seat from the helm BLOCK
    // ENTITY instead). null on a normal helm seat. Mirrored into the synced DRIVE_DATA string so the CLIENT can
    // self-drive too (see clientSelfDrive). Standing up is plain vanilla sneak-dismount (SHIFT), like the helm.
    var driveShipId: Long? = null
    var driveRelPos: Vector3d? = null

    init {
        // Don't prevent blocks colliding with this entity from being placed
        blocksBuilding = false
        // Don't collide with terrain
        noPhysics = true
    }

    override fun tick() {
        super.tick()
        val lvl = level()
        if (lvl is ServerLevel) {
            if (passengers.isEmpty()) {
                // Kill this entity if nothing is riding it (1.21.11: kill() now takes a ServerLevel)
                kill(lvl)
                return
            }
            if (`vs$isPassengerSeat`()) {
                // Reconnect passenger seat: self-drive (carry with the ship), show the prompt, handle standing.
                // It ticks in world space, so -- unlike the helm seat -- it needs no external block-entity driver.
                serverSeatTick(lvl)
                return
            }
        }

        if (lvl.isClientSide) {
            if (`vs$isPassengerSeat`()) {
                // Smooth the seat client-side (see clientSelfDrive). Standing up is plain vanilla sneak-dismount
                // (SHIFT) -- the world seat ticks, so vanilla rideTick handles it; no custom key path needed.
                clientSelfDrive()
            } else if (level().getLoadedShipManagingPos(blockPosition()) != null) {
                sendDrivingPacket()
            }
        }
    }

    // SERVER: per-tick driver for a reconnect passenger seat. Keeps the seat (and rider) glued to the moving ship
    // in world space, re-shows the prompt, and executes the stand on request or when the ship vanishes.
    private fun serverSeatTick(lvl: ServerLevel) {
        val rider = passengers.firstOrNull() ?: return
        val shipId = driveShipId
        val rel = driveRelPos
        if (shipId == null || rel == null) return
        val ship = lvl.shipObjectWorld.allShips.getById(shipId)
        if (ship == null) {
            // Ship unloaded / deleted while seated: force-stand where they are, no fall.
            standRider(lvl, rider, null)
            return
        }
        // Self-drive: move the seat to the ship-relative spot in world space; the rider follows via positionRider.
        // This Y-sync is the anti-fall-death guarantee (mirrors ShipHelmBlockEntity): the eventual stand is a Y
        // no-op.
        val world = ship.shipToWorld.transformPosition(Vector3d(rel))
        snapTo(world.x, world.y, world.z, yRot, xRot)
        // Persistent white prompt above the hotbar (actionbar self-expires, so re-send each tick).
        (rider as? ServerPlayer)?.displayClientMessage(SEATED_PROMPT, true)
    }

    // SERVER: stand the rider up at their exact seated spot with NO fall damage, then remove the seat. [ship] may
    // be null (force-stand because the ship vanished), in which case the rider just stands where they are.
    private fun standRider(lvl: ServerLevel, rider: Entity, ship: Ship?) {
        val rel = driveRelPos
        val target = if (ship != null && rel != null)
            ship.shipToWorld.transformPosition(Vector3d(rel)) else null
        rider.stopRiding()
        if (target != null && rider is ServerPlayer) {
            // A Y no-op thanks to the per-tick sync (so no fall is created); also lands them on the current deck
            // spot for the descended-while-seated case.
            rider.teleportTo(target.x, target.y, target.z)
        }
        rider.deltaMovement = Vec3(rider.deltaMovement.x, 0.0, rider.deltaMovement.z)
        // Re-establish the normal ship carry so the player rides normally AND the next logout writes LastShipId.
        if (ship != null && rel != null && rider is IEntityDraggingInformationProvider) {
            rider.draggingInformation.serverRelativePlayerPosition = Vector3d(rel)
            rider.`vs$dragImmediately`(ship)
        }
        // fallDistance reset LAST, after the rider is at their final Y, so no settling fall registers.
        rider.resetFallDistance()
        kill(lvl)
    }

    // Parse the synced "shipId;relX;relY;relZ" anchor (available on BOTH sides, unlike the server-only
    // driveShipId/driveRelPos fields). Returns null for a helm seat (empty DRIVE_DATA).
    private fun parseDriveData(): Pair<Long, Vector3d>? {
        val data = entityData.get(DRIVE_DATA)
        if (data.isEmpty()) return null
        val parts = data.split(';')
        if (parts.size < 4) return null
        val shipId = parts[0].toLongOrNull() ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        return shipId to Vector3d(x, y, z)
    }

    // CLIENT: keep the seat's own entity position roughly correct (logical position; the actual per-frame RENDER
    // is driven by the ship-mount path -- see provideShipMountedToData + MixinGameRenderer.preRender). Uses the
    // physics transform for a consistent tick-boundary value.
    private fun clientSelfDrive() {
        val drive = parseDriveData() ?: return
        val ship = level().shipObjectWorld.allShips.getById(drive.first) ?: return
        val world = ship.transform.shipToWorld.transformPosition(drive.second)
        setPos(world.x, world.y, world.z)
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

    // A reconnect passenger seat must NOT persist: its drive state isn't saved, so a reloaded one would be a
    // dead, non-driving orphan the player is stuck on. Skip saving it; on relog the player loads un-mounted and
    // the reconnect logic spawns a fresh seat. Helm seats DO persist (Eureka re-adopts them after a reload).
    override fun shouldBeSaved(): Boolean = !`vs$isPassengerSeat`() && super.shouldBeSaved()

    // 1.21.11: Entity declares abstract hurtServer. ShipMountingEntity is an invisible mount
    // point — it can't be damaged.
    override fun hurtServer(serverLevel: ServerLevel, damageSource: net.minecraft.world.damagesource.DamageSource, f: Float): Boolean = false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(IS_PASSENGER_SEAT, false)
        builder.define(DRIVE_DATA, "")
    }

    // Synced so the CLIENT can tell a reconnect PASSENGER seat from a helm seat and render the rider SITTING
    // (arms at side) instead of the helm's standing arms-on-wheel pose. Set once before addFreshEntity, so the
    // initial value rides along with the entity the rider is mounted on.
    fun `vs$isPassengerSeat`(): Boolean = entityData.get(IS_PASSENGER_SEAT)

    // Drive the rider's camera + per-frame render through the ship's render transform -- exactly the path a helm
    // rider uses -- so the seated player is rock-still relative to the ship, not just per-tick-interpolated. For a
    // helm seat (no drive anchor) this replicates the default getShipMountedToData behaviour byte-for-byte, so the
    // helm is unaffected. For a reconnect passenger seat it supplies the ship + ship-relative pos directly, which
    // is what a WORLD-space seat otherwise lacks (its blockPos isn't in the shipyard, so the default lookup fails).
    override fun provideShipMountedToData(passenger: Entity, partialTicks: Float?): ShipMountedToData? {
        // Use the SYNCED anchor (driveShipId/driveRelPos are server-only -- null on the client, which is exactly
        // why the per-frame ship-mount render path never engaged client-side and the wobble persisted).
        val drive = parseDriveData()
        if (drive != null) {
            val ship = level().shipObjectWorld.allShips.getById(drive.first) as? LoadedShip ?: return null
            // Drop the mount point by half a block: the saved anchor is the player's FEET (the correct stand
            // target, used by standRider via driveRelPos), but the Pose.SITTING model is drawn ~half a block
            // above its position, so seating it at the raw feet Y floats it. Lowering the RENDER/eye mount point
            // (not driveRelPos) seats the body flush on the surface and gives a proper lower seated eye. Because
            // the saved Y already encodes the real surface, this stays correct on slabs/stairs too.
            val rel = drive.second
            return ShipMountedToData(ship, Vector3d(rel.x, rel.y - SEAT_RENDER_DROP, rel.z))
        }
        // Helm seat (no drive anchor): the original default behaviour, byte-for-byte.
        val ship = level().getLoadedShipManagingPos(position().toJOML()) ?: return null
        return ShipMountedToData(ship, getPassengerRidingPosition(passenger).toJOML())
    }

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

    companion object {
        private val IS_PASSENGER_SEAT: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ShipMountingEntity::class.java, EntityDataSerializers.BOOLEAN)

        // Synced "shipId;relX;relY;relZ" so the CLIENT can self-drive the seat from the ship's render transform
        // (no LONG/Vector serializer by default; a string carries the full-precision shipyard coords).
        private val DRIVE_DATA: EntityDataAccessor<String> =
            SynchedEntityData.defineId(ShipMountingEntity::class.java, EntityDataSerializers.STRING)

        private val SEATED_PROMPT: Component = Component.literal("You Are Seated: Press SHIFT to stand")

        // The Pose.SITTING model renders ~half a block above its position; drop the seated render/eye mount point
        // by this so the body sits flush on the surface (the logical stand position is unaffected).
        private const val SEAT_RENDER_DROP = 0.5

        /**
         * Create a non-controller reconnect PASSENGER seat at the given WORLD coordinates, carrying the ship anchor
         * (shipId + ship-relative pos) it self-drives from, and add it to [level]; the caller must startRiding() it.
         * Spawned in WORLD space (not the shipyard) so it actually ticks and can self-drive (see [driveShipId]).
         * The [vs$isPassengerSeat] flag is set before addFreshEntity so it rides along with the mount and the client
         * renders a sitting pose from the first frame. Returns null if entity creation fails.
         */
        @JvmStatic
        fun spawnPassengerSeat(
            level: ServerLevel, worldX: Double, worldY: Double, worldZ: Double, yaw: Float, pitch: Float,
            shipId: Long, relX: Double, relY: Double, relZ: Double
        ): ShipMountingEntity? {
            val seat = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level, EntitySpawnReason.MOB_SUMMONED)
                ?: return null
            seat.snapTo(worldX, worldY, worldZ, yaw, pitch)
            seat.isController = false
            seat.driveShipId = shipId
            seat.driveRelPos = Vector3d(relX, relY, relZ)
            seat.entityData.set(IS_PASSENGER_SEAT, true)
            seat.entityData.set(DRIVE_DATA, "$shipId;$relX;$relY;$relZ")
            level.addFreshEntity(seat)
            return seat
        }
    }
}
