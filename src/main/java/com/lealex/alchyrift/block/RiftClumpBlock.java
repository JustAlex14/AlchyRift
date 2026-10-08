package com.lealex.alchyrift.block;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * The clump of crystal that grows where a conduit meets a rift relay, and that the relay's crack throws its
 * lightning at. Only a look: it is never placed in the world and has no item. The relay's renderer draws it on each
 * side a conduit reaches it from (FACING = that side) in the colour of what that conduit carries (KIND), which keeps
 * the conduits themselves down to a few states.
 */
public class RiftClumpBlock extends Block {
    /** What the conduit carries, in ConduitKind's order: the clump takes its colour. */
    public enum Kind implements StringRepresentable {
        ITEM("item"), FLUID("fluid"), ENERGY("energy");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);

    public RiftClumpBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, Kind.ITEM));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND);
    }
}
