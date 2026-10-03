package net.damagejr05.overseer.core.portal;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * The eye opens, and everyone is thrown out.
 *
 * <p>Started with {@code /overseer evict}. One counter, kept in {@link DoorwayComponent} and
 * synced to every client, drives the whole thing: the server acts on it and the sky is drawn
 * from it, so what people see and what happens to them cannot drift apart.
 *
 * <ol>
 *   <li><b>Waking.</b> The sky darkens. A seam of light appears across it, to a slow heartbeat
 *       that quickens.</li>
 *   <li><b>Opening.</b> The seam parts into two lids and there is an eye behind them, the size
 *       of a hillside, its iris rolled up out of sight.</li>
 *   <li><b>Looking.</b> The iris comes down and searches -- left, right, a blink -- and then
 *       stops, on whoever is watching. The pupil snaps tight and a shockwave rolls out across the
 *       sky. It has found you.</li>
 *   <li><b>Turning.</b> The pupil tears wide open and there is somewhere else inside it: the
 *       pupil <em>is</em> the portal, a real one, with the overworld visible through it. The
 *       lids burn away and the iris becomes a corona around it.</li>
 *   <li><b>The pull.</b> Everyone in the memory dimension is lifted off the ground and drawn up
 *       into it, faster and faster, the stars streaking past, the sky whiting out. They come
 *       out high over the overworld, falling slowly.</li>
 *   <li><b>Closing.</b> Anyone who could not reach it is put through anyway, and the eye shuts
 *       over an empty dimension.</li>
 * </ol>
 *
 * <p>The Overseer is not taken. In a development environment nobody is the real Overseer, so
 * whoever runs the command there goes with everyone else -- which is what makes it possible to
 * see the whole thing alone.
 *
 * @author Flex
 */
public class Eviction {
    private static final int WAKE_TICKS = 50;
    private static final int OPEN_TICKS = 50;
    private static final int LOOK_TICKS = 80;
    private static final int TURN_TICKS = 45;
    private static final int PULL_TICKS = 70;
    private static final int CLOSE_TICKS = 55;

    private static final int OPEN_AT = WAKE_TICKS;
    private static final int LOOK_AT = OPEN_AT + OPEN_TICKS;
    /** The moment it finds you: most of the way through the looking. */
    private static final int LOCK_AT = LOOK_AT + 58;
    private static final int TURN_AT = LOOK_AT + LOOK_TICKS;
    private static final int PULL_AT = TURN_AT + TURN_TICKS;
    private static final int CLOSE_AT = PULL_AT + PULL_TICKS;

    public static final int TOTAL_TICKS = CLOSE_AT + CLOSE_TICKS;

    /** The pupil's radius at each stage, as a fraction of the finished portal's. */
    private static final float PUPIL_RESTING = 0.46F;
    private static final float PUPIL_LOCKED = 0.20F;
    /** A little over one, so the ring of light sits just outside the portal's own rim. */
    private static final float PUPIL_PORTAL = 1.06F;

    // -----------------------------------------------------------------------------------------
    // The animation. Everything below is a pure function of the tick counter, used by the sky
    // shader's renderer and by the portal's compositor alike.
    // -----------------------------------------------------------------------------------------

    /** Whether there is anything in the sky at all: fades in at the start and out at the end. */
    public static float presence(float t) {
        return smooth(t / 25.0F) * (1.0F - smooth((t - (TOTAL_TICKS - 20)) / 20.0F));
    }

    /** How dark and troubled the sky has become. */
    public static float dread(float t) {
        return smooth(t / WAKE_TICKS) * (1.0F - smooth((t - CLOSE_AT) / CLOSE_TICKS));
    }

    /** How far the lids have parted, 0 shut to 1 open. Includes one blink while it searches. */
    public static float lid(float t) {
        float open = easeOutBack((t - OPEN_AT) / OPEN_TICKS);

        // A blink, quick down and slower up, just before it finds what it is looking for.
        float blinkAt = LOOK_AT + 34.0F;
        float blink = 1.0F - 0.92F * bump((t - blinkAt) / 3.0F, (t - blinkAt - 3.0F) / 6.0F);

        float shut = 1.0F - smooth((t - CLOSE_AT - 15.0F) / 30.0F);

        return Math.max(0.0F, open * blink * shut);
    }

