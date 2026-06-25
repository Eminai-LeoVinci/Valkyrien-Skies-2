package org.valkyrienskies.mod.common.util

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.mod.api.toJOML
import org.valkyrienskies.mod.api.toMinecraft
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.entity.handling.VSEntityManager
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityLerper.yawToWorld
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object EntityDragger {
    // How much we decay the addedMovement each tick after player hasn't collided with a ship for at least 10 ticks.
    private const val ADDED_MOVEMENT_DECAY = 0.9


    /**
     * Drag these entities with the ship they're standing on.
     */
    fun dragEntitiesWithShips(entities: Iterable<Entity>, preTick: Boolean = false) {
        for (entity in entities) {
            val entityDraggingInformation = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: continue

            // === 2.4.115: Region-based gliding carry control ===
            // Asymmetric carry semantics for elytra-gliding entities:
            //   - Acquisition: ONLY via the collision-mixin path (EntityShipCollisionUtils
            //     stamps lastShipStoodOn when entity physically collides with a ship voxel).
            //     Vanilla MC ends fall-flying on solid contact, so the natural rejection
            //     case (mid-air glider flying up through the ship's volume) almost never
            //     triggers acquisition here.
            //   - Retention while inside ship AABB: pin ticksSinceStoodOnShip = 0 each tick
            //     so isEntityBeingDraggedByAShip() stays true and the position-drag (Mech 1)
            //     continues to carry the glider with the ship's per-tick translation. The
            //     velocity-push branch (Mech 2) is bypassed because the timer never expires.
            //   - Release on exit: clear lastShipStoodOn and zero the buffered movement
            //     immediately when the glider leaves the AABB. Prevents the velocity-push
            //     branch from firing at all (no leftover buffered movement to push).
            val isGliding = (entity as? LivingEntity)?.isFallFlying == true
            // Airborne carry: a player who jumps/walks off a moving ship (free-fall), OR who is creative-flying, keeps
            // being carried within the ship's footprint just like an elytra glider, instead of dropping out after the
            // 25-tick (~2s) stood-on-ship timer expires. All three reuse the identical border-bounded pin/release path
            // below; only the entry gate differs. The position-drag is a frame-re-anchoring teleport that never writes
            // deltaMovement, so it COMPOSES with active flight/glide input rather than fighting it.
            val isFreefallingPlayer = entity is Player && !entity.onGround() && !entity.abilities.flying
            // Creative/spectator flight: carried while actively flying inside the border (the player took off from the
            // deck, so lastShipStoodOn is still stamped). Mirrors the proven glider path verbatim.
            val isFlyingPlayer = entity is Player && entity.abilities.flying
            var carriedShipId = entityDraggingInformation.lastShipStoodOn

            // Cancel-on-contact + grounded keep-alive, via the RAW hull footprint (robust where onPos is NOT). The
            // support-block probe behind entity.onPos mis-resolves on thin/edge blocks — half-slabs, stairs, fences,
            // shelves, ship edges — landing in the empty part of the cell, so it reads "non-ship" even while the player
            // stands ON the ship (and acquisition likewise stops re-stamping → the old slow drop). So instead transform
            // the player into ship-space and test the RAW hull AABB: inside it = genuinely on/over the hull (deck, slab,
            // stairs, fence, edge, on-ship pool) whatever the sub-block shape; outside it = genuinely off the hull
            // (shore / world water). shipAABB is AABBic (inclusive block indices) so the hull spans continuous [min,
            // max+1] on X/Z; the Y band is generous (feet on the top deck sit at maxY+1) yet still excludes a player on
            // world ground well below a hovering ship.
            if (carriedShipId != null && entity is Player) {
                val hullShip = entity.level().shipObjectWorld.allShips.getById(carriedShipId)
                val hullAABB = hullShip?.shipAABB
                val onCarriedHull = if (hullShip != null && hullAABB != null) {
                    val wp = entity.position()
                    val lp = hullShip.worldToShip.transformPosition(wp.x, wp.y, wp.z, Vector3d())
                    lp.x >= hullAABB.minX() && lp.x <= hullAABB.maxX() + 1.0 &&
                        lp.z >= hullAABB.minZ() && lp.z <= hullAABB.maxZ() + 1.0 &&
                        lp.y >= hullAABB.minY() - 1.0 && lp.y <= hullAABB.maxY() + 2.0
                } else {
                    false
                }
                if (!onCarriedHull && (entity.onGround() || entity.isInWater())) {
                    // Grounded or in water OFF the hull = on shore / in world water → release instantly. (Airborne
                    // players off the hull but still inside the inflated border are NOT released here; the gate below
                    // carries them.) Null the local carriedShipId too so that gate can't re-pin a stale id this tick.
                    entityDraggingInformation.lastShipStoodOn = null
                    entityDraggingInformation.addedMovementLastTick = Vector3d()
                    entityDraggingInformation.addedYawRotLastTick = 0.0
                    carriedShipId = null
                } else if (entity.onGround() && onCarriedHull) {
                    // Standing on the hull (incl. thin/edge blocks where onPos mis-probes) → keep the carry alive by
                    // pinning the stood-on-ship timer, so the 25-tick expiry can never drop a player who is truly aboard.
                    entityDraggingInformation.ticksSinceStoodOnShip = 0
                }
            }

            if ((isGliding || isFreefallingPlayer || isFlyingPlayer) && carriedShipId != null) {
                val carriedShip = entity.level().shipObjectWorld.allShips.getById(carriedShipId)
                val shipAABB = carriedShip?.shipAABB
                val insideAABB = if (carriedShip != null && shipAABB != null) {
                    val worldPos = entity.position()
                    val shipLocalPos = carriedShip.worldToShip.transformPosition(
                        worldPos.x, worldPos.y, worldPos.z, Vector3d()
                    )
                    // Influence border = the ship AABB inflated outward per-FACE by the configured ship-space blocks
                    // on each of the six faces. Ship-space axis -> face: -X = Left, +X = Right; -Y = Bottom, +Y = Top
                    // (gravity-aligned, certain); -Z = Back, +Z = Front (Left/Right & Front/Back are nominal axis-sign
                    // labels — swap the config fields if in-world they read reversed). 0 on a face = the raw ship AABB
                    // on that face (boundary-inclusive).
                    val extLeft = VSClientConfig.CLIENT.influenceExtendLeft
                    val extRight = VSClientConfig.CLIENT.influenceExtendRight
                    val extBottom = VSClientConfig.CLIENT.influenceExtendBottom
                    val extTop = VSClientConfig.CLIENT.influenceExtendTop
                    val extBack = VSClientConfig.CLIENT.influenceExtendBack
                    val extFront = VSClientConfig.CLIENT.influenceExtendFront
                    shipLocalPos.x >= shipAABB.minX() - extLeft && shipLocalPos.x <= shipAABB.maxX() + extRight &&
                        shipLocalPos.y >= shipAABB.minY() - extBottom && shipLocalPos.y <= shipAABB.maxY() + extTop &&
                        shipLocalPos.z >= shipAABB.minZ() - extBack && shipLocalPos.z <= shipAABB.maxZ() + extFront
                } else {
                    false
                }
                if (insideAABB) {
                    // Pin counter at 0: bypass 25-tick expiry, keep Mech 1 active.
                    entityDraggingInformation.ticksSinceStoodOnShip = 0
                } else {
                    // Left the influence border — release carry immediately.
                    entityDraggingInformation.lastShipStoodOn = null
                    entityDraggingInformation.addedMovementLastTick = Vector3d()
                    entityDraggingInformation.addedYawRotLastTick = 0.0
                }
            }

            var dragTheEntity = false
            var addedMovement: Vector3dc? = null
            var addedYRot = 0.0

            val shipDraggingEntity = entityDraggingInformation.lastShipStoodOn


            // Only drag entities that aren't mounted to vehicles
            if (shipDraggingEntity != null && entity.vehicle == null && isDraggable(entity)) {
                if (entityDraggingInformation.isEntityBeingDraggedByAShip()) {
                    // Compute how much we should drag the entity
                    val shipData = entity.level().shipObjectWorld.allShips.getById(shipDraggingEntity)
                    if (shipData != null) {
                        dragTheEntity = true
                        val entityReferencePos: Vector3dc = if (preTick) {
                            Vector3d(entity.x, entity.y, entity.z)
                        } else {
                            Vector3d(entity.xo, entity.yo, entity.zo)
                        }

                        val referenceTransform = shipData.transform

                        // region Compute position dragging
                        val newPosIdeal: Vector3dc = referenceTransform.shipToWorld.transformPosition(
                            shipData.prevTickTransform.worldToShip.transformPosition(
                                Vector3d(entityReferencePos)
                            )
                        )
                        addedMovement = newPosIdeal.sub(entityReferencePos, Vector3d())
                        // endregion

                        // region Compute look dragging
                        // world yRot(deg) <-> ship-relative yaw(rad) in the carry's look-vector convention.
                        fun relYawFromWorld(worldYawDeg: Double, t: ShipTransform): Double {
                            val lw = Vector3d(sin(-Math.toRadians(worldYawDeg)), 0.0, cos(-Math.toRadians(worldYawDeg)))
                            val ls = t.worldToShip.transformDirection(lw, Vector3d())
                            return -atan2(ls.x(), ls.z())
                        }
                        fun worldYawFromRel(relYaw: Double, t: ShipTransform): Double {
                            val ls = Vector3d(sin(-relYaw), 0.0, cos(-relYaw))
                            val lw = t.shipToWorld.transformDirection(ls, Vector3d())
                            return -Math.toDegrees(atan2(lw.x(), lw.z()))
                        }

                        if (entity is ArmorStand) {
                            // ABSOLUTE wobble-immune yaw deck-lock. The incremental look reconstruction
                            // (else-branch) feeds a parked ship's pitch/roll idle-wobble (the Eureka
                            // stabilizer's PD restoring torque) into the yaw and drifts; a static-yaw armor
                            // stand is the SOLE writer of its client yaw (the EntityLerper yaw-lerp is
                            // skipped, the server yaw is never applied), so that per-tick variation IS the
                            // visible jitter -- harsh once the ship is off the N/S axis. Lock the stand's
                            // ship-relative facing once, then each tick SET the world yaw from it via the
                            // CURRENT transform: a parked ship -> CONSTANT yaw at ANY heading; a real turn
                            // tracks the ship exactly (proven in sim). Scoped to ArmorStand so mobs/players
                            // keep the 3D look/pitch reconstruction they re-derive each tick anyway.
                            val info = entityDraggingInformation
                            val stored = info.draggedArmorStandRelYaw
                            // re-aim detect: the stand's yRot diverged from what we SET last tick (==
                            // reconstruct with the prev transform) -> a player/command rotated it -> re-lock.
                            var reAimDiff = if (stored != null)
                                entity.yRot.toDouble() - worldYawFromRel(stored, shipData.prevTickTransform) else 0.0
                            while (reAimDiff <= -180.0) reAimDiff += 360.0
                            while (reAimDiff > 180.0) reAimDiff -= 360.0
                            if (stored == null || info.changedShipLastTick || abs(reAimDiff) > 1.0) {
                                info.draggedArmorStandRelYaw = relYawFromWorld(entity.yRot.toDouble(), referenceTransform)
                            }
                            val targetWorldYaw = worldYawFromRel(info.draggedArmorStandRelYaw!!, referenceTransform)
                            var delta = targetWorldYaw - entity.yRot.toDouble()
                            while (delta <= -180.0) delta += 360.0
                            while (delta > 180.0) delta -= 360.0
                            addedYRot = delta
                        } else {
                            val yViewRot = entity.yRot.toDouble()

                            // Get the y-look vector of the entity only using y-rotation, ignore x-rotation
                            val entityLookYawOnly =
                                Vector3d(sin(-Math.toRadians(yViewRot)), 0.0, cos(-Math.toRadians(yViewRot)))

                            val newLookIdeal = referenceTransform.shipToWorld.transformDirection(
                                shipData.prevTickTransform.worldToShip.transformDirection(
                                    entityLookYawOnly
                                )
                            )

                            // Get the X and Y rotation from [newLookIdeal]
                            val newXRot = asin(-newLookIdeal.y())
                            val xRotCos = cos(newXRot)
                            val newYRot = -atan2(newLookIdeal.x() / xRotCos, newLookIdeal.z() / xRotCos)

                            // The Y rotation of the entity before dragging
                            var entityYRotCorrected = entity.yRot % 360.0
                            // Limit [entityYRotCorrected] to be between -180 to 180 degrees
                            if (entityYRotCorrected <= -180.0) entityYRotCorrected += 360.0
                            if (entityYRotCorrected >= 180.0) entityYRotCorrected -= 360.0

                            // The Y rotation of the entity after dragging
                            val newYRotAsDegrees = Math.toDegrees(newYRot)
                            // Limit [addedYRotFromDragging] to be between -180 to 180 degrees
                            var addedYRotFromDragging = newYRotAsDegrees - entityYRotCorrected
                            if (addedYRotFromDragging <= -180.0) addedYRotFromDragging += 360.0
                            if (addedYRotFromDragging >= 180.0) addedYRotFromDragging -= 360.0

                            addedYRot = addedYRotFromDragging
                        }
                        // endregion
                    }
                } else {
                    addedMovement = Vector3d(entityDraggingInformation.addedMovementLastTick)
                    addedYRot = 0.0
                }
            }

            if (dragTheEntity && addedMovement != null && addedMovement.isFinite && addedYRot.isFinite()) {
                // TODO: Do collision on [addedMovement], as currently this can push players into
                //       blocks
                // Apply [addedMovement]
                val newBB = entity.boundingBox.move(addedMovement.toMinecraft())
                entity.boundingBox = newBB
                entity.setPos(
                    entity.x + addedMovement.x(),
                    entity.y + addedMovement.y(),
                    entity.z + addedMovement.z()
                )

                if(entityDraggingInformation.shouldImpulseMovement && (!entity.level().isClientSide || entity is LocalPlayer)) { //This is the first Tick on the ship. Also, should push the entity in server side only and propagate the result.
                    val acceleration = Vector3d(entityDraggingInformation.addedMovementLastTick) // if it was on a different ship last tick, consider that too.
                        .sub(addedMovement) // relative velocity to current ship.
                    entity.push(acceleration.x, acceleration.y, acceleration.z)
                }

                entityDraggingInformation.addedMovementLastTick = addedMovement

                // Apply [addedYRot] (finiteness already checked by the enclosing guard)
                run {
                    if (!entity.level().isClientSide()) {
                        if (entity !is ServerPlayer) {
                            entity.yRot = ((entity.yRot + addedYRot.toFloat()) + 360f) % 360f
                            entity.yHeadRot = ((entity.yHeadRot + addedYRot.toFloat()) + 360f) % 360f
                            if(entity is LivingEntity) {
                                entity.yBodyRot = ((entity.yBodyRot + addedYRot.toFloat()) + 360f) % 360f
                            }
                        } else {
                            entity.yRot = Mth.wrapDegrees(entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = Mth.wrapDegrees(entity.yHeadRot + addedYRot.toFloat())
                            entity.yBodyRot = Mth.wrapDegrees(entity.yBodyRot + addedYRot.toFloat())
                        }
                    } else {
                        if (!entity.isLocalInstanceAuthoritative && entity !is Player) {
                            entity.yRot = Mth.wrapDegrees(entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = Mth.wrapDegrees(entity.yHeadRot + addedYRot.toFloat())
                            if(entity is LivingEntity) {
                                entity.yBodyRot = Mth.wrapDegrees(entity.yBodyRot + addedYRot.toFloat())
                            }
                        } else {
                            entity.yRot = (entity.yRot + addedYRot.toFloat())
                            entity.yHeadRot = (entity.yHeadRot + addedYRot.toFloat())
                            if(entity is LivingEntity) {
                                entity.yBodyRot = (entity.yBodyRot + addedYRot.toFloat())
                            }
                        }
                    }

                    // An armor stand has a STATIC, deck-locked yaw (the ArmorStand branch above holds it
                    // constant) -- it has no legitimate per-tick yaw interpolation. But while the carry runs
                    // VS suppresses the vanilla yRotO<-yRot sync (so vanilla won't fight the carry), and the
                    // carry writes only the CURRENT yaw, never its previous-tick partner. On a freshly-spawned
                    // stand yRotO/yHeadRotO/yBodyRotO default to 0, so the renderer draws
                    // rotLerp(partialTick, 0, yRot) every carry-ON frame -- a 0deg<->yRot yaw-spin that a relog
                    // (which deserializes the O-fields == the live fields) silently masked. Snap the previous-
                    // tick partners to the freshly-set values so there is nothing left to interpolate.
                    // ArmorStand-only: real mobs/players keep their genuine yaw interpolation.
                    if (entity is ArmorStand) {
                        entity.yRotO = entity.yRot
                        entity.yHeadRotO = entity.yHeadRot
                        entity.yBodyRotO = entity.yBodyRot
                    }

                    entityDraggingInformation.addedYawRotLastTick = addedYRot
                }
            } else if ((!entity.level().isClientSide || entity is LocalPlayer) && entityDraggingInformation.addedMovementLastTick.lengthSquared() > 1e-6) {
                entity.push(entityDraggingInformation.addedMovementLastTick.x(),
                    entityDraggingInformation.addedMovementLastTick.y(),
                    entityDraggingInformation.addedMovementLastTick.z())
                entityDraggingInformation.addedMovementLastTick = Vector3d()
                entityDraggingInformation.addedYawRotLastTick = 0.0
            }
            entityDraggingInformation.ticksSinceStoodOnShip++
            entityDraggingInformation.mountedToEntity = entity.vehicle != null
        }
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns worldspace entity position.
     */
    fun Entity.serversidePosition(): Vec3 {
        if (this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.bestRelativeEntityPosition() != null) {
                return this.draggingInformation.bestRelativeEntityPosition()!!.toMinecraft()
            }
        }
        return this.position()
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns worldspace eye position.
     */
    fun Entity.serversideEyePosition(): Vec3 {
        if (this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.bestRelativeEntityPosition() != null) {
                return this.draggingInformation.bestRelativeEntityPosition()!!.add(0.0, this.getEyeHeight(pose).toDouble(), 0.0,
                    Vector3d())!!.toMinecraft()
            }
        }
        return this.eyePosition
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerYaw] set. If it does, returns that, which is in ship space; otherwise, returns worldspace eye rotation.
     */
    fun Entity.serversideEyeRotation(): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return this.draggingInformation.serverRelativePlayerYaw!! * 180.0 / Math.PI
            }
        }
        return this.yRot.toDouble()
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerPosition] set. If it does, returns that, which is in ship space; otherwise, returns a default value.
     */
    fun Entity.serversideEyePositionOrDefault(default: Vec3): Vec3 {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerPosition != null) {
                return this.draggingInformation.serverRelativePlayerPosition!!.toMinecraft()
            }
        }
        return default
    }

    /**
     * Checks if the entity is a ServerPlayer and has a [serverRelativePlayerYaw] set. If it does, returns that, which is in ship space; otherwise, returns a default value.
     */
    fun Entity.serversideEyeRotationOrDefault(default: Double): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return Math.toDegrees(this.draggingInformation.serverRelativePlayerYaw!!)
            }
        }
        return default
    }


    fun Entity.serversideWorldEyeRotationOrDefault(ship: Ship, default: Double): Double {
        if (this is ServerPlayer && this is IEntityDraggingInformationProvider && this.draggingInformation.isEntityBeingDraggedByAShip()) {
            if (this.draggingInformation.serverRelativePlayerYaw != null) {
                return yawToWorld(ship, this.draggingInformation.serverRelativePlayerYaw!!)
            }
        }
        return default
    }

    @JvmStatic
    fun backOff(vec3: Vec3, ship: Ship, player: Player, cLevel: Level): Vec3 {
        var transformedVec = ship.worldToShip.transformDirection(vec3.toJOML(), Vector3d())
        var d = transformedVec.x
        var e = transformedVec.y
        var f = transformedVec.z

        while (d != 0.0 && !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST)) {
            if (d < 0.025 && d >= -0.025) {
                d = 0.0
            } else if (d > 0.0) {
                d -= 0.025
            } else {
                d += 0.025
            }
        }

        while (f != 0.0 && !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH)) {
            if (f < 0.025 && f >= -0.025) {
                f = 0.0
            } else if (f > 0.0) {
                f -= 0.025
            } else {
                f += 0.025
            }
        }

        while (e != 0.0 && !isValidWalkablePosition(cLevel, ship, player, e, Direction.UP)) {
            if (e < 0.025 && e >= -0.025) {
                e = 0.0
            } else if (e > 0.0) {
                e -= 0.025
            } else {
                e += 0.025
            }
        }

        while (d != 0.0 && f != 0.0 && e != 0.0 &&
            !isValidWalkablePosition(cLevel, ship, player, d, Direction.EAST) &&
            !isValidWalkablePosition(cLevel, ship, player, f, Direction.SOUTH) &&
            !isValidWalkablePosition(cLevel, ship, player, e, Direction.UP)) {
            if (d < 0.025 && d >= -0.025) {
                d = 0.0
            } else if (d > 0.0) {
                d -= 0.025
            } else {
                d += 0.025
            }

            if (f < 0.025 && f >= -0.025) {
                f = 0.0
            } else if (f > 0.0) {
                f -= 0.025
            } else {
                f += 0.025
            }

            if (e < 0.025 && e >= -0.025) {
                e = 0.0
            } else if (e > 0.0) {
                e -= 0.025
            } else {
                e += 0.025
            }
        }

        val motionLength = sqrt(d * d + e * e + f * f)
        return ship.shipToWorld.transformDirection(Vector3d(d, e, f)).normalize().mul(motionLength).toMinecraft()
    }

    private fun isValidWalkablePosition(
        level: Level, ship: Ship, player: Player, step: Double, dir: Direction
    ): Boolean {
        // todo: eventually figure this out
        // val downDirInShip: Vector3dc? = ship.worldToShip.transformDirection(
        //     Vector3d(0.0, -1.0, 0.0), Vector3d()
        // ).normalize().mul(player.maxUpStep().toDouble())
        //
        // val potentialMovement = ship.transform.shipToWorld.transformDirection(Vector3d(dir.step())).normalize().mul(step).add(downDirInShip)
        //
        // val shipPolygons = EntityShipCollisionUtils.getShipPolygonsCollidingWithEntity(
        //     player, potentialMovement.toMinecraft(), player.getBoundingBox().inflate(-0.1), level
        // )
        // val noWorldCollision = level.noCollision(player, player.getBoundingBox().move(potentialMovement.toMinecraft()))
        //
        // val noCollision = noWorldCollision && shipPolygons.isEmpty()
        val clipContext = stepTowardsEdge(level, ship, player, step, dir)
        val result = level.clip(clipContext)
        if (result.type != HitResult.Type.BLOCK) {
            return false
        }
        //get the normal of the hit face in worldspace
        val hitShip = level.getLoadedShipManagingPos(result.blockPos)
        if (hitShip != null) {
            val hitSide = result.direction.unitVec3i.toJOMLD()
            val upDir: Vector3dc = Vector3d(0.0, 1.0, 0.0)
            val hitSideInWorld = hitShip.shipToWorld.transformDirection(hitSide, Vector3d()).normalize()
            // If the hit side is not facing up, we can't walk on it
            val dot = hitSideInWorld.dot(upDir)
            if (dot < 0.5 && dot > 0.001) {
                return false
            }
        }

        return true
    }

    private fun stepTowardsEdge(
        level: Level?, ship: Ship, player: Player, step: Double, dir: Direction
    ): ClipContext {
        val potentialPosition = player.position().add(ship.transform.shipToWorld.transformDirection(Vector3d(dir.step())).normalize().mul(step).toMinecraft())
        val downDirInShip: Vector3dc? = ship.worldToShip.transformDirection(
            Vector3d(0.0, -1.0, 0.0), Vector3d()
        ).normalize().mul(player.maxUpStep().toDouble())

        val maxDistPos: Vector3dc = potentialPosition.toJOML().add(downDirInShip, Vector3d())

        return ClipContext(
            potentialPosition, maxDistPos.toMinecraft(), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, player
        )
    }
    /**
     * Check if the given entity should be dragged. Shipyard entities and ones marked as non-draggable return false.
     */
    @JvmStatic
    fun isDraggable(entity: Entity): Boolean {
        return !VSEntityManager.isShipyardEntity(entity) && entity is IEntityDraggingInformationProvider && (entity as IEntityDraggingInformationProvider).`vs$shouldDrag`()
    }
}
