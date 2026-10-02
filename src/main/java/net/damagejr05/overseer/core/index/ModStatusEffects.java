package net.damagejr05.overseer.core.index;

import net.acoyt.acornlib.api.registrants.MobEffectRegistrant;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.effect.CursedEffect;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * @author Chemthunder
 */
public interface ModStatusEffects {
    MobEffectRegistrant rant = new MobEffectRegistrant(Overseer.MOD_ID);

    RegistryEntry<StatusEffect> CURSED = rant.registerRef("cursed", new CursedEffect());

    static void init() {}
}
