package net.damagejr05.overseer.core.event.client;

import dev.rbn.nitrogen.Nitro;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Easing;
import net.minecraft.util.math.MathHelper;
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

            matrices.push();

            float delta = MinecraftClient.getInstance().getRenderTickCounter().getDynamicDeltaTicks();

            float width = MathHelper.clamp(Easing.inOutCubic(doorway.getDoorwayWidth()), 0, 1.0F);
            float height = 7;

            float additional = 0.4F;
            float additional2 = 0;


            {


                Nitro.quad(
                        matrices.peek(),
                        consumers.getBuffer(RenderLayers.entityTranslucentEmissive(Overseer.id("textures/render/white.png"), true)),
                        x - (width + additional),
                        y,
                        z,

                        x,
                        y + (height + 1),
                        z,

                        x + (width + additional),
                        y,
                        z,

                        x,
                        y - (height + 1),
                        z,

                        1,
                        1,
                        1,
                        1
                );

                Nitro.quad(
                        matrices.peek(),
                        consumers.getBuffer(RenderLayers.endGateway()),
                        x - (width + additional2),
                        y,
                        z,

                        x,
                        y + height,
                        z,

                        x + (width + additional2),
                        y,
                        z,

                        x,
                        y - height,
                        z,

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
