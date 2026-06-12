package org.valkyrienskies.mod.mixin.feature.world_weather;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.CompatUtil;

/**
 * 1.21.11: LevelRenderer.tickRain / renderSnowAndRain were absorbed into WeatherEffectRenderer
 * (tickRainParticles / extractRenderState), so these wraps live here now. The call-site owners
 * for the level queries changed from LevelReader to ClientLevel in the process.
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer {

    // getHeightmapPos appears twice in tickRainParticles (rain-particle surface lookup and
    // rain-sound surface lookup); both are wrapped on purpose so rain sound is ship-aware too.
    @WrapOperation(
        method = "tickRainParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
        ),
        require = 1
    )
    private BlockPos includeShipsInWeatherTickOcclusion(
        final ClientLevel level, final Types types, final BlockPos pos, final Operation<BlockPos> original,
        @Share("weatherSurfaceLookupPos") final LocalRef<BlockPos> weatherSurfaceLookupPos
    ) {
        weatherSurfaceLookupPos.set(null);
        final BlockPos vanillaHeight = original.call(level, types, pos);
        final BlockPos worldHeight = BlockPos.containing(
            CompatUtil.INSTANCE.toSameSpaceAs(level, vanillaHeight.getCenter(), (Ship) null, null)
        );
        final BlockHitResult shipSurfaceHit =
            CompatUtil.INSTANCE.getShipHeightmapHitAboveWorldHeight(level, types, worldHeight);
        weatherSurfaceLookupPos.set(shipSurfaceHit == null ? null : shipSurfaceHit.getBlockPos().immutable());
        return shipSurfaceHit == null
            ? worldHeight
            : new BlockPos(worldHeight.getX(), Mth.floor(Math.nextDown(shipSurfaceHit.getLocation().y)) + 1, worldHeight.getZ());
    }

    @WrapOperation(
        method = "tickRainParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 1
    )
    private BlockState includeShipsInWeatherTickSurfaceState(
        final ClientLevel level,
        final BlockPos pos,
        final Operation<BlockState> original,
        @Share("weatherSurfaceLookupPos") final LocalRef<BlockPos> weatherSurfaceLookupPos
    ) {
        final BlockPos lookupPos = weatherSurfaceLookupPos.get();
        return original.call(level, lookupPos != null ? lookupPos : pos);
    }

    @WrapOperation(
        method = "tickRainParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
        ),
        require = 1
    )
    private FluidState includeShipsInWeatherTickSurfaceFluid(
        final ClientLevel level,
        final BlockPos pos,
        final Operation<FluidState> original,
        @Share("weatherSurfaceLookupPos") final LocalRef<BlockPos> weatherSurfaceLookupPos
    ) {
        final BlockPos lookupPos = weatherSurfaceLookupPos.get();
        return original.call(level, lookupPos != null ? lookupPos : pos);
    }

    @WrapOperation(
        method = "tickRainParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        ),
        require = 1
    )
    private VoxelShape includeShipsInWeatherTickCollisionShape(
        final BlockState state,
        final BlockGetter levelReader,
        final BlockPos pos,
        final Operation<VoxelShape> original,
        @Share("weatherSurfaceLookupPos") final LocalRef<BlockPos> weatherSurfaceLookupPos
    ) {
        final BlockPos lookupPos = weatherSurfaceLookupPos.get();
        // This fixes ship surface lookup, but vanilla still interprets the returned shape in world-axis block space. Solutions welcome?
        return original.call(state, levelReader, lookupPos != null ? lookupPos : pos);
    }

    @WrapOperation(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"
        ),
        require = 1
    )
    private int includeShipsInWeatherRenderOcclusion(
        final Level level, final Types types, final int x, final int z, final Operation<Integer> original
    ) {
        return CompatUtil.INSTANCE.getWorldHeightIncludingShips(level, types, x, z);
    }
}
