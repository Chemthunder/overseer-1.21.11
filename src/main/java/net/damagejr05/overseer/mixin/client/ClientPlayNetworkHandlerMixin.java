package net.damagejr05.overseer.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.damagejr05.overseer.core.client.PortalTransitionClient;
import net.damagejr05.overseer.core.client.SecondaryWorldManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
    @Shadow private ClientWorld.Properties worldProperties;

    /**
     * On a portal crossing, give vanilla the far world it was already looking at instead of a new
     * empty one. See {@link SecondaryWorldManager#adopt}.
     */
    @WrapOperation(
            method = "onPlayerRespawn",
            at = @At(value = "NEW", target = "net/minecraft/client/world/ClientWorld")
    )
    private ClientWorld overseer$adoptSecondaryWorld(ClientPlayNetworkHandler handler, ClientWorld.Properties properties,
                                                     RegistryKey<World> dimension, RegistryEntry<DimensionType> type,
                                                     int loadDistance, int simulationDistance, WorldRenderer renderer,
                                                     boolean debugWorld, long seed, int seaLevel,
                                                     Operation<ClientWorld> original) {
        ClientWorld adopted = null;

        try {
            adopted = SecondaryWorldManager.adopt(dimension, loadDistance, simulationDistance);
        } catch (Throwable e) {
            net.damagejr05.overseer.core.Overseer.LOGGER.error("Could not hand over the secondary world, falling back", e);
        }

        if (adopted == null) {
            return original.call(handler, properties, dimension, type, loadDistance, simulationDistance,
                    renderer, debugWorld, seed, seaLevel);
        }

        // Vanilla has just pointed this at a properties object made for the world it thinks it is
        // about to build. Difficulty and spawn updates are written through it, so it has to be
        // the adopted world's own or they would go nowhere.
        this.worldProperties = adopted.getLevelProperties();
        return adopted;
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void overseer$finishAdoption(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        SecondaryWorldManager.finishAdoption();
    }

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
