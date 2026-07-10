package org.valkyrienskies.mod.mixin.feature.config_recipes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.mod.common.config.RecipeOverrides;

/**
 * Config-driven crafting recipe overrides. At recipe (re)load, swaps the datapack-scanned
 * id->JSON map for one in which any recipe id listed in {@code config/vs_eureka_recipes.json}
 * is replaced/added (or removed). Unlike the 1.21.11 branch (where apply() receives an
 * already-parsed RecipeMap and the mixin must build RecipeHolders itself), 1.21.1's apply()
 * receives the raw JSON map, so the swap happens BEFORE vanilla parses it — tags,
 * ingredient-lists, shaped/shapeless parsing and per-recipe error logging are all vanilla's own.
 *
 * Runs server-side (datapack load); the result is synced to clients normally. Self-contained and
 * fail-safe: any error leaves the built-in recipes untouched.
 */
@Mixin(RecipeManager.class)
public class MixinRecipeManager {

    @ModifyVariable(
        method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 1
    )
    private Map<ResourceLocation, JsonElement> vs$applyConfigRecipeOverrides(
        final Map<ResourceLocation, JsonElement> original) {
        try {
            RecipeOverrides.reload();
            final Map<String, JsonObject> overrides = RecipeOverrides.getOverrides();
            final Set<String> removals = RecipeOverrides.getRemovals();
            if (overrides.isEmpty() && removals.isEmpty()) {
                return original;
            }

            final Map<ResourceLocation, JsonElement> patched = new LinkedHashMap<>(original);
            for (final String rid : removals) {
                try {
                    patched.remove(ResourceLocation.parse(rid));
                } catch (final Exception ignored) {
                    // bad id in config; skip
                }
            }
            int applied = 0;
            for (final Map.Entry<String, JsonObject> e : overrides.entrySet()) {
                try {
                    patched.put(ResourceLocation.parse(e.getKey()), e.getValue());
                    applied++;
                } catch (final Exception ex) {
                    RecipeOverrides.logError("Config recipe '" + e.getKey() + "' has a bad id", ex);
                }
            }

            RecipeOverrides.logInfo(
                "[vs_eureka recipes] applied " + applied + " override(s), " + removals.size() + " removal(s)."
            );
            return patched;
        } catch (final Exception e) {
            RecipeOverrides.logError("Config recipe override pass failed; using built-in recipes", e);
            return original;
        }
    }
}
