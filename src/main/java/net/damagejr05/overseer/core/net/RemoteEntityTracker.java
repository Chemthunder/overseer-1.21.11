package net.damagejr05.overseer.core.net;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
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
 * {@code EntityTrackerEntry}. It re-sends an absolute position for every visible entity every
 * tick instead of maintaining delta state, because the set it covers is small and bounded -- one
 * sphere around one anchor -- and a portal view is only ever a few dozen blocks deep. Correctness
 * under teleports, vehicles and rubber-banding comes for free when nothing is incremental.
 *
 * @author Flex
 */
public class RemoteEntityTracker {
    /** Block radius around the far anchor within which entities are streamed. */
    private static final double RADIUS = 48.0;

    /** How close a player must be to the near anchor before the far side is streamed to them. */
    private static final double ACTIVATION_DISTANCE = 48.0;

    /**
     * Entity ids currently sent to each player, per dimension.
     *
     * <p>Keyed by dimension first for the same reason the chunk tracker is: the two ends of a pair
     * are frequently in the same place in their own coordinate spaces, and a flat key would let
     * one dimension's bookkeeping mask the other's.
     */
    private static final Map<UUID, Map<RegistryKey<World>, IntSet>> SENT = new HashMap<>();

    public static void tick(MinecraftServer server) {
        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Map<RegistryKey<World>, List<Entity>> wanted = new HashMap<>();

            for (PortalPair pair : doorways.pairs()) {
                // Standing near either end streams whatever stands beyond the other one.
                collectIfNear(server, player, pair.outer(), pair.inner(), wanted);
                collectIfNear(server, player, pair.inner(), pair.outer(), wanted);
            }

            sync(player, wanted);
        }
    }

    private static void collectIfNear(MinecraftServer server, ServerPlayerEntity player,
                                      PortalAnchor near, PortalAnchor far,
                                      Map<RegistryKey<World>, List<Entity>> wanted) {
        if (player.getEntityWorld().getRegistryKey() != near.dimension()) {
            return;
        }

        // A pair with both ends in one dimension needs nothing streamed: the client already has
        // every entity there, and sending duplicates would spawn a second copy of each.
        if (far.dimension() == player.getEntityWorld().getRegistryKey()) {
            return;
        }

        if (player.getEntityPos().squaredDistanceTo(near.center()) > ACTIVATION_DISTANCE * ACTIVATION_DISTANCE) {
            return;
        }

        ServerWorld world = server.getWorld(far.dimension());
        if (world == null) {
            return;
        }

        Box box = Box.of(far.center(), RADIUS * 2, RADIUS * 2, RADIUS * 2);

        wanted.computeIfAbsent(far.dimension(), key -> new ArrayList<>())
                .addAll(world.getOtherEntities(null, box, entity -> !entity.isRemoved() && entity != player));
    }

    private static void sync(ServerPlayerEntity player, Map<RegistryKey<World>, List<Entity>> wanted) {
        Map<RegistryKey<World>, IntSet> sent = SENT.computeIfAbsent(player.getUuid(), key -> new HashMap<>());

        // Retire anything that has walked out of range, died, or whose portal has closed. Done
        // first so an id can be retired and re-spawned in the same tick if it moved between pairs.
        for (Map.Entry<RegistryKey<World>, IntSet> entry : sent.entrySet()) {
            IntSet previously = entry.getValue();
            if (previously.isEmpty()) {
                continue;
            }

            IntSet stillWanted = new IntOpenHashSet();
            for (Entity entity : wanted.getOrDefault(entry.getKey(), List.of())) {
                stillWanted.add(entity.getId());
            }

            IntList gone = new IntArrayList();
            previously.forEach((int id) -> {
                if (!stillWanted.contains(id)) {
                    gone.add(id);
                }
            });

            if (!gone.isEmpty()) {
                PacketRedirection.send(player, entry.getKey(), new EntitiesDestroyS2CPacket(gone));
                previously.removeAll(gone);
            }
        }

        for (Map.Entry<RegistryKey<World>, List<Entity>> entry : wanted.entrySet()) {
            RegistryKey<World> dimension = entry.getKey();
            IntSet previously = sent.computeIfAbsent(dimension, key -> new IntOpenHashSet());

            for (Entity entity : entry.getValue()) {
                if (previously.add(entity.getId())) {
                    spawn(player, dimension, entity);
                } else {
                    PacketRedirection.send(player, dimension, EntityPositionSyncS2CPacket.create(entity));
                }

                // The body's facing rides along with the position, but a head turns on its own,
                // and a mob whose head is welded straight ahead reads as a statue.
                if (entity instanceof LivingEntity living) {
                    PacketRedirection.send(player, dimension, new EntitySetHeadYawS2CPacket(
                            entity, (byte) MathHelper.floor(living.getHeadYaw() * 256.0F / 360.0F)
                    ));
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
}
