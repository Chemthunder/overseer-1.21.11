package net.damagejr05.overseer.mixin.client;

import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.client.render.debug.EntityHitboxDebugRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Chemthunder
 */
@Mixin(value = EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
    @Inject(method = "drawHitbox", at = @At(value = "HEAD"), cancellable = true)
    private void overseer$nohitboxplease(Entity entity, float tickProgress, boolean inLocalServer, CallbackInfo ci) {
        if (entity instanceof PlayerEntity player) {
            if (OverseerComponent.KEY.get(player).isActive()) {
                ci.cancel();
            }
        }
    }
}
