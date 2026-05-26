package org.valkyrienskies.mod.mixin.feature.tick_ship_chunks;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * These methods fix random ticking on ship chunks
 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {

    @Shadow
    @Final
    ServerLevel level;

    // 1.21.11: ChunkMap.euclideanDistanceSquared now takes (ChunkPos, Vec3) instead of
    // (ChunkPos, Entity), and is static — so the ship-aware override can't reach the level.
    // Dropped; noPlayersCloseForSpawning below still covers ship-chunk spawning.

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("RETURN"), cancellable = true)
    void noPlayersCloseForSpawning(final ChunkPos chunkPos, final CallbackInfoReturnable<Boolean> cir) {
        if (VSGameUtilsKt.isChunkInShipyard(level, chunkPos.x, chunkPos.z)) {
            if (!cir.getReturnValue()) {
                final ServerShip ship = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()
                    .getByChunkPos(chunkPos.x, chunkPos.z, VSGameUtilsKt.getDimensionId(level));
                if (ship != null) {
                    cir.setReturnValue(true);
                }
            }
        }
    }

}
