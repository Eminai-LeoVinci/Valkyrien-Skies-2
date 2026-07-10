package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(PathNavigation.class)
public class MixinPathNavigation {
    @Shadow
    @Final
    protected Level level;

    @Shadow
    @Final
    protected Mob mob;

    // ============================ D2 navigation-target ship-frame re-anchor ============================
    // The A* path Nodes are baked ONCE in WORLD coordinates (the +0.5 deck overlay in MixinPathNavigationRegion
    // reads ship blocks but the Node ints land at WORLD coords; onMoveToCreatePath rewrites the moveTo destination
    // to world via toWorldCoordinates). The Nodes NEVER re-anchor, yet EntityDragger carries the mob's world pos
    // with the ship every tick -- so the mob steers back toward where the deck WAS, drifting toward the ship's
    // rear under forward cruise (front under reverse). FIX: at the instant a path is set for a ship-dragged mob,
    // snapshot the ship's transform (T_build, an immutable per-tick ShipTransform) + the ship id; then each tick
    // map every frozen WORLD node N to its LIVE world position through the CURRENT shipToWorld:
    //     liveWorld = shipNow.shipToWorld( T_build.worldToShip( N ) )
    // so the consumed target tracks the moving deck. Anchored to BUILD time (not prev-tick like EntityDragger's
    // carry) for zero residual at speed. We re-anchor ONLY the consumed target (steer + reached); the A* build,
    // the +0.5 overlay, onMoveToCreatePath, the getGroundY bypass, isStableDestination etc. are untouched.

    // Immutable per-tick snapshot of the ship transform at the moment the current path was set. Null = the current
    // path is NOT a ship-dragged-mob path, so every re-anchor hook below early-returns vanilla behavior.
    @Unique
    private ShipTransform vs$buildTransform = null;

    // Ship id captured alongside vs$buildTransform. Null = no ship path (see above).
    @Unique
    private Long vs$buildShipId = null;

