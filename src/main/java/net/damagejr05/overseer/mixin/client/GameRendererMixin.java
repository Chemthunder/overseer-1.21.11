package net.damagejr05.overseer.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Renders portal views immediately before the main world render, reusing the frame's real matrices.
 *
 * <p>Wrapping the call rather than injecting before it hands us the exact matrices vanilla is about
 * to render with. That matters: the composite samples the offscreen view at each portal fragment's
 * own screen position, which only lines up if the far view was rendered with the *same* projection
 * as the near one. Recomputing it independently drops view bobbing and hurt tilt, and the whole
 * sampled image shifts.
 *
 * <p>Running here also keeps the nested render outside the main render rather than re-entering it,
 * while still being late enough that every piece of per-frame setup is done.
 *
 * @author Flex
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @WrapOperation(
            method = "renderWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"
            )
    )
    private void overseer$renderPortalViews(WorldRenderer renderer, ObjectAllocator allocator,
                                            RenderTickCounter tickCounter, boolean renderBlockOutline,
                                            Camera camera, Matrix4f positionMatrix,
                                            Matrix4f basicProjectionMatrix, Matrix4f projectionMatrix,
                                            GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky,
                                            Operation<Void> original) {
        PortalViewRenderer.renderPortalViews(
                tickCounter, positionMatrix, basicProjectionMatrix, projectionMatrix, fogBuffer, fogColor
        );

        original.call(renderer, allocator, tickCounter, renderBlockOutline, camera, positionMatrix,
                basicProjectionMatrix, projectionMatrix, fogBuffer, fogColor, renderSky);
    }
}
