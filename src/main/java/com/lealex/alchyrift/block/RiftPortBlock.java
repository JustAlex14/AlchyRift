package com.lealex.alchyrift.block;

import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.conduit.ConduitJunction;
import com.lealex.alchyrift.conduit.ConduitNetworks;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RoomLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A rift port: placed anywhere inside a pocket room, it is the room's end of its gates. Conduits that reach a port
 * are joined to the conduits that reach the stabilizer of every gate that leads to the room, outside. A room can hold several ports;
 * outside a room a port does nothing.
 */
public class RiftPortBlock extends Block implements ConduitJunction {
    private static final VoxelShape SHAPE = Block.box(1, 1, 1, 15, 15, 15);
    private static final Direction[] DIRECTIONS = Direction.values();

    public RiftPortBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** The stabilizers of the formed gates that lead to this port's room. */
    @Override
    public List<GlobalPos> joined(ServerLevel level, BlockPos pos) {
        if (level.dimension() != RoomLayout.DIMENSION) return List.of();
        RiftRooms.Room room = RiftRooms.get(level.getServer()).at(pos);
        if (room == null) return List.of();
        List<GlobalPos> stabilizers = new ArrayList<>();
        for (RiftAnchorBlockEntity gate : gatesOf(level.getServer(), room)) {
            if (gate.getLevel() != null) stabilizers.add(GlobalPos.of(gate.getLevel().dimension(), gate.stabilizerPos()));
        }
        return stabilizers;
    }

    /** The formed, loaded gates that lead to a room. */
    public static List<RiftAnchorBlockEntity> gatesOf(MinecraftServer server, RiftRooms.Room room) {
        List<RiftAnchorBlockEntity> gates = new ArrayList<>();
        for (RiftRooms.Gate known : RiftRooms.get(server).gatesTo(room.id())) {
            ServerLevel gateLevel = server.getLevel(known.pos().dimension());
            if (gateLevel == null || !gateLevel.isLoaded(known.pos().pos())) continue;
            if (gateLevel.getBlockEntity(known.pos().pos()) instanceof RiftAnchorBlockEntity gate
                    && gate.isFormed() && gate.getRoomId() == room.id()) gates.add(gate);
        }
        return gates;
    }

    /**
     * The link between a room and its gate changed (a port placed or broken, the gate formed, broken or moved): the
     * conduit networks on both sides are forgotten and worked out again when needed.
     */
    public static void refreshLinks(MinecraftServer server, RiftRooms.Room room) {
        ServerLevel rift = server.getLevel(RoomLayout.DIMENSION);
        if (rift != null) {
            for (BlockPos port : room.ports()) ConduitNetworks.invalidate(rift, port);
        }
        for (RiftAnchorBlockEntity gate : gatesOf(server, room)) {
            if (gate.getLevel() != null) ConduitNetworks.invalidate(gate.getLevel(), gate.stabilizerPos());
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!(level instanceof ServerLevel serverLevel) || oldState.is(this) || level.dimension() != RoomLayout.DIMENSION) return;
        RiftRooms rooms = RiftRooms.get(serverLevel.getServer());
        RiftRooms.Room room = rooms.at(pos);
        if (room != null) refreshLinks(serverLevel.getServer(), rooms.addPort(room, pos));
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        ConduitNetworks.invalidate(level, pos);
        for (Direction direction : DIRECTIONS) ConduitNetworks.invalidate(level, pos.relative(direction));
        if (level.dimension() != RoomLayout.DIMENSION) return;
        RiftRooms rooms = RiftRooms.get(level.getServer());
        RiftRooms.Room room = rooms.at(pos);
        if (room != null) refreshLinks(level.getServer(), rooms.removePort(room, pos));
    }
}
