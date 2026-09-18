package net.damagejr05.overseer.core.client.render;

import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Bends a projection matrix's near plane onto the portal plane.
 *
 * <p>Without this the virtual camera sees everything between itself and the exit portal — the far
 * side of the wall the portal is set into, terrain behind it — all composited into the aperture as
 * though it were in front. Replacing the near plane with the portal plane clips that away exactly.
 *
 * <p>Uses Eric Lengyel's oblique near-plane clipping: the third row of the projection matrix is
 * replaced with the clip plane, scaled so the far plane is preserved.
 *
 * <p><b>Two things here are easy to get wrong and both produce NaN rather than a visible error.</b>
 * Minecraft renders camera-relative — the view matrix is rotation-only and geometry is expressed
 * relative to the camera — so the clip plane must be built in camera-relative space too; a
 * world-space plane is wrong by the camera's world position, which is typically thousands of
 * blocks. And the scaling divisor can land on zero for a grazing plane. A NaN escaping into the
 * projection propagates into the frustum, and {@code Frustum.coverBoxAroundSetPosition} then spins
 * forever because its containment test can never succeed — a hung client, not a crash.
 *
 * @author Flex
 */
public class ObliqueProjection {
    /** Below this the scaling divisor is treated as degenerate. */
    private static final float EPSILON = 1.0e-6F;

    /**
     * @param projection the ordinary projection matrix, not modified
     * @param view       the camera's rotation matrix (Minecraft's view matrix carries no translation)
     * @param cameraPos  the virtual camera's world position
     * @param exit       the portal being looked out of
     * @return a copy of {@code projection} clipped to the exit portal's plane, or an unmodified
     * copy whenever the clip cannot be computed safely
     */
    public static Matrix4f apply(Matrix4f projection, Matrix4f view, Vec3d cameraPos, PortalAnchor exit) {
        Matrix4f fallback = new Matrix4f(projection);

        // Points away from the door, towards the half-space worth keeping. Lengyel's construction
        // keeps where n·x + d > 0, so the normal has to face the kept side -- which is always the
        // side the virtual camera is NOT on.
        //
        // With exit.forward() unconditionally here, the camera always landed on the positive side,
        // the guard below tripped on every frame, and the clip silently never ran: everything
        // between the virtual camera and the door stayed in the view, so the far side was seen
        // through the terrain the portal is set into.
        //
        // The sign has to follow the camera because a portal is now see-through from both faces.
        // Looking in through the back puts the virtual camera on the exit's approach side instead,
        // and a fixed normal would then clip away the half containing everything worth seeing.
        Vec3d normal = exit.normal();
        if (exit.signedDistance(cameraPos) > 0) {
            normal = normal.negate();
        }

        // Camera-relative, to match the space the view matrix and geometry are in.
        Vec3d toPortal = exit.center().subtract(cameraPos);
        double distance = -normal.dotProduct(toPortal);

        // The camera sits on the clipped side by construction, so distance is normally negative.
        // Approaching zero means the camera is level with the door -- clipping there would remove
        // the whole view, and it happens for a frame as the seam is crossed.
        if (distance > -0.05) {
            return fallback;
        }

        Vector4f worldPlane = new Vector4f(
                (float) normal.x,
                (float) normal.y,
                (float) normal.z,
                (float) distance
        );

        // The view matrix is a pure rotation, so its inverse-transpose is itself and the plane's
        // distance term passes through untouched.
        Vector4f clipPlane = new Matrix4f(view).transform(new Vector4f(worldPlane));

        if (!isFinite(clipPlane)) {
            return fallback;
        }

        Matrix4f result = new Matrix4f(projection);

        // Corner of the near plane furthest from the clip plane, in clip space.
        Vector4f corner = new Vector4f(
                (Math.signum(clipPlane.x) + result.m20()) / result.m00(),
                (Math.signum(clipPlane.y) + result.m21()) / result.m11(),
                -1.0F,
                (1.0F + result.m22()) / result.m32()
        );

        if (!isFinite(corner)) {
            return fallback;
        }

        float denominator = clipPlane.dot(corner);
        if (Math.abs(denominator) < EPSILON) {
            return fallback;
        }

        Vector4f scaled = new Vector4f(clipPlane).mul(2.0F / denominator);
        if (!isFinite(scaled)) {
            return fallback;
        }

        result.m02(scaled.x);
        result.m12(scaled.y);
        result.m22(scaled.z + 1.0F);
        result.m32(scaled.w);

        // Last line of defence. Anything non-finite reaching the frustum hangs the render thread,
        // so it is never worth returning a matrix that has not been checked.
        return isFinite(result) ? result : fallback;
    }

    private static boolean isFinite(Vector4f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z) && Float.isFinite(v.w);
    }

    private static boolean isFinite(Matrix4f m) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(m.get(column, row))) {
                    return false;
                }
            }
        }

        return true;
    }
}
