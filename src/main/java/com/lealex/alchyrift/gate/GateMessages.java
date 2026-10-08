package com.lealex.alchyrift.gate;

import com.lealex.alchyrift.AlchyRift;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * What goes between the server and the gate screen: the server tells a player what a gate is and what it may lead
 * to, the player's screen sends back what they chose.
 */
@EventBusSubscriber(modid = AlchyRift.MODID)
public final class GateMessages {
    private GateMessages() {}

    /** Longest room, gate or player name. */
    public static final int MAX_NAME = 32;

    /** A gate that leads nowhere yet / to a room / to another gate. */
    public static final int UNSET = 0, TO_ROOM = 1, TO_GATE = 2;

    /** Client: opens (or refreshes) the gate screen. Set by the client entry point, null on a dedicated server. */
    public static @Nullable Consumer<Screen> clientOpener;

    /** A room a player may choose. {@code mine}: the player owns it. */
    public record RoomEntry(int id, String name, String owner, boolean mine) {
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeUtf(name, MAX_NAME);
            buf.writeUtf(owner, MAX_NAME);
            buf.writeBoolean(mine);
        }

        static RoomEntry read(RegistryFriendlyByteBuf buf) {
            return new RoomEntry(buf.readVarInt(), buf.readUtf(MAX_NAME), buf.readUtf(MAX_NAME), buf.readBoolean());
        }
    }

    /** Another gate a player may choose as an exit. */
    public record GateEntry(GlobalPos pos, String name) {
        void write(RegistryFriendlyByteBuf buf) {
            GlobalPos.STREAM_CODEC.encode(buf, pos);
            buf.writeUtf(name, MAX_NAME);
        }

        static GateEntry read(RegistryFriendlyByteBuf buf) {
            return new GateEntry(GlobalPos.STREAM_CODEC.decode(buf), buf.readUtf(MAX_NAME));
        }
    }

    /**
     * Server to client: everything the gate screen shows.
     *
     * @param changeable a greater gate: its owner may choose the destination again
     * @param state    {@link #UNSET}, {@link #TO_ROOM} or {@link #TO_GATE}
     * @param rooms    unset gate: the rooms to choose from
     * @param gates    unset gate: the player's other gates to choose from
     * @param room     gate set to a room: that room
     * @param shared   gate set to a room: who it is shared with (shown to its owner only)
     * @param exitName gate set to a gate: that gate's name ("" if it is gone)
     * @param rules    gate set to a room: what the room allows (the bits of RoomRule; shown to its owner only)
     * @param lightning whether lightning jumps from its frame
     * @param roomTier gate set to a room: its size, tier n being (n + 1) chunks a side (shown to its owner only)
     * @param growCost gate set to a room: how many of {@code growItem} growing it once more costs; -1 when it is as
     *                 large as rooms get
     * @param growItem the id of the item growing costs
     * @param growExtra the id of the one more item this growth takes ("" for none)
     * @param online   gate set to a room: the other players online now, whom its owner may share it with
     */
    public record Screen(BlockPos gate, String gateName, boolean gateMine, boolean changeable, int state, List<RoomEntry> rooms,
                         List<GateEntry> gates, Optional<RoomEntry> room, List<String> shared, String exitName, int rules,
                         boolean lightning, int roomTier, int growCost, String growItem, String growExtra, List<String> online) implements CustomPacketPayload {
        public static final Type<Screen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "gate_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Screen> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeBlockPos(data.gate);
                    buf.writeUtf(data.gateName, MAX_NAME);
                    buf.writeBoolean(data.gateMine);
                    buf.writeBoolean(data.changeable);
                    buf.writeVarInt(data.state);
                    buf.writeVarInt(data.rooms.size());
                    for (RoomEntry room : data.rooms) room.write(buf);
                    buf.writeVarInt(data.gates.size());
                    for (GateEntry gate : data.gates) gate.write(buf);
                    buf.writeBoolean(data.room.isPresent());
                    if (data.room.isPresent()) data.room.get().write(buf);
                    buf.writeVarInt(data.shared.size());
                    for (String name : data.shared) buf.writeUtf(name, MAX_NAME);
                    buf.writeUtf(data.exitName, MAX_NAME);
                    buf.writeVarInt(data.rules);
                    buf.writeBoolean(data.lightning);
                    buf.writeVarInt(data.roomTier);
                    buf.writeVarInt(data.growCost + 1);
                    buf.writeUtf(data.growItem, 256);
                    buf.writeUtf(data.growExtra, 256);
                    buf.writeVarInt(data.online.size());
                    for (String name : data.online) buf.writeUtf(name, MAX_NAME);
                },
                buf -> {
                    BlockPos gate = buf.readBlockPos();
                    String gateName = buf.readUtf(MAX_NAME);
                    boolean gateMine = buf.readBoolean();
                    boolean changeable = buf.readBoolean();
                    int state = buf.readVarInt();
                    List<RoomEntry> rooms = new java.util.ArrayList<>();
                    for (int i = buf.readVarInt(); i > 0; i--) rooms.add(RoomEntry.read(buf));
                    List<GateEntry> gates = new java.util.ArrayList<>();
                    for (int i = buf.readVarInt(); i > 0; i--) gates.add(GateEntry.read(buf));
                    Optional<RoomEntry> room = buf.readBoolean() ? Optional.of(RoomEntry.read(buf)) : Optional.empty();
                    List<String> shared = new java.util.ArrayList<>();
                    for (int i = buf.readVarInt(); i > 0; i--) shared.add(buf.readUtf(MAX_NAME));
                    return new Screen(gate, gateName, gateMine, changeable, state, rooms, gates, room, shared, buf.readUtf(MAX_NAME), buf.readVarInt(),
                            buf.readBoolean(), buf.readVarInt(), buf.readVarInt() - 1, buf.readUtf(256), buf.readUtf(256), readNames(buf));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client: a rift just closed, to be drawn collapsing. Set by the client entry point, null on a dedicated server. */
    public static @Nullable Consumer<Closed> clientCloser;

    /**
     * Server to client: the rift that stood between the cells {@code from} and {@code to} just closed (its gate was
     * set to lead elsewhere, or broken). By then nothing of it is left in the world, so the client draws its
     * collapse from this alone.
     *
     * @param alongX  whether the opening's width runs along z (the gate is seen along x)
     * @param greater a greater gate's rift
     */
    public record Closed(BlockPos from, BlockPos to, boolean alongX, boolean greater) implements CustomPacketPayload {
        public static final Type<Closed> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "rift_closed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Closed> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeBlockPos(data.from);
                    buf.writeBlockPos(data.to);
                    buf.writeBoolean(data.alongX);
                    buf.writeBoolean(data.greater);
                },
                buf -> new Closed(buf.readBlockPos(), buf.readBlockPos(), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static List<String> readNames(RegistryFriendlyByteBuf buf) {
        List<String> names = new java.util.ArrayList<>();
        for (int i = buf.readVarInt(); i > 0; i--) names.add(buf.readUtf(MAX_NAME));
        return names;
    }

    /** What a player does on the gate screen. */
    public enum Kind {
        /** Make a new room named {@code text}. */
        CREATE_ROOM,
        /** Set the gate to room {@code number}. */
        CHOOSE_ROOM,
        /** Set the gate to the gate at {@code target}. */
        CHOOSE_GATE,
        /** Rename the gate's room to {@code text}. */
        RENAME_ROOM,
        /** Share the gate's room with the player named {@code text}, who must be online. */
        SHARE,
        /** Stop sharing the gate's room with the player named {@code text}. */
        UNSHARE,
        /** Rename the gate to {@code text}. */
        RENAME_GATE,
        /** A greater gate: forget the destination, to choose another. */
        RESET,
        /** Switch rule {@code number} (a RoomRule's ordinal) of the gate's room on or off. */
        TOGGLE_RULE,
        /** Switch the lightning off the gate's frame on or off (its owner). */
        TOGGLE_LIGHTNING,
        /** Grow the gate's room by one size, for its price (the room's owner). */
        GROW_ROOM,
        /** Delete room {@code number} and everything in it, for good (its owner). */
        DELETE_ROOM
    }

    /** Client to server: one action on the screen of the gate at {@code gate}. */
    public record Action(BlockPos gate, Kind kind, int number, String text, Optional<GlobalPos> target) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "gate_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> STREAM_CODEC = StreamCodec.of(
                (buf, action) -> {
                    buf.writeBlockPos(action.gate);
                    buf.writeEnum(action.kind);
                    buf.writeVarInt(action.number);
                    buf.writeUtf(action.text, MAX_NAME);
                    buf.writeBoolean(action.target.isPresent());
                    if (action.target.isPresent()) GlobalPos.STREAM_CODEC.encode(buf, action.target.get());
                },
                buf -> new Action(buf.readBlockPos(), buf.readEnum(Kind.class), buf.readVarInt(), buf.readUtf(MAX_NAME),
                        buf.readBoolean() ? Optional.of(GlobalPos.STREAM_CODEC.decode(buf)) : Optional.empty()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Screen.TYPE, Screen.STREAM_CODEC, (data, context) -> {
            if (clientOpener != null) clientOpener.accept(data);
        });
        registrar.playToClient(Closed.TYPE, Closed.STREAM_CODEC, (data, context) -> {
            if (clientCloser != null) clientCloser.accept(data);
        });
        registrar.playToServer(Action.TYPE, Action.STREAM_CODEC, (action, context) -> {
            if (context.player() instanceof ServerPlayer player) GateControl.handle(player, action);
        });
    }
}
