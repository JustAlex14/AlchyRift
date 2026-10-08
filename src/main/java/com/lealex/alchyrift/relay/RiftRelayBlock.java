package com.lealex.alchyrift.relay;

import com.lealex.alchyrift.conduit.ConduitJunction;
import com.lealex.alchyrift.conduit.ConduitNetworks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A rift relay, the wireless end of a conduit line. Relays linked with the rift tuner join the conduits around them
 * as if conduits ran between them. A relay takes several links, so any number of relays can form one group.
 * <ul>
 *   <li>The lesser relay (tier 1) reaches {@link #LESSER_REACH} blocks in its own dimension. A spark travels
 *       between the two when something goes through.</li>
 *   <li>The greater relay (tier 2) reaches anywhere, other dimensions included. A rift opens on both when
 *       something goes through.</li>
 * </ul>
 */
public class RiftRelayBlock extends Block implements EntityBlock, ConduitJunction {
    public static final BooleanProperty LINKED = BooleanProperty.create("linked");
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    /** How far apart two lesser relays work, in blocks. */
    public static final int LESSER_REACH = 64;
    /** How long a greater relay's rift stays open after the last transfer, in ticks. */
    private static final int OPEN_TICKS = 30;
    /** End rod sparks lose 4% of their speed each tick: this start speed per block makes one stop at its target. */
    private static final double SPARK_SPEED = 0.04;
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 13, 14); // a nest of shards and the cage over it
    private static final Direction[] DIRECTIONS = Direction.values();

    private final int tier;

    public RiftRelayBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(LINKED, false).setValue(OPEN, false));
    }

    public int tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LINKED, OPEN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RiftRelayBlockEntity(pos, state);
    }

    /** Whether two relays of a tier may be linked from where they stand. */
    public static boolean inReach(int tier, GlobalPos a, GlobalPos b) {
        if (tier >= 2) return true;
        return a.dimension().equals(b.dimension()) && a.pos().closerThan(b.pos(), LESSER_REACH + 0.5);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        ConduitNetworks.invalidate(level, pos);
        for (Direction direction : DIRECTIONS) ConduitNetworks.invalidate(level, pos.relative(direction));
    }

    // ---- What shows when something goes through ----

    /** The relays this one is linked to and that work right now. */
    @Override
    public List<GlobalPos> joined(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RiftRelayBlockEntity relay)) return List.of();
        List<GlobalPos> joined = new ArrayList<>();
        for (RiftRelayBlockEntity partner : relay.workingPartners()) {
            if (partner.getLevel() != null) joined.add(GlobalPos.of(partner.getLevel().dimension(), partner.getBlockPos()));
        }
        return joined;
    }

    /** Something just went through the link from this relay toward {@code to}. */
    @Override
    public void pulse(ServerLevel level, BlockPos pos, ServerLevel toLevel, BlockPos to) {
        if (!(level.getBlockEntity(pos) instanceof RiftRelayBlockEntity relay)) return;
        long now = level.getServer().getTickCount();
        relay.markPulse(now);
        if (toLevel.getBlockEntity(to) instanceof RiftRelayBlockEntity other) other.markPulse(now);
        if (relay.tier() >= 2) {
            open(level, pos);
            open(toLevel, to);
        } else if (level == toLevel) {
            double dx = to.getX() - pos.getX(), dy = to.getY() - pos.getY(), dz = to.getZ() - pos.getZ();
            level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0,
                    dx * SPARK_SPEED, dy * SPARK_SPEED, dz * SPARK_SPEED, 1.0);
        }
    }

    private static void open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RiftRelayBlock)) return;
        if (!state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.4f, 0.7f);
            level.scheduleTick(pos, state.getBlock(), OPEN_TICKS);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.15, 0.15, 0.15, 0.02);
    }

    // The rift closes once nothing has gone through for a while
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(OPEN)) return;
        long sincePulse = level.getBlockEntity(pos) instanceof RiftRelayBlockEntity relay
                ? level.getServer().getTickCount() - relay.getLastPulse() : OPEN_TICKS;
        if (sincePulse >= OPEN_TICKS) {
            level.setBlock(pos, state.setValue(OPEN, false), Block.UPDATE_CLIENTS);
        } else {
            level.scheduleTick(pos, this, (int) (OPEN_TICKS - sincePulse));
        }
    }
}
