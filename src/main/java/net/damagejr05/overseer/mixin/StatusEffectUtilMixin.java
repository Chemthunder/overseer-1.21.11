package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.index.ModStatusEffects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;

/**
 * @author Chemthunder
 */
@Mixin(value = StatusEffectUtil.class)
public abstract class StatusEffectUtilMixin {
    @WrapMethod(method = "getDurationText")
    private static Text overseer$customDuration(StatusEffectInstance effect, float multiplier, float tickRate, Operation<Text> original) {
        if (effect.getEffectType() == ModStatusEffects.CURSED) {
            if (OverseerComponent.KEY.get(MinecraftClient.getInstance().player).isPrimed()) {
                return Text.literal("Ad Infinitum.").formatted(Formatting.WHITE, Formatting.ITALIC, Formatting.BOLD);
            } else {
                return Text.literal("Ad Infinitum.").formatted(Formatting.DARK_GRAY, Formatting.ITALIC);
            }
        }
        return original.call(effect, multiplier, tickRate);
    }
}
