package org.valkyrienskies.mod.fabric.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.valkyrienskies.mod.compat.LoadedMods;

public class ValkyrienSkiesFabricMixinPlugin implements IMixinConfigPlugin {

    // Compat mixins whose target mod may be absent: apply only when the target class is
    // actually present, instead of letting Mixin emit a WARN per missing target at boot.
    private static final Map<String, String> COMPAT_MIXIN_PROBES = Map.of(
        "org.valkyrienskies.mod.fabric.mixin.compat.cc_restitched.MixinWirelessModemPeripheral",
        "dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral",
        "org.valkyrienskies.mod.fabric.mixin.compat.cc_restitched.MixinSpeakerSound",
        "dan200.computercraft.client.sound.SpeakerSound",
        "org.valkyrienskies.mod.fabric.mixin.compat.connectiblechains.MixinChainable",
        "com.github.legoatoom.connectiblechains.entity.Chainable",
        "org.valkyrienskies.mod.fabric.mixin.compat.connectiblechains.MixinChainCollisionEntity",
        "com.github.legoatoom.connectiblechains.entity.ChainCollisionEntity",
        "org.valkyrienskies.mod.fabric.mixin.compat.connectiblechains.client.MixinChainKnotEntityRenderer",
        "com.github.legoatoom.connectiblechains.client.render.entity.ChainKnotEntityRenderer"
    );

    private static boolean classExists(final String className) {
        try {
            Class.forName(className, false, ValkyrienSkiesFabricMixinPlugin.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void onLoad(final String s) {

    }

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(final String s, final String mixinClassName) {
        if (mixinClassName.contains("org.valkyrienskies.mod.fabric.mixin.compat.old_create")) {
            return LoadedMods.getOldCreate();
        }

        final String probeClass = COMPAT_MIXIN_PROBES.get(mixinClassName);
        if (probeClass != null) {
            return classExists(probeClass);
        }

        return true;
    }

    @Override
    public void acceptTargets(final Set<String> set, final Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }
}
