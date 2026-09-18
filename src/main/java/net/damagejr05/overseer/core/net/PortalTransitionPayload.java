package net.damagejr05.overseer.core.net;

import net.damagejr05.overseer.core.Overseer;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Tells the client that the dimension change it is about to receive is a portal crossing.
 *
 * <p>Vanilla shows a full loading screen on any dimension change, which is exactly the seam a
 * seamless portal is supposed to hide. The client uses this marker to suppress that screen for the
 * transition that immediately follows, without touching ordinary teleports.
 *
 * @author Flex
 */
public record PortalTransitionPayload() implements CustomPayload {
    public static final PortalTransitionPayload INSTANCE = new PortalTransitionPayload();

    public static final Id<PortalTransitionPayload> ID = new Id<>(Overseer.id("portal_transition"));

    public static final PacketCodec<RegistryByteBuf, PortalTransitionPayload> CODEC =
            PacketCodec.unit(INSTANCE);

    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
