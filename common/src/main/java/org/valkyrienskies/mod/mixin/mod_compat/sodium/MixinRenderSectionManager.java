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

    // Ship<->Sodium terrain integration is DISABLED. Drawing ship sections through Sodium's chunk
    // renderer is fundamentally incompatible with Iris: Iris keeps parallel camera/shadow render
    // lists and swaps RenderSectionManager.renderLists between them per pass, while this integration
    // swaps/merges the same fields for ships -- the two collide and punch see-through holes in
    // streaming terrain (shaders only). Ship terrain blocks are instead rendered immediate-mode
    // through MC's normal geometry path (MixinLevelRenderer, submitBlockEntities TAIL), which Iris's
    // gbuffers handle correctly. With this true, afterIterateChunks early-returns, shipRenderLists
    // stays empty, and every Sodium ship-render path (the Fabric redirectRenderLayer, the tick and
    // block-entity swaps) is inert.
    @Unique
    private static final boolean VS_DISABLE_SHIP_TERRAIN_INTEGRATION = true;

    @Inject(at = @At("TAIL"), method = "createTerrainRenderList", require = 1)
    private void afterIterateChunks(final Camera camera, final Viewport viewport, final FogParameters fogParameters,
        final int frame, final boolean spectator, final CallbackInfoReturnable<Boolean> cir) {

        if (VS_DISABLE_SHIP_TERRAIN_INTEGRATION) {
            return;
        }

        for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(Minecraft.getInstance()).getLoadedShips()) {
            // 0.8 replaced VisibleChunkCollector with SectionCollector. We visit ship sections directly
            // instead of walking the cull graph.
            //
            // Queue type matters a LOT here. ZERO_FRAME_DEFER is Sodium's highest-priority rebuild
            // queue: in RenderSectionManager.updateChunks -> submitSectionTasks it is drained FIRST,
            // into the *blocking* collector that the render thread awaitCompletion()s, and it bypasses
            // the per-frame upload budget. Ship sections get re-dirtied constantly while the player
            // moves (Sodium recycles their RenderRegions as you fly), so on ZERO_FRAME those dirty
            // ship builds preempt and block the SAME per-frame build+upload budget that streams in
            // normal terrain. Visible result: terrain meshes land a beat late and the world flashes
            // see-through / sky-backdrop for a fraction of a second whenever a ship is loaded and you
            // move -- the reported bug. (It happens with a *stationary* ship too because it's the
            // player's movement, not the ship's, that re-dirties the sections.)
            //
            // ALWAYS_DEFER routes ship rebuilds to the deferred, non-blocking queue: drained LAST,
            // built only with budget left over after terrain, and never awaited on the render thread.
            // Terrain streaming always wins the budget. Already-built ship sections keep rendering
            // every frame via shipRenderLists; only a freshly-dirtied ship section waits a frame or
            // two to re-mesh, which is imperceptible next to the terrain it no longer stalls.
            final OcclusionSectionCollector collector =
                new OcclusionSectionCollector(frame, TaskQueueType.ALWAYS_DEFER, TaskQueueType.ALWAYS_DEFER);

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

    @Inject(at = @At("TAIL"), method = "resetRenderLists", require = 1)
    private void afterResetLists(final CallbackInfo ci) {
        shipRenderLists.clear();
    }
}
