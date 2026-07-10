package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * D1 "down" fix: keep ship-dragged mobs from anchoring their A* START far below the deck.
 *
 * <p>Vanilla {@link WalkNodeEvaluator#getStart()} (1.21.11), when the mob is NOT on the world ground
 * (the hovering-ship case), runs a descent loop that decrements Y one block at a time reading
 * {@code currentContext.getBlockState(pos)} until it hits a non-air, non-pathfindable block or
 * {@code level().getMinY()}. Those reads go through the stage-1 ship overlay (MixinPathNavigationRegion),
 * which only synthesizes a ship block for a WORLD-AIR cell that maps INSIDE a ship AABB; below the hull
 * footprint the cell is genuine sky-air, so the descent falls through to min build height and the start
 * resolves to the lowest reachable level (mobs gather on the lowest deck / hop into a one-lower block).
 *
 * <p>For a confirmed ship-dragged mob we skip that loop entirely and anchor the start on the cell the mob
 * already occupies ({@code mob.blockPosition()}). Only if the block directly under the feet is air (mob
 * standing ON TOP of a slab/half-deck) do we do a tiny clamped descent (&le; {@link #VS$MAX_DECK_DROP})
 * that stops at the first overlay-reported ship block instead of vanilla's unbounded fall. Everything stays
 * in WORLD coordinates within a few blocks of the mob — no world&lt;-&gt;ship transform runs here, so the
 * ~1e7 shipyard coords never enter the A* start hot path; ship-awareness rides entirely on
 * {@code currentContext.getBlockState} (the overlay does the one AABB membership query upstream).
 *
 * <p><b>Zero {@code @Shadow}</b>: this mixin {@code extends NodeEvaluator} (the repo pattern —
 * SwimNodeEvaluatorMixin / MixinLocalPlayer-extends-Entity) and reaches the inherited {@code mob},
 * {@code currentContext}, and {@code getNode(BlockPos)} directly through that superclass (remapped at build
 * time), instead of {@code @Shadow} (which crashed at apply: "@Shadow field ... not located in the target
 * class" — Mixin doesn't resolve {@code @Shadow} on superclass members in this build). We use the inherited
 * {@code getNode} rather than {@code WalkNodeEvaluator.getStartNode} for the same reason. Non-ship/ground
 * mobs early-return = vanilla. Server-side only.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {

    // How far below the mob's feet we look for the supporting deck. Small + clamped on purpose: a deck is
    // right under the mob's feet, and the clamp guarantees we never descend into world sky-air.
    private static final int VS$MAX_DECK_DROP = 4;

    @Inject(method = "getStart()Lnet/minecraft/world/level/pathfinder/Node;", at = @At("HEAD"), cancellable = true)
    private void vs$anchorStartOnDeck(final CallbackInfoReturnable<Node> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return;
        }
        // Same cheap, non-geometric ship-mob predicate the getGroundY bypass uses (MixinPathNavigation).
        // this.mob + this.currentContext are inherited from NodeEvaluator (reached via the extends above).
        if (!(this.mob instanceof IEntityDraggingInformationProvider dragProvider)
            || !dragProvider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return; // not riding a ship -> vanilla getStart() runs unchanged
        }

        final BlockPos feet = this.mob.blockPosition();
        final int x = feet.getX();
        final int z = feet.getZ();

        // Preferred anchor: the cell the mob occupies (the overlay reports the deck here for a ship mob).
        BlockPos anchor = feet;

        if (!this.currentContext.getBlockState(anchor).isAir()) {
            // (A) The mob's OWN cell is SOLID. The no-collision ship carry re-anchors a standing mob a hair
            // BELOW the deck-top block boundary (observed minY ~= deckTop - 0.02), so blockPosition() floors
            // onto the SOLID deck block instead of the air cell above it. A start Node placed inside solid has
            // NO walkable neighbours, so A* returns a dead single-node path and the mob freezes where it stands
            // -- it still FACES its target (LOOK is unaffected) but never takes a step. This only surfaced once
            // the deck overlay/pathing was otherwise reliable. Step UP to the first air cell the body occupies;
            // the cell just below it is the deck we sank into, so it is a valid standable node.
            for (int up = 1; up <= VS$MAX_DECK_DROP; up++) {
                final BlockPos above = new BlockPos(x, feet.getY() + up, z);
                if (this.currentContext.getBlockState(above).isAir()) {
                    anchor = above;
                    break;
                }
            }
        } else if (this.currentContext.getBlockState(new BlockPos(x, feet.getY() - 1, z)).isAir()) {
            // (C) Feet cell is air AND the block under the feet is air (mob standing ON TOP of a slab/half-deck):
            // drop a SMALL clamped amount to land on the first ship block. currentContext.getBlockState goes
            // through the ship overlay; the clamp guarantees we never fall into world sky-air like vanilla does.
            for (int dy = 1; dy <= VS$MAX_DECK_DROP; dy++) {
                final BlockPos below = new BlockPos(x, feet.getY() - dy, z);
                if (!this.currentContext.getBlockState(below).isAir()) {
                    anchor = new BlockPos(x, feet.getY() - dy + 1, z); // stand on top of that block
                    break;
                }
            }
        }

        // Inherited NodeEvaluator.getNode (not WalkNodeEvaluator.getStartNode) to stay @Shadow-free. If it
        // can't build a node (null), fall through to vanilla getStart() rather than returning null.
        final Node node = this.getNode(anchor);
        if (node != null) {
            cir.setReturnValue(node);
        }
    }

    /**
     * D1 "down" fix, EXPLORATION side: kill the shared-lowest-floor attractor that pulls dozens of
     * ship-carried mobs onto the single deepest reachable cell.
     *
     * <p>In the 1.21.2+ pathfinder, {@code WalkNodeEvaluator.getNeighbors} enumerates only the HORIZONTAL
     * directions; every horizontal step into an interior air cell (air -&gt; {@code PathType.OPEN}) is
     * resolved inside {@code findAcceptedNode} by calling
     * {@link WalkNodeEvaluator#tryFindFirstGroundNodeBelow(int, int, int) tryFindFirstGroundNodeBelow}.
     * Vanilla scans DOWNWARD one block at a time (bounded only by {@code mob.getMaxFallDistance()} and
     * {@code level().getMinY()}) and returns the first non-air floor it finds. Over a ship hull the raw
     * descent prefers the absolute lowest air-bottom within fall range, and since the A* edge cost is pure
     * euclidean distance + (walkable) malus 0, descending carries no penalty — so every step resolves onto
     * the deepest reachable cell. Break one block of a 2-deep deck and the scan continues one block further,
     * exposing a new globally-lowest cell that every idle/wandering mob's path converges on.
     *
     * <p>For a confirmed ship-dragged mob we replace that unbounded descent with a small CLAMPED scan
     * (&le; {@link #VS$MAX_DECK_DROP}) reading {@code this.currentContext.getBlockState} (the overlay-aware
     * read used by the start anchor above), and return {@code getNode} at the FIRST ship block we hit —
     * i.e. the NEAREST deck below the candidate, never the lowest one. If no ship block is found within the
     * clamp we do NOT set a return value, so vanilla {@code tryFindFirstGroundNodeBelow} runs unchanged
     * (e.g. genuinely tall interior cavities fall through to normal behavior). This is the exact analogue of
     * {@code vs$anchorStartOnDeck}: same predicate, same clamp, same overlay read, no world&lt;-&gt;ship
     * transform in the hot path, zero {@code @Shadow} (inherited {@code mob}/{@code currentContext}/
     * {@code getNode} reached via {@code extends NodeEvaluator}).
     *
     * <p>{@code tryFindFirstGroundNodeBelow} is {@code private} in {@code WalkNodeEvaluator}; the Mixin AP
     * matches injection targets by name+descriptor (access modifiers are irrelevant to target resolution)
     * and the named-&gt;intermediary mapping is present, so this remaps reliably — the repo already injects a
     * private MC method (MixinPollinateGoal on {@code Bee$BeePollinateGoal.findNearbyFlower}). {@code require=1}
     * makes any future mapping break fail loudly at apply rather than silently no-op.
     */
    @Inject(
        method = "tryFindFirstGroundNodeBelow(III)Lnet/minecraft/world/level/pathfinder/Node;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void vs$clampDeckDropExploration(final int x, final int y, final int z,
        final CallbackInfoReturnable<Node> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return;
        }
        if (!(this.mob instanceof IEntityDraggingInformationProvider dragProvider)
            || !dragProvider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return; // not riding a ship -> vanilla tryFindFirstGroundNodeBelow() runs unchanged
        }

        // Clamped downward scan from the candidate cell. Vanilla starts at y-1; mirror that. Read through the
        // ship overlay so a deck cell counts as solid even when the raw world cell is sky-air.
        for (int dy = 1; dy <= VS$MAX_DECK_DROP; dy++) {
            final int groundY = y - dy;
            if (!this.currentContext.getBlockState(new BlockPos(x, groundY, z)).isAir()) {
                // Stand ON TOP of the deck block (groundY + 1), not inside it — the standable cell is the air
                // above the solid (matches vanilla, which returns the walkable cell, and the getStart anchor's
                // -dy+1). getNode at the solid cell would be a blocked node that A* rejects.
                final Node node = this.getNode(x, groundY + 1, z);
                if (node != null) {
                    cir.setReturnValue(node);
                }
                return; // nearest deck found -> stop; never descend to the lowest reachable cell
            }
        }
        // No ship block within the clamp: fall through to vanilla (tall interiors keep normal behavior).
    }
}
