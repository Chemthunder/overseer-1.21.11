package net.damagejr05.overseer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
