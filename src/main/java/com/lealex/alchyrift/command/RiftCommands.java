package com.lealex.alchyrift.command;

import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RiftTravel;
import com.lealex.alchyrift.room.RoomBuilder;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyrift.room.RoomLoading;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Operator commands to work on rooms without a gate:
 * /alchyrift room create [tier], goto <id>, grow <id>, list, leave.
 */
public final class RiftCommands {
    private RiftCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("alchyrift")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("room")
                        .then(Commands.literal("create")
                                .executes(c -> create(c.getSource(), 0))
                                .then(Commands.argument("tier", IntegerArgumentType.integer(0, RoomLayout.MAX_TIER))
                                        .executes(c -> create(c.getSource(), IntegerArgumentType.getInteger(c, "tier")))))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(c -> goTo(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
                        .then(Commands.literal("grow")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(c -> grow(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
                        .then(Commands.literal("list").executes(c -> list(c.getSource())))
                        .then(Commands.literal("leave").executes(c -> leave(c.getSource())))));
    }

    private static int create(CommandSourceStack source, int tier) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel rift = rift(source);
        if (rift == null) return 0;
        RiftRooms.Room room = RiftRooms.get(source.getServer()).create(tier, player.getUUID(), player.getScoreboardName(), "");
        RoomBuilder.build(rift, room);
        RoomLoading.refresh(source.getServer());
        enter(player, rift, room.id());
        source.sendSuccess(() -> Component.translatable("commands.alchyrift.room.created", room.id(), room.tier()), true);
        return 1;
    }

    private static int goTo(CommandSourceStack source, int id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel rift = rift(source);
        if (rift == null) return 0;
        if (RiftRooms.get(source.getServer()).get(id) == null) {
            source.sendFailure(Component.translatable("commands.alchyrift.room.unknown", id));
            return 0;
        }
        enter(player, rift, id);
        source.sendSuccess(() -> Component.translatable("commands.alchyrift.room.entered", id), false);
        return 1;
    }

    private static int grow(CommandSourceStack source, int id) {
        ServerLevel rift = rift(source);
        if (rift == null) return 0;
        RiftRooms rooms = RiftRooms.get(source.getServer());
        RiftRooms.Room room = rooms.get(id);
        if (room == null) {
            source.sendFailure(Component.translatable("commands.alchyrift.room.unknown", id));
            return 0;
        }
        if (room.tier() >= RoomLayout.MAX_TIER) {
            source.sendFailure(Component.translatable("commands.alchyrift.room.max_tier", id));
            return 0;
        }
        RiftRooms.Room grown = rooms.setTier(room, room.tier() + 1);
        RoomBuilder.grow(rift, id, room.tier(), grown.tier());
        RoomLoading.refresh(source.getServer());
        source.sendSuccess(() -> Component.translatable("commands.alchyrift.room.grown", id, grown.tier()), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        String rooms = RiftRooms.get(source.getServer()).all().stream()
                .map(room -> room.id() + " (tier " + room.tier() + ")")
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.translatable("commands.alchyrift.room.list", rooms), false);
        return 1;
    }

    /** Out of the room, as if through its crack. */
    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RiftTravel.leave(player);
        return 1;
    }

    private static void enter(ServerPlayer player, ServerLevel rift, int id) {
        Vec3 entry = RoomLayout.entry(id);
        player.teleportTo(rift, entry.x, entry.y, entry.z, Set.of(), RoomLayout.ENTRY_YAW, 0f, true);
    }

    private static ServerLevel rift(CommandSourceStack source) {
        ServerLevel rift = source.getServer().getLevel(RoomLayout.DIMENSION);
        if (rift == null) source.sendFailure(Component.translatable("commands.alchyrift.room.no_dimension"));
        return rift;
    }
}
