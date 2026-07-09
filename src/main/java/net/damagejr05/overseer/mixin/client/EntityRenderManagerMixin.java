package net.damagejr05.overseer.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.cca.entity.VictimComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.debug.EntityHitboxDebugRenderer;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * @author Chemthunder
 */
@Mixin(value = EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
    @WrapMethod(method = "shouldRender")
    private <E extends Entity> boolean overseer$halt(E entity, Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
        if (entity instanceof PlayerEntity player) {
            if (OverseerComponent.KEY.get(player).isInvisible()) {
                if (!VictimComponent.KEY.get(MinecraftClient.getInstance().player).isCanSee()) {
                    return false;
                }
            }
        }
        return original.call(entity, frustum, x, y, z);
    }
}
