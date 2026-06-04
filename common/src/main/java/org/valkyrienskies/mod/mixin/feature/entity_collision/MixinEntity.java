package org.valkyrienskies.mod.mixin.feature.entity_collision;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

@Mixin(Entity.class)
public abstract class MixinEntity implements IEntityDraggingInformationProvider {

    // region collision

    /**
     * Cancel movement of entities that are colliding with unloaded ships
     */
    @Inject(
        at = @At("HEAD"),
        method = "move",
        cancellable = true
    )
    private void beforeMove(final MoverType type, final Vec3 pos, final CallbackInfo ci) {
        if (EntityShipCollisionUtils.isCollidingWithUnloadedShips(Entity.class.cast(this))) {
            ci.cancel();
        }
    }

    /**
     * Allows entities to collide with ships by modifying the movement vector.
     */
    @WrapOperation(
        method = "move",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    public Vec3 collideWithShips(final Entity entity, Vec3 movement, final Operation<Vec3> collide) {
        // Guard against runaway/corrupt collision sweeps before they reach the block-collision
        // sweeper. See vs$sanitizeCollisionMovement: a huge or non-finite movement vector makes
        // the sweeper walk an unbounded number of chunk columns, and in shipyard space each column
        // lazily allocates an empty chunk -- that is the multi-second client freeze.
        movement = vs$sanitizeCollisionMovement(entity, movement);

        final AABB box = this.getBoundingBox();
        final Vec3 requestedMovement = movement;
        movement = EntityShipCollisionUtils.INSTANCE
            .adjustEntityMovementForShipCollisions(entity, movement, box, this.level);
        final Vec3 collisionResultWithWorld = collide.call(entity, movement);

        // 2.4.86: stash the original requested movement and the actual adjusted movement so
        // valkyrienskies$projectVelocityAlongCollisionNormal can compute the collision-normal
        // projection without depending on MixinExtras @Local ordinal resolution at the
        // setDeltaMovement(DDD)V call site. The 2.4.84 @Local-based form may have been
        // resolving to the wrong Vec3 in 1.21.11's move() bytecode (additional Vec3 locals
        // from refactoring), so the fix never fired. These fields are populated once per
        // move() call, then read once at the immediate setDeltaMovement call later in the
        // same move(); no cross-tick lifetime needed.
        this.vs$lastMoveRequestedMovement = requestedMovement;
        this.vs$lastMoveAdjustedMovement = collisionResultWithWorld;

        if (collisionResultWithWorld.distanceToSqr(movement) > 1e-12) {
            // We collided with the world? Set the dragging ship to null.
            final EntityDraggingInformation entityDraggingInformation = getDraggingInformation();
            entityDraggingInformation.setLastShipStoodOn(null);
            entityDraggingInformation.setAddedMovementLastTick(new Vector3d());
            entityDraggingInformation.setAddedYawRotLastTick(0.0);
        }

        return collisionResultWithWorld;
    }

    @Unique
    private Vec3 vs$lastMoveRequestedMovement = Vec3.ZERO;
    @Unique
    private Vec3 vs$lastMoveAdjustedMovement = Vec3.ZERO;

    @Unique
    private static final double VS_MAX_SANE_COLLISION_MOVE = 256.0;
    @Unique
    private static final Logger VS_LOGGER = LogUtils.getLogger();
    @Unique
    private static volatile boolean vs$warnedRunawayMove = false;

    /**
     * Defensive clamp for the movement vector fed to {@code Entity.collide}. A non-finite or
     * absurdly large movement makes the vanilla/Lithium block-collision sweeper iterate every
     * chunk column the swept AABB touches. In VS2 shipyard space {@code getChunk} lazily allocates
     * an empty {@code LevelChunk} per column (see
     * {@code MixinClientChunkCache#getOrCreateEmptyChunk}), so a single corrupt move can spawn
     * millions of chunk objects and hang the render thread for tens of seconds (the client ticks
     * entities on the render thread).
     *
     * <p>The trigger observed in the wild is a firework rocket stranded client-side in the
     * shipyard: the server never removes it, so the client keeps applying its 1.15x/tick speed-up
     * until {@code deltaMovement} explodes. When we detect that, we stop the entity dead for this
     * move and zero its velocity so it cannot immediately re-explode next tick; the sweep then
     * covers only the entity's own bounding box (one chunk). The threshold (256 blocks/tick) is
     * ~25x faster than anything legitimate gameplay produces, so this never fires for normal
     * entities.
     */
    @Unique
    private Vec3 vs$sanitizeCollisionMovement(final Entity entity, final Vec3 movement) {
        final boolean finite =
            Double.isFinite(movement.x) && Double.isFinite(movement.y) && Double.isFinite(movement.z);
        if (finite && movement.lengthSqr() <= VS_MAX_SANE_COLLISION_MOVE * VS_MAX_SANE_COLLISION_MOVE) {
            return movement;
        }
        // Runaway/corrupt movement: halt it and kill the velocity driving it so the swept AABB
        // collapses to the entity's own box instead of spanning the shipyard.
        entity.setDeltaMovement(Vec3.ZERO);
        if (!vs$warnedRunawayMove) {
            vs$warnedRunawayMove = true;
            VS_LOGGER.warn(
                "VS2: clamped a runaway entity collision movement ({}) to prevent a shipyard "
                    + "chunk-allocation freeze; entity={} pos=({}, {}, {})",
                movement, entity.getType(), entity.getX(), entity.getY(), entity.getZ());
        }
        return Vec3.ZERO;
    }

    /**
     * 2.4.84 / 2.4.86: Re-port of upstream PR #1712 — non-axis-aligned collision velocity response.
     *
     * <p>Replaces vanilla's axis-zeroing horizontal-collision response with one that removes only
     * the velocity component PARALLEL TO THE ACTUAL COLLISION NORMAL. Vanilla zeroes the world X
     * or Z axis when it hit something on that axis; that's correct for axis-aligned walls but
     * WRONG for the slanted ship hulls produced by a rotated ship. The leftover velocity along
     * the unzeroed axis still has a component pointing INTO the hull, and consecutive ticks of
     * vanilla retrying that movement against the slanted surface can accumulate velocity. Combined
     * with elytra's per-tick forward thrust, this produces the runaway speed boost users see when
     * brushing a non-axis-aligned ship.
     *
     * <p>2.4.86 change: 2.4.84 used MixinExtras {@code @Local(ordinal = 1)} to fetch the
     * {@code movementAdjustedForCollisions} Vec3 at the {@code setDeltaMovement(DDD)V} call site.
     * 1.21.11's {@code Entity.move()} was refactored (extra {@code Movement} record creation,
     * {@code addMovementThisTick} call) and may have introduced additional Vec3 locals
     * ({@code vec33}, {@code vec34}, {@code vec35}) that shift the ordinal away from {@code vec32}
     * — that would silently misresolve the @Local and either no-op or apply wrong math. To
     * eliminate that fragility, the 2.4.86 form stashes the requested and adjusted movement in
     * {@code @Unique} fields during {@link #collideWithShips} (which always runs immediately
     * before setDeltaMovement in the same move() call) and reads them back here. No @Local on
     * the target's local variable table needed.
     *
     * <p>Why this also fixes elytra: with elytra (fall-flying), each tick vanilla collide()
     * pushes the player out of the ship — produces a small
     * {@code movementAdjustedForCollisions - movement} delta = the collision normal. Vanilla's
     * old axis-zeroing then preserved velocity along the other world axis even though part of
     * that axis projected INTO the hull, so {@code deltaMovement.dot(normal)} stayed nonzero
     * across ticks. Elytra physics then multiplied this in the player's look direction. This
     * mixin replaces that with: remove only the normal-direction component of velocity.
     * Velocity along the hull surface (tangential) is preserved, but velocity INTO the hull is
     * gone — exactly the physics intuition.
     */
    @WrapOperation(
        method = "move",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"
        )
    )
    private void valkyrienskies$projectVelocityAlongCollisionNormal(
        final Entity instance, final double x, final double y, final double z,
        final Operation<Void> original
    ) {
        final Vec3 movement = this.vs$lastMoveRequestedMovement;
        final Vec3 movementAdjustedForCollisions = this.vs$lastMoveAdjustedMovement;

        // Horizontal collision response: how much vanilla clipped the requested movement.
        final Vector3dc collisionResponseHorizontal = new Vector3d(
            movementAdjustedForCollisions.x - movement.x,
            0.0,
            movementAdjustedForCollisions.z - movement.z
        );

        if (collisionResponseHorizontal.lengthSquared() > 1e-6) {
            final Vec3 deltaMovement = getDeltaMovement();
            final Vector3dc collisionResponseHorizontalNormal =
                collisionResponseHorizontal.normalize(new Vector3d());

            // Component of current horizontal velocity that points along the collision normal.
            final double parallelHorizontalVelocityComponent =
                collisionResponseHorizontalNormal.x() * deltaMovement.x
                    + collisionResponseHorizontalNormal.z() * deltaMovement.z;

            // Subtract that parallel component out — keep tangential velocity (sliding along the
            // surface) intact, but drop the into-surface component that vanilla axis-zeroing
            // would have left as a slow drift on slanted hulls.
            final double newX = deltaMovement.x - collisionResponseHorizontalNormal.x() * parallelHorizontalVelocityComponent;
            final double newZ = deltaMovement.z - collisionResponseHorizontalNormal.z() * parallelHorizontalVelocityComponent;
            original.call(
                instance,
                newX,
                y, // y handled by vanilla — passed-through
                newZ
            );
        } else {
            // No horizontal collision response in this branch (rare — e.g. purely vertical) —
            // fall through to vanilla's axis-zeroing values unchanged.
            original.call(instance, x, y, z);
        }
    }

