package net.damagejr05.overseer.core.net;

import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Streams the clock and the weather of a dimension seen through a portal.
 *
 * <p>The third companion to {@link RemoteChunkTracker} and {@link RemoteEntityTracker}. Vanilla
 * only ever tells a client the time and weather of the dimension it is standing in, so a secondary
 * world would otherwise sit at tick zero under a clear sky for ever: the overworld seen from the
 * memory dimension was permanently stuck at dawn, whatever the real time was.
 *
 * <p>Follows the chunk tracker rather than deciding for itself who is watching what, so the three
 * can never disagree about which dimensions a player holds.
 *
 * @author Flex
 */
public class RemoteEnvironmentTracker {
    /** Matches vanilla's own time sync. The client advances the clock itself in between. */
    private static final int TIME_INTERVAL_TICKS = 20;

    private record Weather(float rain, float thunder) {
    }

    /** The weather each player was last told about, per dimension. */
    private static final Map<UUID, Map<RegistryKey<World>, Weather>> SENT = new HashMap<>();

    private static int counter = 0;

    public static void tick(MinecraftServer server) {
        boolean timeDue = counter++ % TIME_INTERVAL_TICKS == 0;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Map<RegistryKey<World>, Weather> sent = SENT.get(player.getUuid());

            for (ServerWorld world : server.getWorlds()) {
                RegistryKey<World> dimension = world.getRegistryKey();

                if (!RemoteChunkTracker.isStreaming(player, dimension)) {
                    if (sent != null) {
                        sent.remove(dimension);
                    }
                    continue;
                }

                if (sent == null) {
                    sent = SENT.computeIfAbsent(player.getUuid(), key -> new HashMap<>());
                }

                Weather previous = sent.get(dimension);
                Weather current = new Weather(world.getRainGradient(1.0F), world.getThunderGradient(1.0F));

                // A dimension that has only just started streaming gets everything at once, so the
                // first frame through the doorway is already at the right time of day.
                boolean fresh = previous == null;

                if (fresh || timeDue) {
                    PacketRedirection.send(player, dimension, new WorldTimeUpdateS2CPacket(
                            world.getTime(),
                            world.getTimeOfDay(),
                            world.getGameRules().getValue(GameRules.ADVANCE_TIME)
                    ));
                }

                // Gradients move a hundredth per tick while the weather turns, so these go out as
                // they change rather than on the slow interval, or a storm rolls in in visible steps.
                if (fresh || current.rain() != previous.rain()) {
                    PacketRedirection.send(player, dimension, new GameStateChangeS2CPacket(
                            GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, current.rain()
                    ));
                }

                if (fresh || current.thunder() != previous.thunder()) {
                    PacketRedirection.send(player, dimension, new GameStateChangeS2CPacket(
                            GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, current.thunder()
                    ));
                }

                sent.put(dimension, current);
            }
        }
    }

    /** See {@link RemoteChunkTracker#onWorldChange}: the client has dropped its copy, so do the same. */
    public static void onWorldChange(ServerPlayerEntity player, RegistryKey<World> destination) {
        Map<RegistryKey<World>, Weather> sent = SENT.get(player.getUuid());
        if (sent != null) {
            sent.remove(destination);
        }
    }

    public static void forget(ServerPlayerEntity player) {
        SENT.remove(player.getUuid());
    }

    public static void clear() {
        SENT.clear();
    }
}
