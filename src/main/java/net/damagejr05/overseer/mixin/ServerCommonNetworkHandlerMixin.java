package net.damagejr05.overseer.mixin;

import net.damagejr05.overseer.core.net.PacketRedirection;
import net.minecraft.network.packet.Packet;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tags outgoing packets with the dimension they describe while a redirect scope is open.
 *
 * <p>Intercepting the single send path means every vanilla system that emits chunk, entity, or
 * block-update packets is redirected automatically, with no per-call-site changes.
 *
 * @author Flex
 */
@Mixin(ServerCommonNetworkHandler.class)
public class ServerCommonNetworkHandlerMixin {
    @ModifyVariable(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> overseer$redirect(Packet<?> packet) {
        RegistryKey<World> dimension = PacketRedirection.current();

        if (dimension == null) {
            return packet;
        }

        return PacketRedirection.wrap(dimension, packet);
    }
}
