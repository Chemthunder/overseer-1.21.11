package net.damagejr05.overseer.core.index;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The memory dimension: a blank, buildable void the Overseer fills with memories.
 *
 * @author Flex
 */
public class ModDimensions {
    public static final RegistryKey<World> MEMORY = RegistryKey.of(
            RegistryKeys.WORLD,
            Overseer.id("memory")
    );

    /**
     * The Y level the generated void_matter floor tops out at. The flat generator stacks 64 layers
     * of air from min_y (-64) and then 4 layers of void_matter, so the floor occupies y=0..3.
     */
    public static final int FLOOR_TOP_Y = 4;

    /**
     * Null if the dimension failed to load — always null-check rather than assuming it exists, a
     * broken datapack should not take the server down.
     */
    public static @Nullable ServerWorld memory(MinecraftServer server) {
        return server.getWorld(MEMORY);
    }

    /**
     * How much the overworld is compressed when reflected into the memory dimension.
     *
     * <p>Ten to one: a doorway opened 1000 blocks from spawn appears 100 blocks from the memory
     * origin. Far-flung doorways stay meaningfully far apart in here without becoming a hike.
     */
    public static final double MEMORY_SCALE = 0.1;

    /** Keeps a scaled doorway from landing on top of the permanent one at the origin. */
    private static final double MINIMUM_SEPARATION = 6.0;

    /** Y that puts a doorway's base on the memory floor. */
    private static double portalY() {
        return FLOOR_TOP_Y + PortalAnchor.DEFAULT_HEIGHT / 2.0;
    }

    /**
     * The memory-side end of the permanent pair: the origin, the fixed point everything else is
     * measured from. Faces north, so you walk north into it to leave and emerge heading south.
     */
    public static PortalAnchor fixedMemoryAnchor() {
        return PortalAnchor.of(MEMORY, new Vec3d(0.5, portalY(), 0.5), Direction.NORTH);
    }

    /**
     * The memory-side end of the temporary pair, placed at the overworld position scaled by
     * {@link #MEMORY_SCALE} so its distance from the origin reflects how far out it was opened.
     */
    public static PortalAnchor dynamicMemoryAnchor(PortalAnchor overworld) {
        double x = overworld.center().x * MEMORY_SCALE;
        double z = overworld.center().z * MEMORY_SCALE;

        // Opening one near world spawn would otherwise put it inside the permanent doorway.
        double distance = Math.sqrt(x * x + z * z);
        if (distance < MINIMUM_SEPARATION) {
            if (distance < 1.0e-4) {
                z = MINIMUM_SEPARATION;
            } else {
                double push = MINIMUM_SEPARATION / distance;
                x *= push;
                z *= push;
            }
        }

        return PortalAnchor.of(
                MEMORY,
                new Vec3d(Math.floor(x) + 0.5, portalY(), Math.floor(z) + 0.5),
                overworld.facing()
        );
    }
}
