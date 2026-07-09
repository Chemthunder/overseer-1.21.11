package net.damagejr05.overseer.core.cca;

import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.cca.entity.VictimComponent;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;

/**
 * @author Chemthunder
 */
public class ModCCA implements EntityComponentInitializer {
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
    }
}
