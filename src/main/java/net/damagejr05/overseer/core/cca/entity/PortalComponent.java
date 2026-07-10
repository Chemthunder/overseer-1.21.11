package net.damagejr05.overseer.core.cca.entity;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.CommonTickingComponent;

public class PortalComponent implements AutoSyncedComponent, CommonTickingComponent {
    public static final ComponentKey<PortalComponent> KEY = ComponentRegistry.getOrCreate(
            Overseer.id("portal"),
            PortalComponent.class
    );
    private final LivingEntity living;

    private int portalCooldown = 0;

    public PortalComponent(LivingEntity living) {
        this.living = living;
    }

    public void tick() {
        DoorwayComponent doorways = DoorwayComponent.KEY.get(living.getEntityWorld());

        ParticleEffect hitboxParticle = new DustParticleEffect(0xFFff0000, 6);

        if (living.getEntityWorld().getServer() != null) {
            ServerWorld overworld = living.getEntityWorld().getServer().getOverworld();

            if (doorways.getDoorwayPos() != null) {
                Vec3d pos = doorways.getDoorwayPos();

                Box collider = new Box(new BlockPos.Mutable(pos.x, pos.y, pos.z)).expand(1.5F, 3F, 0.1F);

                World world = living.getEntityWorld();

                if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.isSneaking()) {
                    {
                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.getCenter().x,
                                collider.getCenter().y,
                                collider.getCenter().z,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.maxY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.maxY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.minY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.maxY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.maxY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.minY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.minY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.minY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );
                    }
                }

                if (!living.isInCreativeMode()) {
                    if (collider.contains(living.getEntityPos()) && portalCooldown <= 0) {
                        if (doorways.getFixedPos() != null) {
                            Vec3d fPos = doorways.getFixedPos();

                            living.teleportTo(new TeleportTarget(
                                    overworld,
                                    fPos,
                                    living.getVelocity(),
                                    living.getYaw(),
                                    living.getPitch(),
                                    TeleportTarget.NO_OP
                            ));
                            portalCooldown = 90;
                            sync();
                        }
                    }
                }
            }

            if (doorways.getFixedPos() != null) {
                Vec3d pos = doorways.getFixedPos();

                Box collider = new Box(new BlockPos.Mutable(pos.x, pos.y, pos.z)).expand(5F, 5F, 5F);

                World world = living.getEntityWorld();

                if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.isSneaking()) {
                    {
                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.getCenter().x,
                                collider.getCenter().y,
                                collider.getCenter().z,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.maxY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.maxY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.minY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.maxY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.maxY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.minY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.maxX,
                                collider.minY,
                                collider.minZ,
                                0,
                                0,
                                0
                        );

                        world.addImportantParticleClient(
                                hitboxParticle,
                                false,
                                collider.minX,
                                collider.minY,
                                collider.maxZ,
                                0,
                                0,
                                0
                        );
                    }
                }

                if (!living.isInCreativeMode()) {
                    if (collider.contains(living.getEntityPos()) && portalCooldown <= 0) {
                        Vec3d fPos = null;

                        if (doorways.getDoorwayPos() != null) {
                            fPos = doorways.getDoorwayPos();
                        } else {
                            if (doorways.getLastDoorwayPos() != null) {
                                fPos = doorways.getLastDoorwayPos();
                            }
                        }

                        living.teleportTo(new TeleportTarget(
                                overworld,
                                fPos,
                                living.getVelocity(),
                                living.getYaw(),
                                living.getPitch(),
                                TeleportTarget.NO_OP
                        ));

                        portalCooldown = 90;
                        sync();
                    }
                }
            }
        }

        if (portalCooldown > 0) {
            portalCooldown--;
            if (portalCooldown == 0) {
                sync();
            }
        }
    }

    public void sync() {
        KEY.sync(living);
    }

    public void readData(ReadView readView) {
        portalCooldown = readView.getInt("PortalCooldown", 0);
    }

    public void writeData(WriteView writeView) {
        writeView.putInt("PortalCooldown", portalCooldown);
    }
}