    /**
     * Where the iris is looking, each axis -1 to 1. Zero is straight at the viewer.
     *
     * <p>It moves in saccades: held still, then a jump almost too fast to follow, the way a real
     * eye moves. Easing smoothly between the points made it look like a searchlight.
     */
    public static float gazeX(float t) {
        return saccades(t - LOOK_AT, -0.85F, GAZE_TIMES, GAZE_X);
    }

    public static float gazeY(float t) {
        // Rolled up under the lid while it opens, then down into view.
        float roll = 1.0F - smooth((t - OPEN_AT - 20.0F) / 30.0F);

        return roll * 1.6F + saccades(t - LOOK_AT, 0.0F, GAZE_TIMES, GAZE_Y);
    }

    /** When, after it starts looking, the eye jumps -- and where to. The last jump is onto you. */
    private static final float[] GAZE_TIMES = {18.0F, 46.0F, LOCK_AT - LOOK_AT};
    private static final float[] GAZE_X = {0.80F, -0.25F, 0.0F};
    private static final float[] GAZE_Y = {-0.30F, 0.35F, 0.0F};

    /** Hold a value, then jump to the next in three ticks at each of {@code times}. */
    private static float saccades(float t, float initial, float[] times, float[] values) {
        float value = initial;

        for (int i = 0; i < times.length; i++) {
            if (t < times[i]) {
                break;
            }

            value = MathHelper.lerp(smooth((t - times[i]) / 3.0F), i == 0 ? initial : values[i - 1], values[i]);
        }

        return value;
    }

    /** The pupil's radius, as a fraction of the finished portal's. */
    public static float pupil(float t) {
        if (t < LOCK_AT) {
            return PUPIL_RESTING;
        }

        if (t < TURN_AT) {
            // Snaps tight the instant it finds you.
            return MathHelper.lerp(smooth((t - LOCK_AT) / 5.0F), PUPIL_RESTING, PUPIL_LOCKED);
        }

        float wide = MathHelper.lerp(smooth((t - TURN_AT) / TURN_TICKS), PUPIL_LOCKED, PUPIL_PORTAL);
        return wide * (1.0F - smooth((t - CLOSE_AT) / 30.0F));
    }

    /** 0 while the pupil is a slit, 1 once it is round. It rounds out as it turns into the portal. */
    public static float roundness(float t) {
        return smooth((t - TURN_AT + 8.0F) / 25.0F);
    }

    /** How far the eye has turned into the portal: lids gone, iris a corona. */
    public static float turned(float t) {
        return smooth((t - TURN_AT) / TURN_TICKS) * (1.0F - smooth((t - CLOSE_AT) / 30.0F));
    }

    /**
     * The size of the real portal, 0 to 1. It opens inside the pupil and never outgrows it, so
     * there is always a rim of darkness and then light around the view through it.
     */
    public static float portalScale(float t) {
        return turned(t) * pupil(t) / PUPIL_PORTAL;
    }

    /** The shockwave when it finds you: 0 as it leaves the eye, 1 when it has crossed the sky. */
    public static float shock(float t) {
        return MathHelper.clamp((t - LOCK_AT) / 28.0F, 0.0F, 1.0F);
    }

    /** How hard the sky is being dragged toward the eye. */
    public static float pull(float t) {
        return MathHelper.clamp((t - PULL_AT) / PULL_TICKS, 0.0F, 1.0F)
                * (1.0F - smooth((t - CLOSE_AT) / 20.0F));
    }

    /** How far the sky has whited out, 0 to 1: rising through the pull, gone soon after. */
    public static float flare(float t) {
        float pull = (t - PULL_AT) / PULL_TICKS;

        if (pull <= 0.0F) {
            return 0.0F;
        }

        if (pull < 1.0F) {
            return pull * pull * pull;
        }

        return Math.max(0.0F, 1.0F - (t - CLOSE_AT) / (CLOSE_TICKS * 0.5F));
    }

