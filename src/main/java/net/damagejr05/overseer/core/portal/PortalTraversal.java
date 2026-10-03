package net.damagejr05.overseer.core.portal;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.net.PortalTransitionPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries everything that is not a player on foot through a doorway.
 *
 * <p>A doorway that only the person who opened it can use is a trick; one that a thrown item, an
 * arrow, a wandering cow or a boat with someone in it passes through is a hole in the world. This
 * is the same swept-segment test {@code PortalComponent} runs for players, applied to whatever
 * else is moving near either end of a pair.
 *
 * <p>Only the bottom of a stack of riders is ever tested. Vanilla's teleport carries passengers
 * along with their vehicle and seats them again on the far side, so a horse and its rider, or a
 * boat with two people in it, cross as one thing at one moment rather than being torn apart by
 * each member crossing on its own tick.
 *
 * <p>Positions are remembered here from one tick to the next rather than read from the entity's
 * own previous-position fields. Those are reset when the entity ticks, and a vehicle steered by a
 * player is moved by that player's packets before it ticks -- so by the end of the tick they
 * already hold the new position and every ridden boat and horse would appear to stand still.
 *
 * @author Flex
 */
public class PortalTraversal {
    /** How far around an opening entities are watched, in blocks. Wide enough to catch an arrow. */
    private static final double REACH = 6.0;

    /** See {@code PortalComponent.MAX_STEP}: a longer jump than this is a teleport, not a movement. */
    private static final double MAX_STEP = 8.0;

    private record Crossing(Entity entity, PortalTransform transform, Vec3d at) {
    }

    /** Where each watched entity was at the end of the last tick. Rebuilt every tick. */
    private static Map<Entity, Vec3d> previous = new IdentityHashMap<>();

    public static void tick(MinecraftServer server) {
        DoorwayComponent doorways = DoorwayComponent.get(server);

        Map<Entity, Vec3d> current = new IdentityHashMap<>();
        List<Crossing> crossings = new ArrayList<>();

        for (PortalPair pair : doorways.pairs()) {
            watch(server, pair.inward(), current, crossings);
            watch(server, pair.outward(), current, crossings);
        }

        previous = current;

        // After the scan, not during it: a teleport removes the entity from the very list the
        // scan is walking.
        for (Crossing crossing : crossings) {
            if (!crossing.entity().isRemoved()) {
                cross(server, crossing);
            }

            previous.remove(crossing.entity());
        }
    }

    private static void watch(MinecraftServer server, PortalTransform transform,
                              Map<Entity, Vec3d> current, List<Crossing> crossings) {
        PortalAnchor entry = transform.from();
        ServerWorld world = server.getWorld(entry.dimension());

        if (world == null) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);

        for (Entity entity : world.getOtherEntities(null, entry.bounds().expand(REACH), candidate -> mayCross(candidate, doorways, transform))) {
            Vec3d now = sample(entity);
            Vec3d before = previous.get(entity);

            // First end of a pair to see it wins; both ends of two pairs can overlap.
            if (current.put(entity, now) != null || before == null) {
                continue;
            }

            if (before.squaredDistanceTo(now) > MAX_STEP * MAX_STEP) {
                continue;
            }

            if (PortalCrossing.find(entry, before, now) != null) {
                crossings.add(new Crossing(entity, transform, now));
            }
        }
    }

    private static boolean mayCross(Entity entity, DoorwayComponent doorways, PortalTransform transform) {
        // Riders go with what they ride, and a player on foot is PortalComponent's.
        if (entity.isRemoved() || entity.hasVehicle() || entity instanceof PlayerEntity) {
            return false;
        }

        // The portal an eviction opens only goes one way, for everything.
        if (!doorways.mayPass(entity, transform)) {
            return false;
        }

        // Vanilla's own answer to "can this be moved between dimensions" -- the dragon cannot.
        if (!entity.canUsePortals(true)) {
            return false;
        }

        // Anything may pass, but nobody who would be turned away on foot gets carried through
        // by sitting in a boat.
        for (Entity passenger : entity.getPassengersDeep()) {
            if (!doorways.mayPass(passenger, transform)) {
                return false;
            }
        }

        return true;
    }

    private static void cross(MinecraftServer server, Crossing crossing) {
        Entity entity = crossing.entity();
        PortalTransform transform = crossing.transform();

        ServerWorld destination = server.getWorld(transform.to().dimension());
        if (destination == null) {
            return;
        }

        Vec3d exitFeet = transform.applyPoint(crossing.at()).subtract(0, entity.getHeight() / 2.0, 0);
        Vec3d exitVelocity = transform.applyVector(entity.getVelocity());

        // As for players: land in a chunk that exists, not one that is about to.
        destination.getChunk(BlockPos.ofFloored(exitFeet));

        if (destination != entity.getEntityWorld()) {
            for (Entity passenger : entity.getPassengersDeep()) {
                if (passenger instanceof ServerPlayerEntity player) {
                    ServerPlayNetworking.send(player, PortalTransitionPayload.INSTANCE);
                }
            }
        }

        entity.teleportTo(new TeleportTarget(
                destination,
                exitFeet,
                exitVelocity,
                transform.applyYaw(entity.getYaw()),
                entity.getPitch(),
                TeleportTarget.NO_OP
        ));
    }

    /** Body centre, matching what players are tested with. */
    private static Vec3d sample(Entity entity) {
        return entity.getEntityPos().add(0, entity.getHeight() / 2.0, 0);
    }

    public static void clear() {
        previous = new IdentityHashMap<>();
    }
}
