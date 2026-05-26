package org.valkyrienskies.mod.mixin.client.platform;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disable MC 1.21.x vanilla "Inactivity FPS Limit" throttling at every layer.
 *
 * <p>Background — the entire MC throttle chain (per 1.21.11 bytecode):
 * <ol>
 *   <li>{@code Minecraft.runTick()} calls {@code framerateLimitTracker.getFramerateLimit()}.</li>
 *   <li>If the returned value is {@code < 260}, it calls
 *       {@code RenderSystem.limitDisplayFPS(int)} which does
 *       {@code glfwWaitEventsTimeout(1.0 / fps)} — that's the actual sleep.</li>
 *   <li>{@code getFramerateLimit()} returns one of: user's maxFps (NONE), 10 (SHORT_AFK
 *       or WINDOW_ICONIFIED), min(maxFps, 30) (LONG_AFK), 60 (OUT_OF_LEVEL_MENU).</li>
 *   <li>The branch is selected by {@code getThrottleReason()}.</li>
 * </ol>
 *
 * <p>2.4.82 attempted to fix this by overriding only {@code getThrottleReason()} to NONE.
 * That should be enough on paper, but the user reported no behavioral change. To rule out
 * any code path that bypasses {@code getThrottleReason()} (e.g. an inlined caller, a mod
 * that calls {@code getFramerateLimit()} via reflection, future MC refactors), 2.4.83
 * overrides every public choke point on the tracker:
 * <ul>
 *   <li>{@code getThrottleReason()} -> always {@code NONE}.</li>
 *   <li>{@code getFramerateLimit()} -> always {@code options.framerateLimit().get()}
 *       (the user's slider value — Sodium's "no limit" maps to a sentinel ≥260 which
 *       runTick treats as no cap).</li>
 *   <li>{@code isHeavilyThrottled()} -> always {@code false} (some code paths gate on
 *       this rather than reading the framerate).</li>
 * </ul>
 *
 * <p>Why this still might not fix the symptom: if the actual throttle is happening at
 * the OS/driver level — Windows DWM compositor halving unfocused 3D window framerate
 * when VSync is on, NVIDIA "Power management = Optimal" throttling background apps —
 * then no MC-side patch can reach it. This mixin guarantees MC is not contributing.
 * The remaining 15-fps-when-unfocused symptom (exactly half of {@code maxFps:30} with
 * VSync on) is consistent with DWM compositor throttling and must be fixed in NVIDIA
 * Control Panel or Windows graphics-performance settings.
 *
 * <p>Sodium 0.8.11 does NOT implement an inactivity throttle of its own — its UI
 * option just binds to vanilla MC's {@code InactivityFpsLimit} enum via
 * {@code SodiumConfigBuilder.createEnumOption(id, class_9927.class)}. This mixin
 * therefore handles the entire in-MC problem regardless of whether Sodium is loaded.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class MixinFramerateLimitTracker {

    @Shadow @Final private Options options;

    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true, require = 1)
    private void valkyrienskies$disableInactivityThrottle(
        final CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
    }

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true, require = 1)
    private void valkyrienskies$forceUserFramerateLimit(final CallbackInfoReturnable<Integer> cir) {
        // Always return the user's configured maxFps slider value, regardless of throttle state.
        // OptionInstance.get() returns Object; cast to Integer.
        cir.setReturnValue((Integer) this.options.framerateLimit().get());
    }

    @Inject(method = "isHeavilyThrottled", at = @At("HEAD"), cancellable = true, require = 1)
    private void valkyrienskies$neverHeavilyThrottled(final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
