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

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
     * The most chunks sent to one player in one tick.
     *
     * <p>The far side used to go out all at once: eighty-one chunks in a single tick, the moment
     * a player came within range of a doorway or stepped through one. The server had to serialise
     * all of them in that tick, and the client then had to unpack, light and queue for meshing
     * all of them in one frame -- a freeze of a few hundred milliseconds on a slow machine, timed
     * for exactly the moment of walking through.
     *
     * <p>A few per tick, nearest the doorway first, spreads the same work over about a second.
     * What can be seen through the opening is overwhelmingly the chunks right behind it, and
     * those still arrive within the first couple of ticks.
     */
    private static final int CHUNKS_PER_TICK = 4;

    /** Every offset within {@link #RADIUS} of the centre, nearest first. */
    private static final int[][] NEAREST_FIRST = nearestFirst();

    private static int[][] nearestFirst() {
        List<int[]> offsets = new ArrayList<>();

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                offsets.add(new int[]{x, z});
            }
        }

        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        return offsets.toArray(new int[0][]);
    }

    /**
     * Chunks currently sent to each player, per dimension.
     *
     * <p>Keyed by dimension first and chunk position second. A flat chunk-position key collides
     * across dimensions -- the two ends of a pair frequently sit in the same chunk coordinates --
     * and a collision makes the tracker believe a chunk it sent for one world already covers the
     * other, so it silently streams nothing.
     */
    private static final Map<UUID, Map<RegistryKey<World>, LongSet>> SENT = new HashMap<>();

    public static void tick(MinecraftServer server) {
        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PortalTransform streamed = streamed(player, doorways);
            sync(server, player, streamed == null ? null : streamed.to());
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

    /**
     * Bring what one player holds into line with the area around {@code far}, or with nothing.
     *
     * <p>Runs every tick, so the common case has to cost nothing: a player nowhere near a doorway
     * who has never been sent a chunk leaves on the first line.
     */
    private static void sync(MinecraftServer server, ServerPlayerEntity player, @Nullable PortalAnchor far) {
        Map<RegistryKey<World>, LongSet> sent = SENT.get(player.getUuid());

        if (sent == null) {
            if (far == null) {
                return;
            }

            sent = new HashMap<>();
            SENT.put(player.getUuid(), sent);
        }

        RegistryKey<World> playerDimension = player.getEntityWorld().getRegistryKey();
        RegistryKey<World> wantedDimension = far == null ? null : far.dimension();
        ChunkPos centre = far == null ? null : new ChunkPos(BlockPos.ofFloored(far.center()));

        // Unloads go out at once and in full. They cost nothing to make or to apply, and the
        // client's window of chunks may be about to move somewhere these would not fit.
        for (Iterator<Map.Entry<RegistryKey<World>, LongSet>> entries = sent.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<RegistryKey<World>, LongSet> entry = entries.next();
            RegistryKey<World> dimension = entry.getKey();
            boolean wanted = dimension == wantedDimension;

            for (LongIterator chunks = entry.getValue().iterator(); chunks.hasNext(); ) {
                long key = chunks.nextLong();

                if (wanted && Math.abs(ChunkPos.getPackedX(key) - centre.x) <= RADIUS
                        && Math.abs(ChunkPos.getPackedZ(key) - centre.z) <= RADIUS) {
                    continue;
                }

                // The player has walked into this dimension. It is vanilla's to manage now, so
                // forget it silently -- an unload here would tear a hole in the world they are in.
                if (dimension != playerDimension) {
                    PacketRedirection.send(player, dimension, new UnloadChunkS2CPacket(new ChunkPos(key)));
                }

                chunks.remove();
            }

            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }

        if (far == null) {
            return;
        }

        ServerWorld world = server.getWorld(wantedDimension);
        if (world == null) {
            return;
        }

        LongSet alreadySent = sent.get(wantedDimension);

        // Everything is there, which is the state this spends nearly all of its time in.
        if (alreadySent != null && alreadySent.size() >= NEAREST_FIRST.length) {
            return;
        }

        int budget = CHUNKS_PER_TICK;

        for (int[] offset : NEAREST_FIRST) {
            int x = centre.x + offset[0];
            int z = centre.z + offset[1];
            long key = ChunkPos.toLong(x, z);

            if (alreadySent != null && alreadySent.contains(key)) {
                continue;
            }

            WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z);

            if (chunk == null) {
                // Not generated yet. PortalChunkLoader holds tickets on the anchors, so this
                // resolves on a later pass rather than blocking the tick to generate it here.
                continue;
            }

            PacketRedirection.send(
                    player,
                    wantedDimension,
                    new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null)
            );

            if (alreadySent == null) {
                alreadySent = new LongOpenHashSet();
                sent.put(wantedDimension, alreadySent);
            }

            alreadySent.add(key);

            if (--budget == 0) {
                break;
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
        Map<RegistryKey<World>, LongSet> sent = SENT.get(player.getUuid());
        if (sent != null) {
            sent.remove(destination);
        }
    }

    /** Whether any of {@code dimension} is currently being streamed to {@code player}. */
    public static boolean isStreaming(ServerPlayerEntity player, RegistryKey<World> dimension) {
        Map<RegistryKey<World>, LongSet> sent = SENT.get(player.getUuid());
        if (sent == null) {
            return false;
        }

        LongSet chunks = sent.get(dimension);
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

        for (Map.Entry<UUID, Map<RegistryKey<World>, LongSet>> entry : SENT.entrySet()) {
            LongSet chunks = entry.getValue().get(dimension);
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
