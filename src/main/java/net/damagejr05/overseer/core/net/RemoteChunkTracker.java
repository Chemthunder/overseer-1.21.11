package net.damagejr05.overseer.core.net;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
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

import java.util.HashMap;
import java.util.HashSet;
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

    private static final int INTERVAL_TICKS = 20;

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

            for (PortalPair pair : doorways.pairs()) {
                // Standing near either end streams whatever lies beyond the other one.
                collectIfNear(player, pair.outer(), pair.inner(), wanted);
                collectIfNear(player, pair.inner(), pair.outer(), wanted);
            }

            sync(server, player, wanted);
        }
    }

    /**
     * If {@code player} is close to {@code near}, mark the chunks around {@code far} for streaming.
     */
    private static void collectIfNear(ServerPlayerEntity player, PortalAnchor near, PortalAnchor far,
                                      Map<RegistryKey<World>, Set<Long>> wanted) {
        if (player.getEntityWorld().getRegistryKey() != near.dimension()) {
            return;
        }

        // Never stream a dimension into itself. If both ends of a pair sit in the same world the
        // player is already in it, and the client must not receive a second, conflicting copy.
        if (far.dimension() == player.getEntityWorld().getRegistryKey()) {
            return;
        }

        if (player.getEntityPos().squaredDistanceTo(near.center()) > ACTIVATION_DISTANCE * ACTIVATION_DISTANCE) {
            return;
        }

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
}
