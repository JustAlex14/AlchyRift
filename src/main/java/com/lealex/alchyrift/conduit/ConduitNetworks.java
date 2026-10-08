package com.lealex.alchyrift.conduit;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.RiftAnchorBlock;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyrift.relay.RiftRelayBlockEntity;
import com.lealex.alchyx.multiblock.CoreTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jspecify.annotations.Nullable;

/**
 * The conduit networks of the loaded world, worked out when first needed and kept until a conduit or a relay of the
 * network changes or one of its chunks unloads. Nothing is saved: the blocks in the world are the only truth.
 *
 * A network is the conduits of one kind joined to one another, directly or through linked rift relays, so it can
 * span dimensions. Server thread only.
 */
@EventBusSubscriber(modid = AlchyRift.MODID)
public final class ConduitNetworks {
    private ConduitNetworks() {}

    /** A network stops growing here (a runaway line still works, in pieces). */
    private static final int MAX_MEMBERS = 8192;
    /** Ticks between two shows of a link's spark or rift, however much goes through. */
    private static final int PULSE_EVERY = 10;
    private static final Direction[] DIRECTIONS = Direction.values();

    /** A block side a network pushes into. */
    public record Endpoint(ResourceKey<Level> dimension, BlockPos pos, Direction side) {}

    /** Two joined junctions (linked relays, a gate and one of its room's ports) a network runs through. */
    private record RelayLink(GlobalPos a, GlobalPos b) {}

    private record Node(ServerLevel level, BlockPos pos) {}

    /** Conduits joined to one another, the relays between them, and every block side they push into. */
    public static final class Network {
        private final Map<ResourceKey<Level>, LongSet> members = new HashMap<>();
        private final Map<ResourceKey<Level>, LongSet> relays = new HashMap<>();
        private final Map<ResourceKey<Level>, LongSet> chunks = new HashMap<>();
        private final List<Endpoint> inserts = new ArrayList<>();
        private final List<RelayLink> links = new ArrayList<>();
        private int size;
        private int cursor;
        private long lastPulse = Long.MIN_VALUE / 2;

        public List<Endpoint> inserts() {
            return inserts;
        }

        /** Where the next transfer starts: each one starts one endpoint further, so they all get their turn. */
        public int nextStart() {
            return inserts.isEmpty() ? 0 : Math.floorMod(cursor++, inserts.size());
        }

        private boolean add(Node node, boolean relay) {
            if (size >= MAX_MEMBERS) return false;
            Map<ResourceKey<Level>, LongSet> kind = relay ? relays : members;
            if (!kind.computeIfAbsent(node.level.dimension(), key -> new LongOpenHashSet()).add(node.pos.asLong())) return false;
            chunks.computeIfAbsent(node.level.dimension(), key -> new LongOpenHashSet()).add(ChunkPos.pack(node.pos));
            size++;
            return true;
        }

        /**
         * Something just moved through this network, pulled at {@code source}: its relay links show it, each from
         * the end nearer the source.
         */
        public void pulse(ServerLevel sourceLevel, BlockPos source) {
            if (links.isEmpty()) return;
            MinecraftServer server = sourceLevel.getServer();
            long now = server.getTickCount();
            if (now - lastPulse < PULSE_EVERY) return;
            lastPulse = now;
            for (RelayLink link : links) {
                boolean fromA = !link.b.dimension().equals(sourceLevel.dimension())
                        || (link.a.dimension().equals(sourceLevel.dimension()) && link.a.pos().distSqr(source) <= link.b.pos().distSqr(source));
                GlobalPos from = fromA ? link.a : link.b, to = fromA ? link.b : link.a;
                ServerLevel fromLevel = server.getLevel(from.dimension()), toLevel = server.getLevel(to.dimension());
                if (fromLevel == null || toLevel == null || !fromLevel.isLoaded(from.pos()) || !toLevel.isLoaded(to.pos())) continue;
                if (fromLevel.getBlockState(from.pos()).getBlock() instanceof ConduitJunction junction) {
                    junction.pulse(fromLevel, from.pos(), toLevel, to.pos());
                }
            }
        }
    }

