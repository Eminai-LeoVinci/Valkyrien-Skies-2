package org.valkyrienskies.mod.fabric.mixin.feature.shipyard_entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.mod.mixinducks.world.OfLevel;

@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinPersistentEntitySectionManager implements OfLevel {
    @Shadow
    @Final
    EntitySectionStorage<Entity> sectionStorage;

    @Unique
    private Level valkyrienskies$level;

    @Override
    public Level getLevel() {
        return valkyrienskies$level;
    }

    @Override
    public void setLevel(final Level level) {
        this.valkyrienskies$level = level;
        ((OfLevel) this.sectionStorage).setLevel(level);
    }
}
