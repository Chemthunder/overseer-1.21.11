package net.damagejr05.overseer.core.cca.entity;

import dev.rbn.chroma.client.screenshake.Screenshake;
import dev.rbn.chroma.client.screenshake.ScreenshakeInstance;
import net.damagejr05.overseer.core.Overseer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.attribute.timeline.EasingType;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.CommonTickingComponent;

import java.util.List;

/**
 * @author Chemthunder
 */
@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class OverseerComponent implements AutoSyncedComponent, CommonTickingComponent {
    public static final ComponentKey<OverseerComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("overseer"),
            OverseerComponent.class
    );
    private final PlayerEntity player;

    private boolean primed = false;
    private boolean active = false;
    private boolean invisible = false;
    private boolean flicker = false;

    private int deathTicks = 0;

    public OverseerComponent(PlayerEntity player) {
        this.player = player;
    }

    public void tick() {
        if (deathTicks > 0) {
            deathTicks--;
            if (deathTicks == 0) {
                kill();
                sync();
            }
        }

        if (flicker) {
            List<LivingEntity> surroundings = player.getEntityWorld().getEntitiesByClass(
                    LivingEntity.class,
                    new Box(player.getBlockPos()).expand(2),
                    entity -> entity != player
            );

            if (surroundings.isEmpty()) {
                if (!invisible) {
                    invisible = true;
                    sync();
                }
            } else {
                if (invisible) {
                    invisible = false;
                    sync();
                }
            }
        }
    }

    public void kill() {
        World world = player.getEntityWorld();
        MinecraftServer server = world.getServer();

        world.getPlayers().forEach(capture -> {
            Screenshake.getInstance().screenshake(1.0F, 60, EasingType.OUT_ELASTIC);
        });

        if (server != null) {
            server.getPlayerManager().broadcast(
                    Text.translatable("broadcast.ban.death", player.getNameForScoreboard()).formatted(Formatting.YELLOW),
                    false
            );

            server.getPlayerManager().broadcast(
                    Text.translatable("broadcast.ban.disconnect", player.getNameForScoreboard()).formatted(Formatting.YELLOW),
                    false
            );

            player.clearStatusEffects();

            active = true;
            primed = false;
            invisible = true;
            flicker = false;
            sync();
        }
    }

    public void sync() {
        KEY.sync(player);
    }

    public void readData(ReadView readView) {
        primed = readView.getBoolean("Primed", false);
        active = readView.getBoolean("Active", false);
        invisible = readView.getBoolean("Invisible", false);
        flicker = readView.getBoolean("Flicker", false);
    }

    public void writeData(WriteView writeView) {
        writeView.putBoolean("Primed", primed);
        writeView.putBoolean("Active", active);
        writeView.putBoolean("Invisible", invisible);
        writeView.putBoolean("Flicker", flicker);
    }

    public boolean isPrimed() {
        return primed;
    }

    public void setPrimed(boolean primed) {
        this.primed = primed;
        sync();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        sync();
    }

    public int getDeathTicks() {
        return deathTicks;
    }

    public void setDeathTicks(int deathTicks) {
        this.deathTicks = deathTicks;
        sync();
    }

    public boolean isInvisible() {
        return invisible;
    }

    public void setInvisible(boolean invisible) {
        this.invisible = invisible;
        sync();
    }

    public boolean isFlicker() {
        return flicker;
    }

    public void setFlicker(boolean flicker) {
        this.flicker = flicker;
        sync();
    }
}
