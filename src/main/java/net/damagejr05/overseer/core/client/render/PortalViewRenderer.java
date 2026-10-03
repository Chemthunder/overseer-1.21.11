package net.damagejr05.overseer.core.client.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import de.nexusrealms.nebulon.api.render.client.ManagedRenderTarget;
import de.nexusrealms.nebulon.api.render.client.NebulonRenderTargets;
import de.nexusrealms.nebulon.api.render.client.RenderTargetDescriptor;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.OverseerClient;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.client.SecondaryWorldManager;
import net.damagejr05.overseer.core.portal.PortalAnchor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlobalSettings;
import net.minecraft.client.option.TextureFilteringMode;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RawProjectionMatrix;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectPool;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributeInterpolator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Renders what lies beyond a portal into an offscreen target.
 *
 * <p>Runs once per frame, before the main world render, so the world renderer is never re-entered
 * while it is already running. The result is composited onto the portal quad later in the frame by
 * {@link PortalViewCompositor}.
 *
 * <p>The far world is rendered full-screen from a virtual camera that sits where the real camera
 * would be if it had already stepped through. Because the virtual view uses the same projection
 * and screen alignment as the real one, the portal quad can simply sample the offscreen colour at
 * its own screen position and the two views line up exactly.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class PortalViewRenderer {
    public static final net.minecraft.util.Identifier TARGET_ID = Overseer.id("portal_view");

    private static @Nullable ManagedRenderTarget target;

    /**
     * Nested world rendering is on by default.
     *
     * <p>With it off the portal falls back to the procedural aperture in the shader. It can be
     * switched off in game with {@code /overseerclient portalview off}, and it switches itself
     * off after {@link #MAX_CONSECUTIVE_FAILURES} failed frames in a row rather than failing
     * sixty times a second for the rest of the session.
     *
     * <p>Neither of those helps if a driver takes the process down on the very first frame, since
     * there is then no session to type a command into. Launching with
     * {@code -Doverseer.portalView=false} starts with it off.
     */
    private static boolean enabled = Boolean.parseBoolean(System.getProperty("overseer.portalView", "true"));

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static int consecutiveFailures = 0;

    /** True when this frame produced a usable portal view texture. */
    private static boolean valid = false;

    /** The portal the live view belongs to, if one was rendered. */
    private static @Nullable PortalAnchor renderedEntry = null;

    /**
     * Every portal standing in the player's dimension, drawn whichever side they are viewed from.
     * A doorway is a physical object, so it should not vanish when walked around.
     */
    private static final List<PortalAnchor> visible = new ArrayList<>();

    /**
     * True when a doorway was in range this frame but skipped only for being off screen.
     *
     * <p>The compositor fades the live view in over the procedural one. A doorway that has merely
     * left the screen has not lost its view in any sense the player can see, and without this the
     * fade would run out while their back was turned and replay every time they looked round.
     */
    private static boolean culledOffScreen = false;

    public static boolean culledOffScreen() {
        return culledOffScreen;
    }

    /**
     * The doorway the camera has passed through but the server has not yet moved the player for.
     *
     * <p>The crossing is decided on the server, once a tick, and the answer then has to travel
     * back. For those few frames the camera is already beyond the opening: the surface it was
     * looking into is behind it, and what is in front is whatever stands behind the doorway in
     * the world it is about to leave -- usually the inside of a wall. That is a flash of the wrong
     * place in the middle of every crossing.
     *
     * <p>While this is set, the far side is rendered without the portal-plane clip, which would
     * now cut away everything in front of the camera, and the compositor fills the whole screen
     * with it. The picture is then the same one the player gets a moment later when the
     * dimension change lands, so the handover has nothing to show.
     */
    private static @Nullable PortalAnchor through = null;
    private static boolean throughFromFront = false;
    private static long throughSince = 0L;

    /** How long to hold the full-screen view waiting for the server, in milliseconds. */
    private static final long THROUGH_TIMEOUT_MS = 750L;

    /** Signed distance of the camera from each doorway last frame, to see it change sign. */
    private static final Map<PortalAnchor, Double> LAST_DISTANCE = new HashMap<>();
    private static @Nullable Vec3d lastCameraPos = null;
    private static @Nullable ClientWorld lastWorld = null;

    /** True when this frame's view should cover the screen rather than fill an aperture. */
    public static boolean isThrough() {
        return through != null && valid && through.equals(renderedEntry);
    }

    private static void trackCrossing(MinecraftClient client, ClientWorld world, Vec3d cameraPos) {
        // A new world means the crossing happened. Nothing measured in the old one carries over.
        if (world != lastWorld) {
            lastWorld = world;
            lastCameraPos = null;
            through = null;
            LAST_DISTANCE.clear();
        }

        // Only someone the server will actually move, and only in first person: in third person
        // the camera trails the body and crosses the plane at a different moment from it.
        boolean willCross = client.options.getPerspective().isFirstPerson()
                && client.player != null
                && DoorwayComponent.get(world).mayEnter(client.player);

        LAST_DISTANCE.keySet().retainAll(visible);

        for (PortalAnchor anchor : visible) {
            double distance = anchor.signedDistance(cameraPos);
            Double before = LAST_DISTANCE.put(anchor, distance);

            if (before == null || lastCameraPos == null || through != null || !willCross) {
                continue;
            }

            if ((before > 0.0) == (distance > 0.0) || before == distance) {
                continue;
            }

            // Through the opening, not round the side of it.
            Vec3d hit = lastCameraPos.lerp(cameraPos, before / (before - distance));
            if (anchor.withinOpening(hit) && lastCameraPos.squaredDistanceTo(cameraPos) < 16.0) {
                through = anchor;
                throughFromFront = before > 0.0;
                throughSince = System.currentTimeMillis();
            }
        }

        if (through != null) {
            double distance = through.signedDistance(cameraPos);

            // Stepped back out, wandered off, or the server never agreed there was a crossing.
            if (!visible.contains(through)
                    || (distance > 0.0) == throughFromFront
                    || Math.abs(distance) > 2.0
                    || System.currentTimeMillis() - throughSince > THROUGH_TIMEOUT_MS) {
                through = null;
            }
        }

        lastCameraPos = cameraPos;
    }

    /** Guards against recursion: a portal seen through a portal renders flat. */
    private static boolean rendering = false;

    /**
     * True while the nested world render is running.
     *
     * <p>The nested render is a real {@code WorldRenderer.render}, so Fabric's world render events
     * fire inside it as well. Anything that draws into the world has to sit this out, or it lands
     * in the portal's offscreen target instead of the scene — which composites the near dimension
     * on top of the far one inside the aperture.
     */
    public static boolean isRenderingPortalView() {
        return rendering;
    }

    /**
     * The nested render's own allocator. The frame graph acquires transient framebuffers from this
     * pool; sharing the game renderer's would have two frame graphs in one frame drawing from the
     * same pool, which is the last piece of per-frame render state that was still shared.
     */
    private static final ObjectPool POOL = new ObjectPool(3);

    /**
     * GPU-side storage for the oblique projection.
     *
     * <p>WorldRenderer never sets the RenderSystem projection itself -- GameRenderer does that just
     * before calling it. The nested render therefore has to upload its own clipped projection and
     * put the original back afterwards, or the far world is drawn with the main camera's matrix.
     */
    private static @Nullable RawProjectionMatrix projectionUpload;

    /**
     * The portal render's own fog.
     *
     * <p>{@link FogRenderer}'s UBO is a ring buffer rotated once per frame, and
     * {@code getFogBuffer(WORLD)} hands out the same slice for the whole of it. Calling the game
     * renderer's {@code applyFog} again here would therefore overwrite the fog the main render is
     * about to draw with. A second instance gives the far world its own slice.
     */
    private static @Nullable FogRenderer fog;

    /**
     * The portal render's own lightmap.
     *
     * <p>Every lit surface takes its brightness from one 16x16 texture owned by the game renderer,
     * built once a frame from the dimension the player is standing in: its ambient light, its sky
     * light factor, its sky colour. Rendering another dimension against that texture lights it as
     * though it were this one. Seen from the memory dimension, which has no skylight at all, the
     * overworld came out dim and flat at noon and midnight alike.
     *
     * <p>A second instance rather than rebuilding the shared one twice a frame, for the same
     * reason the fog has its own: the main render's copy is left completely alone.
     */
    private static @Nullable LightmapTextureManager lightmap;

    /** The client tick and dimension {@link #lightmap} was last rebuilt for. */
    private static int lightmapAge = -1;
    private static @Nullable RegistryKey<World> lightmapDimension = null;

    /** The client tick each dimension's environment was last advanced on. */
    private static final Map<RegistryKey<World>, Integer> ENVIRONMENT_AGES = new HashMap<>();

    /**
     * A separate environment interpolator per remote dimension.
     *
     * <p>Sky, fog and cloud colour are all read from {@code Camera}'s interpolator, which holds the
     * dimension the player is standing in -- which is why the memory dimension was showing the
     * overworld's sky. It cannot simply be re-pointed twice a frame: each entry keeps {@code last}
     * and {@code current} and lerps between them by tick progress, so alternating worlds through
     * one interpolator makes both views flicker between the two dimensions' colours. Each
     * dimension gets its own, swapped onto the camera for the duration of the nested render.
     */
    private static final Map<RegistryKey<World>, EnvironmentAttributeInterpolator> ENVIRONMENTS = new HashMap<>();

    /** Drop cached per-dimension state when a secondary world goes away. */
    public static void forget(RegistryKey<World> dimension) {
        ENVIRONMENTS.remove(dimension);
        ENVIRONMENT_AGES.remove(dimension);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        consecutiveFailures = 0;
    }

    /** Draws the aperture as flat magenta so its real shape and position can be seen. */
    private static boolean solidDebug = false;

    public static boolean isSolidDebug() {
        return solidDebug;
    }

    public static void setSolidDebug(boolean value) {
        solidDebug = value;
    }

    public static void init() {
        target = NebulonRenderTargets.register(
                TARGET_ID,
                RenderTargetDescriptor.fullResolution("overseer portal view", true)
        );
    }

    public static boolean isValid() {
        return valid;
    }

    public static @Nullable PortalAnchor renderedEntry() {
        return renderedEntry;
    }

    /** Every portal to draw this frame. */
    public static List<PortalAnchor> visible() {
        return visible;
    }

    /** Last virtual camera used, for diagnostics. */
    private static @Nullable Vec3d lastVirtualPos = null;
    private static float lastVirtualYaw = 0.0F;
    private static float lastVirtualPitch = 0.0F;
    private static @Nullable PortalAnchor lastExit = null;

    /** Camera position as the renderer actually saw it, read at the call site. */
    private static @Nullable Vec3d lastRenderedCameraPos = null;

    /** The frame's view rotation, needed by the compositor to place the portal quad. */
    private static @Nullable Matrix4f scenePositionMatrix = null;

    public static @Nullable Matrix4f scenePositionMatrix() {
        return scenePositionMatrix;
    }

    /** The virtual camera's view rotation, while the nested render is running. */
    private static @Nullable Matrix4f nestedPositionMatrix = null;

    /**
     * The view rotation of whichever render is in progress: the portal's while its nested render
     * runs, the scene's otherwise. For things that draw into both.
     */
    public static @Nullable Matrix4f activePositionMatrix() {
        return rendering && nestedPositionMatrix != null ? nestedPositionMatrix : scenePositionMatrix;
    }

    public static String describeLastRender() {
        if (lastExit == null || lastVirtualPos == null) {
            return "no portal view rendered";
        }

        return String.format(
                "exit %s @ %.1f %.1f %.1f facing %s | virtual cam %.1f %.1f %.1f yaw %.0f pitch %.0f",
                lastExit.dimension().getValue().getPath(),
                lastExit.center().x, lastExit.center().y, lastExit.center().z,
                lastExit.facing().asString(),
                lastVirtualPos.x, lastVirtualPos.y, lastVirtualPos.z, lastVirtualYaw, lastVirtualPitch
        ) + (lastRenderedCameraPos == null ? "" : String.format(
                " | renderer saw %.1f %.1f %.1f",
                lastRenderedCameraPos.x, lastRenderedCameraPos.y, lastRenderedCameraPos.z
        ));
    }

    public static @Nullable ManagedRenderTarget target() {
        return target;
    }

    /**
     * Render the far side of whichever portal the camera is currently looking at.
     */
    public static void renderPortalViews(RenderTickCounter tickCounter,
                                         Matrix4f framePositionMatrix,
                                         Matrix4f frameBasicProjection,
                                         Matrix4f frameProjection,
                                         GpuBufferSlice frameFog,
                                         Vector4f frameFogColor) {
        // The frame's view rotation. World geometry is camera-relative and vanilla supplies this
        // as the model-view; RenderSystem.getModelViewMatrix() is not it, and drawing the portal
        // quad with that instead projects it without any view rotation -- which is why the
        // aperture came out as a huge skewed polygon rather than a doorway.
        scenePositionMatrix = framePositionMatrix;
        valid = false;
        renderedEntry = null;
        culledOffScreen = false;
        visible.clear();

        if (rendering || target == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null || client.player == null) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(world);

        // Everything in this dimension gets drawn, from any angle.
        visible.addAll(doorways.anchorsIn(world.getRegistryKey()));

        if (!enabled) {
            return;
        }

        trackCrossing(client, world, client.gameRenderer.getCamera().getCameraPos());

        // The doorway being walked through, if there is one; otherwise the nearest on screen.
        PortalTransform transform = null;

        if (through != null) {
            for (var pair : doorways.pairs()) {
                PortalTransform candidate = pair.from(world.getRegistryKey());
                if (candidate != null && candidate.from().equals(through)) {
                    transform = candidate;
                }
            }
        }

        boolean passingThrough = transform != null;

        if (transform == null) {
            transform = selectVisible(client, doorways, framePositionMatrix, frameBasicProjection);
        }

        if (transform == null) {
            return;
        }

        // get, not getOrCreate. The server only streams the far side to players close to a
        // doorway, and until it has there is nothing to show but bare sky -- the procedural
        // aperture reads far better than that, and conjuring an empty world here would only have
        // it dropped again as idle a few seconds later.
        ClientWorld farWorld = SecondaryWorldManager.get(transform.to().dimension());
        WorldRenderer farRenderer = SecondaryWorldManager.renderer(transform.to().dimension());

        if (farWorld == null || farRenderer == null || farWorld == world
                || !SecondaryWorldManager.hasTerrain(transform.to().dimension())) {
            return;
        }

        // Vanilla advances its own pool once per frame; ours has to be advanced too or the
        // transient framebuffers it hands out are never recycled.
        POOL.decrementLifespan();

        rendering = true;
        try {
            // False means it declined to draw this frame, which is not a failure -- but the target
            // then still holds whatever was in it before, so it must not be sampled.
            if (renderInto(client, tickCounter, transform, farWorld, farRenderer,
                    frameBasicProjection, frameProjection, frameFog, frameFogColor, !passingThrough)) {
                valid = true;
                renderedEntry = transform.from();
            }

            consecutiveFailures = 0;
        } catch (Throwable e) {
            Overseer.LOGGER.error("Portal view render failed", e);
            valid = false;
            onFailure(client);
        } finally {
            rendering = false;
        }
    }

    /**
     * One bad frame is survivable and is simply skipped. The same failure every frame is not: it
     * costs a full world render's worth of work and a stack trace in the log sixty times a
     * second, so after a few in a row the live view is switched off and the player is told why
     * their doorways have gone back to the procedural aperture.
     */
    private static void onFailure(MinecraftClient client) {
        if (++consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            return;
        }

        enabled = false;
        consecutiveFailures = 0;

        Overseer.LOGGER.error("Portal see-through disabled after {} consecutive failed frames", MAX_CONSECUTIVE_FAILURES);

        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("Portal see-through hit repeated errors and was turned off. "
                            + "Re-enable with /overseerclient portalview on").formatted(Formatting.RED),
                    false
            );
        }
    }

    /**
     * Pick the portal worth rendering: one in this dimension, on screen, and close enough to
     * matter. Only one is rendered per frame — a second full world render per portal is not worth
     * the cost for a pair that is rarely both on screen.
     */
    private static @Nullable PortalTransform selectVisible(MinecraftClient client, DoorwayComponent doorways,
                                                           Matrix4f view, Matrix4f projection) {
        Vec3d cameraPos = client.gameRenderer.getCamera().getCameraPos();
        var dimension = client.world == null ? null : client.world.getRegistryKey();

        PortalTransform best = null;
        double bestDistance = Double.MAX_VALUE;

        for (var pair : doorways.pairs()) {
            PortalTransform candidate = dimension == null ? null : pair.from(dimension);
            if (candidate == null) {
                continue;
            }

            PortalAnchor entry = candidate.from();

            // Both faces are see-through. The transform is a rigid map, so it is just as valid
            // from behind -- looking in through the back face puts the virtual camera on the
            // exit's approach side rather than its emergence side, and ObliqueProjection flips
            // its clip plane to match. A doorway you can only see through from one side reads as
            // a painted-on decal the moment you walk around it.
            double distance = cameraPos.squaredDistanceTo(entry.center());

            // The portal an eviction opens hangs far off in the sky and is the size of a house.
            // It is also the one the server is streaming, so when it can be seen it is the one
            // to look through, whatever else is nearer.
            if (doorways.isEvictionAnchor(entry)) {
                if (distance < 256 * 256
                        && isOnScreen(entry, cameraPos, view, projection)
                        && SecondaryWorldManager.hasTerrainAt(candidate.to().dimension(), candidate.to().center())) {
                    return candidate;
                }

                continue;
            }

            if (distance >= 64 * 64) {
                continue;
            }

            // The server streams the far side of one doorway at a time. A doorway whose far side
            // is not here keeps its procedural aperture rather than showing a view of nothing.
            if (!SecondaryWorldManager.hasTerrainAt(candidate.to().dimension(), candidate.to().center())) {
                continue;
            }

            if (!isOnScreen(entry, cameraPos, view, projection)) {
                culledOffScreen = true;
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    /**
     * Whether any part of the opening can land on screen.
     *
     * <p>The live view costs a second full world render, and without this it was paid every frame
     * for any doorway within range -- including one directly behind the player. Standing with
     * your back to a doorway halved the frame rate for a picture nobody could see.
     *
     * <p>Deliberately conservative. The opening is only rejected when all four corners are off
     * the same side of the screen, or all four are behind the camera; anything straddling the
     * camera plane counts as visible, since a corner behind the camera has no meaningful screen
     * position to test.
     */
    private static boolean isOnScreen(PortalAnchor entry, Vec3d cameraPos, Matrix4f view, Matrix4f projection) {
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);

        Vec3d centre = entry.center().subtract(cameraPos);
        Vec3d right = entry.right().multiply(entry.width() / 2.0);
        double up = entry.height() / 2.0;

        int behind = 0, left = 0, rightOf = 0, below = 0, above = 0;
        Vector4f clip = new Vector4f();

        for (int corner = 0; corner < 4; corner++) {
            Vec3d point = centre
                    .add(right.multiply((corner & 1) == 0 ? -1.0 : 1.0))
                    .add(0, (corner & 2) == 0 ? -up : up, 0);

            clip.set((float) point.x, (float) point.y, (float) point.z, 1.0F);
            viewProjection.transform(clip);

            if (!(clip.w > 0.0F)) {
                behind++;
                continue;
            }

            if (clip.x < -clip.w) left++;
            if (clip.x > clip.w) rightOf++;
            if (clip.y < -clip.w) below++;
            if (clip.y > clip.w) above++;
        }

        return behind != 4 && left != 4 && rightOf != 4 && below != 4 && above != 4;
    }

    private static boolean isFinite(Vec3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** @return whether the offscreen target now holds this frame's view */
    private static boolean renderInto(MinecraftClient client, RenderTickCounter tickCounter,
                                   PortalTransform transform, ClientWorld farWorld, WorldRenderer farRenderer,
                                   Matrix4f frameBasicProjection, Matrix4f frameProjection,
                                   GpuBufferSlice frameFog, Vector4f frameFogColor, boolean clipToPortal) {
        Camera realCamera = client.gameRenderer.getCamera();
        Vec3d realPos = realCamera.getCameraPos();

        Vec3d virtualPos = transform.applyPoint(realPos);
        float virtualYaw = transform.applyYaw(realCamera.getYaw());

        lastVirtualPos = virtualPos;
        lastVirtualYaw = virtualYaw;
        lastVirtualPitch = realCamera.getPitch();
        lastExit = transform.to();

        // A non-finite camera position poisons the frustum, and the frustum's containment loop
        // then never terminates -- the client hangs rather than throwing. Refuse to render.
        if (!isFinite(virtualPos) || !Float.isFinite(virtualYaw)) {
            Overseer.LOGGER.warn("Skipping portal view: virtual camera is not finite ({} yaw {})", virtualPos, virtualYaw);
            return false;
        }

        Framebuffer main = client.getFramebuffer();
        Framebuffer offscreen = target.prepare(main.textureWidth, main.textureHeight);

        // WorldRenderer reads the camera from shared GameRenderer state in a dozen places rather
        // than only from the argument, so handing it a separate Camera object would mix a virtual
        // view with the real camera's extracted state. Mutate the real camera in place instead and
        // restore it afterwards, so every one of those reads is consistent.
        float realYaw = realCamera.getYaw();
        float realPitch = realCamera.getPitch();

        Camera virtualCamera = realCamera;

        // Everything this method borrows, captured before a single thing is mutated.
        //
        // The restores have to be reachable from the failure path as well as the success one. A
        // throw on the way in -- a degenerate fog colour, a bad projection -- is caught upstream
        // and logged, and if the camera were left pointing at the far dimension's environment the
        // player's own sky and fog would stay wrong for the rest of the session rather than for
        // one frame.
        EnvironmentAttributeInterpolator realEnvironment = virtualCamera.environmentAttributeInterpolator;
        WorldRenderState farState = SecondaryWorldManager.renderState(transform.to().dimension());
        CameraRenderState previousCameraState = farState == null ? null : farState.cameraRenderState;
        GlobalSettings globals = client.gameRenderer.getGlobalSettings();
        Framebuffer previousFramebuffer = client.framebuffer;
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType previousProjectionType = RenderSystem.getProjectionType();
        LightmapTextureManager previousLightmap = client.gameRenderer.lightmapTextureManager;
        RegistryKey<World> farDimension = transform.to().dimension();
        int age = OverseerClient.age;

        try {
            virtualCamera.setPos(virtualPos);
            virtualCamera.setRotation(virtualYaw, realPitch);

            // Point the camera at the far dimension's environment before anything reads a colour off
            // it: the fog below, and the sky renderer inside the nested render, both go through this.
            EnvironmentAttributeInterpolator farEnvironment =
                    ENVIRONMENTS.computeIfAbsent(transform.to().dimension(), key -> new EnvironmentAttributeInterpolator());

            virtualCamera.environmentAttributeInterpolator = farEnvironment;

            // Once per tick, as vanilla does for the camera's own. Every value in here is lerped
            // from where it was at the last update to where it is now by tick progress; updating
            // every frame collapses that to a step, and a sunset through the doorway advanced in
            // twenty jumps a second while the one overhead moved smoothly.
            Integer environmentAge = ENVIRONMENT_AGES.put(farDimension, age);
            if (environmentAge == null || environmentAge != age) {
                farEnvironment.update(farWorld, virtualPos);
            }

            // The frame's own matrices, straight from GameRenderer. Recomputing them here would drop
            // view bobbing and hurt tilt, and the composite would sample a view that no longer lines up
            // with the scene it is drawn into.
            Matrix4f projection = frameProjection;
            Matrix4f basicProjection = frameBasicProjection;
            // The CONJUGATE, matching GameRenderer. A camera's rotation quaternion turns the camera;
            // the view matrix has to turn the world the opposite way, so it needs the inverse. Using
            // the rotation directly renders the far side mirrored and pitched the wrong way.
            //
            // This matrix is also the view passed to the oblique clip, so getting it wrong put the
            // clip plane in the wrong space too.
            Matrix4f positionMatrix = new Matrix4f().rotation(
                    virtualCamera.getRotation().conjugate(new Quaternionf())
            );
            nestedPositionMatrix = positionMatrix;

            // Clip the BOBBED projection. GameRenderer bakes view bob, hurt tilt and nausea into
            // basicProjectionMatrix and uploads that as the rasterisation ProjMat; the third matrix
            // (frameProjection) is a clean one it only builds for the culling frustum, and
            // WorldRenderer.render never reads basicProjectionMatrix at all. Clipping the clean one
            // and uploading it rendered the far side without bob while the near scene had it, so the
            // two drifted apart every time the player walked.
            //
            // Except when the camera is already through. The virtual camera is then on the far
            // side of the exit, looking away from it, and the clip would remove the whole view.
            //
            // One call either way, not a choice between two expressions that each make a matrix:
            // see ObliqueProjection.apply for why that distinction crashes the JVM.
            Matrix4f clipped = ObliqueProjection.apply(basicProjection, positionMatrix, virtualPos, transform.to(), clipToPortal);

            // The far world's own fog, from our own FogRenderer so the main render's slice survives.
            //
            // This is also what makes the sky right. WorldRenderer's frame graph clears the colour
            // attachment to the fog colour it is handed, and with "skybox": "none" nothing is drawn
            // over it -- so passing the near world's fog colour is exactly why the memory dimension
            // showed the overworld's sky through the portal.
            if (fog == null) {
                fog = new FogRenderer();
            }

            fog.rotate();

            float skyDarkness = client.gameRenderer.getSkyDarkness(tickCounter.getTickProgress(false));
            Vector4f fogColor = fog.applyFog(
                    virtualCamera,
                    client.options.getClampedViewDistance(),
                    tickCounter,
                    skyDarkness,
                    farWorld
            );
            GpuBufferSlice fogBuffer = fog.getFogBuffer(FogRenderer.FogType.WORLD);

            // Swap in the far world, its renderer, and the offscreen target for the duration. The
            // world renderer reads all three off MinecraftClient, so this is what redirects its
            // output without touching its internals.
            if (projectionUpload == null) {
                projectionUpload = new RawProjectionMatrix("overseer portal projection");
            }

            RenderSystem.setProjectionMatrix(projectionUpload.set(clipped), ProjectionType.PERSPECTIVE);

            client.framebuffer = offscreen;

            // The camera argument is only half the story. WorldRenderer positions terrain, entities,
            // block entities, clouds, weather and the border relative to
            // WorldRenderState.cameraRenderState.pos, which GameRenderer fills from the *real* camera
            // at the top of renderWorld -- before this runs. Leaving it alone drew the far world laid
            // out around the main camera, so the portal showed the entry's surroundings however far
            // the virtual camera had been moved.
            if (farState != null) {
                CameraRenderState virtualState = new CameraRenderState();
                virtualState.initialized = true;
                virtualState.pos = virtualPos;
                virtualState.blockPos = BlockPos.ofFloored(virtualPos);
                // Billboards orient off this; it has to be the virtual rotation, not the real one.
                virtualState.orientation = new Quaternionf(virtualCamera.getRotation());
                // No entity exists on the far side, and the camera is the closest thing to one.
                virtualState.entityPos = virtualPos;
                farState.cameraRenderState = virtualState;
            }

            // And the Globals UBO, which is where terrain actually gets its camera from. The chunk
            // vertex shader is
            //
            //     pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset
            //
            // with ChunkPosition the section's absolute origin and CameraBlockPos/CameraOffset coming
            // from a single per-frame global that GameRenderer writes from the real camera before
            // renderWorld. Nothing in WorldRenderer.render touches it, so the far world's sections --
            // culled and meshed correctly around the virtual camera -- were still being placed
            // relative to the real one. That is the offset that made the portal show the entry's
            // surroundings.
            writeGlobals(client, globals, tickCounter, farWorld);

            // Colour needs no clear: renderSky fills every pixel. Depth does, or last frame's depth
            // would occlude this one.
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(
                            offscreen.getColorAttachment(), 0,
                            offscreen.getDepthAttachment(), 1.0
                    );

                SecondaryWorldManager.withWorld(farWorld, () -> {
                    // Read it back here rather than trusting what we set: if anything between the
                    // assignment and the call moves the camera, this is where it shows up.
                    lastRenderedCameraPos = client.gameRenderer.getCamera().getCameraPos();

                    // Inside the swap, because the lightmap reads the dimension straight off
                    // client.world and the sky light off the camera's environment -- both of which
                    // are the far side's only in here.
                    if (lightmap == null) {
                        lightmap = new LightmapTextureManager(client.gameRenderer, client);
                    }

                    // tick() is what marks it dirty, and it also advances the torch flicker, so it
                    // is called once per client tick exactly as vanilla calls its own -- plus once
                    // when the view moves to another dimension, which cannot wait for the next tick.
                    if (lightmapAge != age || lightmapDimension != farDimension) {
                        lightmapAge = age;
                        lightmapDimension = farDimension;
                        lightmap.tick();
                    }

                    client.gameRenderer.lightmapTextureManager = lightmap;
                    lightmap.update(1.0F);

                    farRenderer.render(
                        POOL,
                        tickCounter,
                        false,
                        virtualCamera,
                        positionMatrix,
                        basicProjection,
                        // Deliberately the UNCLIPPED projection.
                        //
                        // This argument is what WorldRenderer builds its culling frustum from, and an
                        // oblique near plane is not a valid frustum for culling: the near plane is no
                        // longer parallel to the far one, so the region it bounds is a wedge.
                        // Frustum.coverBoxAroundSetPosition walks the origin backwards until a box is
                        // fully inside that region, which for a wedge never happens -- an infinite
                        // loop on the render thread.
                        //
                        // The clip belongs only on the rasterisation matrix, set on RenderSystem
                        // above. Culling with the wider unclipped frustum simply submits a little
                        // geometry that the shader then clips away.
                        projection,
                        fogBuffer,
                        fogColor,
                        true
                    );
                });

            return true;
        } finally {
            nestedPositionMatrix = null;
            client.gameRenderer.lightmapTextureManager = previousLightmap;
            virtualCamera.environmentAttributeInterpolator = realEnvironment;
            if (farState != null && previousCameraState != null) {
                farState.cameraRenderState = previousCameraState;
            }
            client.framebuffer = previousFramebuffer;
            RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
            realCamera.setPos(realPos);
            realCamera.setRotation(realYaw, realPitch);
            // Only valid once the camera is back, since it reads the camera off the game renderer.
            writeGlobals(client, globals, tickCounter, client.world);
        }
    }

    /**
     * Rewrite the Globals UBO for whichever camera the game renderer currently holds.
     *
     * <p>Mirrors GameRenderer's own once-per-frame call. Cheap enough to do twice a frame: it is a
     * single small buffer upload, and it is the only way to move terrain onto the virtual camera
     * without reaching into the chunk shader.
     */
    private static void writeGlobals(MinecraftClient client, GlobalSettings globals,
                                     RenderTickCounter tickCounter, @Nullable ClientWorld world) {
        globals.set(
                client.getWindow().getFramebufferWidth(),
                client.getWindow().getFramebufferHeight(),
                client.options.getGlintStrength().getValue(),
                world == null ? 0L : world.getTime(),
                tickCounter,
                client.options.getMenuBackgroundBlurrinessValue(),
                client.gameRenderer.getCamera(),
                client.options.getTextureFiltering().getValue() == TextureFilteringMode.RGSS
        );
    }
}
