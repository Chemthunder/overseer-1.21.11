package net.damagejr05.overseer.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.cca.entity.VictimComponent;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

/**
 * @author Chemthunder
 */
public class OverseerCommand implements CommandRegistrationCallback {
    public void register(CommandDispatcher<ServerCommandSource> commandDispatcher, CommandRegistryAccess commandRegistryAccess, CommandManager.RegistrationEnvironment registrationEnvironment) {
        commandDispatcher.register(literal("overseer").requires(OverseerCommand::isViable)
                .then(literal("prime").executes(context -> {
                    PlayerEntity player = context.getSource().getPlayerOrThrow();
                    OverseerComponent o = OverseerComponent.KEY.get(player);

                    o.setPrimed(!o.isPrimed());
                    return 1;
                }))
                .then(literal("kill").executes(context -> {
                    PlayerEntity player = context.getSource().getPlayerOrThrow();
                    OverseerComponent o = OverseerComponent.KEY.get(player);

                    o.kill();
                    return 1;
                }))
                .then(literal("flicker").then(argument("state", BoolArgumentType.bool()).executes(context -> {
                    PlayerEntity player = context.getSource().getPlayerOrThrow();
                    OverseerComponent o = OverseerComponent.KEY.get(player);

                    o.setFlicker(BoolArgumentType.getBool(context, "state"));
                    return 1;
                })))
                .then(literal("invisible").then(argument("state", BoolArgumentType.bool()).executes(context -> {
                    PlayerEntity player = context.getSource().getPlayerOrThrow();
                    OverseerComponent o = OverseerComponent.KEY.get(player);

                    o.setInvisible(BoolArgumentType.getBool(context, "state"));
                    return 1;
                })))
                .then(literal("active").then(argument("state", BoolArgumentType.bool()).executes(context -> {
                    PlayerEntity player = context.getSource().getPlayerOrThrow();
                    OverseerComponent o = OverseerComponent.KEY.get(player);

                    o.setActive(BoolArgumentType.getBool(context, "state"));
                    return 1;
                })))

                .then(literal("setViewer").then(argument("player", EntityArgumentType.player()).then(argument("state", BoolArgumentType.bool()).executes(context -> {
                    PlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                    VictimComponent.KEY.get(player).setCanSee(BoolArgumentType.getBool(context, "state"));
                    return 1;
                }))))
        );
    }

    public static boolean isViable(ServerCommandSource source) {
        return Overseer.isDamage(source.getPlayer()) || FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
