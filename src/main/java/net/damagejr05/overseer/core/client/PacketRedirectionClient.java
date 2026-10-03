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
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
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

        Packet<ClientPlayPacketListener> packet = (Packet<ClientPlayPacketListener>) payload.packet();

        // Only terrain brings a secondary world into being. Everything else describes a world
        // that should already exist, and if it does not -- it was dropped a moment ago, and this
        // is a late unload or a last entity update for it -- building a whole world and renderer
        // just to apply that to nothing is pure waste.
        ClientWorld target = packet instanceof ChunkDataS2CPacket
                ? SecondaryWorldManager.getOrCreate(payload.dimension())
                : SecondaryWorldManager.get(payload.dimension());

        if (target == null || target == client.world) {
            return;
        }

        // Clock and weather are applied by hand rather than replayed through the network handler.
        // Its handlers do more than touch the world: the time packet also advances the telemetry
        // session, and a game-state packet can change the player's game mode or roll the credits.
        if (packet instanceof WorldTimeUpdateS2CPacket time) {
            target.setTime(time.time(), time.timeOfDay(), time.tickDayTime());
            return;
        }

        if (packet instanceof GameStateChangeS2CPacket state) {
            applyWeather(target, state);
            return;
        }

        // A ClientChunkManager is a ring buffer around a centre and silently drops anything
        // outside it -- "Ignoring chunk since it's not in the view range". A secondary world never
        // gets the vanilla packet that sets that centre, so it is set here instead.
        //
        // Deliberately not done by redirecting the vanilla packets: ChunkLoadDistanceS2CPacket
        // writes a field on the shared network handler and a global game option, so redirecting it
        // would change the view distance of the world the player is actually standing in.
        if (packet instanceof ChunkDataS2CPacket chunk) {
            centreOn(target, payload.dimension(), chunk.getChunkX(), chunk.getChunkZ());
        }

        SecondaryWorldManager.withWorld(target, () -> applyTo(packet, client));
    }

    private static void applyWeather(ClientWorld target, GameStateChangeS2CPacket state) {
        GameStateChangeS2CPacket.Reason reason = state.getReason();

        if (reason == GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED) {
            target.getLevelProperties().setRaining(state.getValue() > 0.0F);
            target.setRainGradient(state.getValue());
        } else if (reason == GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED) {
            target.setThunderGradient(state.getValue());
        }
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
    private static void centreOn(ClientWorld target, RegistryKey<World> dimension, int chunkX, int chunkZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(client.world);

        // Whichever anchor this chunk was sent for. There can be two in one dimension, far apart,
        // and the server streams around one at a time; taking the first in the list regardless
        // left the window centred on the wrong one and every chunk for the other was dropped.
        PortalAnchor anchor = null;
        long best = Long.MAX_VALUE;

        for (PortalAnchor candidate : doorways.anchorsIn(dimension)) {
            ChunkPos at = new ChunkPos(BlockPos.ofFloored(candidate.center()));
            long dx = at.x - chunkX;
            long dz = at.z - chunkZ;
            long distance = dx * dx + dz * dz;

            if (distance < best) {
                best = distance;
                anchor = candidate;
            }
        }

        if (anchor == null) {
            return;
        }

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
