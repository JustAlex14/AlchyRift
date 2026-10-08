package com.lealex.alchyrift.room;

import com.lealex.alchyrift.AlchyRift;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/** Every pocket room and every rift gate of the world, saved with the overworld (data/alchyrift/rooms). */
public final class RiftRooms extends SavedData {
    /**
     * One room. Its place in the rift dimension follows from the id (see {@link RoomLayout}).
     *
     * @param gate     the rift anchor a player last entered it through (kept for old saves; gates are found through
     *                 {@link #gatesTo})
     * @param lastUsed a stamp that grows with each entry into any room (0 = never entered)
     * @param ports    the rift ports placed inside it
     * @param owner    the player it belongs to (empty: a room from before rooms had owners, free to claim)
     * @param shared   names of the players the owner lets open gates onto it
     * @param rules    what the owner allows inside: the bits of {@link RoomRule} (0: nothing)
     */
    public record Room(int id, int tier, Optional<GlobalPos> gate, long lastUsed, List<BlockPos> ports,
                       Optional<UUID> owner, String ownerName, String name, List<String> shared, int rules) {
        static final Codec<Room> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Room::id),
                Codec.intRange(0, RoomLayout.MAX_TIER).fieldOf("tier").forGetter(Room::tier),
                GlobalPos.CODEC.optionalFieldOf("gate").forGetter(Room::gate),
                Codec.LONG.optionalFieldOf("last_used", 0L).forGetter(Room::lastUsed),
                BlockPos.CODEC.listOf().optionalFieldOf("ports", List.of()).forGetter(Room::ports),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Room::owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Room::ownerName),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Room::name),
                Codec.STRING.listOf().optionalFieldOf("shared", List.of()).forGetter(Room::shared),
                Codec.INT.optionalFieldOf("rules", 0).forGetter(Room::rules)
        ).apply(i, Room::new));

        /** The name to show: its own, or "Room 3" for one that never got any. */
        public String displayName() {
            return name.isBlank() ? "Room " + id : name;
        }

        public boolean ownedBy(UUID player) {
            return owner.isPresent() && owner.get().equals(player);
        }

        /** Whether a player may open a gate onto this room: its owner, someone it is shared with, or anyone if nobody owns it. */
        public boolean usableBy(UUID player, String playerName) {
            return owner.isEmpty() || ownedBy(player) || shared.stream().anyMatch(name -> name.equalsIgnoreCase(playerName));
        }

        private Room with(int tier, Optional<GlobalPos> gate, long lastUsed, List<BlockPos> ports) {
            return new Room(id, tier, gate, lastUsed, ports, owner, ownerName, name, shared, rules);
        }

        private Room with(Optional<UUID> owner, String ownerName, String name, List<String> shared) {
            return new Room(id, tier, gate, lastUsed, ports, owner, ownerName, name, shared, rules);
        }

        private Room withRules(int rules) {
            return new Room(id, tier, gate, lastUsed, ports, owner, ownerName, name, shared, rules);
        }
    }

    /**
     * A formed rift gate: where its anchor stands, whose it is, its name, and the room it leads to (-1: none, it
     * leads to another gate or nowhere yet). Other gates of the same owner can pick it as their exit.
     */
    public record Gate(GlobalPos pos, UUID owner, String ownerName, String name, int room) {
        static final Codec<Gate> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("pos").forGetter(Gate::pos),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Gate::owner),
                Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Gate::ownerName),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Gate::name),
                Codec.INT.optionalFieldOf("room", -1).forGetter(Gate::room)
        ).apply(i, Gate::new));
    }

    /** Where a player stood before stepping into a rift, and the gate they took: the way back out of a room. */
    public record ReturnPoint(GlobalPos pos, float yaw, Optional<GlobalPos> gate) {
        static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("pos").forGetter(ReturnPoint::pos),
                Codec.FLOAT.fieldOf("yaw").forGetter(ReturnPoint::yaw),
                GlobalPos.CODEC.optionalFieldOf("gate").forGetter(ReturnPoint::gate)
        ).apply(i, ReturnPoint::new));
    }

    private static final Codec<RiftRooms> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("next_id").forGetter(rooms -> rooms.nextId),
            Room.CODEC.listOf().fieldOf("rooms").forGetter(rooms -> new ArrayList<>(rooms.rooms.values())),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, ReturnPoint.CODEC).optionalFieldOf("returns", Map.of())
                    .forGetter(rooms -> rooms.returns),
            Gate.CODEC.listOf().optionalFieldOf("gates", List.of()).forGetter(rooms -> new ArrayList<>(rooms.gates.values()))
    ).apply(i, RiftRooms::new));

    public static final SavedDataType<RiftRooms> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AlchyRift.MODID, "rooms"), RiftRooms::new, CODEC);

    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Map<Long, Integer> byPlot = new HashMap<>();
    private final Map<UUID, ReturnPoint> returns = new HashMap<>();
    private final Map<GlobalPos, Gate> gates = new LinkedHashMap<>();
    private int nextId;

    public RiftRooms() {}

    private RiftRooms(int nextId, List<Room> rooms, Map<UUID, ReturnPoint> returns, List<Gate> gates) {
        this.nextId = nextId;
        for (Room room : rooms) put(room);
        this.returns.putAll(returns);
        for (Gate gate : gates) this.gates.put(gate.pos(), gate);
    }

    public static RiftRooms get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // ---- Rooms ----

    /** A new room of a player. */
    public Room create(int tier, UUID owner, String ownerName, String name) {
        Room room = new Room(nextId++, tier, Optional.empty(), 0L, List.of(), Optional.of(owner), ownerName, name, List.of(), 0);
        put(room);
        setDirty();
        return room;
    }

    public @Nullable Room get(int id) {
        return rooms.get(id);
    }

    /** The room whose plot holds this position of the rift dimension. */
    public @Nullable Room at(BlockPos pos) {
        Integer id = byPlot.get(RoomLayout.plotKey(pos));
        return id == null ? null : rooms.get(id);
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    /** The rooms a player may open a gate onto: their own first, then the shared ones, then the unowned. */
    public List<Room> usableBy(UUID player, String playerName) {
        List<Room> usable = new ArrayList<>();
        for (Room room : rooms.values()) if (room.ownedBy(player)) usable.add(room);
        for (Room room : rooms.values()) if (!room.ownedBy(player) && room.usableBy(player, playerName)) usable.add(room);
        return usable;
    }

    /** Forgets a room for good (its blocks are RoomBuilder.erase's business). Its number is never given again. */
    public void remove(Room room) {
        if (rooms.remove(room.id()) == null) return;
        byPlot.remove(RoomLayout.plotKey(room.id()));
        setDirty();
    }

    public Room setTier(Room room, int tier) {
        return replace(room.with(tier, room.gate(), room.lastUsed(), room.ports()));
    }

    /** A player enters the room through this gate at this moment (a stamp that orders rooms by last use). */
    public Room enter(Room room, GlobalPos gate, long stamp) {
        return replace(room.with(room.tier(), Optional.of(gate), stamp, room.ports()));
    }

    /** A room nobody owns becomes this player's. */
    public Room claim(Room room, UUID owner, String ownerName) {
        return room.owner().isPresent() ? room : replace(room.with(Optional.of(owner), ownerName, room.name(), room.shared()));
    }

    public Room rename(Room room, String name) {
        return replace(room.with(room.owner(), room.ownerName(), name, room.shared()));
    }

    public Room share(Room room, String playerName) {
        if (room.shared().stream().anyMatch(name -> name.equalsIgnoreCase(playerName))) return room;
        List<String> shared = new ArrayList<>(room.shared());
        shared.add(playerName);
        return replace(room.with(room.owner(), room.ownerName(), room.name(), List.copyOf(shared)));
    }

    public Room unshare(Room room, String playerName) {
        List<String> shared = new ArrayList<>(room.shared());
        if (!shared.removeIf(name -> name.equalsIgnoreCase(playerName))) return room;
        return replace(room.with(room.owner(), room.ownerName(), room.name(), List.copyOf(shared)));
    }

    /** Switches one rule of a room on or off. */
    public Room toggle(Room room, RoomRule rule) {
        return replace(room.withRules(room.rules() ^ rule.bit()));
    }

    public Room addPort(Room room, BlockPos port) {
        if (room.ports().contains(port)) return room;
        List<BlockPos> ports = new ArrayList<>(room.ports());
        ports.add(port.immutable());
        return replace(room.with(room.tier(), room.gate(), room.lastUsed(), List.copyOf(ports)));
    }

    public Room removePort(Room room, BlockPos port) {
        if (!room.ports().contains(port)) return room;
        List<BlockPos> ports = new ArrayList<>(room.ports());
        ports.remove(port);
        return replace(room.with(room.tier(), room.gate(), room.lastUsed(), List.copyOf(ports)));
    }

    // ---- Gates ----

    /** A formed gate, as it is now (replaces what was known of it). */
    public void putGate(Gate gate) {
        if (gate.equals(gates.put(gate.pos(), gate))) return;
        setDirty();
    }

    public void removeGate(GlobalPos pos) {
        if (gates.remove(pos) != null) setDirty();
    }

    public @Nullable Gate gate(GlobalPos pos) {
        return gates.get(pos);
    }

    public List<Gate> gatesOf(UUID owner) {
        List<Gate> owned = new ArrayList<>();
        for (Gate gate : gates.values()) if (gate.owner().equals(owner)) owned.add(gate);
        return owned;
    }

    /** Every formed gate that leads to a room. */
    public List<Gate> gatesTo(int room) {
        List<Gate> leading = new ArrayList<>();
        for (Gate gate : gates.values()) if (gate.room() == room) leading.add(gate);
        return leading;
    }

    // ---- Ways back ----

    public void setReturnPoint(UUID player, ReturnPoint point) {
        returns.put(player, point);
        setDirty();
    }

    public @Nullable ReturnPoint getReturnPoint(UUID player) {
        return returns.get(player);
    }

    private Room replace(Room room) {
        put(room);
        setDirty();
        return room;
    }

    private void put(Room room) {
        rooms.put(room.id(), room);
        byPlot.put(RoomLayout.plotKey(room.id()), room.id());
    }
}
