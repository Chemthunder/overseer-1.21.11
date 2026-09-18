package net.damagejr05.overseer.core.portal;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The rigid transform carrying one portal's frame onto another's.
 *
 * <p>{@link PortalAnchor#facing()} is the direction you travel to walk <em>into</em> that portal
 * from its approach side. A linked pair is two doorways, so entering {@code from} along
 * {@code from.facing()} means emerging out of {@code to}'s front face — travelling along
 * <em>minus</em> {@code to.facing()}.
 *
 * <p>That sign is what makes a pair work in both directions with a single facing stored per end.
 * Walking back into {@code to} then returns you out of {@code from}, exactly reversing the trip.
 *
 * <p>Both anchor bases are horizontal-plus-up, so the rotation is always a yaw about Y. Every
 * consumer — traversal, the virtual camera, collision clipping — goes through this one class, so
 * the whole system stays consistent by construction.
 *
 * @author Flex
 */
public record PortalTransform(PortalAnchor from, PortalAnchor to) {
    /**
     * Yaw to add to an entity's rotation when passing through, in degrees.
     */
    public float yawDelta() {
        // The 180 is the "out of the far door" flip described above.
        return MathHelper.wrapDegrees(
                Direction.getHorizontalDegreesOrThrow(to.facing())
                        - Direction.getHorizontalDegreesOrThrow(from.facing())
                        + 180.0F
        );
    }

    /**
     * Map a world-space point from the source portal's frame into the destination's.
     */
    public Vec3d applyPoint(Vec3d point) {
        Vec3d local = point.subtract(from.center());
        double u = local.dotProduct(from.right());
        double v = local.y;
        double w = local.dotProduct(from.forward());

        // Mirrored across the exit's plane and side axis, which is the same 180 turn.
        return to.center()
                .add(to.right().multiply(-u))
                .add(0, v, 0)
                .add(to.forward().multiply(-w));
    }

    /**
     * Map a direction or velocity. Same basis change as {@link #applyPoint} minus the translation.
     */
    public Vec3d applyVector(Vec3d vector) {
        double u = vector.dotProduct(from.right());
        double v = vector.y;
        double w = vector.dotProduct(from.forward());

        return to.right().multiply(-u)
                .add(0, v, 0)
                .add(to.forward().multiply(-w));
    }

    public float applyYaw(float yaw) {
        return MathHelper.wrapDegrees(yaw + yawDelta());
    }

    public PortalTransform inverse() {
        return new PortalTransform(to, from);
    }
}
