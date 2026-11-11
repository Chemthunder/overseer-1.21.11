package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerLikeState;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Carries the player's own state across a portal crossing, so stepping through a doorway is one
 * continuous movement rather than a teleport.
 *
 * <p>A crossing is a vanilla dimension change underneath, and vanilla's dimension change is built
 * to happen behind a loading screen. Two things about it show once the screen is taken away:
 *
 * <ul>
 *   <li><b>The player is a new object.</b> The client throws its player entity away and makes
 *       another, which starts at the origin with every piece of presentation state at zero --
 *       the walk bob, the sway of the held item, the timer that raises the hand after a swing.
 *       Left alone the view stops bobbing mid-stride and the held item dips out of sight and
 *       comes back, on every crossing.</li>
 *   <li><b>The server's placement is out of date.</b> It moves the player from where it last
 *       heard they were, and the answer takes a round trip to arrive. The client has kept
 *       walking, so being put where the server says is a jump backwards by a few tenths of a
 *       block -- more with latency, and always in the middle of the doorway.</li>
 * </ul>
 *
 * <p>Both are settled the same way. The doorway is a rigid transform and the client knows it, so
 * the moment the new player exists it is given the old one's state carried through that
 * transform: position, the previous tick's position (the camera interpolates between the two),
 * facing, velocity and the presentation state. The camera is then exactly where the view through
 * the doorway was already being drawn from a frame earlier, and nothing moves.
 *
 * <p>The server's own teleport still arrives and is still acknowledged exactly as vanilla does.
 * It is simply not allowed to drag the player back when it agrees with where they already are to
 * within what latency explains; anything further off than that is the server knowing something
 * the client does not, and is obeyed.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public final class CrossingContinuity {
    /**
     * How far the client's own position may be from the server's placement and still be kept, in
     * blocks. Generous for a sprint at a poor ping, well inside what the server accepts as an
     * ordinary movement, and far short of anything that could be used to go somewhere.
     */
    private static final double MAX_DRIFT = 6.0;

    /** The doorway the next dimension change belongs to, once the server has announced it. */
    private static @Nullable PortalTransform expected = null;
    private static long expectedAt = 0L;

    /** The player being left behind, for the duration of the respawn packet that replaces it. */
    private static @Nullable ClientPlayerEntity departing = null;

    /** The new player, from the respawn until the server's teleport for the crossing lands. */
    private static @Nullable ClientPlayerEntity carried = null;
    private static long carriedAt = 0L;

    /** The carried player's state, held across vanilla applying the server's teleport. */
    private static @Nullable Held held = null;

    private record Held(ClientPlayerEntity player, Vec3d pos, Vec3d lastPos, float yaw, float pitch,
                        float lastYaw, float lastPitch, Vec3d velocity) {
    }

    private CrossingContinuity() {
    }

    /** The server has said the dimension change that follows is a crossing of this doorway. */
    public static void expect(PortalTransform transform) {
        expected = transform;
        expectedAt = System.currentTimeMillis();
        departing = null;
    }

    /** Before vanilla handles the respawn packet: remember who is leaving. */
    public static void beforeRespawn(MinecraftClient client) {
        departing = null;

        if (expected == null) {
            return;
        }

        if (System.currentTimeMillis() - expectedAt > PortalTransitionClient.TIMEOUT_MS) {
            expected = null;
            return;
        }

        departing = client.player;
    }

    /** After vanilla has handled the respawn packet: hand the new player the old one's state. */
    public static void afterRespawn(MinecraftClient client) {
        ClientPlayerEntity from = departing;
        PortalTransform transform = expected;

        departing = null;
        expected = null;
        carried = null;

        ClientPlayerEntity to = client.player;

        if (from == null || transform == null || to == null || to == from) {
            return;
        }

        // Only for the change this doorway makes. Anything else arriving in the window -- a death,
        // a command -- is not a crossing, and the old player's position means nothing to it.
        if (from.getEntityWorld().getRegistryKey() != transform.from().dimension()
                || to.getEntityWorld().getRegistryKey() != transform.to().dimension()) {
            return;
        }

        try {
            carry(from, to, transform);

            carried = to;
            carriedAt = System.currentTimeMillis();

            // The camera trails the player by up to a tick and has yet to come out of the
            // doorway itself. The renderer has to know, or it takes that for walking back in.
            PortalViewRenderer.arrivedThrough(transform.to(), to.getEntityPos());
        } catch (Throwable e) {
            // Never worth failing a dimension change over: without it the crossing is merely
            // the plain vanilla one.
            Overseer.LOGGER.error("Could not carry the player's state through the doorway", e);
        }
    }

    private static void carry(ClientPlayerEntity from, ClientPlayerEntity to, PortalTransform transform) {
        float turn = transform.yawDelta();

        Vec3d pos = transform.applyPoint(from.getEntityPos());
        Vec3d last = transform.applyPoint(new Vec3d(from.lastX, from.lastY, from.lastZ));

        to.setPosition(pos);
        to.setYaw(from.getYaw() + turn);
        to.setPitch(from.getPitch());
        // The camera sits between last tick's position and this one's. Carrying both keeps it on
        // the same point of the same stride rather than snapping to the end of it.
        to.setLastPositionAndAngles(last, from.lastYaw + turn, from.lastPitch);
        to.setVelocity(transform.applyVector(from.getVelocity()));
        to.setOnGround(from.isOnGround());

        to.bodyYaw = from.bodyYaw + turn;
        to.lastBodyYaw = from.lastBodyYaw + turn;
        to.headYaw = from.headYaw + turn;
        to.lastHeadYaw = from.lastHeadYaw + turn;

        // The held item trails the view by a fraction of how far the view has turned since these
        // were last updated. From zero that is the whole of the player's yaw, and the hand swings
        // out of frame and eases back.
        to.renderYaw = from.renderYaw + turn;
        to.lastRenderYaw = from.lastRenderYaw + turn;
        to.renderPitch = from.renderPitch;
        to.lastRenderPitch = from.lastRenderPitch;

        // The walk bob: how far along the stride, and how strongly it is swinging.
        ClientPlayerLikeState stride = from.getState();
        ClientPlayerLikeState carriedStride = to.getState();
        carriedStride.distanceMoved = stride.distanceMoved;
        carriedStride.lastDistanceMoved = stride.lastDistanceMoved;
        carriedStride.movement = stride.movement;
        carriedStride.lastMovement = stride.lastMovement;

        // Both count up from zero and the hand is drawn lowered until they have: a new player
        // looks exactly like one that has just swung.
        to.ticksSinceLastAttack = from.ticksSinceLastAttack;
        to.ticksSinceHandEquipping = from.ticksSinceHandEquipping;

        // The server sends the real inventory a moment later. Until it does the new player holds
        // nothing, and if a tick falls in the gap the held item vanishes and is raised again.
        to.getInventory().clone(from.getInventory());
    }

    /**
     * Before vanilla applies a teleport from the server. Called for every one; it is the first
     * after a carried respawn that matters.
     */
    public static void beforeTeleport(MinecraftClient client, PlayerPositionLookS2CPacket packet) {
        held = null;

        ClientPlayerEntity player = carried;
        if (player == null) {
            return;
        }

        // Whatever this is, the crossing's teleport is the first to follow its respawn. A later
        // one must never be mistaken for it.
        carried = null;

        if (player != client.player
                || player.hasVehicle()
                || System.currentTimeMillis() - carriedAt > PortalTransitionClient.TIMEOUT_MS
                || !isCrossingTeleport(packet.relatives())) {
            return;
        }

        held = new Held(
                player,
                player.getEntityPos(),
                new Vec3d(player.lastX, player.lastY, player.lastZ),
                player.getYaw(), player.getPitch(),
                player.lastYaw, player.lastPitch,
                player.getVelocity()
        );

        // The packet turns the player relative to the way they face, and they have been turned
        // already. Take that back out first, so the turn is applied once and the acknowledgement
        // vanilla sends from inside its handler carries the right facing.
        player.setYaw(player.getYaw() - packet.change().yaw());
        player.setPitch(player.getPitch() - packet.change().pitch());
    }

    /** After vanilla has applied and acknowledged the teleport. */
    public static void afterTeleport(MinecraftClient client) {
        Held state = held;
        held = null;

        if (state == null || state.player() != client.player) {
            return;
        }

        ClientPlayerEntity player = state.player();
        Vec3d placed = player.getEntityPos();

        // Facing and velocity are the client's own in vanilla too: the packet only ever turns
        // them. Put back exactly what was carried rather than the same thing rounded twice.
        player.setYaw(state.yaw());
        player.setPitch(state.pitch());
        player.setVelocity(state.velocity());

        boolean agrees = state.pos().squaredDistanceTo(placed) <= MAX_DRIFT * MAX_DRIFT
                && player.getEntityWorld().isSpaceEmpty(player, player.getBoundingBox().offset(state.pos().subtract(placed)));

        if (agrees) {
            player.setPosition(state.pos());
            player.setLastPositionAndAngles(state.lastPos(), state.lastYaw(), state.lastPitch());
        } else {
            player.setLastPositionAndAngles(placed, state.lastYaw(), state.lastPitch());
        }

        Overseer.LOGGER.debug("Crossing: client was {} blocks ahead of the server's placement, {}",
                String.format("%.3f", Math.sqrt(state.pos().squaredDistanceTo(placed))),
                agrees ? "kept its own position" : "took the server's");
    }

    /**
     * The shape of the teleport a doorway sends: an absolute place, with facing and velocity
     * turned rather than replaced. See {@code PortalComponent}.
     */
    private static boolean isCrossingTeleport(Set<PositionFlag> relatives) {
        return relatives.contains(PositionFlag.Y_ROT)
                && relatives.contains(PositionFlag.ROTATE_DELTA)
                && !relatives.contains(PositionFlag.X)
                && !relatives.contains(PositionFlag.Y)
                && !relatives.contains(PositionFlag.Z);
    }

    public static void clear() {
        expected = null;
        departing = null;
        carried = null;
        held = null;
    }
}