    // endregion

    // region Block standing on friction and sprinting particles mixins
    @Unique
    private BlockPos getPosStandingOnFromShips(final Vector3dc blockPosInGlobal) {
        final double radius = 0.5;
        final AABBdc testAABB = new AABBd(
            blockPosInGlobal.x() - radius, blockPosInGlobal.y() - radius, blockPosInGlobal.z() - radius,
            blockPosInGlobal.x() + radius, blockPosInGlobal.y() + radius, blockPosInGlobal.z() + radius
        );
        final Iterable<Ship> intersectingShips = VSGameUtilsKt.getShipsIntersecting(level, testAABB);
        for (final Ship ship : intersectingShips) {
            final Vector3dc blockPosInLocal =
                ship.getTransform().getWorldToShip().transformPosition(blockPosInGlobal, new Vector3d());
            final BlockPos blockPos = BlockPos.containing(
                Math.floor(blockPosInLocal.x()), Math.floor(blockPosInLocal.y()), Math.floor(blockPosInLocal.z())
            );
            final BlockState blockState = level.getBlockState(blockPos);
            if (!blockState.isAir()) {
                return blockPos;
            } else {
                // Check the block below as well, in the cases of fences
                final Vector3dc blockPosInLocal2 = ship.getTransform().getWorldToShip()
                    .transformPosition(
                        new Vector3d(blockPosInGlobal.x(), blockPosInGlobal.y() - 1.0, blockPosInGlobal.z()));
                final BlockPos blockPos2 = BlockPos.containing(
                    Math.round(blockPosInLocal2.x()), Math.round(blockPosInLocal2.y()), Math.round(blockPosInLocal2.z())
                );
                final BlockState blockState2 = level.getBlockState(blockPos2);
                if (!blockState2.isAir()) {
                    return blockPos2;
                }
            }
        }
        return null;
    }

