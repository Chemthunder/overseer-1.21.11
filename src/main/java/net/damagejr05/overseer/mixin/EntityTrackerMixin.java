package net.damagejr05.overseer.mixin;

import net.damagejr05.overseer.core.net.RemoteEntityTracker;
import net.minecraft.entity.Entity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets someone watching an entity through a portal see it do things, not just move.
 *
 * <p>This is the one method every per-entity broadcast funnels through: tracked data, equipment,
 * attributes, arm swings, hurt and death animations. Vanilla already decides when each of those
 * needs sending, so copying its output is both less code and more correct than tracking the same
 * state a second time.
 *
 * @author Flex
 */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public class EntityTrackerMixin {
    @Shadow @Final Entity entity;

    @Inject(method = "sendToListeners", at = @At("HEAD"))
    private void overseer$forwardToPortalViewers(Packet<? super ClientPlayPacketListener> packet, CallbackInfo ci) {
        RemoteEntityTracker.forward(this.entity, packet);
    }
}
