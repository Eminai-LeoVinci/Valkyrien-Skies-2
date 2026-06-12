package org.valkyrienskies.mod.mixin.feature.config_recipes;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.mod.common.config.RecipeOverrides;

/**
 * Config-driven crafting recipe overrides. At recipe (re)load, swaps the freshly-built {@link RecipeMap}
 * for one in which any recipe id listed in {@code config/vs_eureka_recipes.json} is replaced (or removed).
 * Recipes are built by handing config-derived JSON to vanilla's own {@code RecipeManager.fromJson}, so
 * tags, ingredient-lists and shaped/shapeless parsing all behave exactly like a datapack recipe.
 *
 * Runs server-side (datapack load); the result is synced to clients normally. Self-contained and
 * fail-safe: any error leaves the built-in recipes untouched.
 */
@Mixin(RecipeManager.class)
public class MixinRecipeManager {

    @Shadow
    @Final
    private HolderLookup.Provider registries;

    @Shadow
    private static RecipeHolder<?> fromJson(ResourceKey<Recipe<?>> resourceKey, JsonObject json,
                                            HolderLookup.Provider provider) {
        throw new AssertionError();
    }

    @ModifyVariable(
        method = "apply(Lnet/minecraft/world/item/crafting/RecipeMap;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 1
    )
    private RecipeMap vs$applyConfigRecipeOverrides(RecipeMap original) {
        try {
            RecipeOverrides.reload();
            final Map<String, JsonObject> overridesJson = RecipeOverrides.getOverrides();
            final Set<String> removals = RecipeOverrides.getRemovals();
            if (overridesJson.isEmpty() && removals.isEmpty()) {
                return original;
            }

            final Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> built = new HashMap<>();
            for (final Map.Entry<String, JsonObject> e : overridesJson.entrySet()) {
                try {
                    final ResourceKey<Recipe<?>> key =
                        ResourceKey.create(Registries.RECIPE, Identifier.parse(e.getKey()));
                    built.put(key, fromJson(key, e.getValue(), this.registries));
                } catch (final Exception ex) {
                    RecipeOverrides.logError("Config recipe '" + e.getKey() + "' failed to build", ex);
                }
            }

            final Set<ResourceKey<Recipe<?>>> dropKeys = new HashSet<>(built.keySet());
            for (final String rid : removals) {
                try {
                    dropKeys.add(ResourceKey.create(Registries.RECIPE, Identifier.parse(rid)));
                } catch (final Exception ignored) {
                    // bad id in config; skip
                }
            }
            if (built.isEmpty() && dropKeys.isEmpty()) {
                return original;
            }

            final List<RecipeHolder<?>> result = new ArrayList<>();
            for (final RecipeHolder<?> holder : original.values()) {
                if (!dropKeys.contains(holder.id())) {
                    result.add(holder);
                }
            }
            result.addAll(built.values());

            RecipeOverrides.logInfo(
                "[vs_eureka recipes] applied " + built.size() + " override(s), " + removals.size() + " removal(s)."
            );
            return RecipeMap.create(result);
        } catch (final Exception e) {
            RecipeOverrides.logError("Config recipe override pass failed; using built-in recipes", e);
            return original;
        }
    }
}
