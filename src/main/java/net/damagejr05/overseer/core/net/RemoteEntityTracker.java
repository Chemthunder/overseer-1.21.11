package net.damagejr05.overseer.core.net;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streams the entities standing beyond a portal, so the view through it is not an empty world.
 *
 * <p>The companion to {@link RemoteChunkTracker}, which does the same for terrain. Between them
 * they are the whole of what a client needs to render a dimension it is not in: that tracker sends
 * the blocks, this one sends everything that moves.
 *
 * <p>This is a deliberately simple tracker rather than a second copy of vanilla's
 * {@code EntityTrackerEntry}. Positions are always sent absolutely rather than as deltas, so
 * teleports, vehicles and rubber-banding need no special handling -- but only when they have
 * changed. Sending every entity every tick, as this once did, was two packets a tick for each
 * item frame, armour stand and sleeping cow near the far side of a doorway, to every player near
 * the near side, and around a built-up base that drowned the connection.
 *
 * <p>Everything that is <em>not</em> movement -- tracked data, equipment, attributes, swings, hurt
 * and death animations -- is not re-derived here at all. Vanilla's own tracker already works out
 * exactly when each of those changes, so {@link #forward} simply copies what it broadcasts.
 *
 * @author Flex
 */
public class RemoteEntityTracker {
    /** Block radius around the far anchor within which entities are streamed. */
    private static final double RADIUS = 48.0;

    /** How close a player must be to the near anchor before the far side is streamed to them. */
    private static final double ACTIVATION_DISTANCE = 48.0;

    /** Where an entity was, and which way it faced, when a player was last told. */
    private record Motion(double x, double y, double z, float yaw, float pitch, boolean onGround, byte headYaw) {
        static Motion of(Entity entity) {
            return new Motion(
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(), entity.isOnGround(),
                    (byte) MathHelper.floor(entity.getHeadYaw() * 256.0F / 360.0F)
            );
        }

        boolean movedSince(Motion before) {
            return x != before.x || y != before.y || z != before.z
                    || yaw != before.yaw || pitch != before.pitch || onGround != before.onGround;
        }
    }

    /**
     * Entities currently sent to each player, per dimension, by id, with what was last sent.
     *
     * <p>Keyed by dimension first for the same reason the chunk tracker is: the two ends of a pair
     * are frequently in the same place in their own coordinate spaces, and a flat key would let
     * one dimension's bookkeeping mask the other's.
     */
    private static final Map<UUID, Map<RegistryKey<World>, Int2ObjectMap<Motion>>> SENT = new HashMap<>();

    /**
     * Every id streamed to anyone, rebuilt each tick.
     *
     * <p>{@link #forward} is called for every packet every tracked entity on the server
     * broadcasts, and almost none of them are near a doorway. This turns the common case into a
     * single lookup.
     */
    private static final IntSet STREAMED = new IntOpenHashSet();

    public static void tick(MinecraftServer server) {
        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Map<RegistryKey<World>, List<Entity>> wanted = new HashMap<>();

            // The same doorway the chunk tracker is streaming terrain for, and no other.
            PortalTransform streamed = RemoteChunkTracker.streamed(player, doorways);
            if (streamed != null) {
                collect(server, player, streamed.to(), wanted);
            }

            sync(player, wanted);
        }

        STREAMED.clear();
        for (Map<RegistryKey<World>, Int2ObjectMap<Motion>> perPlayer : SENT.values()) {
            for (Int2ObjectMap<Motion> ids : perPlayer.values()) {
                STREAMED.addAll(ids.keySet());
            }
        }
    }

    /**
     * Copy a packet vanilla is broadcasting about {@code entity} to everyone watching it through
     * a portal. Called from the tracker's own send path, so it sees every update vanilla makes.
     */
    public static void forward(Entity entity, Packet<? super ClientPlayPacketListener> packet) {
        if (STREAMED.isEmpty() || !STREAMED.contains(entity.getId()) || isHandledHere(packet)) {
            return;
        }

        if (!(entity.getEntityWorld() instanceof ServerWorld world) || !world.getServer().isOnThread()) {
            return;
        }

        RegistryKey<World> dimension = world.getRegistryKey();

        for (Map.Entry<UUID, Map<RegistryKey<World>, Int2ObjectMap<Motion>>> entry : SENT.entrySet()) {
            Int2ObjectMap<Motion> ids = entry.getValue().get(dimension);
            if (ids == null || !ids.containsKey(entity.getId())) {
                continue;
            }

            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());

            // Anyone standing in the entity's own world is already one of vanilla's listeners.
            if (player == null || player.getEntityWorld() == world) {
                continue;
            }

            player.networkHandler.sendPacket(PacketRedirection.wrap(dimension, packet));
        }
    }

    /**
     * Movement is sent absolutely by {@link #sync}; vanilla's relative moves are deltas
     * against a baseline the remote client never had. Passenger and leash packets name a second
     * entity that may well not be streamed, and the client logs a warning for each one it cannot
     * resolve.
     */
    private static boolean isHandledHere(Packet<?> packet) {
        return packet instanceof EntityS2CPacket
                || packet instanceof EntityPositionSyncS2CPacket
                || packet instanceof EntityPositionS2CPacket
                || packet instanceof EntitySetHeadYawS2CPacket
                || packet instanceof EntityPassengersSetS2CPacket
                || packet instanceof EntityAttachS2CPacket;
    }

    private static void collect(MinecraftServer server, ServerPlayerEntity player, PortalAnchor far,
                                Map<RegistryKey<World>, List<Entity>> wanted) {
        ServerWorld world = server.getWorld(far.dimension());
        if (world == null) {
            return;
        }

        Box box = Box.of(far.center(), RADIUS * 2, RADIUS * 2, RADIUS * 2);

        wanted.computeIfAbsent(far.dimension(), key -> new ArrayList<>())
                .addAll(world.getOtherEntities(null, box, entity -> !entity.isRemoved() && entity != player));
    }

    private static void sync(ServerPlayerEntity player, Map<RegistryKey<World>, List<Entity>> wanted) {
        Map<RegistryKey<World>, Int2ObjectMap<Motion>> sent = SENT.computeIfAbsent(player.getUuid(), key -> new HashMap<>());

        // Retire anything that has walked out of range, died, or whose portal has closed. Done
        // first so an id can be retired and re-spawned in the same tick if it moved between pairs.
        for (Map.Entry<RegistryKey<World>, Int2ObjectMap<Motion>> entry : sent.entrySet()) {
            Int2ObjectMap<Motion> previously = entry.getValue();
            if (previously.isEmpty()) {
                continue;
            }

            IntSet stillWanted = new IntOpenHashSet();
            for (Entity entity : wanted.getOrDefault(entry.getKey(), List.of())) {
                stillWanted.add(entity.getId());
            }

            IntList gone = new IntArrayList();
            previously.keySet().forEach((int id) -> {
                if (!stillWanted.contains(id)) {
                    gone.add(id);
                }
            });

            if (!gone.isEmpty()) {
                PacketRedirection.send(player, entry.getKey(), new EntitiesDestroyS2CPacket(gone));
                previously.keySet().removeAll(gone);
            }
        }

        for (Map.Entry<RegistryKey<World>, List<Entity>> entry : wanted.entrySet()) {
            RegistryKey<World> dimension = entry.getKey();
            Int2ObjectMap<Motion> previously = sent.computeIfAbsent(dimension, key -> new Int2ObjectOpenHashMap<>());

            for (Entity entity : entry.getValue()) {
                Motion now = Motion.of(entity);
                Motion before = previously.put(entity.getId(), now);

                // The spawn carries the position and the head's facing with it.
                if (before == null) {
                    spawn(player, dimension, entity);
                    continue;
                }

                if (now.movedSince(before)) {
                    PacketRedirection.send(player, dimension, EntityPositionSyncS2CPacket.create(entity));
                }

                // The body's facing rides along with the position, but a head turns on its own,
                // and a mob whose head is welded straight ahead reads as a statue.
                if (entity instanceof LivingEntity && now.headYaw() != before.headYaw()) {
                    PacketRedirection.send(player, dimension, new EntitySetHeadYawS2CPacket(entity, now.headYaw()));
                }
            }
        }
    }

    private static void spawn(ServerPlayerEntity player, RegistryKey<World> dimension, Entity entity) {
        PacketRedirection.send(player, dimension, new EntitySpawnS2CPacket(
                entity.getId(),
                entity.getUuid(),
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getPitch(), entity.getYaw(),
                entity.getType(),
                0,
                entity.getVelocity(),
                entity.getHeadYaw()
        ));

        // Without the tracked data the client has a correctly placed entity with none of its
        // state: no pose, no name, no baby flag, and a player rendered with default skin parts.
        List<DataTracker.SerializedEntry<?>> tracked = entity.getDataTracker().getChangedEntries();
        if (tracked != null && !tracked.isEmpty()) {
            PacketRedirection.send(player, dimension, new EntityTrackerUpdateS2CPacket(entity.getId(), tracked));
        }

        if (entity instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getEquippedStack(slot);
                if (!stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }

            if (!equipment.isEmpty()) {
                PacketRedirection.send(player, dimension, new EntityEquipmentUpdateS2CPacket(entity.getId(), equipment));
            }
        }
    }

    /** Drop a player's bookkeeping. Their client is gone, so nothing needs retiring first. */
    public static void forget(ServerPlayerEntity player) {
        SENT.remove(player.getUuid());
    }

    /**
     * See {@link RemoteChunkTracker#onWorldChange}. The client has already thrown away its copy of
     * {@code destination}, entities included, so there is nothing to retire.
     */
    public static void onWorldChange(ServerPlayerEntity player, RegistryKey<World> destination) {
        Map<RegistryKey<World>, Int2ObjectMap<Motion>> sent = SENT.get(player.getUuid());
        if (sent != null) {
            sent.remove(destination);
        }
    }

    public static void clear() {
        SENT.clear();
        STREAMED.clear();
    }
}
