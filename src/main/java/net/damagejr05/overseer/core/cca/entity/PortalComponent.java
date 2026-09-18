package net.damagejr05.overseer.core.cca.entity;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.damagejr05.overseer.core.portal.PortalCrossing;
import net.damagejr05.overseer.core.portal.PortalPair;
import net.damagejr05.overseer.core.portal.PortalTransform;
import net.damagejr05.overseer.core.net.PortalTransitionPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.CommonTickingComponent;

/**
 * Moves a player through the portal pair.
 *
 * <p>Crossing is detected by sweeping the player's movement for this tick against the portal quad
 * rather than testing whether they are standing in a box, so it fires at the exact moment of
 * passage and cannot be tunnelled through at speed.
 *
 * @author Chemthunder, Flex
 */
public class PortalComponent implements AutoSyncedComponent, CommonTickingComponent {
    public static final ComponentKey<PortalComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("portal"),
            PortalComponent.class
    );

    private final PlayerEntity player;

    /** Body-centre position last tick, used as the start of the swept segment. */
    private @Nullable Vec3d previousPos = null;

    public PortalComponent(PlayerEntity player) {
        this.player = player;
    }

    public void tick() {
        Vec3d current = samplePos();

        Vec3d previous = previousPos;
        previousPos = current;

        if (previous == null || player.getEntityWorld().isClient()) {
            return;
        }

        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) {
            return;
        }

        // Portals are visible to everyone but only the Overseer may pass through them.
        if (!Overseer.isOverseer(player)) {
            return;
        }

        DoorwayComponent doorways = DoorwayComponent.get(server);

        // Each pair has an end in this dimension at most once, so at most one crossing can fire.
        for (PortalPair pair : doorways.pairs()) {
            PortalTransform transform = pair.from(player.getEntityWorld().getRegistryKey());

            if (transform != null && tryCross(server, transform, previous, current)) {
                return;
            }
        }
    }

    private boolean tryCross(MinecraftServer server, PortalTransform transform, Vec3d previous, Vec3d current) {
        PortalAnchor entry = transform.from();

        if (player.getEntityWorld().getRegistryKey() != entry.dimension()) {
            return false;
        }

        if (PortalCrossing.find(entry, previous, current) == null) {
            return false;
        }

        ServerWorld destination = server.getWorld(transform.to().dimension());
        if (destination == null) {
            Overseer.LOGGER.warn("Portal destination dimension {} is not loaded", transform.to().dimension().getValue());
            return false;
        }

        Vec3d exitCentre = transform.applyPoint(current);
        Vec3d exitVelocity = transform.applyVector(player.getVelocity());

        // samplePos() works from the body centre, so undo that offset to get a feet position.
        Vec3d exitFeet = exitCentre.subtract(0, player.getHeight() / 2.0, 0);

        // Pull the arrival chunk in synchronously. Chunk tickets load asynchronously, so without
        // this the player can land in a chunk that has not finished loading, drop straight through
        // the floor that is about to exist, and die in the void.
        destination.getChunk(BlockPos.ofFloored(exitFeet));

        // Warn the client before the dimension change lands, so it can skip the loading screen.
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, PortalTransitionPayload.INSTANCE);
        }

        player.teleportTo(new TeleportTarget(
                destination,
                exitFeet,
                exitVelocity,
                transform.applyYaw(player.getYaw()),
                player.getPitch(),
                TeleportTarget.NO_OP
        ));

        // Carry momentum across the seam; teleportTo alone does not preserve fall distance state.
        player.setVelocity(exitVelocity);
        player.fallDistance = 0.0;

        // The swept segment must not span the teleport, or the next tick would look like a huge
        // jump straight back through the exit portal.
        previousPos = samplePos();

        return true;
    }

    private Vec3d samplePos() {
        return player.getEntityPos().add(0, player.getHeight() / 2.0, 0);
    }

    public void sync() {
        KEY.sync(player);
    }

    public void readData(ReadView readView) {
    }

    public void writeData(WriteView writeView) {
    }
}
