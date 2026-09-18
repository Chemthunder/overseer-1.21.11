package net.damagejr05.overseer.core.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.nexusrealms.nebulon.api.render.client.ManagedRenderTarget;
import de.nexusrealms.nebulon.api.render.client.ShaderMaterial;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Draws the portal aperture into the main scene, sampling the offscreen view rendered earlier in
 * the frame by {@link PortalViewRenderer}.
 *
 * <p>The quad is drawn in world space with depth testing and depth writing on, so blocks in front
 * of the portal occlude it and it occludes what is behind, exactly like a solid surface.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class PortalViewCompositor {
    private static @Nullable ShaderMaterial material;

    /**
     * Half the doorway's thickness, in blocks.
     *
     * <p>The doorway occupies exactly one block of wall: the view surface sits on the anchor plane
     * -- which the placement code puts at a block's centre -- and a recess runs out from it to
     * each of that block's faces. So a doorway set into a wall indents it from both sides, and
     * nothing the compositor draws is ever coplanar with the block faces around the opening.
     *
     * <p>It is drawn as a recess rather than a decal because screen-space projective sampling
     * makes the view content identical wherever the surface sits; the depth has to be sold by the
     * side faces being shaded as walls, which is the whole reason they are drawn.
     */
    private static final double DEPTH = 0.5;

    /**
     * Pulls every face just clear of the block plane it would otherwise land on.
     *
     * <p>Anchors are placed at a block's centre in the horizontal and on its floor in Y, so with a
     * default 3-block height the top and bottom of the opening fall exactly on block boundaries,
     * and the lips fall exactly on the wall's own faces. Coplanar with a real block face, the two
     * fight for the depth test and flicker. A couple of millimetres is far below what any camera
     * distance can resolve and puts the argument to rest.
     */
    private static final double INSET = 0.002;

    /** Faces per portal: the aperture plus the four sides of each of the two recesses. */
    private static final int FACES = 9;

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(PortalViewCompositor::render);
    }

    private static ShaderMaterial material() {
        if (material == null) {
            material = ShaderMaterial.builder(Overseer.id("portal_view"))
                    .shaders(Overseer.id("core/portal_view"))
                    .geometry(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                    .transparency(ShaderMaterial.Transparency.ALPHA)
                    .depth(com.mojang.blaze3d.platform.DepthTestFunction.LEQUAL_DEPTH_TEST, true)
                    .cull(false)
                    .sampler("PortalView")
                    .build();
        }

        return material;
    }

    private static void render(WorldRenderContext context) {
        // The nested portal render fires this same event. Drawing here would paint the near
        // dimension's portals into the far dimension's offscreen target, stacking both worlds
        // inside the aperture.
        if (PortalViewRenderer.isRenderingPortalView()) {
            return;
        }

        List<PortalAnchor> portals = PortalViewRenderer.visible();
        ManagedRenderTarget target = PortalViewRenderer.target();

        if (portals.isEmpty() || target == null) {
            return;
        }

        PortalAnchor seeThrough = PortalViewRenderer.renderedEntry();

        // The sampler must be bound to something valid even when there is no live view, so make
        // sure the target exists before drawing.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!target.isPrepared()) {
            target.prepare(mc.getFramebuffer().textureWidth, mc.getFramebuffer().textureHeight);
        }

        ShaderMaterial mat = material();
        if (!mat.shouldRender()) {
            return;
        }

        MinecraftClient client = mc;
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        boolean hasView = PortalViewRenderer.isValid();

        int stride = VertexFormats.POSITION_TEXTURE_COLOR.getVertexSize();

        try (BufferAllocator allocator = new BufferAllocator(4 * FACES * stride * portals.size())) {
            BufferBuilder builder = new BufferBuilder(
                    allocator,
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_TEXTURE_COLOR
            );

            for (PortalAnchor portal : portals) {
                // Only the portal that actually has a view rendered behind it samples the texture;
                // the rest fall back to the procedural aperture so they still read as doorways.
                float viewFlag = PortalViewRenderer.isSolidDebug()
                        ? 0.5F
                        : ((hasView && portal.equals(seeThrough)) ? 1.0F : 0.0F);
                emitPortal(builder, portal, camera, viewFlag);
            }

            try (BuiltBuffer built = builder.end()) {
                draw(client, built, target, FACES * portals.size());
            }
        } catch (Throwable e) {
            Overseer.LOGGER.error("Portal composite failed", e);
        }
    }

    /**
     * One doorway as a recess: the aperture at the back, four side faces running out to the wall.
     *
     * <p>Everything is camera-relative, matching the space world geometry is drawn in. Culling is
     * off for this material, so winding does not matter.
     */
    private static void emitPortal(BufferBuilder builder, PortalAnchor entry, Vec3d camera, float viewFlag) {
        Vec3d centre = entry.center().subtract(camera);
        Vec3d right = entry.right().multiply(entry.width() / 2.0 - INSET);
        Vec3d up = new Vec3d(0, entry.height() / 2.0 - INSET, 0);
        Vec3d out = entry.normal().multiply(DEPTH - INSET);

        // The view surface, on the anchor plane.
        Vec3d midBL = centre.subtract(right).subtract(up);
        Vec3d midBR = centre.add(right).subtract(up);
        Vec3d midTR = centre.add(right).add(up);
        Vec3d midTL = centre.subtract(right).add(up);

        aperture(builder, midBL, 0.0F, 1.0F, viewFlag);
        aperture(builder, midBR, 1.0F, 1.0F, viewFlag);
        aperture(builder, midTR, 1.0F, 0.0F, viewFlag);
        aperture(builder, midTL, 0.0F, 0.0F, viewFlag);

        // A recess out to each face of the block. Both are emitted every frame and the depth test
        // picks: the aperture is opaque and spans the whole opening, so the recess on the far side
        // lies entirely within its silhouette and is never visible.
        //
        // That is the point of drawing both. Choosing one by which side the camera is on made the
        // doorway visibly turn itself inside out at the moment you crossed its plane.
        recess(builder, midBL, midBR, midTR, midTL, out, viewFlag);
        recess(builder, midBL, midBR, midTR, midTL, out.negate(), viewFlag);
    }

    /** One side's worth of opening: four walls from the aperture out to a face of the block. */
    private static void recess(BufferBuilder builder, Vec3d midBL, Vec3d midBR, Vec3d midTR, Vec3d midTL,
                               Vec3d out, float viewFlag) {
        Vec3d lipBL = midBL.add(out);
        Vec3d lipBR = midBR.add(out);
        Vec3d lipTR = midTR.add(out);
        Vec3d lipTL = midTL.add(out);

        // Recess depth runs 0 at the lip to 1 at the aperture, and the shader shades along it,
        // which is what gives the doorway its thickness.
        wall(builder, lipBL, lipBR, midBR, midBL, viewFlag);
        wall(builder, lipTL, lipTR, midTR, midTL, viewFlag);
        wall(builder, lipBL, lipTL, midTL, midBL, viewFlag);
        wall(builder, lipBR, lipTR, midTR, midBR, viewFlag);
    }

    /** A side of the recess. First two corners sit at the lip, second two at the aperture. */
    private static void wall(BufferBuilder builder, Vec3d lipA, Vec3d lipB, Vec3d backB, Vec3d backA, float viewFlag) {
        vertex(builder, lipA, 0.0F, 0.0F, 0.0F, 1.0F, viewFlag);
        vertex(builder, lipB, 1.0F, 0.0F, 0.0F, 1.0F, viewFlag);
        vertex(builder, backB, 1.0F, 1.0F, 1.0F, 1.0F, viewFlag);
        vertex(builder, backA, 0.0F, 1.0F, 1.0F, 1.0F, viewFlag);
    }

    private static void aperture(BufferBuilder builder, Vec3d pos, float u, float v, float viewFlag) {
        vertex(builder, pos, u, v, 1.0F, 0.0F, viewFlag);
    }

    /**
     * Colour is a channel-packed mode selector rather than a tint, which keeps the whole doorway
     * in one draw call without a second uniform block.
     *
     * <ul>
     *   <li>red — depth into the recess, 0 at the lip and 1 at the aperture</li>
     *   <li>green — 1 on a side face, 0 on the aperture</li>
     *   <li>alpha — whether a live see-through view exists, so the shader can fall back to the
     *       procedural aperture</li>
     * </ul>
     */
    private static void vertex(BufferBuilder builder, Vec3d pos, float u, float v,
                               float recess, float isWall, float viewFlag) {
        builder.vertex((float) pos.x, (float) pos.y, (float) pos.z)
                .texture(u, v)
                .color(recess, isWall, 1.0F, viewFlag);
    }

    private static void draw(MinecraftClient client, BuiltBuffer built, ManagedRenderTarget target, int quadCount) {
        GpuBuffer vertexBuffer = RenderSystem.getDevice()
                .createBuffer(() -> "overseer portal quad", GpuBuffer.USAGE_VERTEX, built.getBuffer());

        Framebuffer main = client.getFramebuffer();

        RenderSystem.ShapeIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
        GpuBuffer indexBuffer = indices.getIndexBuffer(6 * quadCount);

        // The frame's view rotation, not RenderSystem's model-view. World geometry is drawn
        // camera-relative against this matrix; using anything else skews the quad across the
        // screen instead of placing it at the portal.
        Matrix4f modelView = PortalViewRenderer.scenePositionMatrix();
        if (modelView == null) {
            modelView = new Matrix4f();
        }

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                modelView,
                new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                new Vector3f(),
                new Matrix4f()
        );

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "overseer portal composite",
                main.getColorAttachmentView(), OptionalInt.empty(),
                main.getDepthAttachmentView(), OptionalDouble.empty()
        )) {
            pass.setPipeline(material().pipeline());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.bindTexture("PortalView", target.colorView(),
                    RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, indices.getIndexType());
            pass.drawIndexed(0, 0, 6 * quadCount, 1);
        } finally {
            vertexBuffer.close();
        }
    }
}
