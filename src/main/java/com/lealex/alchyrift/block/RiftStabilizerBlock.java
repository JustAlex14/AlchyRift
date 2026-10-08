package com.lealex.alchyrift.block;

import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.conduit.ConduitJunction;
import com.lealex.alchyrift.conduit.ConduitNetworks;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyx.multiblock.CoreTracker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The rift stabilizer: an obsidian foot with amethyst prongs, placed at the front left corner of a rift gate. It
 * holds the rift open, and once the gate is formed the gate's room shows in small between its prongs (drawn by the
 * client's RiftVision).
 *
 * It is also the gate's end for logistics: conduits that reach a formed gate (this stabilizer or any other block of
 * the gate) are joined to the conduits that reach the rift ports inside the gate's room. The networks keep the whole
 * gate under this block's position.
 */
public class RiftStabilizerBlock extends Block implements ConduitJunction {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);
    private static final Direction[] DIRECTIONS = Direction.values();

    public RiftStabilizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Every port of the room behind the formed gate this stabilizer is part of. */
    @Override
    public List<GlobalPos> joined(ServerLevel level, BlockPos pos) {
        if (!(CoreTracker.formedCoreAt(level, pos) instanceof RiftAnchorBlockEntity gate) || gate.getRoomId() < 0) return List.of();
        if (!gate.stabilizerPos().equals(pos)) return List.of();
        RiftRooms.Room room = RiftRooms.get(level.getServer()).get(gate.getRoomId());
        if (room == null) return List.of();
        List<GlobalPos> ports = new ArrayList<>(room.ports().size());
        for (BlockPos port : room.ports()) ports.add(GlobalPos.of(RoomLayout.DIMENSION, port));
        return ports;
    }

    /** A formed gate is entered from any of its blocks: its frame, its floor, its anchor, this stabilizer. */
    @Override
    public Iterable<BlockPos> body(ServerLevel level, BlockPos pos) {
        if (CoreTracker.formedCoreAt(level, pos) instanceof RiftAnchorBlockEntity gate && gate.stabilizerPos().equals(pos)) return gate.body();
        return List.of(pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        ConduitNetworks.invalidate(level, pos);
        for (Direction direction : DIRECTIONS) ConduitNetworks.invalidate(level, pos.relative(direction));
    }
}
