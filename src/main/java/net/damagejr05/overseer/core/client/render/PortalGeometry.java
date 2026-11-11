package net.damagejr05.overseer.core.client.render;

import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

/**
 * Where a doorway's view surface is drawn, which is not always where the doorway is.
 *
 * <p>Shared by the two things that have to agree about it: {@link PortalViewCompositor}, which
 * draws the surface, and {@link PortalViewRenderer}, which works out how much of the screen the
 * surface can cover and renders only that much.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
final class PortalGeometry {
    /**
     * The closest the view surface is ever allowed to come to the camera, in blocks.
     *
     * <p>The surface is a quad on the doorway's plane, and the camera walks straight through
     * that plane. The projection's near plane is a twentieth of a block out, further at the
     * corners of the screen, and a quad nearer than that is simply cut away -- so for the last
     * frame or two before stepping through, the corners of the screen showed what was behind the
     * doorway instead of what was through it.
     *
     * <p>So the surface backs away. Inside this distance it is moved off the plane, away from the
     * camera, by exactly as much as the camera has come inside it, and stays this far ahead until
     * the camera is through. Nothing about the picture changes: the surface is sampled by screen
     * position, so what it shows is the same wherever it sits. The recess walls are simply drawn
     * that much deeper to meet it.
     *
     * <p>Less than the depth of the recess's ledge, so the surface never backs out through the
     * far side of the block the doorway occupies.
     */
    static final double GUARD = 0.35;

    /** How far past the edge of the opening the retreat takes to fade away, in blocks. */
    private static final double FEATHER = 0.5;

    private PortalGeometry() {
    }

    /**
     * How far to move the view surface off the anchor's plane, measured along
     * {@link PortalAnchor#normal()}. Zero for a camera at any ordinary distance.
     *
     * <p>Fades out to the sides instead of stopping at the edge of the opening, so that a camera
     * passing the end of a free-standing doorway does not see the surface jump.
     */
    static double retreat(PortalAnchor anchor, Vec3d camera) {
        Vec3d local = camera.subtract(anchor.center());
        double distance = local.dotProduct(anchor.normal());
        double inside = GUARD - Math.abs(distance);

        if (inside <= 0.0) {
            return 0.0;
        }

        double across = Math.abs(local.dotProduct(anchor.right())) - anchor.width() / 2.0;
        double along = Math.abs(local.y) - anchor.height() / 2.0;
        double beyondEdge = Math.max(Math.max(across, along), 0.0);

        if (beyondEdge >= FEATHER) {
            return 0.0;
        }

        double amount = inside * (1.0 - beyondEdge / FEATHER);

        // Away from whichever side the camera is on.
        return distance >= 0.0 ? -amount : amount;
    }
}
