package net.damagejr05.overseer.core.index;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
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
    public static final double MINIMUM_SEPARATION = 6.0;

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

    /** How far from the exact scaled position to look for somewhere safe to stand, in blocks. */
    private static final int SAFE_SEARCH_RADIUS = 24;

    /**
     * The overworld end for a doorway opened from <em>inside</em> the memory dimension.
     *
     * <p>The same scale as {@link #dynamicMemoryAnchor}, run backwards: ten blocks out there for
     * every block in here. That is what makes the memory dimension a way of travelling rather
     * than only a place to visit -- walk a hundred blocks, open a doorway, and step out a thousand
     * blocks from where you came in. It is also the way out when no other doorway is open.
     *
     * <p>Nobody has chosen the spot on the other side or can see it beforehand, so it has to be
     * somewhere a person can step out and live. The exact scaled position is only the starting
     * point: the search works outward from it, ring by ring, and takes the first column that is
     * solid, level ground with room to stand. Open water is kept as a fallback if there is no
     * land in range -- wet, but survivable. Lava, fire and the like are never accepted.
     */
    public static PortalAnchor overworldAnchorFor(ServerWorld overworld, PortalAnchor memory) {
        BlockPos ground = safeSurface(
                overworld,
                MathHelper.floor(memory.center().x / MEMORY_SCALE),
                MathHelper.floor(memory.center().z / MEMORY_SCALE)
        );

        return PortalAnchor.of(
                overworld.getRegistryKey(),
                new Vec3d(ground.getX() + 0.5, ground.getY() + PortalAnchor.DEFAULT_HEIGHT / 2.0, ground.getZ() + 0.5),
                memory.facing()
        );
    }

    /**
     * The nearest place to x, z where someone can be put down and live: the block position of
     * the air just above safe ground. See {@link #overworldAnchorFor} for what counts.
     */
    public static BlockPos safeSurface(ServerWorld world, int originX, int originZ) {
        BlockPos water = null;

        // Rings of increasing distance, so the result is the nearest safe spot, not just a safe one.
        for (int radius = 0; radius <= SAFE_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    int x = originX + dx;
                    int z = originZ + dz;

                    Standing standing = standingAt(world, x, z);

                    if (standing == Standing.SAFE) {
                        return new BlockPos(x, footprintTop(world, x, z), z);
                    }

                    if (standing == Standing.WATER && water == null) {
                        water = new BlockPos(x, footprintTop(world, x, z), z);
                    }
                }
            }
        }

        if (water != null) {
            return water;
        }

        // Nothing safe within reach: a lava sea, or no ground at all. High above whatever is
        // there, so the way out still exists and what happens next is at least visible first.
        return new BlockPos(originX, Math.max(world.getSeaLevel(), surface(world, originX, originZ)) + 1, originZ);
    }

    private enum Standing {
        SAFE, WATER, UNSAFE
    }

    private static int surface(ServerWorld world, int x, int z) {
        // Generates the chunk if it has never been visited. A pause on a command is acceptable;
        // reading a height from a chunk that is not there is not.
        world.getChunk(x >> 4, z >> 4);
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Whether someone stepping out onto the column at x, z would be all right. */
    private static Standing standingAt(ServerWorld world, int x, int z) {
        int top = surface(world, x, z);

        if (top <= world.getBottomY()) {
            return Standing.UNSAFE;
        }

        BlockPos floorPos = new BlockPos(x, top - 1, z);
        BlockState floor = world.getBlockState(floorPos);

        if (floor.getFluidState().isIn(FluidTags.LAVA)
                || floor.isOf(Blocks.MAGMA_BLOCK) || floor.isOf(Blocks.CACTUS)
                || floor.isIn(BlockTags.FIRE) || floor.isIn(BlockTags.CAMPFIRES)
                || floor.isOf(Blocks.POWDER_SNOW) || floor.isOf(Blocks.SWEET_BERRY_BUSH)) {
            return Standing.UNSAFE;
        }

        // Level enough to stand a doorway on: no neighbour more than a block higher or lower.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(surface(world, x + dx, z + dz) - top) > 1) {
                    return Standing.UNSAFE;
                }
            }
        }

        // The heightmap ignores leaves, so a canopy can still fill the space above the ground.
        for (int dy = 0; dy < 3; dy++) {
            BlockPos body = floorPos.up(1 + dy);
            BlockState state = world.getBlockState(body);

            if (!state.getCollisionShape(world, body).isEmpty() || !state.getFluidState().isEmpty()) {
                return Standing.UNSAFE;
            }
        }

        if (floor.getFluidState().isIn(FluidTags.WATER)) {
            return Standing.WATER;
        }

        return floor.isSideSolidFullSquare(world, floorPos, Direction.UP) ? Standing.SAFE : Standing.UNSAFE;
    }

    /** The highest ground under a doorway's footprint at x, z, so it stands clear of a slope. */
    private static int footprintTop(ServerWorld world, int x, int z) {
        int ground = world.getBottomY();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ground = Math.max(ground, surface(world, x + dx, z + dz));
            }
        }

        return ground;
    }
}
