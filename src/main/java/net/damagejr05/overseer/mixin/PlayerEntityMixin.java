package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @ModifyReturnValue(method = "getDisplayName", at = @At(value = "RETURN"))
    private Text overseer$customName(Text original) {
        if (OverseerComponent.KEY.get(this).isActive()) {
            return Text.literal("The Overseer").formatted(Formatting.GOLD, Formatting.OBFUSCATED, Formatting.BOLD);
        }
        return original;
    }
}
