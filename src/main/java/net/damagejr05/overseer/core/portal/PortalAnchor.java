package net.damagejr05.overseer.core.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * One end of a portal: where it is, which way it faces, and how big the opening is.
 *
 * <p>{@code facing} is the direction you look when you walk <em>into</em> the portal, i.e. the
 * inward normal of the front face. It is always horizontal.
 *
 * @author Flex
 */
public record PortalAnchor(
        RegistryKey<World> dimension,
        Vec3d center,
        Direction facing,
        float width,
        float height
) {
    public static final float DEFAULT_WIDTH = 2.0F;
    public static final float DEFAULT_HEIGHT = 3.0F;

    public static final Codec<PortalAnchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            World.CODEC.fieldOf("Dimension").forGetter(PortalAnchor::dimension),
            Vec3d.CODEC.fieldOf("Center").forGetter(PortalAnchor::center),
            Direction.CODEC.fieldOf("Facing").forGetter(PortalAnchor::facing),
            Codec.FLOAT.fieldOf("Width").forGetter(PortalAnchor::width),
            Codec.FLOAT.fieldOf("Height").forGetter(PortalAnchor::height)
    ).apply(instance, PortalAnchor::new));

    public PortalAnchor {
        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("Portal facing must be horizontal, got " + facing);
        }
    }

    public static PortalAnchor of(RegistryKey<World> dimension, Vec3d center, Direction facing) {
        return new PortalAnchor(dimension, center, facing, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Unit vector along the direction of travel through the portal.
     */
    public Vec3d forward() {
        return Vec3d.of(facing.getVector());
    }

    /**
     * Unit vector pointing out of the front face, i.e. back towards someone about to walk in.
     */
    public Vec3d normal() {
        return forward().negate();
    }

    /**
     * Unit vector along the portal's width, perpendicular to the normal and horizontal.
     */
    public Vec3d right() {
        return Vec3d.of(facing.rotateYClockwise().getVector());
    }

    public Vec3d up() {
        return new Vec3d(0, 1, 0);
    }

    /**
     * Signed distance from the portal plane. Positive means in front of the portal (the side you
     * approach from), negative means behind it.
     */
    public double signedDistance(Vec3d point) {
        return point.subtract(center).dotProduct(normal());
    }

    /**
     * Whether a point lying on the plane falls inside the opening rather than outside its frame.
     */
    public boolean withinOpening(Vec3d pointOnPlane) {
        Vec3d local = pointOnPlane.subtract(center);
        double u = local.dotProduct(right());
        double v = local.dotProduct(up());
        return Math.abs(u) <= width / 2.0 && Math.abs(v) <= height / 2.0;
    }

    /**
     * Loose bounding box around the opening, used for broad-phase checks and chunk tracking.
     */
    public Box bounds() {
        double half = Math.max(width, height) / 2.0 + 1.0;
        return new Box(
                center.x - half, center.y - half, center.z - half,
                center.x + half, center.y + half, center.z + half
        );
    }

    public PortalAnchor withCenter(Vec3d newCenter) {
        return new PortalAnchor(dimension, newCenter, facing, width, height);
    }

    public PortalAnchor withFacing(Direction newFacing) {
        return new PortalAnchor(dimension, center, newFacing, width, height);
    }
}
