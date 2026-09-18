package net.damagejr05.overseer.core.portal;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Detects the moment an entity passes through a portal opening.
 *
 * <p>This tests the swept segment from last tick's position to this tick's against the portal
 * quad, rather than asking whether the entity is standing inside a box. That matters for two
 * reasons: a box test fires late and needs a cooldown to stop it re-triggering, and anything
 * moving faster than the box is deep can tunnel straight through it without ever being inside.
 *
 * @author Flex
 */
public class PortalCrossing {
    /**
     * @return the point on the portal plane where the entity passed through, or null if it did not
     * cross the opening this tick. Either face counts.
     */
    public static @Nullable Vec3d find(PortalAnchor anchor, Vec3d previous, Vec3d current) {
        double before = anchor.signedDistance(previous);
        double after = anchor.signedDistance(current);

        // A crossing in either direction. Requiring the sign to actually change means it can only
        // fire once per passage, so no cooldown is needed.
        //
        // The transform handles the back-to-front case without any special casing: it is a rigid
        // map, so a position behind the entry lands behind the exit and a velocity pointing out of
        // the entry's back face maps to one pointing out of the exit's back face. You emerge
        // moving away from the far door rather than into it, which is what stops the two ends
        // bouncing a traveller between them.
        boolean frontToBack = before > 0.0 && after <= 0.0;
        boolean backToFront = before < 0.0 && after >= 0.0;

        if (!frontToBack && !backToFront) {
            return null;
        }

        double denominator = before - after;
        if (denominator == 0.0) {
            return null;
        }

        double t = before / denominator;
        Vec3d hit = previous.lerp(current, t);

        return anchor.withinOpening(hit) ? hit : null;
    }
}
