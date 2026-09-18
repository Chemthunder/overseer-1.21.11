package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Reports multi-world client state, so "are the remote chunks actually arriving?" can be answered
 * in game with one command instead of by eye.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class ClientDebugCommand {
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(literal("overseerclient").then(literal("portals").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();

                    line(context.getSource().getClient(), "--- Overseer client state ---", Formatting.GOLD);

                    ClientWorld primary = client.world;
                    if (primary != null) {
                        line(client, "standing in: " + primary.getRegistryKey().getValue(), Formatting.WHITE);
                    }

                    report(client, "memory", ModDimensions.MEMORY);
                    report(client, "overworld", net.minecraft.world.World.OVERWORLD);

                    if (primary != null) {
                        DoorwayComponent doorways = DoorwayComponent.get(primary);
                        describePair(client, "fixed", doorways.getFixed());
                        describePair(client, "dynamic", doorways.getDynamic());
                    }

                    line(client, "see-through: " + (PortalViewRenderer.isEnabled() ? "on" : "off (procedural aperture)"),
                            PortalViewRenderer.isEnabled() ? Formatting.GREEN : Formatting.YELLOW);

                    line(client, "drawing " + PortalViewRenderer.visible().size() + " portal(s) here", Formatting.WHITE);
                    line(client, PortalViewRenderer.describeLastRender(), Formatting.LIGHT_PURPLE);

                    if (client.player != null) {
                        Vec3d eye = client.player.getEyePos();
                        line(client, String.format("your eye %.1f %.1f %.1f yaw %.0f",
                                eye.x, eye.y, eye.z, client.player.getYaw()), Formatting.WHITE);
                    }

                    return 1;
                })).then(literal("portalview")
                        .then(literal("on").executes(context -> setPortalView(true)))
                        .then(literal("off").executes(context -> setPortalView(false))))
                        .then(literal("solid")
                                .then(literal("on").executes(context -> {
                                    PortalViewRenderer.setSolidDebug(true);
                                    line(MinecraftClient.getInstance(),
                                            "Aperture drawn as flat magenta - check its shape and position",
                                            Formatting.LIGHT_PURPLE);
                                    return 1;
                                }))
                                .then(literal("off").executes(context -> {
                                    PortalViewRenderer.setSolidDebug(false);
                                    line(MinecraftClient.getInstance(), "Aperture back to normal", Formatting.GREEN);
                                    return 1;
                                }))))
        );
    }

    /**
     * Nested world rendering can hard-crash the process on some drivers, so it stays behind this
     * switch rather than being on by default.
     */
    private static int setPortalView(boolean enabled) {
        PortalViewRenderer.setEnabled(enabled);

        MinecraftClient client = MinecraftClient.getInstance();
        line(client, "Portal see-through " + (enabled ? "enabled - this may crash the client" : "disabled"),
                enabled ? Formatting.RED : Formatting.GREEN);

        return 1;
    }

    private static void report(MinecraftClient client, String label, net.minecraft.registry.RegistryKey<net.minecraft.world.World> key) {
        ClientWorld world = SecondaryWorldManager.get(key);

        if (world == null) {
            line(client, label + ": no client world", Formatting.GRAY);
            return;
        }

        String role = world == client.world ? "primary" : "secondary";
        int chunks = world.getChunkManager().getLoadedChunkCount();

        line(client, label + ": " + role + ", " + chunks + " chunks loaded",
                chunks > 0 ? Formatting.GREEN : Formatting.YELLOW);
    }

    private static void describePair(MinecraftClient client, String label, PortalPair pair) {
        if (pair == null) {
            line(client, label + ": unset", Formatting.GRAY);
            return;
        }

        describe(client, label + " outer", pair.outer());
        describe(client, label + " inner", pair.inner());
    }

    private static void describe(MinecraftClient client, String label, PortalAnchor anchor) {
        if (anchor == null) {
            line(client, label + ": unset", Formatting.GRAY);
            return;
        }

        line(client, label + ": " + anchor.dimension().getValue()
                + " @ " + String.format("%.1f %.1f %.1f", anchor.center().x, anchor.center().y, anchor.center().z)
                + " facing " + anchor.facing().asString(), Formatting.AQUA);
    }

    private static void line(MinecraftClient client, String text, Formatting colour) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text).formatted(colour), false);
        }
    }
}
