package org.valkyrienskies.mod.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.client.ShipCameraZoom;

@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    /**
     * While the ship-mounted third-person camera is actually rendering (set authoritatively each
     * frame by MixinGameRenderer -- standing-helm FRONT slot or any sitting-helm third person),
     * the scroll wheel zooms that camera instead of switching hotbar slots -- hotbar scrolling is
     * useless at a helm anyway. Consumes the event so the hotbar doesn't move; every other context
     * (GUIs open, on foot, first person, the standing-helm BACK slot which is the vanilla camera)
     * falls through to vanilla untouched.
     */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 1)
    private void valkyrienskies$shipCameraZoomScroll(final long window, final double xOffset,
        final double yOffset, final CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null || mc.player == null
            || !ShipCameraZoom.isShipCameraActive()) {
            return;
        }
        if (yOffset != 0.0) {
            ShipCameraZoom.scroll(yOffset > 0.0 ? 1.0 : -1.0);
        }
        ci.cancel();
    }
}
