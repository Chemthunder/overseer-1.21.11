package net.damagejr05.overseer.core.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.JsonOps;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPipeline;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.render.ProjectionMatrix2;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.Optional;

/**
 * The glow of the memory dimension: a post effect in the game's own format, run over the world
 * after it has been drawn and before the hand and the interface are.
 *
 * <p>What it does is in {@code assets/overseer/post_effect/memory.json} and the three shaders
 * under {@code shaders/post}: bright things bleed light, the corners of the view fall away a
 * little, and there is a trace of grain. Being an ordinary post effect, a resource pack can
 * replace any of it.
 *
 * <p><b>It is not loaded the way the game loads its own</b>, although it could be in one line.
 * The game's loader treats a post effect that fails to load as a broken resource pack, and with
 * nothing but the default pack enabled its answer to a broken resource pack is a crash report.
 * A cosmetic filter is not worth that on anyone's machine, so the definition is read and built
 * here, where a failure is logged once and the effect simply stays off.
 *
 * <p><b>It fades.</b> The effect belongs to the dimension, but a doorway is supposed to have no
 * seam, and a filter that switched on at the moment of stepping through would be one. So it
 * eases in over a second or so after arriving and out again after leaving -- which means that
 * for that second it is running in the overworld, and that is deliberate. The game gives a post
 * effect no way to be told a number that changes, so the amount is handed over as a texture one
 * pixel in size, repainted whenever it moves.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public final class MemoryAtmosphere {
    /** The definition, as a resource, and the name its passes are labelled with. */
    private static final Identifier DEFINITION = Overseer.id("post_effect/memory.json");
    private static final Identifier EFFECT = Overseer.id("memory");

    /**
     * Where the game looks for the texture the definition calls {@code overseer:memory_fade}.
     * There is no such file: a texture is registered under the name before the effect is built,
     * and the effect is handed that.
     */
    private static final Identifier FADE_TEXTURE = Overseer.id("textures/effect/memory_fade.png");

    /** How long the effect takes to come in or go out, in seconds. */
    private static final float FADE_SECONDS = 1.4F;

    /**
     * On by default; {@code /overseerclient glow off} or {@code -Doverseer.memoryGlow=false}
     * turns it off. It is also left off whenever the game's graphics are set to Fast, since two
     * more passes over the whole screen are exactly what that setting is asking not to have.
     */
    private static boolean enabled = Boolean.parseBoolean(System.getProperty("overseer.memoryGlow", "true"));

    private static @Nullable PostEffectProcessor processor = null;
    private static @Nullable ProjectionMatrix2 projection = null;
    private static @Nullable NativeImageBackedTexture fadeTexture = null;

    /** Set once building or running it has failed, so that it is not tried again every frame. */
    private static boolean failed = false;

    /** Set by a resource reload: the shaders may have changed, so it has to be built again. */
    private static boolean stale = false;

    /**
     * Whether the effect has been run once since it was built.
     *
     * <p>Its shaders are compiled the first time each pass is drawn, which takes a noticeable
     * moment -- and left to itself that moment would be the first frame of the fade, which is
     * the frame the player steps through a doorway. So it is run once, at nothing, on the first
     * frame of the session, where a hitch is lost among all the others.
     */
    private static boolean warmed = false;

    /** How far in the effect is, 0 to 1, and the value last painted into the texture. */
    private static float fade = 0.0F;
    private static int painted = -1;
    private static long lastFrameNanos = 0L;

    private MemoryAtmosphere() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;

        // Asking for it again is also asking for another try, if it had given up.
        if (value) {
            failed = false;
        }
    }

    /** From the resource reloader; acted on at the next frame, on the render thread. */
    public static void onResourcesReloaded() {
        stale = true;
        failed = false;
    }

    /** The session has ended. The next one starts with the effect all the way out. */
    public static void reset() {
        fade = 0.0F;
        lastFrameNanos = 0L;
    }

    /**
     * Run the effect over the frame the world renderer has just finished, if any of it is
     * showing. Called once per frame from the game renderer, for the player's own view only.
     */
    public static void render(MinecraftClient client) {
        long now = System.nanoTime();
        // Clamped: the first frame, and any frame after a long hitch, must not jump the fade.
        float delta = lastFrameNanos == 0L ? 0.0F : Math.min((now - lastFrameNanos) / 1.0e9F, 0.1F);
        lastFrameNanos = now;

        boolean available = enabled && client.options.getPreset().getValue() != GraphicsMode.FAST;
        boolean wanted = available
                && client.world != null
                && client.world.getRegistryKey() == ModDimensions.MEMORY;

        float step = delta / FADE_SECONDS;
        fade = wanted ? Math.min(1.0F, fade + step) : Math.max(0.0F, fade - step);

        if (stale) {
            stale = false;
            discard();
        }

        boolean warming = !warmed && available && !failed;

        if ((fade <= 0.0F && !warming) || failed) {
            return;
        }

        // Everything a pass borrows and might not put back if it throws half way.
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType previousProjectionType = RenderSystem.getProjectionType();

        try {
            PostEffectProcessor effect = processor(client);

            // Smoothstep, so it eases in and out rather than starting and stopping dead.
            paint(MathHelper.clamp(Math.round(fade * fade * (3.0F - 2.0F * fade) * 255.0F), 0, 255));

            effect.render(client.getFramebuffer(), client.gameRenderer.pool);
            warmed = true;
        } catch (Throwable e) {
            failed = true;
            Overseer.LOGGER.error("The memory dimension's glow could not be drawn and has been turned off", e);

            if (previousProjection != null) {
                RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
            }

            discard();
        }
    }

    private static PostEffectProcessor processor(MinecraftClient client) throws Exception {
        if (processor != null) {
            return processor;
        }

        // Before the effect is built, because building it is when it looks the texture up, and
        // it keeps what it finds.
        if (fadeTexture == null) {
            fadeTexture = new NativeImageBackedTexture("overseer memory glow fade", 1, 1, false);
            client.getTextureManager().registerTexture(FADE_TEXTURE, fadeTexture);
            painted = -1;
            paint(0);
        }

        Optional<Resource> resource = client.getResourceManager().getResource(DEFINITION);
        if (resource.isEmpty()) {
            throw new IllegalStateException("Missing " + DEFINITION);
        }

        JsonElement json;
        try (Reader reader = resource.get().getReader()) {
            json = JsonParser.parseReader(reader);
        }

        PostEffectPipeline definition = PostEffectPipeline.CODEC
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow(JsonParseException::new);

        if (projection == null) {
            projection = new ProjectionMatrix2("overseer memory glow", 0.1F, 1000.0F, false);
        }

        processor = PostEffectProcessor.parseEffect(
                definition, client.getTextureManager(), DefaultFramebufferSet.MAIN_ONLY, EFFECT, projection
        );

        return processor;
    }

    /** Set how much of the effect the shader applies, 0 to 255. */
    private static void paint(int amount) {
        if (amount == painted || fadeTexture == null) {
            return;
        }

        NativeImage image = fadeTexture.getImage();
        if (image == null) {
            return;
        }

        // The shader reads green; the rest is set to match so the texture is simply grey.
        image.setColorArgb(0, 0, 0xFF000000 | amount << 16 | amount << 8 | amount);
        fadeTexture.upload();
        painted = amount;
    }

    private static void discard() {
        if (processor == null) {
            return;
        }

        try {
            processor.close();
        } catch (Throwable e) {
            Overseer.LOGGER.error("Failed to release the memory dimension's glow", e);
        }

        processor = null;
        warmed = false;
    }
}
