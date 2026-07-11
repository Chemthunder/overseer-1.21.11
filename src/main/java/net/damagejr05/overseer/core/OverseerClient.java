package net.damagejr05.overseer.core;

import net.damagejr05.overseer.core.event.client.DoorwayRenderEvent;
import net.damagejr05.overseer.core.event.client.FixedRenderEvent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class OverseerClient implements ClientModInitializer {
    public static int age = 0;

    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(minecraftClient -> age++);

        DoorwayRenderEvent.create();
        FixedRenderEvent.create();
    }
}
