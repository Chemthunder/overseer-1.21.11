package net.damagejr05.overseer.core.portal;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Makes a doorway solid to anyone who may not go through it.
 *
 * <p>Without this a player who is not admitted simply walks into the opening and out of its back,
 * through whatever the doorway is set into: nothing stops them, the picture just fails to become
 * a place. A doorway that will not let you in should feel like a surface.
 *
 * <p>It is not a block and has no collision shape. The entity's movement for the tick is trimmed
 * instead, so that its bounding box comes to rest against the doorway's plane, from whichever
 * side it approached. That runs wherever the entity is moved from -- which for a player is their
 * own client, so the wall is felt immediately and not as a rubber-band a moment later.
 *
 * @author Flex
 */
public class PortalBarrier {
    /** A hair's breadth off the plane, so resting against it is not counted as being in it. */
    private static final double SKIN = 1.0e-3;

    public static Vec3d clamp(Entity entity, Vec3d movement) {
        DoorwayComponent doorways = DoorwayComponent.get(entity.getEntityWorld());

        if (doorways.mayEnter(entity)) {
            return movement;
        }

        Box box = entity.getBoundingBox();
        Vec3d centre = box.getCenter();
        double halfWidth = box.getLengthX() / 2.0;
        double halfHeight = box.getLengthY() / 2.0;

        for (PortalAnchor anchor : doorways.anchorsIn(entity.getEntityWorld().getRegistryKey())) {
            // Nobody is being kept out of the portal an eviction opens.
            if (doorways.isEvictionAnchor(anchor)) {
                continue;
            }

            Vec3d local = centre.subtract(anchor.center());

            // Only where the body actually overlaps the opening. Past its edge is just wall or
            // open air, and neither is this class's business.
            if (Math.abs(local.dotProduct(anchor.right())) >= anchor.width() / 2.0 + halfWidth
                    || Math.abs(local.y) >= anchor.height() / 2.0 + halfHeight) {
                continue;
            }

            Vec3d normal = anchor.normal();
            double distance = local.dotProduct(normal);
            double along = movement.dotProduct(normal);

            // Room left between the near face of the body and the plane. Negative means it is
            // already part way in -- placed there, or pushed -- and then the only movement allowed
            // across the plane is back out.
            double room = Math.max(Math.abs(distance) - halfWidth - SKIN, 0.0);
            double allowed = along;

            if (distance > 0.0 && along < -room) {
                allowed = -room;
            } else if (distance <= 0.0 && along > room) {
                allowed = room;
            }

            if (allowed != along) {
                movement = movement.add(normal.multiply(allowed - along));
            }
        }

        return movement;
    }
}
