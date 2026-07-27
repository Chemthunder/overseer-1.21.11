package net.damagejr05.overseer.core.event.client;

import dev.rbn.nitrogen.Nitro;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.OverseerClient;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * @author Chemthunder
 */
public class FixedRenderEvent implements WorldRenderEvents.AfterEntities {
    public void afterEntities(WorldRenderContext worldRenderContext) {
        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        DoorwayComponent doorway = DoorwayComponent.KEY.get(world);

        if (doorway.getFixedPos() != null) {
            Vec3d pos = doorway.getFixedPos();

            float x = (float) (pos.x - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().x);
            float y = (float) (pos.y - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().y);
            float z = (float) (pos.z - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().z);

            float delta = MinecraftClient.getInstance().getRenderTickCounter().getDynamicDeltaTicks();

            VertexConsumerProvider consumers = worldRenderContext.consumers();
            MatrixStack matrices = worldRenderContext.matrices();

            {
                matrices.push();

                matrices.multiply(RotationAxis.POSITIVE_Y.rotation((OverseerClient.age + delta) / 64),
                        x,
                        y,
                        z
                );

                matrices.multiply(RotationAxis.POSITIVE_X.rotation((OverseerClient.age + delta) / 64),
                        x,
                        y,
                        z
                );

//                Nitro.texCubeAtPos(
//                        matrices,
//                        consumers.getBuffer(RenderLayers.eyes(Overseer.id("textures/render/white.png"))),
//                        x,
//                        y,
//                        z,
//                        0.45F,
//                        new Vec2f(0, 0),
//                        1
//                );

                matrices.pop();
            }

            {
                matrices.push();

                matrices.multiply(RotationAxis.POSITIVE_Y.rotation((OverseerClient.age + delta) / 64),
                        x,
                        y,
                        z
                );

                matrices.multiply(RotationAxis.POSITIVE_X.rotation((OverseerClient.age + delta) / 64),
                        x,
                        y,
                        z
                );

                Nitro.texCubeAtPos(
                        matrices,
                        consumers.getBuffer(RenderLayers.endPortal()),
                        x,
                        y,
                        z,
                        0.4F,
                        new Vec2f((OverseerClient.age + delta) / 4, 0),
                        1
                );

                matrices.pop();
            }


        }
    }

    public static void create() {
        WorldRenderEvents.AFTER_ENTITIES.register(new FixedRenderEvent());
    }
}