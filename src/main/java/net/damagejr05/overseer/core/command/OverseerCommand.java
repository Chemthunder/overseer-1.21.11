package net.damagejr05.overseer.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.damagejr05.overseer.core.Overseer;
import net.damagejr05.overseer.core.cca.entity.OverseerComponent;
import net.damagejr05.overseer.core.cca.entity.VictimComponent;
import net.damagejr05.overseer.core.cca.world.DoorwayComponent;
import net.damagejr05.overseer.core.index.ModDimensions;
import net.damagejr05.overseer.core.portal.PortalAnchor;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * @author Chemthunder
 */
public class OverseerCommand implements CommandRegistrationCallback {
    private static final String[] CARDINAL_NAMES = {"north", "south", "east", "west"};

    /** Used when /overseer doorway create is run without an explicit duration. */
    private static final int DEFAULT_DURATION_SECONDS = 120;

    private static int openDynamic(CommandContext<ServerCommandSource> context, int seconds) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
        Direction facing = cardinal(context);

        // Same convention as the permanent doorway: base on the block's floor, so the opening
        // stands on the ground rather than sinking a block into it.
        PortalAnchor outer = PortalAnchor.of(
                context.getSource().getWorld().getRegistryKey(),
                blockFloor(pos).add(0, PortalAnchor.DEFAULT_HEIGHT / 2.0, 0),
                facing
        );

        DoorwayComponent.get(context.getSource().getServer()).openDynamic(outer, seconds * 20);

        Vec3d inner = ModDimensions.dynamicMemoryAnchor(outer).center();

        context.getSource().sendFeedback(
                () -> Text.literal(String.format(
                        "Doorway opened at %s facing %s for %ds, linked to memory %.1f %.1f %.1f",
                        pos.toShortString(), facing.asString(), seconds, inner.x, inner.y, inner.z
                )),
                true
        );
        return 1;
    }

    /**
     * Places the permanent doorway's overworld end. Its memory end is derived, never given.
     */
    /** Centre of a block in X/Z, its floor in Y — where a player standing there would be. */
    private static Vec3d blockFloor(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static int placeFixed(CommandContext<ServerCommandSource> context, Vec3d centre, Direction facing) {
        // Raise it so its base sits at the given position rather than its middle.
        Vec3d anchorCentre = centre.add(0, PortalAnchor.DEFAULT_HEIGHT / 2.0, 0);

        DoorwayComponent.get(context.getSource().getServer()).setFixed(
                PortalAnchor.of(context.getSource().getWorld().getRegistryKey(), anchorCentre, facing)
        );

        Vec3d inner = ModDimensions.fixedMemoryAnchor().center();

        context.getSource().sendFeedback(
                () -> Text.literal(String.format(
                        "Permanent doorway anchored at %.1f %.1f %.1f facing %s, linked to the memory origin (%.1f %.1f %.1f)",
                        centre.x, centre.y, centre.z, facing.asString(), inner.x, inner.y, inner.z
                )),
                true
        );

        return 1;
    }

    private static final SuggestionProvider<ServerCommandSource> CARDINALS =
            (context, builder) -> CommandSource.suggestMatching(CARDINAL_NAMES, builder);

    private static final SimpleCommandExceptionType NOT_CARDINAL =
            new SimpleCommandExceptionType(Text.literal("Facing must be north, south, east or west"));

    private static final SimpleCommandExceptionType NO_MEMORY_DIMENSION =
            new SimpleCommandExceptionType(Text.literal("The memory dimension is not loaded"));

    /**
     * Portals snap to the four horizontal directions, so anything else is rejected outright rather
     * than silently rounded.
     */
    private static Direction cardinal(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Direction direction = Direction.byId(StringArgumentType.getString(context, "facing"));

        if (direction == null || direction.getAxis().isVertical()) {
            throw NOT_CARDINAL.create();
        }

        return direction;
    }

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

                .then(literal("doorway")
                        .then(literal("fixed")
                                // No arguments: at your feet, facing the way you are looking.
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

                                    return placeFixed(
                                            context,
                                            player.getEntityPos(),
                                            player.getHorizontalFacing()
                                    );
                                })
                                .then(argument("pos", BlockPosArgumentType.blockPos())
                                        .then(argument("facing", StringArgumentType.word())
                                                .suggests(CARDINALS)
                                                .executes(context -> placeFixed(
                                                        context,
                                                        // Centred on the block in X/Z but resting
                                                        // on its floor in Y, so an explicit
                                                        // position sits exactly where standing
                                                        // there and running the no-argument form
                                                        // would put it. toCenterPos alone would
                                                        // raise it half a block.
                                                        blockFloor(BlockPosArgumentType.getBlockPos(context, "pos")),
                                                        cardinal(context)
                                                )))))
                        .then(literal("create")
                                .then(argument("pos", BlockPosArgumentType.blockPos())
                                        .then(argument("facing", StringArgumentType.word())
                                                .suggests(CARDINALS)
                                                // Duration is optional so a quick doorway does not
                                                // need a number every time.
                                                .executes(context -> openDynamic(context, DEFAULT_DURATION_SECONDS))
                                                .then(argument("duration", IntegerArgumentType.integer(1))
                                                        .executes(context -> openDynamic(
                                                                context,
                                                                IntegerArgumentType.getInteger(context, "duration")
                                                        ))))))
                        .then(literal("destroy").executes(context -> {
                            DoorwayComponent.get(context.getSource().getServer()).closeDynamic();
                            context.getSource().sendFeedback(() -> Text.literal("Doorway closed."), true);
                            return 1;
                        }))
                        .then(literal("tp").executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            ServerWorld memory = ModDimensions.memory(context.getSource().getServer());

                            if (memory == null) {
                                throw NO_MEMORY_DIMENSION.create();
                            }

                            // Always the memory origin: that is where the permanent doorway's
                            // memory end lives, whether or not one has been placed yet.
                            Vec3d target = ModDimensions.fixedMemoryAnchor().center();

                            player.teleportTo(new TeleportTarget(
                                    memory, target, Vec3d.ZERO,
                                    player.getYaw(), player.getPitch(),
                                    TeleportTarget.NO_OP
                            ));
                            return 1;
                        }))
                )
        );
    }

    public static boolean isViable(ServerCommandSource source) {
        return Overseer.isOverseer(source.getPlayer());
    }
}
