package com.lealex.alchyrift.block;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * What a rift gate's frame turns into when the gate forms: claws of cracked obsidian and amethyst shards closing on
 * the rift. Only a look (the gate's pattern names it as a result, AlchyX's shells wear it): never placed as itself,
 * no item. FACING is the side the rift is on (for a lintel: either side it faces), PART its place in the frame.
 */
public class RiftFrameBlock extends Block {
    public enum Part implements StringRepresentable {
        /** The foot of a pillar. */
        BASE("base"),
        /** The middle of a pillar. */
        SHAFT("shaft"),
        /** The top of a pillar, hooking toward the rift. */
        CROWN("crown"),
        /** Where a pillar meets a beam: the pillar's end, with a stub of beam toward the rift. */
        CORNER("corner"),
        /** A beam over the rift. */
        LINTEL("lintel"),
        /** The crystal hanging over the rift (the same from every side). */
        KEYSTONE("keystone");

        private final String id;

        Part(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public RiftFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.SHAFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    // The gate's pattern turns with its anchor: so does the frame's look
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
