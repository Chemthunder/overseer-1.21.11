package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * @author AcoYT
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Shadow @Final private List<ServerPlayerEntity> players;

//    @WrapWithCondition(
//            method = "onPlayerConnect",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"
//            )
//    )
//    private boolean mindsEye$dontAnnounceEthosJoin(PlayerManager instance, Text message, boolean overlay, ClientConnection connection, ServerPlayerEntity player) {
//        return !OverseerComponent.KEY.get(instance).isActive();
//    }
//
//    @ModifyReturnValue(method = "getPlayerNames", at = @At("RETURN"))
//    private String[] mindsEye$excludeEthosFromTabList(String[] original) {
//        for (int i = 0; i < this.players.size(); i++) {
//            if (!OverseerComponent.KEY.get(this.players.get(i)).isActive()) {
//                original[i] = this.players.get(i).getGameProfile().name();
//            }
//        }
//
//        return original;
//    }
}
