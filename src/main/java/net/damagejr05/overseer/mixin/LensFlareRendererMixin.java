package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.rbn.chroma.client.lens_flare.LensFlareHudRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * @author Chemthunder
 */
@Mixin(value = LensFlareHudRenderer.class)
public abstract class LensFlareRendererMixin {
    @WrapMethod(method = "render")
    private void killitwithfire(DrawContext gg, RenderTickCounter deltaTracker, Operation<Void> original) {}
}
