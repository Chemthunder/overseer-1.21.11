package net.damagejr05.overseer.mixin;

import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * @author Chemthunder
 */
@Mixin(value = LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "tryUseDeathProtector", at = @At(value = "HEAD"), cancellable = true)
    private void overseer$transform(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof PlayerEntity player) {
            OverseerComponent o = OverseerComponent.KEY.get(player);

            if (o.isPrimed() && !o.isActive()) {
                o.kill();
                cir.setReturnValue(true);
            }
        }
    }
}
