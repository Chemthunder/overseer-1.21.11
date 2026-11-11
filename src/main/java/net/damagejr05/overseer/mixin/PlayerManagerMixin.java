package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * @author Chemthunder
 */
@Mixin(value = PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Shadow @Final private List<ServerPlayerEntity> players;

    @ModifyReturnValue(method = "getPlayerNames", at = @At(value = "RETURN"))
    private String[] overseer$removeFromTabList(String[] original) {
        for (int i = 0; i < this.players.size(); i++) {
            if (!OverseerComponent.KEY.get(this.players.get(i)).isActive()) {
                original[i] = this.players.get(i).getGameProfile().name();
            }
        }
        return original;
    }

    /**
     * The Overseer arrives unannounced.
     *
     * <p>As far as anyone on the server knows, that player left for good the moment they became
     * the Overseer: {@code /overseer kill} announces it, in the game's own words. A yellow
     * "joined the game" the next time they log in would undo the whole pretence in one line.
     *
     * <p>Decided by the same flag that keeps them off the tab list, so the two cannot disagree.
     * It is read from the player's saved data, which has been loaded by the time this runs.
     * The line is withheld from the server console as well, since the console is fed by the same
     * broadcast; the log still records the login itself.
     *
     * @author Flex
     */
    @WrapWithCondition(
            method = "onPlayerConnect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"
            )
    )
    private boolean overseer$arriveUnannounced(PlayerManager manager, Text message, boolean overlay,
                                               ClientConnection connection, ServerPlayerEntity player,
                                               ConnectedClientData clientData) {
        return !OverseerComponent.KEY.get(player).isActive();
    }
}
