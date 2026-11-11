package net.damagejr05.overseer.core.portal;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the chunks under both ends of a doorway loaded while anyone is near enough to use it.
 *
 * <p>Without this, walking into a portal whose far side sits in an ungenerated chunk stalls the
 * server while that chunk generates, which is exactly the hitch that makes a portal feel like a
 * teleport instead of a doorway. Holding a ticket on both ends means the destination is always
 * ready before anyone arrives.
 *
 * <p>Only while somebody is near, though. The tickets used to be held unconditionally: eleven
 * chunks square at each end of every pair, loaded and ticking for as long as the server ran,
 * whether or not a single player was in either dimension. For the permanent doorway that is
 * nearly two hundred and fifty chunks of terrain, entities and block entities simulated around
 * the clock for the benefit of nobody. A ticket that is not renewed simply lapses, so standing
 * down is a matter of not asking again.
 *
 * @author Flex
 */
public class PortalChunkLoader {
    /**
     * Chunk radius held around each anchor.
     *
     * <p>One more than the radius RemoteChunkTracker streams. A ticket's level falls off by one
     * per chunk and the outermost ring it reaches is loaded but not ticking, and a chunk that is
     * not ticking never broadcasts block changes -- so at an equal radius the edge of the streamed
     * area would arrive once and then never update.
     */
    private static final int RADIUS = 5;

    /**
     * How near a player must be to either end of a pair for both ends to be held, in blocks.
     *
     * <p>Well beyond the distance at which the far side starts being streamed, because loading is
     * not instant and a far side that has never been visited has to be generated first. From here
     * a sprinting player is still some fourteen seconds short of that distance.
     */
    private static final double HOLD_DISTANCE = 128.0;

    /** A portal ticket lasts 300 ticks, so this renews it many times over before it can lapse. */
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private static int counter = 0;

    public static void tick(MinecraftServer server) {
        if (counter++ % REFRESH_INTERVAL_TICKS != 0) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);
        PortalPair eviction = doorways.getEviction();

        for (PortalPair pair : doorways.pairs()) {
            // The portal an eviction opens is about to be used by everyone under it, wherever
            // they happen to be standing, and does not last long enough to be worth rationing.
            if (pair.equals(eviction) || inUse(server, pair.outer()) || inUse(server, pair.inner())) {
                hold(server, pair.outer());
                hold(server, pair.inner());
            }
        }
    }

    private static boolean inUse(MinecraftServer server, @Nullable PortalAnchor anchor) {
        if (anchor == null) {
            return false;
        }

        ServerWorld world = server.getWorld(anchor.dimension());
        if (world == null) {
            return false;
        }

        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getEntityPos().squaredDistanceTo(anchor.center()) <= HOLD_DISTANCE * HOLD_DISTANCE) {
                return true;
            }
        }

        return false;
    }

    private static void hold(MinecraftServer server, @Nullable PortalAnchor anchor) {
        if (anchor == null) {
            return;
        }

        ServerWorld world = server.getWorld(anchor.dimension());
        if (world == null) {
            return;
        }

        ChunkPos pos = new ChunkPos(BlockPos.ofFloored(anchor.center()));

        world.getChunkManager().addTicket(ChunkTicketType.PORTAL, pos, RADIUS);
    }
}
