package net.damagejr05.overseer.core.cca.world;

import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public class PortalComponent implements AutoSyncedComponent {
    public void readData(ReadView readView) {

    }

    public void writeData(WriteView writeView) {

    }
}

/**
 * Has a fixed point that can be moved with a command, then can create other portals that lead to said fixed position
 * /overseer doorway fix ~ ~ ~
 * /overseer doorway create ~ ~ ~
 */