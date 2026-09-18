package net.damagejr05.overseer.core.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server side of packet redirection.
 *
 * <p>Work done for a world the player is not currently in gets wrapped in
 * {@link RedirectPayload} so the client can apply it to the right secondary world. Rather than
 * finding and rewriting every call site that sends a chunk or entity packet, a thread-local scope
 * marks "everything sent from here belongs to dimension X" and the send mixin does the wrapping.
 *
 * @author Flex
 */
public class PacketRedirection {
    private static final ThreadLocal<RegistryKey<World>> REDIRECT = new ThreadLocal<>();

    public static void init() {
        PayloadTypeRegistry.playS2C().register(RedirectPayload.ID, RedirectPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PortalTransitionPayload.ID, PortalTransitionPayload.CODEC);
    }

    /**
     * Run {@code action}, tagging every packet it sends as belonging to {@code world}.
     *
     * <p>Must be called on the server thread — the scope is thread-local, so running it off-thread
     * would silently tag the wrong packets or none at all.
     */
    public static void withRedirect(ServerWorld world, Runnable action) {
        withRedirect(world, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T withRedirect(ServerWorld world, Supplier<T> action) {
        RegistryKey<World> previous = REDIRECT.get();
        REDIRECT.set(world.getRegistryKey());

        try {
            return action.get();
        } finally {
            if (previous == null) {
                REDIRECT.remove();
            } else {
                REDIRECT.set(previous);
            }
        }
    }

    /**
     * The dimension packets are currently being tagged with, or null outside any redirect scope.
     */
    public static @Nullable RegistryKey<World> current() {
        return REDIRECT.get();
    }

    /**
     * Send a single packet tagged for a dimension, without opening a scope.
     */
    public static void send(ServerPlayerEntity player, RegistryKey<World> dimension, Packet<? extends ClientPlayPacketListener> packet) {
        player.networkHandler.sendPacket(wrap(dimension, packet));
    }

    /**
     * Wrap a packet for a dimension. Bundles are recursed into rather than wrapped whole, because
     * vanilla gives bundle packets special handling on the receiving end and a wrapped bundle
     * would never reach it.
     */
    @SuppressWarnings("unchecked")
    public static Packet<?> wrap(RegistryKey<World> dimension, Packet<?> packet) {
        if (isRedirected(packet)) {
            return packet;
        }

        if (packet instanceof BundleS2CPacket bundle) {
            List<Packet<? super ClientPlayPacketListener>> wrapped = new ArrayList<>();

            for (Packet<? super ClientPlayPacketListener> sub : bundle.getPackets()) {
                wrapped.add((Packet<? super ClientPlayPacketListener>) wrap(dimension, sub));
            }

            return new BundleS2CPacket(wrapped);
        }

        return new CustomPayloadS2CPacket(
                new RedirectPayload(dimension, (Packet<? extends ClientPlayPacketListener>) packet)
        );
    }

    public static boolean isRedirected(Packet<?> packet) {
        return packet instanceof CustomPayloadS2CPacket custom
                && custom.payload() instanceof RedirectPayload;
    }
}
