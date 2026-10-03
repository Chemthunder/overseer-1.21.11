package net.damagejr05.overseer.core.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.nexusrealms.nebulon.api.render.client.ManagedRenderTarget;
import de.nexusrealms.nebulon.api.render.client.ShaderMaterial;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.Eviction;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
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
import net.minecraft.util.math.MathHelper;
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

    /**
     * The profile of the recess, from the wall face in to the view surface.
     *
     * <p>A straight-sided box has a problem beyond looking plain: seen from in front, its sides
     * are edge-on and cannot be seen at all, so the doorway reads as a flat picture with a border
     * until you move off to one side. A moulded reveal fixes both. It runs in three steps --
     *
     * <ol>
     *   <li>a short straight lip, square to the wall;</li>
     *   <li>a ledge, stepping in toward the middle and facing straight out at the viewer;</li>
     *   <li>a long splay, sloping in the rest of the way to the view surface.</li>
     * </ol>
     *
     * <p>The ledge and the splay both face outward, so they are visible head-on, and each catches
     * the light differently from the one before it.
     *
     * <p>The floor is left flat. Stepping the bottom in as well would raise a sill across the
     * threshold, and the player walks through there.
     */
    private static final double LIP_DEPTH = 0.12;
    private static final double LEDGE_INSET = 0.07;
    private static final double SPLAY_INSET = 0.19;

    /** Faces per portal: the aperture, and three bands of four faces for each of two recesses. */
    private static final int FACES = 25;

    /** Marks the full-screen fill in the vertex colour's alpha; see the shader. */
    private static final float FULLSCREEN_FLAG = 0.8F;

    /** Doorways that have been drawn with no live view behind them. */
    private static final java.util.Set<PortalAnchor> SEEN_WITHOUT_VIEW = new java.util.HashSet<>();

    /** How long the live view takes to fade in over the procedural aperture, in seconds. */
    private static final float FADE_SECONDS = 0.35F;

    /**
     * Fade state for the one doorway that has a live view.
     *
     * <p>The view does not exist the instant a doorway comes into range: the far side has to be
     * streamed and meshed first. Cutting from the procedural aperture to the real thing the frame
     * it turns up is a visible pop, so it is cross-faded instead.
     */
    private static @Nullable PortalAnchor fadeAnchor = null;
    private static float fade = 0.0F;
    private static long lastFrameNanos = 0L;

    /** Seconds since the previous frame, for anything that eases over real time. */
    private static float frameDelta = 0.0F;

    /**
     * How sealed the doorways look, 0 to 1, easing toward whatever the access setting is.
     *
     * <p>Shown to everyone, the Overseer included. They can still walk through, but they are
     * also the one who needs to know at a glance which way the setting currently stands.
     */
    private static float sealed = 0.0F;

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

        SEEN_WITHOUT_VIEW.retainAll(portals);
        advanceFade(hasView ? seeThrough : null);

        float sealedTarget = DoorwayComponent.get(client.world).isAdmitting() ? 0.0F : 1.0F;
        sealed += (sealedTarget - sealed) * Math.min(1.0F, frameDelta * 5.0F);

        // A temporary doorway grows out of a point when it opens. The permanent one never does:
        // both share one synced counter, and it is only ever restarted for the temporary pair.
        PortalPair dynamic = DoorwayComponent.get(client.world).getDynamic();
        float opening = DoorwayComponent.get(client.world)
                .openProgress(client.getRenderTickCounter().getTickProgress(false));

        int stride = VertexFormats.POSITION_TEXTURE_COLOR.getVertexSize();

        boolean through = PortalViewRenderer.isThrough();
        DoorwayComponent doorways = DoorwayComponent.get(client.world);

        int quads = through ? 1 : 0;
        for (PortalAnchor portal : portals) {
            quads += doorways.isEvictionAnchor(portal) ? 1 : FACES;
        }

        // The eviction's portal opens inside the eye's pupil, and is whatever size that is.
        int evicting = doorways.evictionTicks();
        float skyScale = evicting == 0 ? 0.0F
                : Eviction.portalScale(evicting + client.getRenderTickCounter().getTickProgress(false));

        try (BufferAllocator allocator = new BufferAllocator(4 * quads * stride)) {
            BufferBuilder builder = new BufferBuilder(
                    allocator,
                    VertexFormat.DrawMode.QUADS,
                    VertexFormats.POSITION_TEXTURE_COLOR
            );

            for (PortalAnchor portal : portals) {
                // Only the portal that actually has a view rendered behind it samples the texture;
                // the rest fall back to the procedural aperture so they still read as doorways.
                float view = portal.equals(fadeAnchor) ? MathHelper.clamp(fade, 0.0F, 1.0F) : 0.0F;
                // Smoothstep, so it eases in and out rather than starting and stopping dead.
                view = view * view * (3.0F - 2.0F * view);

                boolean isDynamic = dynamic != null
                        && (portal.equals(dynamic.outer()) || portal.equals(dynamic.inner()));

                // A doorway only counts as having gone without a view while it was somewhere it
                // could be seen going without one.
                if (view <= 0.0F && !PortalViewRenderer.culledOffScreen()) {
                    SEEN_WITHOUT_VIEW.add(portal);
                }

                if (doorways.isEvictionAnchor(portal)) {
                    emitRound(builder, portal, camera, view, skyScale);
                    continue;
                }

                emitPortal(builder, portal, camera, view, isDynamic ? easeOutBack(opening) : 1.0F);
            }

            // Last, and nearer than anything else, so it covers the doorway it is passing through.
            if (through) {
                emitFullscreen(builder, client);
            }

            try (BuiltBuffer built = builder.end()) {
                draw(client, built, target, quads);
            }
        } catch (Throwable e) {
            Overseer.LOGGER.error("Portal composite failed", e);
        }
    }

    /** Move the live view's fade towards wherever it should be, by real elapsed time. */
    private static void advanceFade(@Nullable PortalAnchor live) {
        long now = System.nanoTime();
        // Clamped: the first frame, and any frame after a long hitch, must not jump the fade.
        float delta = lastFrameNanos == 0L ? 0.0F : Math.min((now - lastFrameNanos) / 1.0e9F, 0.1F);
        lastFrameNanos = now;
        frameDelta = delta;

        if (live != null) {
            if (!live.equals(fadeAnchor)) {
                fadeAnchor = live;

                // The fade exists to hide the moment a view turns up late. A doorway whose view
                // is there the first time it is drawn has nothing to hide -- and that is every
                // doorway just stepped out of. Fading that one in showed the procedural aperture
                // for a third of a second at the end of each crossing: a flash.
                fade = SEEN_WITHOUT_VIEW.contains(live) ? 0.0F : 1.0F;
            }

            SEEN_WITHOUT_VIEW.remove(live);

            fade = Math.min(1.0F, fade + delta / FADE_SECONDS);
        } else if (!PortalViewRenderer.culledOffScreen()) {
            // Fading out samples the last frame that was rendered, which is exactly the frozen
            // image a view that has just stopped updating should dissolve from.
            fade = Math.max(0.0F, fade - delta / FADE_SECONDS);
        }
    }

    /** Overshoots a little past full size and settles back, so the doorway snaps open. */
    private static float easeOutBack(float progress) {
        if (progress >= 1.0F) {
            return 1.0F;
        }

        float c = 1.70158F;
        float x = progress - 1.0F;
        return Math.max(0.0F, 1.0F + (c + 1.0F) * x * x * x + c * x * x);
    }

    /**
     * One doorway as a recess: the aperture at the back, four side faces running out to the wall.
     *
     * <p>Everything is camera-relative, matching the space world geometry is drawn in. Culling is
     * off for this material, so winding does not matter.
     */
    private static void emitPortal(BufferBuilder builder, PortalAnchor entry, Vec3d camera, float viewFlag, float scale) {
        // Never quite zero: a degenerate quad is harmless, but its UVs feed a division.
        scale = Math.max(scale, 0.001F);

        double halfWidth = (entry.width() / 2.0 - INSET) * scale;
        double halfHeight = (entry.height() / 2.0 - INSET) * scale;

        Vec3d centre = entry.center().subtract(camera);
        Vec3d right = entry.right();
        Vec3d out = entry.normal();

        // The view surface, on the anchor plane, at the narrow end of the splay.
        Vec3d[] view = ring(centre, right, halfWidth, halfHeight, SPLAY_INSET * scale, Vec3d.ZERO);

        // UVs in blocks from the surface's own centre. The shader measures its rim and edge
        // shading in blocks, so they keep the same thickness whatever size the opening is, and it
        // recovers the half-size from the corners' absolute values.
        float u = (float) (halfWidth - SPLAY_INSET * scale);
        float v = (float) (halfHeight - SPLAY_INSET * scale / 2.0);

        aperture(builder, view[0], -u, -v, viewFlag);
        aperture(builder, view[1], u, -v, viewFlag);
        aperture(builder, view[2], u, v, viewFlag);
        aperture(builder, view[3], -u, v, viewFlag);

        // A recess out to each face of the block. Both are emitted every frame and the depth test
        // picks: the aperture is opaque and spans the whole opening, so the recess on the far side
        // lies entirely within its silhouette and is never visible.
        //
        // That is the point of drawing both. Choosing one by which side the camera is on made the
        // doorway visibly turn itself inside out at the moment you crossed its plane.
        recess(builder, centre, right, out, halfWidth, halfHeight, scale, view, viewFlag);
        recess(builder, centre, right, out.negate(), halfWidth, halfHeight, scale, view, viewFlag);
    }

    /**
     * The portal an eviction opens: one round surface hanging in the air, with no frame.
     *
     * <p>It is not set into anything, so there is no wall for a recess to be cut from, and a
     * square hole in the sky reads as a billboard. The quad is still square; the shader cuts the
     * circle out of it and puts the glow around the edge.
     */
    private static void emitRound(BufferBuilder builder, PortalAnchor entry, Vec3d camera, float viewFlag, float scale) {
        scale = Math.max(scale, 0.001F);

        float halfWidth = entry.width() / 2.0F * scale;
        float halfHeight = entry.height() / 2.0F * scale;

        Vec3d centre = entry.center().subtract(camera);
        Vec3d right = entry.right().multiply(halfWidth);
        Vec3d up = new Vec3d(0, halfHeight, 0);

        round(builder, centre.subtract(right).subtract(up), -halfWidth, -halfHeight, viewFlag);
        round(builder, centre.add(right).subtract(up), halfWidth, -halfHeight, viewFlag);
        round(builder, centre.add(right).add(up), halfWidth, halfHeight, viewFlag);
        round(builder, centre.subtract(right).add(up), -halfWidth, halfHeight, viewFlag);
    }

    /** As {@link #aperture}, with red at one half: the shader's cue to cut a circle. */
    private static void round(BufferBuilder builder, Vec3d pos, float u, float v, float viewFlag) {
        vertex(builder, pos, u, v, 0.5F, 0.0F, viewFlag);
    }

    /**
     * Four corners of one cross-section of the recess: bottom-left, bottom-right, top-right,
     * top-left. {@code inset} narrows the sides and lowers the top; the bottom never moves.
     */
    private static Vec3d[] ring(Vec3d centre, Vec3d right, double halfWidth, double halfHeight,
                                double inset, Vec3d offset) {
        Vec3d side = right.multiply(halfWidth - inset);
        Vec3d bottom = new Vec3d(0, -halfHeight, 0);
        Vec3d top = new Vec3d(0, halfHeight - inset, 0);
        Vec3d middle = centre.add(offset);

        return new Vec3d[]{
                middle.subtract(side).add(bottom),
                middle.add(side).add(bottom),
                middle.add(side).add(top),
                middle.subtract(side).add(top)
        };
    }

    /** One side's worth of opening: lip, ledge and splay, from a face of the block in to the view. */
    private static void recess(BufferBuilder builder, Vec3d centre, Vec3d right, Vec3d out,
                               double halfWidth, double halfHeight, float scale, Vec3d[] view, float viewFlag) {
        double depth = DEPTH - INSET;
        float lipEnd = (float) (LIP_DEPTH / depth);

        Vec3d[] lip = ring(centre, right, halfWidth, halfHeight, 0.0, out.multiply(depth));
        Vec3d[] lipBack = ring(centre, right, halfWidth, halfHeight, 0.0, out.multiply(depth - LIP_DEPTH));
        Vec3d[] ledge = ring(centre, right, halfWidth, halfHeight, LEDGE_INSET * scale, out.multiply(depth - LIP_DEPTH));

        // Recess depth runs 0 at the lip to 1 at the aperture, and the shader shades along it.
        // The second number is how much brighter a band is for facing the viewer: the lip is
        // square to the wall, the ledge faces straight out, and the splay is somewhere between.
        band(builder, lip, lipBack, 0.0F, lipEnd, 0.0F, viewFlag);
        band(builder, lipBack, ledge, lipEnd, lipEnd, 0.30F, viewFlag);
        band(builder, ledge, view, lipEnd, 1.0F, 0.12F, viewFlag);
    }

    /**
     * The four faces joining one cross-section to the next.
     *
     * <p>Each gets its own brightness, on the scale Minecraft uses for the faces of a block. The
     * floor of the recess faces up and is the brightest; its ceiling faces down and is the
     * darkest; the two sides sit in between and differ from each other. Four faces lit alike read
     * as a flat border -- it is the difference between them that reads as depth.
     */
    private static void band(BufferBuilder builder, Vec3d[] outer, Vec3d[] inner,
                             float outerDepth, float innerDepth, float facing, float viewFlag) {
        wall(builder, outer[0], outer[1], inner[1], inner[0], outerDepth, innerDepth, FLOOR_LIGHT, viewFlag);
        wall(builder, outer[3], outer[2], inner[2], inner[3], outerDepth, innerDepth, CEILING_LIGHT + facing, viewFlag);
        wall(builder, outer[0], outer[3], inner[3], inner[0], outerDepth, innerDepth, SIDE_LIGHT_A + facing, viewFlag);
        wall(builder, outer[1], outer[2], inner[2], inner[1], outerDepth, innerDepth, SIDE_LIGHT_B + facing, viewFlag);
    }

    /**
     * Covers the screen with the far view while the camera is passing through a doorway.
     *
     * <p>A quad just beyond the near plane, squarely in front of the camera and far larger than
     * the view. It samples by screen position like the aperture does, so its exact size and
     * placement do not matter, only that it covers everything and sits in front of everything.
     */
    private static void emitFullscreen(BufferBuilder builder, MinecraftClient client) {
        var camera = client.gameRenderer.getCamera();

        Vec3d forward = new Vec3d(camera.getHorizontalPlane());
        Vec3d up = new Vec3d(camera.getVerticalPlane());
        Vec3d left = new Vec3d(camera.getDiagonalPlane());
        Vec3d centre = forward.multiply(0.06);

        fullscreen(builder, centre.add(left).subtract(up));
        fullscreen(builder, centre.subtract(left).subtract(up));
        fullscreen(builder, centre.subtract(left).add(up));
        fullscreen(builder, centre.add(left).add(up));
    }

    private static void fullscreen(BufferBuilder builder, Vec3d pos) {
        builder.vertex((float) pos.x, (float) pos.y, (float) pos.z)
                .texture(0.0F, 0.0F)
                .color(1.0F, 0.0F, 1.0F, FULLSCREEN_FLAG);
    }

    private static final float FLOOR_LIGHT = 1.0F;
    private static final float CEILING_LIGHT = 0.5F;
    private static final float SIDE_LIGHT_A = 0.8F;
    private static final float SIDE_LIGHT_B = 0.62F;

    /**
     * A side of the recess. First two corners sit at the lip, second two at the aperture.
     *
     * <p>u runs along the face in blocks, measured from its middle, and v is the face's light.
     * Both reach the shader as constants across the face; see the vertex shader for how.
     */
    private static void wall(BufferBuilder builder, Vec3d lipA, Vec3d lipB, Vec3d backB, Vec3d backA,
                             float lipDepth, float backDepth, float light, float viewFlag) {
        // The outer edge's length for all four corners, though a splayed face is shorter at the
        // back: the shader needs the same value at every corner to read it as a constant.
        float half = (float) (lipA.distanceTo(lipB) / 2.0);
        light = Math.min(light, 1.0F);

        vertex(builder, lipA, -half, light, lipDepth, 1.0F, viewFlag);
        vertex(builder, lipB, half, light, lipDepth, 1.0F, viewFlag);
        vertex(builder, backB, half, light, backDepth, 1.0F, viewFlag);
        vertex(builder, backA, -half, light, backDepth, 1.0F, viewFlag);
    }

    private static void aperture(BufferBuilder builder, Vec3d pos, float u, float v, float viewFlag) {
        vertex(builder, pos, u, v, 1.0F, 0.0F, viewFlag);
    }

    /**
     * Colour is a channel-packed mode selector rather than a tint, which keeps the whole doorway
     * in one draw call without a second uniform block.
     *
     * <ul>
     *   <li>red — on a side face, depth into the recess, 0 at the lip and 1 at the view surface;
     *       on a view surface, 1 for a framed doorway and one half for the frameless round one</li>
     *   <li>green — 1 on a side face, 0 on the aperture</li>
     *   <li>blue — how far the live see-through view has faded in over the procedural aperture,
     *       0 when there is none</li>
     *   <li>alpha — 1 normally, one half for the flat diagnostic fill, and
     *       {@link #FULLSCREEN_FLAG} for the full-screen view shown while passing through</li>
     * </ul>
     */
    private static void vertex(BufferBuilder builder, Vec3d pos, float u, float v,
                               float recess, float isWall, float viewFlag) {
        builder.vertex((float) pos.x, (float) pos.y, (float) pos.z)
                .texture(u, v)
                .color(recess, isWall, viewFlag, PortalViewRenderer.isSolidDebug() ? 0.5F : 1.0F);
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

        // The colour modulator is unused by this shader as a colour, so it carries the seal.
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                modelView,
                new Vector4f(sealed, 0.0F, 0.0F, 1.0F),
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
            // Linear: the rippled edge samples between texels, and nearest turns that into crawl.
            pass.bindTexture("PortalView", target.colorView(),
                    RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            pass.setVertexBuffer(0, vertexBuffer);
            pass.setIndexBuffer(indexBuffer, indices.getIndexType());
            pass.drawIndexed(0, 0, 6 * quadCount, 1);
        } finally {
            vertexBuffer.close();
        }
    }
}