    private static float smooth(float x) {
        x = MathHelper.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Overshoots and settles: the lids fly open a little too far and come back. */
    private static float easeOutBack(float x) {
        x = MathHelper.clamp(x, 0.0F, 1.0F);
        float c = 1.70158F;
        float y = x - 1.0F;
        return 1.0F + (c + 1.0F) * y * y * y + c * y * y;
    }

    /** Rises over the first argument's 0..1 and falls over the second's. */
    private static float bump(float up, float down) {
        return smooth(up) * (1.0F - smooth(down));
    }

    /** Where in the sky the portal opens, as a direction from the people it is opening for. */
    private static final Vec3d SKYWARD = new Vec3d(0.0, 0.56, -0.83).normalize();

    /** How far off it opens, and how big it is. Large enough to fill a good part of the sky. */
    private static final double DISTANCE = 48.0;
    public static final float SIZE = 32.0F;

    /** How far above the ground its overworld end's lowest point hangs. */
    private static final double EXIT_CLEARANCE = 12.0;

    /** Long enough to drift down from the top of the exit with time to spare. */
    private static final int SLOW_FALLING_TICKS = 45 * 20;

    /**
     * Work out where the portal goes and begin.
     *
     * @param fallback where to open it over if the memory dimension is empty
     * @return false if a sequence is already running or the memory dimension is missing
     */
    public static boolean start(MinecraftServer server, Vec3d fallback) {
        ServerWorld memory = ModDimensions.memory(server);
        if (memory == null) {
            return false;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);

        // Over the middle of whoever is there, so it opens in front of all of them at once.
        List<ServerPlayerEntity> taken = taken(memory);
        Vec3d below = fallback;

        if (!taken.isEmpty()) {
            below = Vec3d.ZERO;
            for (ServerPlayerEntity player : taken) {
                below = below.add(player.getEntityPos());
            }
            below = below.multiply(1.0 / taken.size());
        }

        // It faces south, back toward the people beneath it, so they travel north and up into it.
        PortalAnchor inner = new PortalAnchor(
                ModDimensions.MEMORY, below.add(SKYWARD.multiply(DISTANCE)), Direction.NORTH, SIZE, SIZE
        );

        // The other end hangs over wherever they are being sent: the ground in front of the
        // permanent doorway if there is one, the way back in, and otherwise the world's spawn.
        ServerWorld overworld = server.getOverworld();
        PortalPair fixed = doorways.getFixed();
        BlockPos near = overworld.getSpawnPoint().globalPos().pos();

        if (fixed != null && fixed.outer().dimension() == overworld.getRegistryKey()) {
            near = BlockPos.ofFloored(fixed.outer().center().add(fixed.outer().normal().multiply(4.0)));
        }

        // Somewhere survivable to come down, even under slow falling.
        BlockPos ground = ModDimensions.safeSurface(overworld, near.getX(), near.getZ());

        PortalAnchor outer = new PortalAnchor(
                overworld.getRegistryKey(),
                new Vec3d(ground.getX() + 0.5, ground.getY() + EXIT_CLEARANCE + SIZE / 2.0, ground.getZ() + 0.5),
                Direction.NORTH, SIZE, SIZE
        );

        return doorways.startEviction(new PortalPair(outer, inner));
    }

    private static List<ServerPlayerEntity> taken(ServerWorld memory) {
        List<ServerPlayerEntity> taken = new ArrayList<>();

        for (ServerPlayerEntity player : memory.getPlayers()) {
            if (!Overseer.isDamage(player) && !player.isSpectator()) {
                taken.add(player);
            }
        }

        return taken;
    }

    /** Called once a tick by the canonical doorway component while a sequence is running. */
    public static void tick(MinecraftServer server, int ticks) {
        ServerWorld memory = ModDimensions.memory(server);
        PortalPair portal = DoorwayComponent.get(server).getEviction();

        if (memory == null || portal == null) {
            return;
        }

        List<ServerPlayerEntity> taken = taken(memory);

        // A heartbeat from the first tick until it finds them, slow at first and then not.
        if (ticks < LOCK_AT) {
            int interval = ticks < OPEN_AT ? 20 : ticks < LOOK_AT ? 14 : 9;
            if (ticks % interval == 1) {
                sound(memory, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0F, 0.8F);
            }
        }

        if (ticks == OPEN_AT) {
            sound(memory, SoundEvents.ENTITY_WARDEN_ROAR, 0.9F, 0.5F);
        }

        if (ticks == LOCK_AT) {
            sound(memory, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.0F, 0.6F);
        }

        if (ticks == TURN_AT) {
            sound(memory, SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.0F, 0.6F);
        }

        if (ticks == PULL_AT) {
            sound(memory, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.0F, 0.7F);

            for (ServerPlayerEntity player : taken) {
                // Nobody is pulled out of a boat; they are pulled out of the dimension.
                player.stopRiding();
            }
        }

        if (ticks >= PULL_AT && ticks < CLOSE_AT) {
            float pull = (ticks - PULL_AT) / (float) PULL_TICKS;
            int remaining = CLOSE_AT - ticks;

            for (ServerPlayerEntity player : taken) {
                // They come out high over the overworld. Refreshed every tick, so it is certainly
                // still running whenever they happen to go through.
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.SLOW_FALLING, SLOW_FALLING_TICKS, 0, false, false, true
                ));

                // A little beyond the portal's centre rather than at it, so they are still
                // travelling when they reach its plane and go through instead of stopping on it.
                Vec3d target = portal.inner().center().add(portal.inner().forward().multiply(3.0));
                Vec3d toward = target.subtract(player.getEntityPos().add(0, player.getHeight() / 2.0, 0));
                double distance = toward.length();

                if (distance < 1.0e-3) {
                    continue;
                }

                // Building speed as it goes -- but never less than it takes to arrive before the
                // pull ends, however far away they started.
                double speed = Math.min(Math.max(0.25 + 1.6 * pull * pull, distance / Math.max(remaining, 1)), 4.0);

                // Set outright every tick rather than added to. Gravity, drag and whatever the
                // player is doing with their keys all act on velocity too, and an impulse would
                // have to win against them; a fixed velocity simply is the answer.
                player.setVelocity(toward.multiply(speed / distance));
                player.fallDistance = 0.0;
                player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            }
        }