    // A ship-dragged mob's wantedY must not be snapped to the world floor (which sits far below the deck) -- that
    // drags its move target off the ship. Keep the requested Y as-is for mobs currently riding a ship.
    @Inject(method = "getGroundY(Lnet/minecraft/world/phys/Vec3;)D", at = @At("HEAD"), cancellable = true)
    private void vs$bypassGroundSnapForShipMobs(Vec3 vec3, CallbackInfoReturnable<Double> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return;
        }
        if (this.mob instanceof IEntityDraggingInformationProvider dragProvider
            && dragProvider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            cir.setReturnValue(vec3.y);
        }
    }

    @WrapOperation(method = "moveTo(DDDD)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(DDDI)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path onMoveToCreatePath(PathNavigation instance, double d, double e, double f, int i,
        Operation<Path> original) {
        Vec3 transformedPos = VSGameUtilsKt.toWorldCoordinates(this.level, new Vec3(d, e, f));
        return original.call(instance, transformedPos.x, transformedPos.y, transformedPos.z, i);
    }

    @WrapOperation(
        method = "isStableDestination",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState vs$getBlockStateIsNotStable(
        Level instance, BlockPos blockPos, Operation<BlockState> original) {
        BlockState originalState = original.call(instance, blockPos);
        if (originalState.isSolidRender(instance, blockPos)) return originalState;
        // 1.21.1 form of the 1.21.11 positionToNearbyShips loop (the mod.api.ValkyrienSkies helper class does
        // not exist on this branch): resolve the ships intersecting the cell above, map that cell into each
        // ship's local frame, and probe the ship-local block through the same wrapped getBlockState.
        final BlockPos above = blockPos.above();
        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(instance, new AABB(above))) {
            Vector3d candidate = ship.getTransform().getWorldToShip().transformPosition(
                new Vector3d(above.getX(), above.getY(), above.getZ()), new Vector3d());
            Vector3dc upVector = ship.getTransform().getShipToWorld()
                .transformDirection(VectorConversionsMCKt.toJOMLD(Direction.UP.getNormal())).normalize();
            Direction closestDirection = Direction.getNearest(upVector.x(), upVector.y(), upVector.z()).getOpposite();
            BlockPos candidatePos = BlockPos.containing(candidate.x, candidate.y, candidate.z).relative(closestDirection);
            BlockState candidateState = original.call(instance, candidatePos);
            if (candidateState.isSolidRender(instance, candidatePos)) {
                return candidateState;
            }
        }
        return originalState;
    }

    // CAPTURE: the single setter ALL goal paths funnel through after createPath. Snapshot the ship transform +
    // id for a ship-dragged mob, or clear so non-ship paths are byte-for-byte untouched. HEAD so the snapshot is
    // taken before the path is installed (the path object itself is the same frozen-world Nodes either way).
    @Inject(method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z", at = @At("HEAD"))
    private void vs$captureBuildTransform(final Path path, final double speed,
        final CallbackInfoReturnable<Boolean> cir) {
        final Long shipId = vs$shipDraggingMob();
        if (shipId == null) {
            // Not a ship-dragged mob (or no path) -> clear so every re-anchor hook early-returns vanilla.
            vs$buildShipId = null;
            vs$buildTransform = null;
            return;
        }
        final Ship ship = VSGameUtilsKt.getAllShips(this.level).getById(shipId);
        if (ship == null) {
            vs$buildShipId = null;
            vs$buildTransform = null;
            return;
        }
        // ship.getTransform() is an immutable per-tick ShipTransform snapshot (the ship swaps in a new instance
        // each tick: transform <- next, prevTickTransform <- old). Holding the reference is safe.
        vs$buildTransform = ship.getTransform();
        vs$buildShipId = shipId;
    }

    // RE-ANCHOR STEER: tick()'s tail feeds path.getNextEntityPos(mob) (frozen world node + bbWidth 0.5) straight
    // into getMoveControl().setWantedPosition. There are two getNextEntityPos(Entity) calls in tick() (an early
    // jump-Y check + the steer); both are the same node-pos accessor and both must read the live deck target, so
    // we wrap them un-ordinaled. Scoped to tick() only -- shouldTargetNextNodeInDirection's call is untouched.
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/Path;getNextEntityPos(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 vs$reanchorSteer(final Path instance, final net.minecraft.world.entity.Entity entity,
        final Operation<Vec3> original) {
        final Vec3 worldNode = original.call(instance, entity);
        if (!vs$shipPathStillActive()) {
            return worldNode;
        }
        final Vec3 live = vs$reanchor(worldNode);
        return live;
    }

    // RE-ANCHOR ADVANCE/REACHED: followThePath() compares the LIVE mob world pos (mob.getX/Y/Z) against the raw
    // frozen node ints (+0.5 on X/Z) to decide path.advance(). There is NO wrappable node accessor in that compare
    // (it reads Path.getNextNodePos() -> a BlockPos and uses raw Vec3i ints), so instead we wrap the mob.getX/Y/Z
    // reads to map the mob's live world pos BACK into the frozen build-time WORLD frame:
    //     buildFrameMob = T_build.shipToWorld( shipNow.worldToShip( liveMobWorld ) )
    // i.e. the inverse of vs$reanchor. This keeps mob-vs-node in ONE consistent frame (the frozen build frame),
    // so a mob that has physically reached the live deck target registers arrival instead of re-pathing forever.
    @WrapOperation(
        method = "followThePath",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;getX()D")
    )
    private double vs$reanchorReachedX(final Mob mobInstance, final Operation<Double> original) {
        final double world = original.call(mobInstance);
        if (!vs$shipPathStillActive()) {
            return world;
        }
        return vs$mobInBuildFrame().x;
    }

    @WrapOperation(
        method = "followThePath",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;getY()D")
    )
    private double vs$reanchorReachedY(final Mob mobInstance, final Operation<Double> original) {
        final double world = original.call(mobInstance);
        if (!vs$shipPathStillActive()) {
            return world;
        }
        return vs$mobInBuildFrame().y;
    }

    @WrapOperation(
        method = "followThePath",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;getZ()D")
    )
    private double vs$reanchorReachedZ(final Mob mobInstance, final Operation<Double> original) {
        final double world = original.call(mobInstance);
        if (!vs$shipPathStillActive()) {
            return world;
        }
        return vs$mobInBuildFrame().z;
    }

    // ---- helpers ----

    // Returns the ship id if this.mob is a non-local mob currently being dragged by a ship, else null. Matches the
    // predicate the rest of the file/D1 uses, and is the strict gate: when this is null nothing re-anchors.
    @Unique
    private Long vs$shipDraggingMob() {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return null;
        }
        if (this.mob instanceof IEntityDraggingInformationProvider p
            && p.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return p.getDraggingInformation().getLastShipStoodOn();
        }
        return null;
    }

    // True only when a ship build-snapshot exists AND the mob is STILL dragged by that same ship. If the mob left
    // the ship (or hopped ships) the steer/reached hooks fall back to vanilla world-frame behavior.
    @Unique
    private boolean vs$shipPathStillActive() {
        if (vs$buildShipId == null || vs$buildTransform == null) {
            return false;
        }
        final Long now = vs$shipDraggingMob();
        return now != null && now.equals(vs$buildShipId);
    }

    // Map a frozen build-time WORLD node to its LIVE world position: local = T_build.worldToShip(N); then
    // live = shipNow.shipToWorld(local). X/Z track the deck; keep Y from the node (the getGroundY bypass already
    // returns the requested Y unchanged for ship mobs, so we re-anchor Y too for a consistent deck-local target
    // -- it does not fight the bypass, which only suppresses world-floor snapping).
    @Unique
    private Vec3 vs$reanchor(final Vec3 worldNode) {
        final Ship shipNow = VSGameUtilsKt.getAllShips(this.level).getById(vs$buildShipId);
        if (shipNow == null) {
            return worldNode;
        }
        final Matrix4dc worldToShipBuild = vs$buildTransform.getWorldToShip();
        final Matrix4dc shipToWorldNow = shipNow.getTransform().getShipToWorld();
        final Vector3d local = worldToShipBuild.transformPosition(
            new Vector3d(worldNode.x, worldNode.y, worldNode.z), new Vector3d());
        final Vector3d live = shipToWorldNow.transformPosition(local, new Vector3d());
        return new Vec3(live.x, live.y, live.z);
    }

    // Map the mob's LIVE world pos into the frozen build-time WORLD frame (inverse of vs$reanchor): local =
    // shipNow.worldToShip(mobWorld); buildFrame = T_build.shipToWorld(local). Used by the reached-test so the mob
    // and the frozen node are compared in the SAME frame.
    @Unique
    private Vec3 vs$mobInBuildFrame() {
        final Ship shipNow = VSGameUtilsKt.getAllShips(this.level).getById(vs$buildShipId);
        if (shipNow == null) {
            return new Vec3(this.mob.getX(), this.mob.getY(), this.mob.getZ());
        }
        final Matrix4dc worldToShipNow = shipNow.getTransform().getWorldToShip();
        final Matrix4dc shipToWorldBuild = vs$buildTransform.getShipToWorld();
        final Vector3d local = worldToShipNow.transformPosition(
            new Vector3d(this.mob.getX(), this.mob.getY(), this.mob.getZ()), new Vector3d());
        final Vector3d build = shipToWorldBuild.transformPosition(local, new Vector3d());
        return new Vec3(build.x, build.y, build.z);
    }
}
