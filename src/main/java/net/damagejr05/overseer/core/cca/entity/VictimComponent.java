package net.damagejr05.overseer.core.cca.entity;

import net.damagejr05.overseer.core.Overseer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/**
 * @author Chemthunder
 */
public class VictimComponent implements AutoSyncedComponent {
    public static final ComponentKey<VictimComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("victim"),
            VictimComponent.class
    );
    private final PlayerEntity player;

    private boolean canSee = false;

    public VictimComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        KEY.sync(player);
    }

    public void readData(ReadView readView) {
        canSee = readView.getBoolean("CanSee", canSee);
    }

    public void writeData(WriteView writeView) {
        writeView.putBoolean("CanSee", canSee);
    }

    public boolean isCanSee() {
        return canSee;
    }

    public void setCanSee(boolean canSee) {
        this.canSee = canSee;
        sync();
    }
}
