package net.damagejr05.overseer.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Chemthunder
 */
@Mixin(value = PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Shadow @Final private MinecraftClient client;

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/hud/PlayerListHud;collectPlayerEntries()Ljava/util/List;"
            )
    )
    private List<PlayerListEntry> overseer$removeFromTabList(PlayerListHud instance, Operation<List<PlayerListEntry>> original) {
        List<PlayerListEntry> entries = new ArrayList<>(original.call(instance));
        ClientWorld world = this.client.world;

        if (world != null) {
            for (PlayerListEntry entry : entries) {
                PlayerEntity player = world.getPlayerByUuid(entry.getProfile().id());

                if (player != null && OverseerComponent.KEY.get(player).isActive()) {
                    entries.remove(entry);
                    return entries;
                }
            }
        }
        return original.call(instance);
    }
}
