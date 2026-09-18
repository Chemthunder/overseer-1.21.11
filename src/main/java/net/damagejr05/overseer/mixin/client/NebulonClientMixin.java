package net.damagejr05.overseer.mixin.client;

import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suspends Nebulon's per-stage dispatch while the portal's nested world render is running.
 *
 * <p>{@code NebulonClient.renderStage} binds {@code SceneAttachmentManager} to
 * {@code MinecraftClient.getFramebuffer()}, and that state is static and per-frame. The nested
 * render is a real {@code WorldRenderer.render}, so Fabric's world render events fire inside it and
 * Nebulon binds its scene state to the portal's offscreen target instead of the actual scene. The
 * main render then composites against the wrong surface, which corrupts the whole frame.
 *
 * <p>Nebulon has no notion of a foreign nested render, so the suspension is applied from here.
 * The proper home for this is a suspend flag inside Nebulon itself; until then this achieves the
 * same thing without reaching into its internals.
 *
 * @author Flex
 */
@Mixin(targets = "de.nexusrealms.nebulon.client.NebulonClient", remap = false)
public class NebulonClientMixin {
    // require = 1: Nebulon is a hard dependency, so if a future version renames this the mod
    // should fail at load rather than silently going back to corrupting the frame.
    @Inject(method = "renderStage", at = @At("HEAD"), cancellable = true, require = 1)
    private static void overseer$suspendDuringPortalRender(CallbackInfo ci) {
        if (PortalViewRenderer.isRenderingPortalView()) {
            ci.cancel();
        }
    }
}
