package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.net.RedirectPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies a redirected packet to the secondary world it was tagged for.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class PacketRedirectionClient {
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(RedirectPayload.ID, (payload, context) ->
                handle(payload, context.client())
        );
    }

    @SuppressWarnings("unchecked")
    private static void handle(RedirectPayload payload, MinecraftClient client) {
        // Never touch the world the player is standing in. Vanilla owns that one completely, and
        // portal streaming has no business in it.
        //
        // Getting this wrong was destructive rather than merely useless: a stale unload tagged for
        // the dimension you just walked into would delete real terrain around you, and recentring
        // its chunk ring buffer on a portal anchor would drop everything else. That is what made
        // the memory dimension fall apart on arrival.
        if (client.world != null && client.world.getRegistryKey() == payload.dimension()) {
            return;
        }

        ClientWorld target = SecondaryWorldManager.getOrCreate(payload.dimension());

        if (target == null || target == client.world) {
            return;
        }

        Packet<ClientPlayPacketListener> packet = (Packet<ClientPlayPacketListener>) payload.packet();

        // A ClientChunkManager is a ring buffer around a centre and silently drops anything
        // outside it -- "Ignoring chunk since it's not in the view range". A secondary world never
        // gets the vanilla packet that sets that centre, so it is set here instead.
        //
        // Deliberately not done by redirecting the vanilla packets: ChunkLoadDistanceS2CPacket
        // writes a field on the shared network handler and a global game option, so redirecting it
        // would change the view distance of the world the player is actually standing in.
        if (packet instanceof ChunkDataS2CPacket) {
            centreOn(target, payload.dimension());
        }

        SecondaryWorldManager.withWorld(target, () -> applyTo(packet, client));
    }

    /** Last centre applied per secondary world, so the ring buffer is not thrashed every chunk. */
    private static final Map<RegistryKey<World>, Long> CENTRES = new HashMap<>();

    /**
     * Point a secondary world's chunk ring buffer at the portal anchor that lives in it.
     *
     * <p>The anchor is the centre of everything the server streams for that dimension, so it is
     * the correct focus. The doorway component is synced to the client, so no extra packet is
     * needed to know where it is.
     */
    private static void centreOn(ClientWorld target, RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(client.world);

        var anchors = doorways.anchorsIn(dimension);
        if (anchors.isEmpty()) {
            return;
        }

        PortalAnchor anchor = anchors.get(0);

        ChunkPos centre = new ChunkPos(BlockPos.ofFloored(anchor.center()));
        long packed = centre.toLong();

        Long previous = CENTRES.get(dimension);
        if (previous != null && previous == packed) {
            return;
        }

        target.getChunkManager().setChunkMapCenter(centre.x, centre.z);
        CENTRES.put(dimension, packed);
    }

    public static void clear() {
        CENTRES.clear();
    }

    /** Forget a dimension's centre so a world recreated later is centred afresh. */
    public static void forget(RegistryKey<World> dimension) {
        CENTRES.remove(dimension);
    }

    private static void applyTo(Packet<ClientPlayPacketListener> packet, MinecraftClient client) {
        ClientPlayPacketListener listener = client.getNetworkHandler();

        if (listener == null) {
            return;
        }

        try {
            packet.apply(listener);
        } catch (Throwable e) {
            Overseer.LOGGER.error("Failed to apply redirected packet {}", packet.getClass().getSimpleName(), e);
        }
    }
}
