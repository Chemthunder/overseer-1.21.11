package net.damagejr05.overseer.core.net;

import io.netty.buffer.ByteBuf;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Tells the client that the dimension change it is about to receive is a portal crossing, and
 * which doorway it is a crossing of.
 *
 * <p>Vanilla shows a full loading screen on any dimension change, which is exactly the seam a
 * seamless portal is supposed to hide. The client uses this marker to suppress that screen for the
 * transition that immediately follows, without touching ordinary teleports.
 *
 * <p>The two anchors are the doorway's rigid transform. The server places the player from where
 * it last heard they were, which is a round trip out of date by the time the answer lands; with
 * the transform in hand the client can carry its own, current position through instead, so the
 * view does not jump back by however far it walked in the meantime. See
 * {@code CrossingContinuity}.
 *
 * @author Flex
 */
public record PortalTransitionPayload(PortalAnchor from, PortalAnchor to) implements CustomPayload {
    public static final Id<PortalTransitionPayload> ID = new Id<>(Overseer.id("portal_transition"));

    private static final PacketCodec<ByteBuf, PortalAnchor> ANCHOR = PacketCodec.tuple(
            RegistryKey.createPacketCodec(RegistryKeys.WORLD), PortalAnchor::dimension,
            Vec3d.PACKET_CODEC, PortalAnchor::center,
            Direction.PACKET_CODEC, PortalAnchor::facing,
            PacketCodecs.FLOAT, PortalAnchor::width,
            PacketCodecs.FLOAT, PortalAnchor::height,
            PortalAnchor::new
    );

    public static final PacketCodec<RegistryByteBuf, PortalTransitionPayload> CODEC = PacketCodec.tuple(
            ANCHOR, PortalTransitionPayload::from,
            ANCHOR, PortalTransitionPayload::to,
            PortalTransitionPayload::new
    );

    public PortalTransitionPayload(PortalTransform transform) {
        this(transform.from(), transform.to());
    }

    public PortalTransform transform() {
        return new PortalTransform(from, to);
    }

    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
