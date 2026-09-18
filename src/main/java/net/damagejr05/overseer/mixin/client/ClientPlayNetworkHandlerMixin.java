package net.damagejr05.overseer.mixin.client;

import net.damagejr05.overseer.core.client.PortalTransitionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientChunkLoadProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hides the world-loading screen for a portal crossing.
 *
 * <p>Vanilla shows a full loading screen on every dimension change, which is precisely the seam a
 * seamless portal exists to hide. Only the screen itself is suppressed — the chunk-load progress
 * object is still created and started, because that is real state the client needs regardless.
 *
 * <p>The flag is armed per crossing and consumed here, so ordinary teleports, respawns and
 * nether travel keep their screen.
 *
 * @author Flex
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Redirect(
            method = "startWorldLoading",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/world/LevelLoadingScreen;init(Lnet/minecraft/client/world/ClientChunkLoadProgress;Lnet/minecraft/client/gui/screen/world/LevelLoadingScreen$WorldEntryReason;)V"
            )
    )
    private void overseer$skipLoadingScreenInit(LevelLoadingScreen screen, ClientChunkLoadProgress progress,
                                                LevelLoadingScreen.WorldEntryReason reason) {
        if (PortalTransitionClient.consume()) {
            return;
        }

        screen.init(progress, reason);
    }

    @Redirect(
            method = "startWorldLoading",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;setScreenAndRender(Lnet/minecraft/client/gui/screen/Screen;)V"
            )
    )
    private void overseer$skipLoadingScreen(MinecraftClient client, Screen screen) {
        if (PortalTransitionClient.consume()) {
            return;
        }

        client.setScreenAndRender(screen);
    }
}
