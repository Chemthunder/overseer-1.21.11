package net.damagejr05.overseer.core.client;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.client.render.PortalViewRenderer;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.RenderDispatcher;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profilers;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
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
     * How long a secondary world may sit with no chunks before it is dropped, in ticks.
     *
     * <p>The server unloads the far side when the player walks away from a doorway or the doorway
     * closes, which leaves an empty world still holding a renderer, a chunk builder and its
     * buffers, ticking for the rest of the session. Long enough that walking in and out of range
     * does not rebuild it every time.
     */
    private static final int EMPTY_TICKS_BEFORE_DISPOSAL = 200;

    private static final Map<RegistryKey<World>, Integer> EMPTY_TICKS = new HashMap<>();

    /**
     * How long a secondary world may go without a single packet from the server before it is
     * dropped, chunks or no chunks, in ticks.
     *
     * <p>The server sends the clock of every dimension it is streaming once a second, so silence
     * this long means it has stopped. That happens with chunks still held after a crossing: the
     * world just left keeps a ring of terrain the server never streamed and so never unloads,
     * which kept it from ever counting as empty and left it ticking for the rest of the session.
     */
    private static final int SILENT_TICKS_BEFORE_DISPOSAL = 200;

    /** The tick each secondary world last heard from the server on. */
    private static final Map<RegistryKey<World>, Integer> LAST_STREAMED = new HashMap<>();

    /** Counts unpaused client ticks. A paused integrated server sends nothing, and that is not silence. */
    private static int ticks = 0;

    /** A redirected packet for {@code dimension} has just arrived. */
    public static void noteStreamed(RegistryKey<World> dimension) {
        LAST_STREAMED.put(dimension, ticks);
    }

    /**
     * A renderer's buffer storage, and how many block buffer sets its pool holds when none is out
     * with a meshing task.
     */
    private record PooledBuffers(BufferBuilderStorage storage, int builders) {
    }

    /**
     * Buffer storage is recycled between secondary renderers rather than made for each.
     *
     * <p>It is several megabytes of native memory per processor, nothing in vanilla ever frees
     * one -- it only has the one, for the life of the game -- and a renderer is built here every
     * time a doorway comes back into range. Making a new storage each time grew the process by a
     * hundred megabytes or more per doorway approached until the machine ran out.
     *
     * <p>Freeing it when the renderer closes is not safe either: a meshing task still running on
     * a worker thread may be holding one of its buffers. So a closed renderer's storage is kept
     * and handed to the next renderer once every buffer is back in its pool.
     */
    private static final Map<WorldRenderer, PooledBuffers> BUFFERS_IN_USE = new IdentityHashMap<>();
    private static final List<PooledBuffers> SPARE_BUFFERS = new ArrayList<>();

    private static PooledBuffers acquireBuffers() {
        for (Iterator<PooledBuffers> spares = SPARE_BUFFERS.iterator(); spares.hasNext(); ) {
            PooledBuffers spare = spares.next();

            // A task from the renderer that last had it is still out. Leave it for next time.
            if (spare.storage().getBlockBufferBuildersPool().getAvailableBuilderCount() == spare.builders()) {
                spares.remove();
                return spare;
            }
        }

        // Sized the way vanilla sizes its own. Passing 0 starves the chunk builder of block
        // buffer builders and stalls terrain meshing.
        BufferBuilderStorage storage = new BufferBuilderStorage(Runtime.getRuntime().availableProcessors());
        return new PooledBuffers(storage, storage.getBlockBufferBuildersPool().getAvailableBuilderCount());
    }

    /** {@code renderer} has been closed; its storage is free for the next one. */
    private static void releaseBuffers(WorldRenderer renderer) {
        PooledBuffers pooled = BUFFERS_IN_USE.remove(renderer);
        if (pooled != null) {
            SPARE_BUFFERS.add(pooled);
        }
    }

    /** Framebuffer size the secondary renderers were last sized for. */
    private static int sizedWidth = -1;
    private static int sizedHeight = -1;

    /** Set from the resource reload, acted on at the next tick. */
    private static boolean resourcesReloaded = false;

    /**
     * Vanilla rebuilds its own world renderer after a resource reload and knows nothing about
     * these. Left alone they keep meshes whose texture coordinates point into the old atlas, and
     * the far side of every doorway turns to noise the first time a resource pack changes.
     *
     * <p>Deferred to the tick rather than done in the reloader: the rebuild has to see the new
     * atlas, and this may be called before it has been stitched.
     */
    public static void onResourcesReloaded() {
        resourcesReloaded = true;
    }

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

        PooledBuffers pooled = acquireBuffers();

        try {
            // Everything per-frame and mutable is its own. Sharing any of these would have two
            // renderers writing the same buffers within one frame, which corrupts both.
            //
            // The RenderDispatcher matters most: it owns the ordered command queue that entity and
            // block-entity rendering submits into. Sharing it meant the portal render's commands
            // and the main render's commands landed in one queue and were flushed together with
            // mismatched camera and buffer state.
            BufferBuilderStorage buffers = pooled.storage();

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

            BUFFERS_IN_USE.put(renderer, pooled);

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
            loadClouds(client, renderer);

            // Vanilla only ever calls this from the window resize path, so a renderer created
            // mid-session would otherwise keep an unsized entity-outline framebuffer.
            renderer.onResized(
                    client.getFramebuffer().textureWidth,
                    client.getFramebuffer().textureHeight
            );

            WORLDS.put(dimension, world);
            RENDERERS.put(dimension, renderer);
            LAST_STREAMED.put(dimension, ticks);

            // The clock starts at zero and the server's first sync is up to a second away. The
            // player's own world is a far better guess than tick zero in the meantime: every
            // dimension shares one day counter, so this is normally exactly right already.
            world.setTime(primary.getTime(), primary.getTimeOfDay(), true);

            Overseer.LOGGER.info("Created secondary client world {}", dimension.getValue());
            return world;
        } catch (Exception e) {
            Overseer.LOGGER.error("Failed to create secondary client world {}", dimension.getValue(), e);
            BUFFERS_IN_USE.values().remove(pooled);
            SPARE_BUFFERS.add(pooled);
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

        if (!client.isPaused()) {
            ticks++;
        }

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

        maintainRenderers(client);
        disposeEmptyWorlds();

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

    /** Keep the secondary renderers in step with things vanilla only tells its own renderer. */
    private static void maintainRenderers(MinecraftClient client) {
        if (resourcesReloaded) {
            resourcesReloaded = false;

            // Every renderer built here, wherever it currently is. One of them may be standing
            // in as the client's main renderer, and vanilla reloads only the one it built.
            List<WorldRenderer> ours = new ArrayList<>(RENDERERS.values());
            ours.add(client.worldRenderer);

            for (WorldRenderer renderer : ours) {
                if (renderer == vanillaRenderer) {
                    continue;
                }

                try {
                    renderer.reload(client.getResourceManager());
                    loadClouds(client, renderer);
                    renderer.reload();
                } catch (Throwable e) {
                    Overseer.LOGGER.error("Failed to reload a secondary world renderer", e);
                }
            }
        }

        trimAdoptedWorld(client);

        // Vanilla resizes its renderer from the window callback. A stale size here leaves the
        // entity-outline target at the old resolution, and the glowing-entity pass then blits
        // between two framebuffers that no longer match.
        int width = client.getFramebuffer().textureWidth;
        int height = client.getFramebuffer().textureHeight;

        if (width != sizedWidth || height != sizedHeight) {
            sizedWidth = width;
            sizedHeight = height;

            for (WorldRenderer renderer : RENDERERS.values()) {
                renderer.onResized(width, height);
            }
        }
    }

    private static void loadClouds(MinecraftClient client, WorldRenderer renderer) {
        CloudRenderer clouds = renderer.getCloudRenderer();
        clouds.apply(clouds.prepare(client.getResourceManager(), Profilers.get()), client.getResourceManager(), Profilers.get());
    }

    // ---------------------------------------------------------------------------------------
    // Stepping through
    // ---------------------------------------------------------------------------------------

    /**
     * The renderer and render state vanilla created at startup.
     *
     * <p>Remembered because they are special: vanilla registered that renderer as a resource
     * reloader, so it must never be closed, and on disconnect it is put back where vanilla left
     * it so that a session always starts from the state vanilla expects.
     */
    private static @Nullable WorldRenderer vanillaRenderer = null;
    private static @Nullable WorldRenderState vanillaState = null;

    /** The dispatcher belonging to the client's current main renderer, if that is one of ours. */
    private static @Nullable RenderDispatcher primaryDispatcher = null;

    /** The world being handed to vanilla, for the duration of the respawn packet that takes it. */
    private static @Nullable ClientWorld adopting = null;

    /** The dimension just left, whose leftovers still need clearing out. */
    private static @Nullable RegistryKey<World> departed = null;

    /** Chunks of the dimension just left that lie beyond anything a doorway will show. */
    private static final ArrayDeque<ChunkPos> TRIM = new ArrayDeque<>();
    private static @Nullable RegistryKey<World> trimDimension = null;

    /** Spread over ticks: a whole view distance of unloads in one frame is a visible hitch. */
    private static final int TRIM_PER_TICK = 48;

    /** Matches the radius the server streams, with a ring to spare. */
    private static final int KEEP_RADIUS = 5;

    /**
     * Hand the far world over to vanilla as the world the player is now in.
     *
     * <p>Without this, stepping through a doorway is a vanilla dimension change: the client throws
     * its world away, builds an empty one, and waits for the server to send terrain and for that
     * terrain to be meshed. For that long the player stands in nothing -- and all of it is
     * terrain the client was holding, fully built, a moment earlier, because it was drawing it
     * through the doorway.
     *
     * <p>So the two worlds trade places instead. The secondary world and its renderer become the
     * client's own, meshes and all, and the world just left becomes the secondary for the
     * doorway behind the player, so looking back shows it at once as well. Vanilla then carries
     * on exactly as usual: the server's chunks arrive and replace what is there with identical
     * data, one at a time, with nothing ever missing in between.
     *
     * @return the world to use in place of a new one, or null to let vanilla build its own
     */
    public static @Nullable ClientWorld adopt(RegistryKey<World> dimension, int loadDistance, int simulationDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld outgoing = client.world;

        // Only for a crossing. A respawn, a nether portal or a /tp is not arriving next to
        // anything that was on screen, and a stale secondary world is no substitute for a new one.
        if (outgoing == null || !PortalTransitionClient.isArmed()) {
            return null;
        }

        ClientWorld incoming = WORLDS.get(dimension);
        WorldRenderer incomingRenderer = RENDERERS.get(dimension);
        WorldRenderState incomingState = RENDER_STATES.get(dimension);
        RegistryKey<World> outgoingDimension = outgoing.getRegistryKey();

        if (incoming == null || incomingRenderer == null || incomingState == null
                || incoming == outgoing || WORLDS.containsKey(outgoingDimension) || !hasTerrain(dimension)) {
            return null;
        }

        if (vanillaRenderer == null) {
            vanillaRenderer = client.worldRenderer;
            vanillaState = client.gameRenderer.worldRenderState;
        }

        // The world just left takes the incoming one's place as a secondary...
        WORLDS.remove(dimension);
        RENDERERS.remove(dimension);
        RENDER_STATES.remove(dimension);
        RenderDispatcher incomingDispatcher = DISPATCHERS.remove(dimension);
        EMPTY_TICKS.remove(dimension);
        LAST_STREAMED.remove(dimension);
        PacketRedirectionClient.forget(dimension);
        PortalViewRenderer.forget(dimension);

        WORLDS.put(outgoingDimension, outgoing);
        LAST_STREAMED.put(outgoingDimension, ticks);
        RENDERERS.put(outgoingDimension, client.worldRenderer);
        RENDER_STATES.put(outgoingDimension, client.gameRenderer.worldRenderState);
        if (primaryDispatcher != null) {
            DISPATCHERS.put(outgoingDimension, primaryDispatcher);
        }

        // ...and the incoming one becomes the client's own.
        primaryDispatcher = incomingDispatcher;
        client.worldRenderer = incomingRenderer;
        client.gameRenderer.worldRenderState = incomingState;

        // It was sized to hold a doorway's surroundings. It now has to hold a view distance.
        incoming.getChunkManager().updateLoadDistance(loadDistance);
        incoming.setSimulationDistance(simulationDistance);

        DoorwayComponent.KEY.get(incoming).copyFrom(DoorwayComponent.KEY.get(outgoing));

        adopting = incoming;
        departed = outgoingDimension;

        Overseer.LOGGER.info("Stepped through into {} without reloading it", dimension.getValue());
        return incoming;
    }

    /**
     * True while {@code world} is being handed over. Its renderer already holds it, fully meshed,
     * and must not be told to start again from nothing.
     */
    public static boolean isAdopting(@Nullable ClientWorld world) {
        return world != null && world == adopting;
    }

    /** Runs once vanilla has finished with the respawn packet, whether or not a world was adopted. */
    public static void finishAdoption() {
        adopting = null;

        RegistryKey<World> dimension = departed;
        departed = null;

        ClientWorld world = dimension == null ? null : WORLDS.get(dimension);
        if (world == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        // Everything that was in the world just left, the player's own previous body included --
        // which would otherwise stand in the doorway behind them for ever. Vanilla simply drops
        // the whole world at this point; this one is being kept, so it is emptied instead, and the
        // server streams back whatever is still near the doorway within a tick.
        List<Integer> ids = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            ids.add(entity.getId());
        }

        withWorld(world, () -> {
            for (int id : ids) {
                world.removeEntity(id, Entity.RemovalReason.DISCARDED);
            }
        });

        // And everything out of sight of a doorway. The server only keeps the area around the
        // doorway current from here on, so the rest would be a snapshot going stale -- and would
        // keep this world from ever counting as empty and being dropped.
        List<PortalAnchor> anchors = DoorwayComponent.KEY.get(world).anchorsIn(dimension);
        ChunkPos centre = client.player == null
                ? new ChunkPos(0, 0)
                : new ChunkPos(BlockPos.ofFloored(client.gameRenderer.getCamera().getCameraPos()));
        int reach = client.options.getClampedViewDistance() + 3;

        TRIM.clear();
        trimDimension = dimension;

        // The camera has not moved yet -- the new position arrives with the next packet -- so it
        // still marks the middle of what the departed world has loaded.
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                ChunkPos pos = new ChunkPos(centre.x + x, centre.z + z);

                if (world.getChunkManager().getWorldChunk(pos.x, pos.z) != null && !nearAnchor(pos, anchors)) {
                    TRIM.add(pos);
                }
            }
        }
    }

    private static boolean nearAnchor(ChunkPos pos, List<PortalAnchor> anchors) {
        for (PortalAnchor anchor : anchors) {
            ChunkPos at = new ChunkPos(BlockPos.ofFloored(anchor.center()));

            if (Math.abs(pos.x - at.x) <= KEEP_RADIUS && Math.abs(pos.z - at.z) <= KEEP_RADIUS) {
                return true;
            }
        }

        return false;
    }

    private static void trimAdoptedWorld(MinecraftClient client) {
        if (TRIM.isEmpty() || trimDimension == null) {
            return;
        }

        ClientWorld world = WORLDS.get(trimDimension);
        if (world == null || client.getNetworkHandler() == null) {
            TRIM.clear();
            return;
        }

        // Through the vanilla handler, so lighting and the renderer are told exactly as they
        // would be for an unload the server had sent.
        withWorld(world, () -> {
            for (int i = 0; i < TRIM_PER_TICK && !TRIM.isEmpty(); i++) {
                try {
                    client.getNetworkHandler().onUnloadChunk(new UnloadChunkS2CPacket(TRIM.poll()));
                } catch (Throwable e) {
                    Overseer.LOGGER.error("Failed to trim a chunk from the world just left", e);
                    TRIM.clear();
                }
            }
        });
    }

    private static void disposeEmptyWorlds() {
        List<RegistryKey<World>> expired = null;

        for (Map.Entry<RegistryKey<World>, ClientWorld> entry : WORLDS.entrySet()) {
            boolean abandoned = ticks - LAST_STREAMED.getOrDefault(entry.getKey(), ticks) >= SILENT_TICKS_BEFORE_DISPOSAL;

            if (!abandoned && entry.getValue().getChunkManager().getLoadedChunkCount() > 0) {
                EMPTY_TICKS.remove(entry.getKey());
                continue;
            }

            if (abandoned || EMPTY_TICKS.merge(entry.getKey(), 1, Integer::sum) >= EMPTY_TICKS_BEFORE_DISPOSAL) {
                if (expired == null) {
                    expired = new ArrayList<>(1);
                }
                expired.add(entry.getKey());
            }
        }

        if (expired == null) {
            return;
        }

        for (RegistryKey<World> dimension : expired) {
            dispose(dimension);
            Overseer.LOGGER.info("Dropped idle secondary world {}", dimension.getValue());
        }
    }

    /** Whether a dimension has terrain to show. An empty world renders as bare sky. */
    public static boolean hasTerrain(RegistryKey<World> dimension) {
        ClientWorld world = WORLDS.get(dimension);
        return world != null && world.getChunkManager().getLoadedChunkCount() > 0;
    }

    /**
     * Whether the terrain around one spot of a dimension is loaded. A dimension can hold the far
     * side of one doorway and not of another standing right beside it.
     */
    public static boolean hasTerrainAt(RegistryKey<World> dimension, net.minecraft.util.math.Vec3d pos) {
        ClientWorld world = WORLDS.get(dimension);
        if (world == null) {
            return false;
        }

        ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(pos));
        return world.getChunkManager().isChunkLoaded(chunk.x, chunk.z);
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

            // Vanilla's own renderer is only ever set aside, never closed: it is registered as a
            // resource reloader and goes back into service on disconnect.
            if (renderer != vanillaRenderer) {
                renderer.close();
                releaseBuffers(renderer);
            }
        }

        RENDER_STATES.remove(dimension);
        EMPTY_TICKS.remove(dimension);
        LAST_STREAMED.remove(dimension);
        PortalViewRenderer.forget(dimension);

        if (dimension == trimDimension) {
            TRIM.clear();
            trimDimension = null;
        }

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

                if (renderer != vanillaRenderer) {
                    renderer.close();
                    releaseBuffers(renderer);
                }
            }
        }

        for (RenderDispatcher dispatcher : DISPATCHERS.values()) {
            dispatcher.close();
        }

        // If one of ours is standing in as the main renderer, put vanilla's back. The next
        // session then starts from exactly the objects vanilla created, whatever happened in
        // this one.
        if (vanillaRenderer != null && client.worldRenderer != vanillaRenderer) {
            WorldRenderer standIn = client.worldRenderer;

            client.worldRenderer = vanillaRenderer;
            client.gameRenderer.worldRenderState = vanillaState;

            standIn.setWorld(null);
            standIn.close();
            releaseBuffers(standIn);

            if (primaryDispatcher != null) {
                primaryDispatcher.close();
            }
        }

        primaryDispatcher = null;
        adopting = null;
        departed = null;
        TRIM.clear();
        trimDimension = null;

        PacketRedirectionClient.clear();

        WORLDS.clear();
        RENDERERS.clear();
        DISPATCHERS.clear();
        RENDER_STATES.clear();
        EMPTY_TICKS.clear();
        LAST_STREAMED.clear();
        swapped = false;
        lastDimension = null;
    }
}
