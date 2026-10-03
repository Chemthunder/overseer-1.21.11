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
     * @param clip       false to skip the clip and simply copy the projection
     * @return a copy of {@code projection} clipped to the exit portal's plane, or an unmodified
     * copy whenever the clip is not wanted or cannot be computed safely
     *
     * <p><b>This method is written in a deliberately plain style, and it matters that it stays
     * that way.</b> There is exactly one matrix allocated and exactly one returned, and the
     * plane's normal is carried as three numbers rather than as a vector that is conditionally
     * replaced by its negation. Both used to be otherwise -- return either the fallback or the
     * result; negate the normal if the camera is on the far side -- and each of those is a place
     * where one variable may hold either of two freshly allocated objects.
     *
     * <p>HotSpot's C2 compiler has an optimisation for exactly that shape (ReduceAllocationMerges,
     * on by default since JDK 22) and on some builds it crashes the whole JVM while compiling a
     * method that contains one. This method is inlined into the per-frame portal render, so once
     * that got hot enough to be compiled -- a minute or two into looking at a doorway -- the game
     * died on the spot with no exception and no crash report, only an hs_err file. That is the
     * hard crash that kept see-through portals switched off.
     */
    public static Matrix4f apply(Matrix4f projection, Matrix4f view, Vec3d cameraPos, PortalAnchor exit, boolean clip) {
        Matrix4f result = new Matrix4f(projection);

        if (!clip) {
            return result;
        }

        // Points away from the door, towards the half-space worth keeping. Lengyel's construction
        // keeps where n.x + d > 0, so the normal has to face the kept side -- which is always the
        // side the virtual camera is NOT on.
        //
        // The sign has to follow the camera because a portal is see-through from both faces.
        // Looking in through the back puts the virtual camera on the exit's approach side instead,
        // and a fixed normal would then clip away the half containing everything worth seeing.
        double sign = exit.signedDistance(cameraPos) > 0 ? 1.0 : -1.0;

        // The exit's outward normal is minus its facing; flipped again if the camera is in front.
        double nx = exit.facing().getOffsetX() * sign;
        double ny = exit.facing().getOffsetY() * sign;
        double nz = exit.facing().getOffsetZ() * sign;

        // Camera-relative, to match the space the view matrix and geometry are in.
        double distance = -(nx * (exit.center().x - cameraPos.x)
                + ny * (exit.center().y - cameraPos.y)
                + nz * (exit.center().z - cameraPos.z));

        // The camera sits on the clipped side by construction, so distance is normally negative.
        // Approaching zero means the camera is level with the door -- clipping there would remove
        // the whole view, and it happens for a frame as the seam is crossed.
        if (distance > -0.05) {
            return result;
        }

        // The view matrix is a pure rotation, so its inverse-transpose is itself and the plane's
        // distance term passes through untouched.
        Vector4f clipPlane = view.transform(new Vector4f((float) nx, (float) ny, (float) nz, (float) distance));

        if (!isFinite(clipPlane)) {
            return result;
        }

        // Corner of the near plane furthest from the clip plane, in clip space.
        float cornerX = (Math.signum(clipPlane.x) + result.m20()) / result.m00();
        float cornerY = (Math.signum(clipPlane.y) + result.m21()) / result.m11();
        float cornerW = (1.0F + result.m22()) / result.m32();

        float denominator = clipPlane.x * cornerX + clipPlane.y * cornerY - clipPlane.z + clipPlane.w * cornerW;

        if (!Float.isFinite(denominator) || Math.abs(denominator) < EPSILON) {
            return result;
        }

        float scale = 2.0F / denominator;
        float m02 = clipPlane.x * scale;
        float m12 = clipPlane.y * scale;
        float m22 = clipPlane.z * scale + 1.0F;
        float m32 = clipPlane.w * scale;

        // Last line of defence. Anything non-finite reaching the frustum hangs the render thread,
        // so nothing is written into the matrix until every value going into it has been checked.
        if (!Float.isFinite(m02) || !Float.isFinite(m12) || !Float.isFinite(m22) || !Float.isFinite(m32)) {
            return result;
        }

        result.m02(m02);
        result.m12(m12);
        result.m22(m22);
        result.m32(m32);

        return result;
    }

    private static boolean isFinite(Vector4f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z) && Float.isFinite(v.w);
    }
}
