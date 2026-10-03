package net.damagejr05.overseer.mixin;

import net.damagejr05.overseer.core.portal.PortalBarrier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stops a player who is not admitted at the face of a doorway. See {@link PortalBarrier}.
 *
 * @author Flex
 */
@Mixin(Entity.class)
public class EntityMixin {
    @ModifyVariable(
            method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Vec3d overseer$stopAtSealedDoorway(Vec3d movement) {
        // This runs for every entity that moves, every tick. Only players are ever turned away,
        // so everything else leaves on the first comparison.
        if (!((Object) this instanceof PlayerEntity)) {
            return movement;
        }

        return PortalBarrier.clamp((Entity) (Object) this, movement);
    }
}
