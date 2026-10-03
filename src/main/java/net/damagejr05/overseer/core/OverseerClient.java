package net.damagejr05.overseer.core;

import net.damagejr05.overseer.core.client.ClientDebugCommand;
import net.damagejr05.overseer.core.client.PacketRedirectionClient;
import net.damagejr05.overseer.core.client.PortalTransitionClient;
import net.damagejr05.overseer.core.client.SecondaryWorldManager;
import net.damagejr05.overseer.core.client.render.MemorySkyRenderer;
import net.damagejr05.overseer.core.client.render.PortalViewCompositor;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;

public class OverseerClient implements ClientModInitializer {
    public static int age = 0;

    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(minecraftClient -> age++);
        ClientTickEvents.END_CLIENT_TICK.register(minecraftClient -> SecondaryWorldManager.tick());

        // Secondary worlds hold a reference to the network handler, so they must not outlive it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SecondaryWorldManager.clear();
            PortalTransitionClient.clear();
        });

        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Overseer.id("secondary_worlds"),
                (SynchronousResourceReloader) manager -> SecondaryWorldManager.onResourcesReloaded()
        );

        PacketRedirectionClient.init();
        PortalTransitionClient.init();
        ClientDebugCommand.init();

        PortalViewRenderer.init();
        // Before the compositor: both draw at the same stage, and the sky belongs behind the doorways.
        MemorySkyRenderer.init();
        PortalViewCompositor.init();
    }
}
