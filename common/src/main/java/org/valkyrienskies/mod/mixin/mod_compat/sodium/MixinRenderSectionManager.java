package org.valkyrienskies.mod.mixin.mod_compat.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.OcclusionSectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.mixinducks.mod_compat.sodium.RenderSectionManagerDuck;

/**
 * Hi! Not many people read Valkyrien Skies' code, and even fewer will read this particular file. If you're
 * here because you're contributing to VS, thank you so much! This is complex stuff, and your work is appreciated by
 * all of our users.
 * <p>
 * If you're here because you develop a competitor mod, we can't stop you from using this code - but at least have
 * the decency to give credit to us, the original authors, and abide by the terms of our open source license. Don't
 * pretend that you wrote this code. That's not cool.
 *
 * @author Rubydesic
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager implements RenderSectionManagerDuck {

    @Unique
    private final WeakHashMap<ClientShip, SortedRenderLists> shipRenderLists = new WeakHashMap<>();

    @Override
    public WeakHashMap<ClientShip, SortedRenderLists> vs_getShipRenderLists() {
        return shipRenderLists;
    }

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    private SortedRenderLists renderLists;

    @Shadow
    protected abstract RenderSection getRenderSection(int x, int y, int z);

    @Shadow
    private Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;

    @Shadow
    public abstract void tickVisibleRenders();

    @Inject(at = @At("TAIL"), method = "createTerrainRenderList")
    private void afterIterateChunks(final Camera camera, final Viewport viewport, final FogParameters fogParameters,
        final int frame, final boolean spectator, final CallbackInfoReturnable<Boolean> cir) {

        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(Minecraft.getInstance()).getLoadedShips()) {
            // 0.8 replaced VisibleChunkCollector with SectionCollector. We visit ship sections directly
            // instead of walking the cull graph, so ZERO_FRAME_DEFER just makes ship rebuilds prompt.
            final OcclusionSectionCollector collector =
                new OcclusionSectionCollector(frame, TaskQueueType.ZERO_FRAME_DEFER, TaskQueueType.ZERO_FRAME_DEFER);

            ship.getActiveChunksSet().forEach((x, z) -> {
                final LevelChunk levelChunk = level.getChunk(x, z);
                final LevelChunkSection[] sections = levelChunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i].hasOnlyAir()) {
                        continue;
                    }
                    final int sectionY = levelChunk.getSectionYFromSectionIndex(i);
                    final RenderSection section = getRenderSection(x, sectionY, z);
                    if (section == null) {
                        continue;
                    }
                    collector.visit(section);
                }
            });

            shipRenderLists.put(ship, collector.createRenderLists(viewport));

            // Merge ship rebuild tasks into the manager's queues, otherwise ship chunks never get built.
            for (final var entry : collector.getTaskLists().entrySet()) {
                final ArrayDeque<RenderSection> managerQueue = this.taskLists.get(entry.getKey());
                if (managerQueue != null) {
                    managerQueue.addAll(entry.getValue());
                }
            }
        }
    }

    @WrapMethod(method = "tickVisibleRenders")
    private void tickVisibleShipRenders(Operation<Void> original) {
        original.call();

        SortedRenderLists trueRenderLists = renderLists;

        for (final SortedRenderLists currentShipRenderLists : shipRenderLists.values()) {
            renderLists = currentShipRenderLists;
            original.call();
        }

        renderLists = trueRenderLists;
    }

    @Inject(at = @At("TAIL"), method = "resetRenderLists")
    private void afterResetLists(final CallbackInfo ci) {
        shipRenderLists.clear();
    }
}
