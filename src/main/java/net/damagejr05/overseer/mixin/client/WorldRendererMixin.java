package net.damagejr05.overseer.mixin.client;

import net.damagejr05.overseer.core.client.SecondaryWorldManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a renderer's meshes when the world it is handed is the one it already has.
 *
 * <p>On a dimension change vanilla calls {@code setWorld} on the main renderer, which throws away
 * every built section and starts again. When the world arriving is one that was being drawn
 * through a doorway, the renderer arriving with it has all of it built already; rebuilding would
 * put back the very gap the handover exists to remove.
 *
 * @author Flex
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Shadow private @Nullable ClientWorld world;

    @Inject(method = "setWorld", at = @At("HEAD"), cancellable = true)
    private void overseer$keepMeshesWhenAdopting(@Nullable ClientWorld incoming, CallbackInfo ci) {
        if (incoming == this.world && SecondaryWorldManager.isAdopting(incoming)) {
            ci.cancel();
        }
    }
}
