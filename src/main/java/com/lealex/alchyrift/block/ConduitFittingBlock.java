package com.lealex.alchyrift.block;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * What is seen of a sieve and of surge crystals on a conduit's end: a collar around the arm, and one to three
 * crystals standing off it. Only a look: it is never placed in the world and has no item. The client draws it over
 * the conduit's arm (client/ConduitFittings) from what the server tells it, because a conduit keeps none of this in
 * its own block state (it would multiply its thousands of states again).
 */
public class ConduitFittingBlock extends Block {
    /** The sieve on the end, if any. */
    public enum Sieve implements StringRepresentable {
        NONE("none"), PLAIN("plain"), GREATER("greater");

        private final String id;

        Sieve(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Sieve> SIEVE = EnumProperty.create("sieve", Sieve.class);
    public static final IntegerProperty SURGE = IntegerProperty.create("surge", 0, 3);
    /** What the conduit carries: the lit parts take its colour (violet, teal, amber), like the clump at a relay. */
    public static final EnumProperty<RiftClumpBlock.Kind> KIND = RiftClumpBlock.KIND;

    public ConduitFittingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SIEVE, Sieve.NONE).setValue(SURGE, 0)
                .setValue(KIND, RiftClumpBlock.Kind.ITEM));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIEVE, SURGE, KIND);
    }
}
