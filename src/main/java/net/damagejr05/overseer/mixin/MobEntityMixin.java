package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MobEntity.class)
public abstract class MobEntityMixin {
    @Shadow private @Nullable LivingEntity target;

    @WrapMethod(method = "getTarget")
    private LivingEntity a(Operation<LivingEntity> original) {
        if (this.target instanceof PlayerEntity player) {
            if (OverseerComponent.KEY.get(player).isActive()) {
                return null;
            }
        }
        return original.call();
    }

    @WrapMethod(method = "getTargetInBrain")
    private LivingEntity b(Operation<LivingEntity> original) {
        if (this.target instanceof PlayerEntity player) {
            if (OverseerComponent.KEY.get(player).isActive()) {
                return null;
            }
        }
        return original.call();
    }
}
