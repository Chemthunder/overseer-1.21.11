package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.net.PortalTransitionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Tracks whether the dimension change currently arriving is a portal crossing.
 *
 * <p>The flag is armed by the server just before it teleports, and disarmed as soon as the world
 * load it covers has been handled. It also times out: if the transition never arrives — the packet
 * raced a disconnect, say — a stale flag must not silently suppress a legitimate loading screen
 * later on.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class PortalTransitionClient {
    /** How long an armed transition stays valid, in milliseconds. */
    private static final long TIMEOUT_MS = 5_000L;

    private static long armedAt = Long.MIN_VALUE;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(PortalTransitionPayload.ID, (payload, context) ->
                arm()
        );
    }

    public static void arm() {
        armedAt = System.currentTimeMillis();
    }

    /**
     * True if a portal crossing is in flight. Consumes the flag, so it only ever covers the single
     * world load it was armed for.
     */
    public static boolean consume() {
        if (armedAt == Long.MIN_VALUE) {
            return false;
        }

        boolean fresh = System.currentTimeMillis() - armedAt <= TIMEOUT_MS;
        armedAt = Long.MIN_VALUE;

        return fresh;
    }

    public static void clear() {
        armedAt = Long.MIN_VALUE;
    }
}