    @Inject(method = "getBlockPosBelowThatAffectsMyMovement", at = @At("HEAD"), cancellable = true)
    private void preGetBlockPosBelowThatAffectsMyMovement(final CallbackInfoReturnable<BlockPos> cir) {
        final Vector3dc blockPosInGlobal = new Vector3d(
            position.x,
            getBoundingBox().minY - 0.5,
            position.z
        );
        final BlockPos blockPosStandingOnFromShip = getPosStandingOnFromShips(blockPosInGlobal);
        if (blockPosStandingOnFromShip != null) {
            cir.setReturnValue(blockPosStandingOnFromShip);
        }
    }

    /**
     * @author tri0de
     * @reason Allows ship blocks to spawn landing particles, running particles, and play step sounds
     */
    @Inject(method = "getOnPos()Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void preGetOnPos(final CallbackInfoReturnable<BlockPos> cir) {
        final Vector3dc blockPosInGlobal = new Vector3d(
            position.x,
            position.y - 0.2,
            position.z
        );
        final BlockPos blockPosStandingOnFromShip = getPosStandingOnFromShips(blockPosInGlobal);
        if (blockPosStandingOnFromShip != null) {
            cir.setReturnValue(blockPosStandingOnFromShip);
        }
    }

    @Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
    private void preSpawnSprintParticle(final CallbackInfo ci) {
        final Vector3dc blockPosInGlobal = new Vector3d(
            position.x,
            position.y - 0.2,
            position.z
        );
        final BlockPos blockPosStandingOnFromShip = getPosStandingOnFromShips(blockPosInGlobal);
        if (blockPosStandingOnFromShip != null) {
            final BlockState blockState = this.level.getBlockState(blockPosStandingOnFromShip);
            if (blockState.getRenderShape() != RenderShape.INVISIBLE) {
                final Vec3 vec3 = this.getDeltaMovement();
                this.level.addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                    this.getX() + (this.random.nextDouble() - 0.5) * (double) this.dimensions.width(),
                    this.getY() + 0.1,
                    this.getZ() + (this.random.nextDouble() - 0.5) * (double) this.dimensions.width(),
                    vec3.x * -4.0,
                    1.5,
                    vec3.z * -4.0
                );
                ci.cancel();
            }
        }
    }
    // endregion

    // region shadow functions and fields
    @Shadow
    public Level level;

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract void setDeltaMovement(double x, double y, double z);

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getX();

    @Shadow
    private Vec3 position;

    @Shadow
    @Final
    protected RandomSource random;

    @Shadow
    private EntityDimensions dimensions;
    // endregion
}
