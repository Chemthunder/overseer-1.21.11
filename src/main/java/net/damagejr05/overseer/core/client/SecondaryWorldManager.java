package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.RenderDispatcher;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets the client hold more than one dimension loaded at a time.
 *
 * <p>Vanilla assumes exactly one {@link ClientWorld}. Seeing through a portal into another
 * dimension means the client needs that dimension's chunks too, so each remote dimension gets its
 * own {@code ClientWorld} and its own {@link WorldRenderer}. Redirected packets are applied by
 * temporarily swapping the active world, which lets every vanilla packet handler run unmodified.
 *
 * <p>Modelled on Immersive Portals' {@code ClientWorldLoader}, rebuilt for 1.21.11.
 *
 * @author Flex
 */
@Environment(EnvType.CLIENT)
public class SecondaryWorldManager {
    private static final Map<RegistryKey<World>, ClientWorld> WORLDS = new HashMap<>();
    private static final Map<RegistryKey<World>, WorldRenderer> RENDERERS = new HashMap<>();
    private static final Map<RegistryKey<World>, RenderDispatcher> DISPATCHERS = new HashMap<>();
    private static final Map<RegistryKey<World>, WorldRenderState> RENDER_STATES = new HashMap<>();

    /**
     * Vanilla never tells the client which dimension type backs which dimension, so the mapping is
     * declared here. Only dimensions that can appear through a portal need an entry.
     */
    private static final Map<RegistryKey<World>, RegistryKey<DimensionType>> DIMENSION_TYPES = Map.of(
            ModDimensions.MEMORY, RegistryKey.of(RegistryKeys.DIMENSION_TYPE, Overseer.id("memory")),
            World.OVERWORLD, DimensionTypes.OVERWORLD
    );

    private static boolean swapped = false;

    /**
     * The world for a dimension, creating it if this is the first time it has been needed.
     * Returns null if the dimension cannot be represented client-side.
     */
    public static @Nullable ClientWorld getOrCreate(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null && client.world.getRegistryKey() == dimension) {
            return client.world;
        }

        ClientWorld existing = WORLDS.get(dimension);
        if (existing != null) {
            return existing;
        }

