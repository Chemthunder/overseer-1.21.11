package net.damagejr05.overseer.core.cca;

import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.cca.entity.PortalComponent;
import net.damagejr05.overseer.core.cca.entity.VictimComponent;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.minecraft.entity.LivingEntity;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import org.ladysnake.cca.api.v3.world.WorldComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.world.WorldComponentInitializer;

/**
 * @author Chemthunder
 */
public class ModCCA implements EntityComponentInitializer, WorldComponentInitializer {
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry module) {
        module.registerForPlayers(
                OverseerComponent.KEY,
                OverseerComponent::new,
                RespawnCopyStrategy.ALWAYS_COPY
        );

        module.registerForPlayers(
                VictimComponent.KEY,
                VictimComponent::new,
                RespawnCopyStrategy.ALWAYS_COPY
        );

        module.beginRegistration(
                LivingEntity.class,
                PortalComponent.KEY
        ).respawnStrategy(RespawnCopyStrategy.ALWAYS_COPY).end(PortalComponent::new);
    }

    public void registerWorldComponentFactories(WorldComponentFactoryRegistry module) {
        module.register(DoorwayComponent.KEY, DoorwayComponent::new);
    }
}
