package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Overseer leaves unannounced, for the same reason they arrive that way: see
 * {@link PlayerManagerMixin}. Every way of leaving -- quitting, a kick, a timeout -- ends in this
 * one method, so this covers all of them.
 *
 * @author Flex
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow public ServerPlayerEntity player;

    @WrapWithCondition(
            method = "cleanUp",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"
            )
    )
    private boolean overseer$leaveUnannounced(PlayerManager manager, Text message, boolean overlay) {
        return !OverseerComponent.KEY.get(this.player).isActive();
    }
}
