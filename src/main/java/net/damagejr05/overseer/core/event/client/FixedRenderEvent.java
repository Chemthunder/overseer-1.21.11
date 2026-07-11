package net.damagejr05.overseer.core.event.client;

import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
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

            VertexConsumerProvider consumers = worldRenderContext.consumers();
            MatrixStack matrices = worldRenderContext.matrices();

            float size = 5;

            matrices.push();

            matrices.pop();
        }
    }

    public static void create() {
        WorldRenderEvents.AFTER_ENTITIES.register(new FixedRenderEvent());
    }
}