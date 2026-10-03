package net.damagejr05.overseer.core;

import net.acoyt.acornlib.api.ALib;
import net.damagejr05.overseer.core.command.OverseerCommand;
import net.damagejr05.overseer.core.event.ApplyCursedEffectEvent;
import net.damagejr05.overseer.core.index.ModBlocks;
import net.damagejr05.overseer.core.index.ModStatusEffects;
import net.damagejr05.overseer.core.net.PacketRedirection;
import net.damagejr05.overseer.core.net.RemoteChunkTracker;
import net.damagejr05.overseer.core.net.RemoteEntityTracker;
import net.damagejr05.overseer.core.net.RemoteEnvironmentTracker;
import net.damagejr05.overseer.core.portal.PortalChunkLoader;
import net.damagejr05.overseer.core.portal.Eviction;
import net.damagejr05.overseer.core.portal.PortalTraversal;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Overseer implements ModInitializer {
	public static final String MOD_ID = "overseer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(new OverseerCommand());
        ServerPlayerEvents.JOIN.register(new ApplyCursedEffectEvent());
        ServerTickEvents.END_SERVER_TICK.register(PortalChunkLoader::tick);
        ServerTickEvents.END_SERVER_TICK.register(PortalTraversal::tick);
        ServerTickEvents.END_SERVER_TICK.register(RemoteChunkTracker::tick);
        ServerTickEvents.END_SERVER_TICK.register(RemoteEntityTracker::tick);
        // After the chunk tracker: it decides which dimensions a player holds, and this follows.
        ServerTickEvents.END_SERVER_TICK.register(RemoteEnvironmentTracker::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RemoteChunkTracker.forget(handler.player);
            RemoteEntityTracker.forget(handler.player);
            RemoteEnvironmentTracker.forget(handler.player);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            RemoteChunkTracker.onWorldChange(player, destination.getRegistryKey());
            RemoteEntityTracker.onWorldChange(player, destination.getRegistryKey());
            RemoteEnvironmentTracker.onWorldChange(player, destination.getRegistryKey());
        });
        // The bookkeeping is static, and in singleplayer the JVM outlives the server.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            RemoteChunkTracker.clear();
            RemoteEntityTracker.clear();
            RemoteEnvironmentTracker.clear();
            PortalTraversal.clear();
            Eviction.clear();
        });

        ModBlocks.init();
        ModStatusEffects.init();
        PacketRedirection.init();

		LOGGER.info("Init Completed.");

        ALib.registerModData(
                MOD_ID,
                new ALib.ModMenuData(
                        Text.literal("Overseer.").formatted(Formatting.ITALIC, Formatting.BOLD, Formatting.OBFUSCATED),
                        Text.literal("Welcome home.").formatted(Formatting.BOLD, Formatting.OBFUSCATED, Formatting.DARK_GRAY),
                        Text.literal("Commissioned by Damagejr05 for Apostasy SMP Season Two. Adds the abilities and powers of the Overseer.").formatted(Formatting.OBFUSCATED)
                )
        );
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

    /**
     * Whether an entity may use the Overseer's abilities.
     *
     * <p>In a development environment any player qualifies, because dev worlds hand out random
     * UUIDs and nothing would ever be testable otherwise. On a real server this is Damage alone.
     * Both the commands and portal traversal go through here so the two can never disagree.
     */
    public static boolean isOverseer(Entity entity) {
        return isDamage(entity) || (FabricLoader.getInstance().isDevelopmentEnvironment() && !DEV_GUEST);
    }

    /**
     * Launch a development client with {@code -Doverseer.guest=true} to be treated as an ordinary
     * player. Everything a guest experiences -- sealed doorways, being turned away at one -- is
     * otherwise impossible to see in a dev environment, where everybody is the Overseer.
     */
    private static final boolean DEV_GUEST = Boolean.getBoolean("overseer.guest");

    public static boolean isDamage(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return false;
        return entity.getUuidAsString().equals("5de5299b-83c1-4fe4-9c47-b8aae4fed6b1");
    }
}
