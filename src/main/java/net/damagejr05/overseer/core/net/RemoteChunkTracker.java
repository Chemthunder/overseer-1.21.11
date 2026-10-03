package net.damagejr05.overseer.core.net;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Streams the far side of a portal to players standing near the near side.
 *
 * <p>A player in the overworld looking through a doorway needs the memory dimension's chunks on
 * their client to see anything real through it. The server sends ordinary
 * {@link ChunkDataS2CPacket}s tagged for that dimension via {@link PacketRedirection}, and the
 * client applies them to its secondary world.
 *
 * <p>Tracking is scoped to the two anchors rather than to general player movement. Unlike a
 * general-purpose implementation there are only ever two endpoints, so the working set is tiny and
 * bounded by {@link #RADIUS}.
 *
 * @author Flex
 */
public class RemoteChunkTracker {
    /** Chunk radius streamed around the far anchor. */
    private static final int RADIUS = 4;

    /** How close a player must be to the near anchor before the far side is streamed to them. */
    private static final double ACTIVATION_DISTANCE = 48.0;

    /**
     * A full second here was visible: step through and the doorway behind you showed the void
     * until the next pass came round. The pass itself is a set difference over a few dozen
     * entries, so there is no reason to make anyone wait for it.
     */
    private static final int INTERVAL_TICKS = 5;

    /**
     * Chunks currently sent to each player, per dimension.
     *
     * <p>Keyed by dimension first and chunk position second. A flat chunk-position key collides
     * across dimensions -- the two ends of a pair frequently sit in the same chunk coordinates --
     * and a collision makes the tracker believe a chunk it sent for one world already covers the
     * other, so it silently streams nothing.
     */
    private static final Map<UUID, Map<RegistryKey<World>, Set<Long>>> SENT = new HashMap<>();

    private static int counter = 0;

    public static void tick(MinecraftServer server) {
        if (counter++ % INTERVAL_TICKS != 0) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Map<RegistryKey<World>, Set<Long>> wanted = new HashMap<>();

            PortalTransform streamed = streamed(player, doorways);
            if (streamed != null) {
                collect(streamed.to(), wanted);
            }

            sync(server, player, wanted);
        }
    }

    /**
     * The one doorway whose far side is streamed to {@code player}: the nearest one in range.
     *
     * <p>One, not all of them. The client holds each remote dimension in a single window of
     * chunks around a single centre, and two doorways standing side by side can lead to places
     * thousands of blocks apart -- which is exactly what happens once doorways are opened from
     * inside the memory dimension. Streaming both sent the client chunks its window could not
     * hold; it dropped them, this side still counted them as delivered, and that doorway showed
     * the void from then on. Only one doorway is ever drawn with a live view anyway.
     *
     * <p>Shared with the entity tracker, so the two always agree about which place is in view.
     */
    public static @Nullable PortalTransform streamed(ServerPlayerEntity player, DoorwayComponent doorways) {
        RegistryKey<World> dimension = player.getEntityWorld().getRegistryKey();

        // The portal an eviction opens takes over the view of everyone beneath it, however far
        // off it hangs: it is the one thing in the sky, and it is about to be where they are.
        PortalPair eviction = doorways.getEviction();
        if (eviction != null && eviction.inner().dimension() == dimension
                && eviction.outer().dimension() != dimension) {
            return eviction.outward();
        }

        PortalTransform best = null;
        double bestDistance = ACTIVATION_DISTANCE * ACTIVATION_DISTANCE;

        for (PortalPair pair : doorways.pairs()) {
            PortalTransform candidate = pair.from(dimension);

            // Never stream a dimension into itself. If both ends of a pair sit in the same world
            // the player is already in it, and the client must not receive a second, conflicting
            // copy.
            if (candidate == null || candidate.to().dimension() == dimension) {
                continue;
            }

            double distance = player.getEntityPos().squaredDistanceTo(candidate.from().center());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private static void collect(PortalAnchor far, Map<RegistryKey<World>, Set<Long>> wanted) {
        ChunkPos centre = new ChunkPos(BlockPos.ofFloored(far.center()));

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                wanted.computeIfAbsent(far.dimension(), key -> new HashSet<>())
                        .add(ChunkPos.toLong(centre.x + x, centre.z + z));
            }
        }
    }

    private static void sync(MinecraftServer server, ServerPlayerEntity player,
                             Map<RegistryKey<World>, Set<Long>> wanted) {
        Map<RegistryKey<World>, Set<Long>> sent =
                SENT.computeIfAbsent(player.getUuid(), uuid -> new HashMap<>());

        RegistryKey<World> playerDimension = player.getEntityWorld().getRegistryKey();

        for (Map.Entry<RegistryKey<World>, Set<Long>> entry : new HashMap<>(sent).entrySet()) {
            RegistryKey<World> dimension = entry.getKey();
            Set<Long> stillWanted = wanted.getOrDefault(dimension, Set.of());

            for (Long key : new HashSet<>(entry.getValue())) {
                if (stillWanted.contains(key)) {
                    continue;
                }

                // The player has walked into this dimension. It is vanilla's to manage now, so
                // forget it silently -- an unload here would tear a hole in the world they are in.
                if (dimension != playerDimension) {
                    PacketRedirection.send(player, dimension, new UnloadChunkS2CPacket(new ChunkPos(key)));
                }

                entry.getValue().remove(key);
            }

            sent.put(dimension, entry.getValue());
        }

        for (Map.Entry<RegistryKey<World>, Set<Long>> entry : wanted.entrySet()) {
            RegistryKey<World> dimension = entry.getKey();

            ServerWorld world = server.getWorld(dimension);
            if (world == null) {
                continue;
            }

            Set<Long> alreadySent = sent.computeIfAbsent(dimension, key -> new HashSet<>());

            for (Long key : entry.getValue()) {
                if (alreadySent.contains(key)) {
                    continue;
                }

                ChunkPos pos = new ChunkPos(key);
                WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);

                if (chunk == null) {
                    // Not generated yet. PortalChunkLoader holds tickets on the anchors, so this
                    // resolves on a later pass rather than blocking the tick to generate it here.
                    continue;
                }

                PacketRedirection.send(
                        player,
                        dimension,
                        new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null)
                );

                alreadySent.add(key);
            }
        }
    }

    public static void forget(ServerPlayerEntity player) {
        SENT.remove(player.getUuid());
    }

    public static void clear() {
        SENT.clear();
    }

    /**
     * The player has arrived in {@code destination}, and vanilla owns that dimension for them now.
     *
     * <p>The client drops its secondary copy the moment it walks in, so the bookkeeping has to be
     * dropped at the same moment. Leaving it for the next pass loses a race: step through and
     * straight back inside one interval and this side still believes everything was delivered,
     * never resends it, and the doorway shows the void until the player walks out of range.
     */
    public static void onWorldChange(ServerPlayerEntity player, RegistryKey<World> destination) {
        Map<RegistryKey<World>, Set<Long>> sent = SENT.get(player.getUuid());
        if (sent != null) {
            sent.remove(destination);
        }
    }

    /** Whether any of {@code dimension} is currently being streamed to {@code player}. */
    public static boolean isStreaming(ServerPlayerEntity player, RegistryKey<World> dimension) {
        Map<RegistryKey<World>, Set<Long>> sent = SENT.get(player.getUuid());
        if (sent == null) {
            return false;
        }

        Set<Long> chunks = sent.get(dimension);
        return chunks != null && !chunks.isEmpty();
    }

    /**
     * Everyone holding a streamed copy of one chunk, so changes to it can follow the original.
     * Never includes players standing in {@code dimension}: vanilla already updates those.
     */
    public static List<ServerPlayerEntity> viewers(MinecraftServer server, RegistryKey<World> dimension, ChunkPos pos) {
        if (SENT.isEmpty()) {
            return List.of();
        }

        long key = pos.toLong();
        List<ServerPlayerEntity> result = null;

        for (Map.Entry<UUID, Map<RegistryKey<World>, Set<Long>>> entry : SENT.entrySet()) {
            Set<Long> chunks = entry.getValue().get(dimension);
            if (chunks == null || !chunks.contains(key)) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || player.getEntityWorld().getRegistryKey() == dimension) {
                continue;
            }

            if (result == null) {
                result = new ArrayList<>(1);
            }
            result.add(player);
        }

        return result == null ? List.of() : result;
    }
}