    private static final class Networks {
        /** By conduit: a conduit is in one network. */
        private final Long2ObjectOpenHashMap<Network> byMember = new Long2ObjectOpenHashMap<>();
        /** By relay: a relay is in one network per kind of conduit that reaches it. */
        private final Long2ObjectOpenHashMap<Set<Network>> byRelay = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Set<Network>> byChunk = new Long2ObjectOpenHashMap<>();
    }

    private static final Map<ResourceKey<Level>, Networks> LEVELS = new HashMap<>();

    /**
     * The junction a block is, or belongs to: itself for a junction block (a relay, a port, a stabilizer); for any
     * other block of a FORMED rift gate (its frame, its floor, its anchor), the gate's stabilizer, so the gate is
     * one junction whichever of its blocks a conduit touches. Null for anything else.
     */
    public static @Nullable BlockPos junctionAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ConduitJunction) return pos;
        RiftAnchorBlockEntity gate = null;
        if (state.getBlock() instanceof RiftAnchorBlock) {
            if (level.getBlockEntity(pos) instanceof RiftAnchorBlockEntity anchor && anchor.isFormed()) gate = anchor;
        } else if (!state.isAir() && CoreTracker.formedCoreAt(level, pos) instanceof RiftAnchorBlockEntity anchor) {
            gate = anchor;
        }
        if (gate == null) return null;
        BlockPos stabilizer = gate.stabilizerPos();
        return level.getBlockState(stabilizer).getBlock() instanceof ConduitJunction ? stabilizer : null;
    }

    /** The network of the conduit at {@code pos}. */
    public static Network get(ServerLevel level, BlockPos pos, RiftConduitBlock block) {
        Networks here = LEVELS.get(level.dimension());
        Network known = here == null ? null : here.byMember.get(pos.asLong());
        if (known != null) return known;

        Network built = new Network();
        ArrayDeque<Node> open = new ArrayDeque<>();
        Node first = new Node(level, pos.immutable());
        built.add(first, false);
        open.add(first);
        while (!open.isEmpty()) {
            Node node = open.poll();
            BlockState state = node.level.getBlockState(node.pos);
            if (state.is(block)) {
                for (Direction direction : DIRECTIONS) {
                    ConduitLink link = state.getValue(RiftConduitBlock.property(direction));
                    BlockPos next = node.pos.relative(direction);
                    if (link == ConduitLink.INSERT) {
                        built.inserts.add(new Endpoint(node.level.dimension(), next, direction.getOpposite()));
                    } else if (link.joins() && node.level.isLoaded(next)) {
                        BlockState nextState = node.level.getBlockState(next);
                        if (nextState.is(block)) {
                            join(built, open, new Node(node.level, next), false);
                        } else {
                            BlockPos junction = junctionAt(node.level, next);
                            if (junction != null) join(built, open, new Node(node.level, junction), true);
                        }
                    }
                }
            } else if (state.getBlock() instanceof ConduitJunction junction) {
                // A junction joins every conduit of this kind that reaches it (through any block of it, when it is a
                // whole structure), here and around the junctions it is joined to
                for (BlockPos part : junction.body(node.level, node.pos)) {
                    for (Direction direction : DIRECTIONS) {
                        BlockPos next = part.relative(direction);
                        if (!node.level.isLoaded(next)) continue;
                        BlockState nextState = node.level.getBlockState(next);
                        if (nextState.is(block) && nextState.getValue(RiftConduitBlock.property(direction.getOpposite())).joins()) {
                            join(built, open, new Node(node.level, next), false);
                        }
                    }
                }
                for (GlobalPos other : junction.joined(node.level, node.pos)) {
                    ServerLevel otherLevel = node.level.getServer().getLevel(other.dimension());
                    if (otherLevel == null || !otherLevel.isLoaded(other.pos())) continue;
                    if (!(otherLevel.getBlockState(other.pos()).getBlock() instanceof ConduitJunction)) continue;
                    if (join(built, open, new Node(otherLevel, other.pos()), true)) {
                        built.links.add(new RelayLink(GlobalPos.of(node.level.dimension(), node.pos), other));
                    }
                }
            }
        }
        built.members.forEach((dimension, positions) -> {
            Networks networks = LEVELS.computeIfAbsent(dimension, key -> new Networks());
            for (long member : positions) networks.byMember.put(member, built);
        });
        built.relays.forEach((dimension, positions) -> {
            Networks networks = LEVELS.computeIfAbsent(dimension, key -> new Networks());
            for (long relay : positions) networks.byRelay.computeIfAbsent(relay, key -> new HashSet<>()).add(built);
        });
        built.chunks.forEach((dimension, chunks) -> {
            Networks networks = LEVELS.computeIfAbsent(dimension, key -> new Networks());
            for (long chunk : chunks) networks.byChunk.computeIfAbsent(chunk, key -> new HashSet<>()).add(built);
        });
        return built;
    }

    private static boolean join(Network network, ArrayDeque<Node> open, Node node, boolean relay) {
        if (!network.add(node, relay)) return false;
        open.add(new Node(node.level, node.pos.immutable()));
        return true;
    }

    /** A conduit or relay here was placed, removed or changed: its network is forgotten and worked out again when needed. */
    public static void invalidate(Level level, BlockPos pos) {
        Networks networks = LEVELS.get(level.dimension());
        if (networks == null) return;
        Network network = networks.byMember.get(pos.asLong());
        if (network != null) forget(network);
        Set<Network> throughRelay = networks.byRelay.get(pos.asLong());
        if (throughRelay != null) List.copyOf(throughRelay).forEach(ConduitNetworks::forget);
        // A block of a gate: the networks are kept under the gate's stabilizer
        BlockPos junction = level.isLoaded(pos) ? junctionAt(level, pos) : null;
        if (junction != null && !junction.equals(pos)) {
            Set<Network> throughGate = networks.byRelay.get(junction.asLong());
            if (throughGate != null) List.copyOf(throughGate).forEach(ConduitNetworks::forget);
        }
    }

    private static void forget(Network network) {
        network.relays.forEach((dimension, positions) -> {
            Networks networks = LEVELS.get(dimension);
            if (networks == null) return;
            for (long relay : positions) {
                Set<Network> through = networks.byRelay.get(relay);
                if (through != null && through.remove(network) && through.isEmpty()) networks.byRelay.remove(relay);
            }
        });
        network.members.forEach((dimension, positions) -> {
            Networks networks = LEVELS.get(dimension);
            if (networks == null) return;
            for (long member : positions) networks.byMember.remove(member, network);
        });
        network.chunks.forEach((dimension, chunks) -> {
            Networks networks = LEVELS.get(dimension);
            if (networks == null) return;
            for (long chunk : chunks) {
                Set<Network> inChunk = networks.byChunk.get(chunk);
                if (inChunk != null && inChunk.remove(network) && inChunk.isEmpty()) networks.byChunk.remove(chunk);
            }
        });
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Networks networks = LEVELS.get(level.dimension());
        if (networks == null) return;
        Set<Network> inChunk = networks.byChunk.get(event.getChunk().getPos().pack());
        if (inChunk != null) List.copyOf(inChunk).forEach(ConduitNetworks::forget);
    }

    // A chunk coming back may hold the other end of a relay link: networks that stopped there are worked out again
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (BlockPos pos : event.getChunk().getBlockEntitiesPos()) {
            if (event.getChunk().getBlockEntity(pos) instanceof RiftAnchorBlockEntity gate && gate.getRoomId() >= 0) {
                // A gate coming back: the lines in its room stopped at their ports
                RiftRooms.Room room = RiftRooms.get(level.getServer()).get(gate.getRoomId());
                Networks rift = LEVELS.get(RoomLayout.DIMENSION);
                if (room == null || rift == null) continue;
                for (BlockPos port : room.ports()) {
                    Set<Network> through = rift.byRelay.get(port.asLong());
                    if (through != null) List.copyOf(through).forEach(ConduitNetworks::forget);
                }
                continue;
            }
            if (!(event.getChunk().getBlockEntity(pos) instanceof RiftRelayBlockEntity relay)) continue;
            for (GlobalPos partner : relay.getPartners()) {
                Networks there = LEVELS.get(partner.dimension());
                Set<Network> through = there == null ? null : there.byRelay.get(partner.pos().asLong());
                if (through != null) List.copyOf(through).forEach(ConduitNetworks::forget);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Networks networks = LEVELS.get(level.dimension());
        if (networks == null) return;
        List.copyOf(networks.byMember.values()).forEach(ConduitNetworks::forget);
        networks.byRelay.values().stream().flatMap(Set::stream).toList().forEach(ConduitNetworks::forget);
        LEVELS.remove(level.dimension());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }
}
