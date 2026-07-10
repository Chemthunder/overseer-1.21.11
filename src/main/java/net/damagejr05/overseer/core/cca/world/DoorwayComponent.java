package net.damagejr05.overseer.core.cca.world;

import net.damagejr05.overseer.core.Overseer;
import net.minecraft.particle.ParticleTypes;
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

public class DoorwayComponent implements AutoSyncedComponent, CommonTickingComponent {
    public static final ComponentKey<DoorwayComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("doorway"),
            DoorwayComponent.class
    );
    private final World world;

    public static final float MAX_DOORWAY_WIDTH = 3.0F;

    private @Nullable Vec3d fixedPos = null;
    private @Nullable Vec3d doorwayPos = null;

    private @Nullable Vec3d lastDoorwayPos = null;

    private int doorwayDuration = 0;

    private float doorwayWidth = 0.0F;
    private float doorwayRot = 0.0F;

    public DoorwayComponent(World world) {
        this.world = world;
    }

    public void tick() {
//        doorwayWidth = MathHelper.clamp(doorwayWidth, 0.0F, MAX_DOORWAY_WIDTH);

        if (doorwayWidth < 3.0F) {
            doorwayWidth += 0.05F;
            if (doorwayWidth == 3.0F) {
                sync();
            }
        }

        if (doorwayDuration > 0) {
            doorwayDuration--;
            if (doorwayDuration == 0) {
                if (world instanceof ServerWorld serverWorld) {
                    serverWorld.spawnParticles(
                            ParticleTypes.END_ROD,
                            doorwayPos.x,
                            doorwayPos.y,
                            doorwayPos.z,
                            15,
                            0,
                            0,
                            0,
                            0.3F
                    );
                }

                lastDoorwayPos = doorwayPos;
                doorwayPos = null;
                sync();
            }
        }
    }

    public void sync() {
        KEY.sync(world);
    }

    public void readData(ReadView readView) {
        fixedPos = readView.read("FixedPos", Vec3d.CODEC).orElse(null);
        doorwayPos = readView.read("DoorwayPos", Vec3d.CODEC).orElse(null);
        lastDoorwayPos = readView.read("LastDoorwayPos", Vec3d.CODEC).orElse(null);

        doorwayDuration = readView.getInt("DoorwayDuration", 0);
        doorwayWidth = readView.getFloat("DoorwayWidth", 0.0F);
        doorwayRot = readView.getFloat("DoorwayRot", 0.0F);
    }

    public void writeData(WriteView writeView) {
        if (fixedPos != null) {
            writeView.put("FixedPos", Vec3d.CODEC, fixedPos);
        }
        if (doorwayPos != null) {
            writeView.put("DoorwayPos", Vec3d.CODEC, doorwayPos);
        }
        if (lastDoorwayPos != null) {
            writeView.put("LastDoorwayPos", Vec3d.CODEC, lastDoorwayPos);
        }

        writeView.putInt("DoorwayDuration", doorwayDuration);
        writeView.putFloat("DoorwayWidth", doorwayWidth);
        writeView.putFloat("DoorwayRot", doorwayRot);
    }

    public @Nullable Vec3d getFixedPos() {
        return fixedPos;
    }

    public void setFixedPos(@Nullable Vec3d fixedPos) {
        this.fixedPos = fixedPos;
        sync();
    }

    public @Nullable Vec3d getDoorwayPos() {
        return doorwayPos;
    }

    public void setDoorwayPos(@Nullable Vec3d doorwayPos) {
        this.doorwayPos = doorwayPos;
        sync();
    }

    public @Nullable Vec3d getLastDoorwayPos() {
        return lastDoorwayPos;
    }

    public void setLastDoorwayPos(@Nullable Vec3d lastDoorwayPos) {
        this.lastDoorwayPos = lastDoorwayPos;
        sync();
    }

    public int getDoorwayDuration() {
        return doorwayDuration;
    }

    public void setDoorwayDuration(int doorwayDuration) {
        this.doorwayDuration = doorwayDuration;
        sync();
    }

    public float getDoorwayWidth() {
        return doorwayWidth;
    }

    public void setDoorwayWidth(float doorwayWidth) {
        this.doorwayWidth = doorwayWidth;
        sync();
    }

    public float getDoorwayRot() {
        return doorwayRot;
    }

    public void setDoorwayRot(float doorwayRot) {
        this.doorwayRot = doorwayRot;
        sync();
    }
}

/**
 * Has a fixed point that can be moved with a command, then can create other portals that lead to said fixed position
 * /overseer doorway fix ~ ~ ~
 * /overseer doorway create ~ ~ ~
 */