        return create(dimension);
    }

    public static @Nullable ClientWorld get(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null && client.world.getRegistryKey() == dimension) {
            return client.world;
        }

        return WORLDS.get(dimension);
    }

    public static @Nullable RenderDispatcher dispatcher(RegistryKey<World> dimension) {
        return DISPATCHERS.get(dimension);
    }

    public static @Nullable WorldRenderer renderer(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null && client.world.getRegistryKey() == dimension) {
            return client.worldRenderer;
        }

        // Only hand back a renderer that still has its world. Disposal nulls a renderer's world
        // before dropping it, and rendering with one in that state throws deep inside
        // WorldRenderer where the cause is far from obvious.
        if (!WORLDS.containsKey(dimension)) {
            return null;
        }

        return RENDERERS.get(dimension);
    }

    /**
     * The render state a dimension's renderer draws from.
     *
     * <p>{@code WorldRenderer} keeps this private and has no getter, but everything it draws is
     * positioned relative to {@code cameraRenderState.pos} rather than the {@code Camera} argument
     * -- vanilla fills that in from {@code GameRenderer} once per frame, long before the render
     * call. A nested render therefore has to install its own, or the far world is drawn around the
     * main camera no matter where the virtual one was put.
     */
    public static @Nullable WorldRenderState renderState(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null && client.world.getRegistryKey() == dimension) {
            return client.gameRenderer.getEntityRenderStates();
        }

        if (!WORLDS.containsKey(dimension)) {
            return null;
        }

        return RENDER_STATES.get(dimension);
    }

    private static @Nullable ClientWorld create(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        ClientWorld primary = client.world;

        if (handler == null || primary == null) {
            return null;
        }

        RegistryKey<DimensionType> typeKey = DIMENSION_TYPES.get(dimension);
        if (typeKey == null) {
            Overseer.LOGGER.warn("No dimension type mapping for {}", dimension.getValue());
            return null;
        }

        RegistryEntry<DimensionType> type = handler.getRegistryManager()
                .getOrThrow(RegistryKeys.DIMENSION_TYPE)
                .getOptional(typeKey)
                .orElse(null);

        if (type == null) {
            Overseer.LOGGER.warn("Dimension type {} is not present in the synced registry", typeKey.getValue());
            return null;
        }

        try {
            // Everything per-frame and mutable is its own. Sharing any of these would have two
            // renderers writing the same buffers within one frame, which corrupts both.
            //
            // The RenderDispatcher matters most: it owns the ordered command queue that entity and
            // block-entity rendering submits into. Sharing it meant the portal render's commands
            // and the main render's commands landed in one queue and were flushed together with
            // mismatched camera and buffer state.
            //
            // The storage is sized the way vanilla sizes its own. Passing 0 starves the secondary
            // chunk builder of block buffer builders and stalls terrain meshing.
            BufferBuilderStorage buffers = new BufferBuilderStorage(
                    Runtime.getRuntime().availableProcessors()
            );

            RenderDispatcher dispatcher = new RenderDispatcher(
                    new OrderedRenderCommandQueueImpl(),
                    client.getBlockRenderManager(),
                    buffers.getEntityVertexConsumers(),
                    client.getAtlasManager(),
                    buffers.getOutlineVertexConsumers(),
                    buffers.getEffectVertexConsumers(),
                    client.textRenderer
            );

            WorldRenderState renderState = new WorldRenderState();

            WorldRenderer renderer = new WorldRenderer(
                    client,
                    client.getEntityRenderDispatcher(),
                    client.getBlockEntityRenderDispatcher(),
                    buffers,
                    renderState,
                    dispatcher
            );

            DISPATCHERS.put(dimension, dispatcher);
            RENDER_STATES.put(dimension, renderState);

            ClientWorld.Properties properties = new ClientWorld.Properties(
                    primary.getLevelProperties().getDifficulty(),
                    primary.getLevelProperties().isHardcore(),
                    primary.getLevelProperties().flatWorld
            );

            ClientWorld world = new ClientWorld(
                    handler,
                    properties,
                    dimension,
                    type,
                    client.options.getClampedViewDistance(),
                    primary.getSimulationDistance(),
                    renderer,
                    primary.isDebugWorld(),
                    primary.getBiomeAccess().seed,
                    primary.getSeaLevel()
            );

            // Wide enough to hold everything RemoteChunkTracker streams around an anchor.
            world.getChunkManager().updateLoadDistance(8);

            renderer.setWorld(world);
            renderer.reload(client.getResourceManager());

            // Vanilla only ever calls this from the window resize path, so a renderer created
            // mid-session would otherwise keep an unsized entity-outline framebuffer.
            renderer.onResized(
                    client.getFramebuffer().textureWidth,
                    client.getFramebuffer().textureHeight
            );

            WORLDS.put(dimension, world);
            RENDERERS.put(dimension, renderer);

            Overseer.LOGGER.info("Created secondary client world {}", dimension.getValue());
            return world;
        } catch (Exception e) {
            Overseer.LOGGER.error("Failed to create secondary client world {}", dimension.getValue(), e);
            return null;
        }
    }

    /**
     * Run {@code action} with {@code world} installed as the active client world.
     *
     * <p>This is what lets an unmodified vanilla packet handler write into a secondary dimension:
     * it reads {@code client.world} and the network handler's world, so pointing those at the
     * secondary world for the duration is enough. Always restores, including on exception.
     */
    public static void withWorld(ClientWorld world, Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client.getNetworkHandler();

        if (handler == null) {
            return;
        }

        ClientWorld previousWorld = client.world;
        WorldRenderer previousRenderer = client.worldRenderer;
        ClientWorld previousHandlerWorld = handler.world;
        boolean previousSwapped = swapped;

        WorldRenderer renderer = RENDERERS.get(world.getRegistryKey());

        client.world = world;
        handler.world = world;
        if (renderer != null) {
            client.worldRenderer = renderer;
        }
        // Assigned directly rather than through setWorld, which clears every live particle.
        client.particleManager.world = world;
        swapped = true;

        try {
            action.run();
        } finally {
            client.world = previousWorld;
            handler.world = previousHandlerWorld;
            client.worldRenderer = previousRenderer;
            client.particleManager.world = previousWorld;
            swapped = previousSwapped;
        }
    }

    /**
     * True while a secondary world is installed. Used to stop swapped-in state leaking into logic
     * that assumes it is looking at the world the player is actually standing in.
     */
    public static boolean isSwapped() {
        return swapped;
    }

    /** The dimension the player was in last tick, to detect a change. */
    private static @Nullable RegistryKey<World> lastDimension = null;

    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null) {
            RegistryKey<World> current = client.world.getRegistryKey();

            // Only the chunk centres are reset wholesale on a dimension change. Tearing down every
            // secondary world here closed renderers that had just been created in the same tick,
            // and a render landing in that window used a renderer whose world was already null.
            if (lastDimension != null && lastDimension != current) {
                Overseer.LOGGER.info("Dimension changed {} -> {}", lastDimension.getValue(), current.getValue());
                PacketRedirectionClient.clear();
            }

            lastDimension = current;

            // Walking into a dimension makes vanilla's copy of it authoritative. Any secondary
            // copy is now a stale duplicate holding its own chunks and renderer, so drop it.
            if (WORLDS.containsKey(current)) {
                dispose(current);
                Overseer.LOGGER.info("Dropped secondary world {} on entering it", current.getValue());
            }
        }

        for (ClientWorld world : WORLDS.values()) {
            if (world == client.world) {
                continue;
            }

            withWorld(world, () -> {
                try {
                    world.tickEntities();
                    world.tick(() -> true);
                } catch (Throwable e) {
                    Overseer.LOGGER.error("Error ticking secondary world {}", world.getRegistryKey().getValue(), e);
                }
            });
        }
    }

    /** Tear down one secondary world and everything allocated alongside it. */
    private static void dispose(RegistryKey<World> dimension) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Drop the world entry first: renderer() keys off it, so once this line runs no caller
        // can be handed the renderer that is about to be torn down.
        WORLDS.remove(dimension);

        WorldRenderer renderer = RENDERERS.remove(dimension);
        if (renderer != null && renderer != client.worldRenderer) {
            renderer.setWorld(null);
            renderer.close();
        }

        RENDER_STATES.remove(dimension);
        PortalViewRenderer.forget(dimension);

        RenderDispatcher dispatcher = DISPATCHERS.remove(dimension);
        if (dispatcher != null) {
            dispatcher.close();
        }

        PacketRedirectionClient.forget(dimension);
    }

    /**
     * Drop every secondary world. Must run on disconnect, or the next session would reuse worlds
     * bound to a dead network handler.
     */
    public static void clear() {
        MinecraftClient client = MinecraftClient.getInstance();

        for (WorldRenderer renderer : RENDERERS.values()) {
            if (renderer != client.worldRenderer) {
                renderer.setWorld(null);
                renderer.close();
            }
        }

        for (RenderDispatcher dispatcher : DISPATCHERS.values()) {
            dispatcher.close();
        }

        PacketRedirectionClient.clear();

        WORLDS.clear();
        RENDERERS.clear();
        DISPATCHERS.clear();
        RENDER_STATES.clear();
        swapped = false;
        lastDimension = null;
    }
}
