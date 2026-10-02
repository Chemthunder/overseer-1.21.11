package net.damagejr05.overseer.core.event;

import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * @author Chemthunder
 */
public class ApplyCursedEffectEvent implements ServerPlayerEvents.Join {
    public void onJoin(ServerPlayerEntity serverPlayerEntity) {
        if (Overseer.isDamage(serverPlayerEntity)) {
            OverseerComponent o = OverseerComponent.KEY.get(serverPlayerEntity);

            if (!o.isActive()) {
                o.setActive(true);
            }
        }
    }
}
