package net.damagejr05.overseer.core.net;

import net.damagejr05.overseer.core.Overseer;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * A normal clientbound play packet, tagged with the dimension it belongs to.
 *
 * <p>This is the trick that makes multi-world clients tractable: rather than inventing a parallel
 * protocol for remote chunks and entities, the server sends the ordinary vanilla packets and marks
 * which world they describe. The client replays each one against the matching secondary
 * {@code ClientWorld}. Everything vanilla already knows how to serialise comes along for free.
 *
 * <p>Borrowed from Immersive Portals' {@code PacketRedirection}, rebuilt on 1.21.11's networking.
 *
 * @author Flex
 */
public record RedirectPayload(
        RegistryKey<World> dimension,
        Packet<? extends ClientPlayPacketListener> packet
) implements CustomPayload {
    public static final Id<RedirectPayload> ID = new Id<>(Overseer.id("redirect"));

    /**
     * The clientbound play protocol, bound so it can encode a packet into an arbitrary buffer.
     * The passed buffer is always a RegistryByteBuf in practice, so the mapping is just a cast.
     */
    private static final NetworkState<ClientPlayPacketListener> PLAY_S2C =
            PlayStateFactories.S2C.bind(buf -> (RegistryByteBuf) buf);

    public static final PacketCodec<RegistryByteBuf, RedirectPayload> CODEC =
            CustomPayload.codecOf(RedirectPayload::write, RedirectPayload::read);

    @SuppressWarnings("unchecked")
    private void write(RegistryByteBuf buf) {
        buf.writeIdentifier(dimension.getValue());
        PLAY_S2C.codec().encode(buf, (Packet<? super ClientPlayPacketListener>) packet);
    }

    @SuppressWarnings("unchecked")
    private static RedirectPayload read(RegistryByteBuf buf) {
        Identifier id = buf.readIdentifier();
        RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, id);

        Packet<? extends ClientPlayPacketListener> packet =
                (Packet<? extends ClientPlayPacketListener>) PLAY_S2C.codec().decode(buf);

        return new RedirectPayload(dimension, packet);
    }

    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
