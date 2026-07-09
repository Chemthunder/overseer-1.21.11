package net.damagejr05.overseer.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * @author AcoYT
 */
@Mixin(value = PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Shadow @Final private MinecraftClient client;

//    @ModifyReturnValue(method = "collectPlayerEntries", at = @At("RETURN"))
//    private List<PlayerListEntry> mindsEye$excludeEthosFromTabList(List<PlayerListEntry> original) {
//        List<PlayerListEntry> entries = new ArrayList<>(original);
//        if (this.client.player == null) {
//            entries.removeIf(this::overseer$validToRemove);
//        }
//
//        return entries;
//    }
//
//    @Unique
//    private boolean overseer$validToRemove(PlayerListEntry entry) {
//        ClientWorld world = this.client.world;
//        if (world != null) {
//            PlayerEntity player = world.getPlayerByUuid(entry.getProfile().id());
//            return player == null || !OverseerComponent.KEY.get(player).isActive();
//        }
//
//        return true;
//    }
}
