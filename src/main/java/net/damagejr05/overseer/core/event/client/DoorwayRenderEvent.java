package net.damagejr05.overseer.core.event.client;

import dev.rbn.nitrogen.Nitro;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Easing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class DoorwayRenderEvent implements WorldRenderEvents.AfterEntities {
    public void afterEntities(WorldRenderContext worldRenderContext) {
        World world = MinecraftClient.getInstance().world;
        if (world == null) return;

        DoorwayComponent doorway = DoorwayComponent.KEY.get(world);

        if (doorway.getDoorwayPos() != null) {
            Vec3d pos = doorway.getDoorwayPos();

            float x = (float) (pos.x - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().x);
            float y = (float) (pos.y - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().y);
            float z = (float) (pos.z - (float) worldRenderContext.gameRenderer().getCamera().getCameraPos().z);

            VertexConsumerProvider consumers = worldRenderContext.consumers();
            MatrixStack matrices = worldRenderContext.matrices();

            float width = MathHelper.clamp(Easing.inOutCubic(doorway.getDoorwayWidth()), 0, 1.0F);
            float height = 2;

            float additional = 0.2F;
            float additional2 = 0;

            matrices.push();

            {
                matrices.translate(x, y, z);

                Nitro.quad(
                        matrices.peek(),
                        consumers.getBuffer(RenderLayers.endGateway()),
                        0 - (width + additional2),
                        0,
                        0,

                        0,
                        0 + height,
                        0,

                        0 + (width + additional2),
                        0,
                        0,

                        0,
                        0 - height,
                        0,

                        1,
                        1,
                        1,
                        1
                );
            }

            matrices.pop();

            matrices.push();

            {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180), x, y, z);
                matrices.translate(x, y, z);

                Nitro.quad(
                        matrices.peek(),
                        consumers.getBuffer(RenderLayers.endGateway()),
                        0 - (width + additional2),
                        0,
                        0,

                        0,
                        0 + height,
                        0,

                        0 + (width + additional2),
                        0,
                        0,

                        0,
                        0 - height,
                        0,

                        1,
                        1,
                        1,
                        1
                );
            }

            matrices.pop();
        }
    }

    public static void create() {
        WorldRenderEvents.AFTER_ENTITIES.register(new DoorwayRenderEvent());
    }
}
