package net.damagejr05.overseer.core.portal;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the chunks under both portal anchors loaded.
 *
 * <p>Without this, walking into a portal whose far side sits in an ungenerated chunk stalls the
 * server while that chunk generates, which is exactly the hitch that makes a portal feel like a
 * teleport instead of a doorway. Holding a ticket on both ends means the destination is always
 * ready before anyone arrives.
 *
 * <p>Tickets are refreshed on a slow interval rather than every tick — they outlive the interval,
 * so re-adding constantly is wasted work.
 *
 * @author Flex
 */
public class PortalChunkLoader {
    /**
     * Chunk radius held around each anchor.
     *
     * <p>Must be at least the radius RemoteChunkTracker streams, or the outer ring is never
     * generated server-side and so never reaches the client to be rendered.
     */
    private static final int RADIUS = 4;

    /** Ticket lifetime comfortably exceeds this, so the refresh never leaves a gap. */
    private static final int REFRESH_INTERVAL_TICKS = 100;

    private static int counter = 0;

    public static void tick(MinecraftServer server) {
        if (counter++ % REFRESH_INTERVAL_TICKS != 0) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (PortalPair pair : doorways.pairs()) {
            hold(server, pair.outer());
            hold(server, pair.inner());
        }
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
