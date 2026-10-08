package com.lealex.alchyrift.gate;

import com.lealex.alchyrift.Config;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RiftTravel;
import com.lealex.alchyrift.room.RoomBuilder;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyrift.room.RoomLoading;
import com.lealex.alchyrift.room.RoomRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** The server side of the gate screen: what a player is shown, and what their choices do. */
public final class GateControl {
    private GateControl() {}

    /** A player right-clicked a formed gate: show them its screen. A gate nobody owns yet becomes theirs. */
    public static void open(ServerPlayer player, RiftAnchorBlockEntity gate) {
        if (gate.getOwner() == null) gate.setOwner(player.getUUID(), player.getScoreboardName());
        send(player, gate);
    }

    private static void send(ServerPlayer player, RiftAnchorBlockEntity gate) {
        RiftRooms rooms = RiftRooms.get(player.level().getServer());
        GlobalPos here = GlobalPos.of(player.level().dimension(), gate.getBlockPos());
        boolean gateMine = player.getUUID().equals(gate.getOwner());
        List<GateMessages.RoomEntry> roomChoices = new ArrayList<>();
        List<GateMessages.GateEntry> gateChoices = new ArrayList<>();
        Optional<GateMessages.RoomEntry> room = Optional.empty();
        List<String> shared = List.of();
        String exitName = "";
        int rules = 0;
        int roomTier = 0, growCost = -1;
        String growExtra = "";
        List<String> online = new ArrayList<>();
        int state = GateMessages.UNSET;

        if (gate.getRoomId() >= 0) {
            state = GateMessages.TO_ROOM;
            RiftRooms.Room set = rooms.get(gate.getRoomId());
            if (set != null) {
                room = Optional.of(entry(set, player));
                if (set.ownedBy(player.getUUID())) {
                    shared = set.shared();
                    rules = set.rules();
                    for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
                        if (other != player) online.add(clip(other.getScoreboardName()));
                    }
                    roomTier = set.tier();
                    growCost = canGrow(set) ? Config.growCost(set.tier()) : -1;
                    Item extra = canGrow(set) ? Config.growExtra(set.tier()) : null;
                    if (extra != null) growExtra = BuiltInRegistries.ITEM.getKey(extra).toString();
                }
            }
        } else if (gate.getExitGate() != null) {
            state = GateMessages.TO_GATE;
            RiftRooms.Gate exit = rooms.gate(gate.getExitGate());
            if (exit != null) exitName = clip(exit.name());
        } else {
            for (RiftRooms.Room usable : rooms.usableBy(player.getUUID(), player.getScoreboardName())) roomChoices.add(entry(usable, player));
            for (RiftRooms.Gate other : rooms.gatesOf(player.getUUID())) {
                if (!other.pos().equals(here)) gateChoices.add(new GateMessages.GateEntry(other.pos(), clip(other.name())));
            }
        }
        PacketDistributor.sendToPlayer(player, new GateMessages.Screen(gate.getBlockPos(), clip(gate.getGateName()), gateMine,
                gate.gate().changeable(), state,
                roomChoices, gateChoices, room, shared, exitName, rules, gate.hasLightning(), roomTier, growCost,
                BuiltInRegistries.ITEM.getKey(Config.growItem()).toString(), growExtra, online));
    }

    /** Whether a room can still be grown: below the code's largest size and the config's. */
    private static boolean canGrow(RiftRooms.Room room) {
        return room.tier() < Math.min(RoomLayout.MAX_TIER, Config.maxRoomTier());
    }

    /**
     * The room's owner asked to grow it: takes the price (the grow items, and for some sizes one more item, a nether
     * star by default for 3 x 3 chunks) from their inventory (nothing in creative), then builds the
     * larger shell around what is there. Nothing happens, and nothing is taken, if they can't pay.
     */
    private static void grow(ServerPlayer player, RiftRooms rooms, RiftRooms.Room room) {
        if (!canGrow(room)) return;
        ServerLevel rift = player.level().getServer().getLevel(RoomLayout.DIMENSION);
        if (rift == null) return;
        Item item = Config.growItem();
        int cost = Config.growCost(room.tier());
        Item extra = Config.growExtra(room.tier());
        if (!player.getAbilities().instabuild) {
            // Both prices are checked before anything is taken
            boolean paid = (cost <= 0 || player.getInventory().countItem(item) >= cost)
                    && (extra == null || player.getInventory().countItem(extra) >= (extra == item ? cost + 1 : 1));
            if (!paid) {
                tell(player, extra == null
                        ? Component.translatable("message.alchyrift.room.grow_missing", cost, new ItemStack(item).getHoverName())
                        : Component.translatable("message.alchyrift.room.grow_missing_extra", cost, new ItemStack(item).getHoverName(),
                                new ItemStack(extra).getHoverName()));
                return;
            }
            if (cost > 0) player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), cost, player.inventoryMenu.getCraftSlots());
            if (extra != null) player.getInventory().clearOrCountMatchingItems(stack -> stack.is(extra), 1, player.inventoryMenu.getCraftSlots());
        }
        RiftRooms.Room grown = rooms.setTier(room, room.tier() + 1);
        RoomBuilder.grow(rift, room.id(), room.tier(), grown.tier());
        RoomLoading.refresh(player.level().getServer());
        int chunks = RoomLayout.chunks(grown.tier());
        tell(player, Component.translatable("message.alchyrift.room.grown", chunks, chunks));
    }

    private static GateMessages.RoomEntry entry(RiftRooms.Room room, ServerPlayer player) {
        return new GateMessages.RoomEntry(room.id(), clip(room.displayName()), clip(room.ownerName()), room.ownedBy(player.getUUID()));
    }

    /** A choice made on the screen. Everything is checked again here: the screen is never trusted. */
    public static void handle(ServerPlayer player, GateMessages.Action action) {
        ServerLevel level = player.level();
        if (!level.isLoaded(action.gate()) || !(level.getBlockEntity(action.gate()) instanceof RiftAnchorBlockEntity gate)) return;
        if (!gate.isFormed() || !gate.withinReach(player)) return;
        RiftRooms rooms = RiftRooms.get(level.getServer());
        String text = clip(action.text().strip());
        RiftRooms.Room room = gate.getRoomId() < 0 ? null : rooms.get(gate.getRoomId());
        boolean roomMine = room != null && room.ownedBy(player.getUUID());

        switch (action.kind()) {
            case CREATE_ROOM -> {
                if (!text.isEmpty()) rooms.create(0, player.getUUID(), player.getScoreboardName(), text);
            }
            case CHOOSE_ROOM -> {
                RiftRooms.Room chosen = rooms.get(action.number());
                if (gate.isSet() || chosen == null || !chosen.usableBy(player.getUUID(), player.getScoreboardName())) break;
                rooms.claim(chosen, player.getUUID(), player.getScoreboardName());
                gate.setDestinationRoom(chosen.id());
                tell(player, Component.translatable("message.alchyrift.gate.opened", chosen.displayName()));
            }
            case CHOOSE_GATE -> {
                RiftRooms.Gate exit = action.target().map(rooms::gate).orElse(null);
                GlobalPos here = GlobalPos.of(level.dimension(), gate.getBlockPos());
                if (gate.isSet() || exit == null || !exit.owner().equals(player.getUUID()) || exit.pos().equals(here)) break;
                gate.setExitGate(exit.pos());
                tell(player, Component.translatable("message.alchyrift.gate.opened", exit.name()));
            }
            case RENAME_ROOM -> {
                if (roomMine && !text.isEmpty()) rooms.rename(room, text);
            }
            case SHARE -> {
                // Only with someone who is here now (the screen lists them; the name is checked again)
                ServerPlayer guest = text.isEmpty() ? null : level.getServer().getPlayerList().getPlayerByName(text);
                if (roomMine && guest != null && guest != player) rooms.share(room, guest.getScoreboardName());
            }
            case UNSHARE -> {
                if (roomMine) rooms.unshare(room, text);
            }
            case RENAME_GATE -> {
                if (player.getUUID().equals(gate.getOwner()) && !text.isEmpty()) gate.setGateName(text);
            }
            case RESET -> {
                if (player.getUUID().equals(gate.getOwner())) gate.clearDestination();
            }
            case TOGGLE_LIGHTNING -> {
                if (player.getUUID().equals(gate.getOwner())) gate.setLightning(!gate.hasLightning());
            }
            case TOGGLE_RULE -> {
                RoomRule[] all = RoomRule.values();
                if (roomMine && action.number() >= 0 && action.number() < all.length) rooms.toggle(room, all[action.number()]);
            }
            case GROW_ROOM -> {
                if (roomMine) grow(player, rooms, room);
            }
            case DELETE_ROOM -> {
                RiftRooms.Room doomed = rooms.get(action.number());
                if (doomed != null && doomed.ownedBy(player.getUUID())) delete(player, rooms, doomed);
            }
        }
        send(player, gate);
    }

    /**
     * A room is deleted by its owner: whoever is inside is sent out, everything in it is destroyed with it, and the
     * world forgets it. Gates that led to it notice by themselves and close (RiftAnchorBlockEntity.tickFormed).
     */
    private static void delete(ServerPlayer player, RiftRooms rooms, RiftRooms.Room room) {
        ServerLevel rift = player.level().getServer().getLevel(RoomLayout.DIMENSION);
        if (rift == null) return;
        BlockPos origin = RoomLayout.origin(room.id()), far = RoomLayout.farCorner(room.id(), room.tier());
        for (ServerPlayer inside : List.copyOf(rift.players())) {
            BlockPos at = inside.blockPosition();
            if (at.getX() >= origin.getX() && at.getX() <= far.getX() && at.getZ() >= origin.getZ() && at.getZ() <= far.getZ()) {
                RiftTravel.leave(inside);
            }
        }
        String name = room.displayName();
        rooms.remove(room);
        RoomBuilder.erase(rift, room.id(), room.tier());
        RoomLoading.refresh(player.level().getServer());
        tell(player, Component.translatable("message.alchyrift.room.deleted", name));
    }

    private static String clip(String text) {
        return text.length() > GateMessages.MAX_NAME ? text.substring(0, GateMessages.MAX_NAME) : text;
    }

    private static void tell(ServerPlayer player, Component message) {
        player.sendSystemMessage(message, true);
    }
}