        // Whoever is still here did not make it through on their own.
        if (ticks == CLOSE_AT) {
            for (ServerPlayerEntity player : taken) {
                putThrough(server, player, portal);
            }
        }
    }

    /** Play a sound to everyone in the memory dimension, from where each of them is standing. */
    private static void sound(ServerWorld memory, net.minecraft.sound.SoundEvent event, float volume, float pitch) {
        for (ServerPlayerEntity player : memory.getPlayers()) {
            memory.playSound(null, player.getX(), player.getY(), player.getZ(), event, SoundCategory.AMBIENT, volume, pitch);
        }
    }

    /** Put a player just outside the overworld end, as though they had come through it. */
    private static void putThrough(MinecraftServer server, ServerPlayerEntity player, PortalPair portal) {
        PortalAnchor outer = portal.outer();
        ServerWorld destination = server.getWorld(outer.dimension());

        if (destination == null) {
            return;
        }

        Vec3d position = outer.center().add(outer.normal().multiply(2.0));
        destination.getChunk(BlockPos.ofFloored(position));

        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOW_FALLING, SLOW_FALLING_TICKS, 0, false, false, true
        ));
        player.teleportTo(new TeleportTarget(
                destination, position, Vec3d.ZERO,
                outer.facing().getOpposite().getPositiveHorizontalDegrees(), 0.0F, TeleportTarget.NO_OP
        ));
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0;
    }

    public static void clear() {
    }
}
