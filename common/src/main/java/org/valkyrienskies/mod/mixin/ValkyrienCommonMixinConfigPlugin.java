package org.valkyrienskies.mod.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.valkyrienskies.mod.compat.LoadedMods;
import org.valkyrienskies.mod.compat.VSRenderer;

/**
 * Gates renderer-/mod-specific mixins on what is actually installed. The 1.21.11 fork registers
 * only the sodium + voxy mod_compat mixins; the upstream filter branches for unregistered compat
 * packages (create, flywheel, bluemap, optifine, ...) were removed with them — restore from
 * upstream if those mixins are ever re-registered.
 */
public class ValkyrienCommonMixinConfigPlugin implements IMixinConfigPlugin {

    private static VSRenderer vsRenderer = null;

    public static VSRenderer getVSRenderer() {
        if (vsRenderer == null) {
            vsRenderer = getVSRendererHelper();
        }
        return vsRenderer;
    }

    private static VSRenderer getVSRendererHelper() {
        //TODO remove?
        if (classExists("optifine.OptiFineTransformationService")) {
            return VSRenderer.OPTIFINE;
        } else if (classExists("net.caffeinemc.mods.sodium.client.SodiumClientMod")) {
            return VSRenderer.SODIUM;
        } else {
            return VSRenderer.VANILLA;
        }
    }

    private static boolean classExists(final String className) {
        try {
            Class.forName(className, false, ValkyrienCommonMixinConfigPlugin.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void onLoad(final String s) {
        MixinExtrasBootstrap.init();
        Mixins.registerErrorHandlerClass("org.valkyrienskies.mod.mixin.ValkyrienMixinErrorHandler");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String s, final String mixinClassName) {
        if (
            mixinClassName.equals("org.valkyrienskies.mod.mixin.client.world.MixinClientChunkCache") ||
                mixinClassName.equals("org.valkyrienskies.mod.mixin.client.renderer.MixinViewAreaVanilla")
        ) {
            return !LoadedMods.getImmersivePortals(); // Only load this if immersive portals is NOT present
        }

        if (mixinClassName.contains("org.valkyrienskies.mod.mixin.mod_compat.sodium")) {
            return getVSRenderer() == VSRenderer.SODIUM;
        }

        return true;
    }

    @Override
    public void acceptTargets(final Set<String> set, final Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }
}
