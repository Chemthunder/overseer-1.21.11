package net.damagejr05.overseer.core.effect;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * @author Chemthunder
 */
public class CursedEffect extends StatusEffect {
    public CursedEffect() {
        super(StatusEffectCategory.NEUTRAL, 0xFF000000);
    }

    public ParticleEffect createParticle(StatusEffectInstance effect) {
        return new DustParticleEffect(0x000000, 1.3F);
    }

    public Text getName() {
        return Text.literal("Cursed").formatted(Formatting.OBFUSCATED);
    }
}
