package net.damagejr05.overseer.core.cca.world;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.CommonTickingComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the two doorways.
 *
 * <p>Each is a {@link PortalPair}: one end in the overworld, one in the memory dimension. The
 * <em>fixed</em> pair is permanent; the <em>dynamic</em> pair expires. Only the overworld end of
 * either is ever placed by hand — the memory end is derived, so the memory dimension stays a
 * coherent map rather than a scatter of hand-placed doorways.
 *
 * <p>That makes the memory dimension a corridor as well as a canvas: step in through one doorway,
 * walk across whatever has been built, step out through the other.
 *
 * <pre>
 * /overseer doorway fixed  [pos] [facing]
 * /overseer doorway create &lt;pos&gt; &lt;facing&gt; [duration]
 * </pre>
 *
 * @author Chemthunder, Flex
 */
public class DoorwayComponent implements AutoSyncedComponent, CommonTickingComponent {
    public static final ComponentKey<DoorwayComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("doorway"),
            DoorwayComponent.class
    );

    /** How long a doorway takes to iris open, in ticks. */
    public static final int OPEN_TICKS = 20;

    private final World world;

    private @Nullable PortalPair fixed = null;
    private @Nullable PortalPair dynamic = null;

    private int dynamicDuration = 0;
    private int openTicks = OPEN_TICKS;

    /**
     * Server-side tick counter. This previously read OverseerClient.age, a client-only static that
     * is permanently zero on a dedicated server.
     */
    private int age = 0;

    public DoorwayComponent(World world) {
        this.world = world;
    }

    /**
     * The canonical store.
     *
     * <p>This is a per-world component but a pair spans two dimensions, so a per-world instance
     * would split it in half. The overworld's instance is the single source of truth and every
     * server-side caller goes through here rather than {@code KEY.get(someWorld)}.
     */
    public static DoorwayComponent get(MinecraftServer server) {
        return KEY.get(server.getOverworld());
    }

    /**
     * Server-side this resolves to the canonical overworld instance. Client-side there is no
     * server, so it falls back to the local world's synced copy.
     */
    public static DoorwayComponent get(World world) {
        MinecraftServer server = world.getServer();
        return server != null ? get(server) : KEY.get(world);
    }

    public void tick() {
        age++;

        // Particles belong to whichever world the anchor stands in, so every instance draws its
        // own. Everything else is state, and only the canonical instance owns that.
        if (age % 50 == 0 && world instanceof ServerWorld serverWorld) {
            for (PortalAnchor anchor : anchorsIn(world.getRegistryKey())) {
                Vec3d pos = anchor.center();
                serverWorld.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 3, 0, 0, 0, 0.1F);
            }
        }

        MinecraftServer server = world.getServer();
        if (server != null && world != server.getOverworld()) {
            return;
        }

        // Re-mirror periodically. Each world persists its own copy, so after a restart the
        // non-canonical ones can hold stale state until the next change would have fixed them.
        if (age % 20 == 0) {
            sync();
        }

        if (openTicks < OPEN_TICKS) {
            openTicks++;
            if (openTicks == OPEN_TICKS) {
                sync();
            }
        }

        if (dynamicDuration > 0) {
            dynamicDuration--;

            if (dynamicDuration == 0 && dynamic != null) {
                if (world instanceof ServerWorld serverWorld) {
                    for (PortalAnchor anchor : List.of(dynamic.outer(), dynamic.inner())) {
                        if (anchor.dimension() != world.getRegistryKey()) {
                            continue;
                        }
                        Vec3d pos = anchor.center();
                        serverWorld.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 15, 0, 0, 0, 0.3F);
                    }
                }

                dynamic = null;
                sync();
            }
        }
    }

    /** Every pair that currently exists. */
    public List<PortalPair> pairs() {
        List<PortalPair> result = new ArrayList<>(2);

        if (fixed != null) {
            result.add(fixed);
        }
        if (dynamic != null) {
            result.add(dynamic);
        }

        return result;
    }

    /** Every anchor standing in a given dimension. */
    public List<PortalAnchor> anchorsIn(net.minecraft.registry.RegistryKey<World> dimension) {
        List<PortalAnchor> result = new ArrayList<>(2);

        for (PortalPair pair : pairs()) {
            PortalAnchor anchor = pair.anchorIn(dimension);
            if (anchor != null) {
                result.add(anchor);
            }
        }

        return result;
    }

    /**
     * 0 while the opening is still growing, 1 once fully open.
     */
    public float openProgress() {
        return Math.min(1.0F, openTicks / (float) OPEN_TICKS);
    }

    public void sync() {
        KEY.sync(world);

        // A world component only syncs to players standing in that world, but the canonical copy
        // lives in the overworld and a pair spans two dimensions. Without mirroring, anyone in the
        // memory dimension receives that world's own empty component and sees no portals at all.
        MinecraftServer server = world.getServer();
        if (server == null || world != server.getOverworld()) {
            return;
        }

        for (ServerWorld other : server.getWorlds()) {
            if (other == world) {
                continue;
            }

            KEY.get(other).mirror(this);
        }
    }

    /**
     * Take on the canonical component's state and push it to this world's players.
     *
     * <p>Sets fields directly rather than going through the setters, which would recurse back into
     * {@link #sync()}.
     */
    private void mirror(DoorwayComponent source) {
        fixed = source.fixed;
        dynamic = source.dynamic;
        dynamicDuration = source.dynamicDuration;
        openTicks = source.openTicks;

        KEY.sync(world);
    }

    public void readData(ReadView readView) {
        fixed = readView.read("Fixed", PortalPair.CODEC).orElse(null);
        dynamic = readView.read("Dynamic", PortalPair.CODEC).orElse(null);

        dynamicDuration = readView.getInt("DynamicDuration", 0);
        openTicks = readView.getInt("OpenTicks", OPEN_TICKS);
    }

    public void writeData(WriteView writeView) {
        if (fixed != null) {
            writeView.put("Fixed", PortalPair.CODEC, fixed);
        }
        if (dynamic != null) {
            writeView.put("Dynamic", PortalPair.CODEC, dynamic);
        }

        writeView.putInt("DynamicDuration", dynamicDuration);
        writeView.putInt("OpenTicks", openTicks);
    }

    public @Nullable PortalPair getFixed() {
        return fixed;
    }

    public @Nullable PortalPair getDynamic() {
        return dynamic;
    }

    public int getDynamicDuration() {
        return dynamicDuration;
    }

    /**
     * Place the permanent doorway. Its memory end is always the origin.
     */
    public void setFixed(PortalAnchor overworld) {
        fixed = new PortalPair(overworld, ModDimensions.fixedMemoryAnchor());
        sync();
    }

    /**
     * Open the temporary doorway, replacing any current one. Its memory end is derived from the
     * overworld position, scaled down.
     */
    public void openDynamic(PortalAnchor overworld, int durationTicks) {
        dynamic = new PortalPair(overworld, ModDimensions.dynamicMemoryAnchor(overworld));
        dynamicDuration = durationTicks;
        openTicks = 0;
        sync();
    }

    public void closeDynamic() {
        dynamic = null;
        dynamicDuration = 0;
        sync();
    }
}
