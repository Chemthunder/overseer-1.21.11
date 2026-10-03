package net.damagejr05.overseer.core.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.nexusrealms.nebulon.api.render.client.ShaderMaterial;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.damagejr05.overseer.core.portal.Eviction;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The sky of the memory dimension.
 *
 * <p>The dimension has no skybox, so vanilla draws nothing above the floor but the fog colour.
 * This fills that space: a slow nebula, stars, an aurora low on the horizon, and an eclipsed eye
 * hanging in the north. All of it is computed per pixel from the direction being looked in, so
 * there are no textures and nothing to tile or stretch.
 *
 * <p>It is drawn after the solid world, as a cube around the camera pushed out to the far plane.
 * The depth test then lets it through only where nothing else was drawn, which is the sky.
 *
 * <p>Unlike everything else the mod draws into the world, this deliberately <em>does</em> run
 * inside the portal's nested render: looking into the memory dimension through a doorway should
 * show its sky too. It keys off whichever world is being rendered, not the one the player is in.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class MemorySkyRenderer {
    private static @Nullable ShaderMaterial material;

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(MemorySkyRenderer::render);
    }

    private static ShaderMaterial material() {
        if (material == null) {
            material = ShaderMaterial.builder(Overseer.id("memory_sky"))
                    .shaders(Overseer.id("core/memory_sky"))
                    .geometry(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
                    .transparency(ShaderMaterial.Transparency.OPAQUE)
                    // Tested, never written: the sky sits at the far plane and must not occlude
                    // the translucent geometry and particles that are drawn after it.
                    .depth(DepthTestFunction.LEQUAL_DEPTH_TEST, false)
                    .cull(false)
                    .build();
        }

        return material;
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        // During the portal's nested render client.world is the far world, so this is "the world
        // being drawn right now" in both cases.
        if (client.world == null || client.world.getRegistryKey() != ModDimensions.MEMORY) {
            return;
        }

        Matrix4f view = PortalViewRenderer.activePositionMatrix();
        ShaderMaterial mat = material();

        if (view == null || !mat.shouldRender()) {
            return;
        }

        try (BufferAllocator allocator = new BufferAllocator(24 * VertexFormats.POSITION.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
            emitCube(builder);

            try (BuiltBuffer built = builder.end()) {
                draw(client, built, view);
            }
        } catch (Throwable e) {
            Overseer.LOGGER.error("Memory sky render failed", e);
        }
    }

    /**
     * A cube around the camera. Only the directions matter to the shader, but its size does
     * matter to how it sits on screen.
     *
     * <p>View bobbing is baked into the projection as a small sideways and vertical shift of the
     * whole scene, a fraction of a block. Against a cube one block across that shift is a large
     * part of its size, and the sky visibly swam with every step. Against one this big it is
     * nothing, which is how a real sky behaves: you cannot make the stars move by nodding. Depth
     * is forced to the far plane in the vertex shader, so the size costs nothing and clips nothing.
     */
    private static void emitCube(BufferBuilder builder) {
        float s = 4096.0F;

        quad(builder, -s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s);
        quad(builder, -s, -s, s, s, -s, s, s, s, s, -s, s, s);
        quad(builder, -s, -s, -s, -s, -s, s, -s, s, s, -s, s, -s);
        quad(builder, s, -s, -s, s, -s, s, s, s, s, s, s, -s);
        quad(builder, -s, s, -s, s, s, -s, s, s, s, -s, s, s);
        quad(builder, -s, -s, -s, s, -s, -s, s, -s, s, -s, -s, s);
    }

    private static void quad(BufferBuilder builder,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        builder.vertex(x1, y1, z1);
        builder.vertex(x2, y2, z2);
        builder.vertex(x3, y3, z3);
        builder.vertex(x4, y4, z4);
    }

    private static void draw(MinecraftClient client, BuiltBuffer built, Matrix4f view) {
        GpuBuffer vertexBuffer = RenderSystem.getDevice()
                .createBuffer(() -> "overseer memory sky", GpuBuffer.USAGE_VERTEX, built.getBuffer());

        // Whatever the world is currently being drawn into: the screen, or a portal's target.
        Framebuffer target = client.getFramebuffer();

        RenderSystem.ShapeIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
        GpuBuffer indexBuffer = indices.getIndexBuffer(36);

        // The eviction's animation, packed into the three fields of the transform block this
        // shader has no other use for. See the top of memory_sky.fsh for the layout.
        Vector4f modulator = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
        Vector3f toward = new Vector3f();
        Matrix4f state = new Matrix4f().zero();

        // The player's own world, not client.world: inside a portal's nested render that is the
        // far world, whose copy of the doorway state is never synced.
        if (client.player != null) {
            DoorwayComponent doorways = DoorwayComponent.KEY.get(client.player.getEntityWorld());
            PortalPair portal = doorways.getEviction();

            if (doorways.evictionTicks() > 0 && portal != null) {
                float t = doorways.evictionTicks() + client.getRenderTickCounter().getTickProgress(false);

                // From this render's own camera, which inside a portal view is the virtual one.
                Vec3d offset = portal.inner().center().subtract(client.gameRenderer.getCamera().getCameraPos());
                double distance = Math.max(offset.length(), 1.0);

                toward.set((float) (offset.x / distance), (float) (offset.y / distance), (float) (offset.z / distance));

                // The angle the finished portal's edge is seen at. Every size in the eye is a
                // multiple of this, so the eye is always in proportion to the portal it becomes.
                float unit = (float) Math.atan(Eviction.SIZE / 2.0 / distance);

                modulator.set(Eviction.presence(t), Eviction.dread(t), Eviction.flare(t), unit);

                state.m00(Eviction.lid(t)).m01(Eviction.gazeX(t)).m02(Eviction.gazeY(t)).m03(Eviction.pupil(t));
                state.m10(Eviction.roundness(t)).m11(Eviction.turned(t)).m12(Eviction.shock(t)).m13(Eviction.pull(t));
            }
        }

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(view, modulator, toward, state);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "overseer memory sky",
                target.getColorAttachmentView(), OptionalInt.empty(),
                target.getDepthAttachmentView(), OptionalDouble.empty()
        )) {
            pass.setPipeline(material().pipeline());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, indices.getIndexType());
            pass.drawIndexed(0, 0, 36, 1);
        } finally {
            vertexBuffer.close();
        }
    }
}
