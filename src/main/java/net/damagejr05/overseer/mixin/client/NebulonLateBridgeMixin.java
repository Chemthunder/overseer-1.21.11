package net.damagejr05.overseer.mixin.client;

import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the portal's nested world render from handing Nebulon a frame to finish.
 *
 * <p>Since 0.1.2 Nebulon no longer draws its final stage from Fabric's END_MAIN event. It stashes
 * that event's render context instead and replays it once the world render has returned, so its
 * effects land on top of particles, clouds and weather. The stash happens outside
 * {@code renderStage}, so {@link NebulonClientMixin} no longer covers it: the nested render fires
 * END_MAIN too, and what gets stashed is the far world's context.
 *
 * <p>Today the main render always fires afterwards and overwrites it, so nothing visibly breaks.
 * That is an accident of ordering rather than something to rely on -- on any frame where it did
 * not, Nebulon would draw the scene's effects with the portal's camera.
 *
 * @author Flex
 */
@Mixin(targets = "de.nexusrealms.nebulon.client.LateWorldRenderBridge", remap = false)
public class NebulonLateBridgeMixin {
    @Inject(method = "capture", at = @At("HEAD"), cancellable = true, require = 1)
    private static void overseer$ignorePortalRender(CallbackInfo ci) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            ci.cancel();
        }
    }
}
