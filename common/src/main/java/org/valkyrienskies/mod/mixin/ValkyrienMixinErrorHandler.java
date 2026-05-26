package org.valkyrienskies.mod.mixin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

public class ValkyrienMixinErrorHandler implements IMixinErrorHandler {

    private final Set<String> warnList = new HashSet<>(Arrays.asList(
        "org.valkyrienskies.mod.mixin.feature.water_in_ships_entity.MixinEntity",
        "org.valkyrienskies.mod.mixin.mod_compat.create_big_cannons.MixinAbstractCannonProjectile",
        "org.valkyrienskies.mod.mixin.mod_compat.create_big_cannons.MixinPitchOrientedContraptionEntity"
    ));

    @Override
    public ErrorAction onPrepareError(final IMixinConfig config, final Throwable th, final IMixinInfo mixin,
        final ErrorAction action) {
        // 1.21.11 PORT DIAGNOSTIC: downgrade ALL mixin prepare failures to WARN so the game can
        // launch and every missing/broken mixin is enumerated in a single run. Revert after triage.
        MixinService.getService().getLogger("mixin").error(
            "[VS2-PORT] MIXIN PREPARE FAILED: " + (mixin != null ? mixin.getClassName() : "<unknown>")
                + " -- " + th);
        return ErrorAction.WARN;
    }

    @Override
    public ErrorAction onApplyError(final String targetClassName, final Throwable th, final IMixinInfo mixin,
        final ErrorAction action) {
        // 1.21.11 PORT DIAGNOSTIC: downgrade ALL mixin apply failures to WARN. Revert after triage.
        MixinService.getService().getLogger("mixin").error(
            "[VS2-PORT] MIXIN APPLY FAILED: " + (mixin != null ? mixin.getClassName() : "<unknown>")
                + " -> " + targetClassName + " -- " + th);
        return ErrorAction.WARN;
    }
}
