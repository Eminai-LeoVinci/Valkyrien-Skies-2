package org.valkyrienskies.mod.mixin.feature.ai.node_evaluator;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code PathNavigation.mob} ({@code protected final Mob}) without {@code @Shadow}.
 *
 * <p>Subclass mixins (e.g. {@code GroundPathNavigationMixin}) cannot {@code @Shadow} this inherited field in
 * this build ({@code @Shadow} on a superclass member crashes at apply: "field ... not located in the target
 * class"), so we expose it through an {@code @Accessor} interface on the declaring class and cast the
 * instance to it — same pattern as {@code PathNavigationRegionAccessor}.
 */
@Mixin(PathNavigation.class)
public interface PathNavigationMobAccessor {
    @Accessor("mob")
    Mob getMob();
}
