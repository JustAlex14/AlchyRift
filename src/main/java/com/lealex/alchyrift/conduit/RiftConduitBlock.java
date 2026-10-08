package com.lealex.alchyrift.conduit;

import com.google.common.collect.ImmutableMap;
import com.lealex.alchyrift.Config;
import com.lealex.alchyrift.item.RiftFilterItem;
import com.lealex.alchyrift.item.RiftTunerItem;
import com.lealex.alchyrift.registry.ModRegistries;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A rift conduit: a thin line that joins others of its kind and moves items, fluids or energy between the blocks it
 * touches. Each side that touches a block either pushes into it (the default) or pulls from it; a rift tuner
 * switches between the two, and a rift sieve set on such an end chooses what it lets through (ConduitFilters). A conduit with a pulling side wakes every half second, pulls a batch and hands it to
 * the pushing sides of its whole network, each in turn.
 *
 * Everything a conduit knows is in its block state, so it needs no block entity, and a conduit with no pulling
 * side costs nothing at all.
 */
public class RiftConduitBlock extends Block {
    /** Ticks between two pulls of an extracting side. */
    private static final int WORK_EVERY = 10;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<Direction, EnumProperty<ConduitLink>> LINKS = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : DIRECTIONS) {
            LINKS.put(direction, EnumProperty.create(direction.getSerializedName(), ConduitLink.class));
        }
    }

    public static EnumProperty<ConduitLink> property(Direction direction) {
        return LINKS.get(direction);
    }

    private final ConduitKind kind;
    private final Map<BlockState, VoxelShape> shapes;

    public RiftConduitBlock(ConduitKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        BlockState none = stateDefinition.any();
        for (Direction direction : DIRECTIONS) none = none.setValue(property(direction), ConduitLink.NONE);
        registerDefaultState(none);
        // Thousands of states, but only 64 ways to look (an arm or none on each side): one shape each
        ImmutableMap.Builder<BlockState, VoxelShape> shapes = ImmutableMap.builder();
        Map<Integer, VoxelShape> byLook = new java.util.HashMap<>();
        for (BlockState state : stateDefinition.getPossibleStates()) {
            int look = 0;
            for (Direction direction : DIRECTIONS) {
                ConduitLink link = state.getValue(property(direction));
                look = look * 2 + (link == ConduitLink.NONE || link == ConduitLink.DISABLED ? 0 : 1);
            }
            shapes.put(state, byLook.computeIfAbsent(look, key -> shapeOf(state)));
        }
        this.shapes = shapes.build();
    }

    public ConduitKind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction direction : DIRECTIONS) builder.add(LINKS.get(direction));
    }

    private static VoxelShape shapeOf(BlockState state) {
        VoxelShape shape = Block.box(5, 5, 5, 11, 11, 11);
        for (Direction direction : DIRECTIONS) {
            ConduitLink link = state.getValue(property(direction));
            if (link == ConduitLink.NONE || link == ConduitLink.DISABLED) continue;      // a sealed side has no arm
            shape = Shapes.or(shape, switch (direction) {
                case DOWN -> Block.box(6, 0, 6, 10, 5, 10);
                case UP -> Block.box(6, 11, 6, 10, 16, 10);
                case NORTH -> Block.box(6, 6, 0, 10, 10, 5);
                case SOUTH -> Block.box(6, 6, 11, 10, 10, 16);
                case WEST -> Block.box(0, 6, 6, 5, 10, 10);
                case EAST -> Block.box(11, 6, 6, 16, 10, 10);
            });
        }
        return shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }

    // ---- Joining what it touches ----

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(property(direction), link(context.getLevel(), context.getClickedPos(), direction, ConduitLink.NONE));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (!(level instanceof Level world)) return state;
        ConduitLink before = state.getValue(property(direction));
        ConduitLink now = link(world, pos, direction, before);
        if (!world.isClientSide()) ConduitNetworks.invalidate(world, pos);
        return now == before ? state : state.setValue(property(direction), now);
    }

    /** What the side toward {@code direction} touches. A side already set to pull keeps pulling, a sealed one stays sealed. */
    private ConduitLink link(Level level, BlockPos pos, Direction direction, ConduitLink before) {
        BlockPos neighbor = pos.relative(direction);
        BlockState neighborState = level.getBlockState(neighbor);
        if (neighborState.is(this) || ConduitNetworks.junctionAt(level, neighbor) != null) return ConduitLink.CONDUIT;
        if (!kind.connects(level, neighbor, direction.getOpposite())) return ConduitLink.NONE;
        return before == ConduitLink.EXTRACT || before == ConduitLink.DISABLED ? before : ConduitLink.INSERT;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide()) return;
        ConduitNetworks.invalidate(level, pos);
        // A junction beside it doesn't hear of a new conduit by itself: the lines through it are worked out again
        for (Direction direction : DIRECTIONS) ConduitNetworks.invalidate(level, pos.relative(direction));
        if (pulls(state)) level.scheduleTick(pos, this, WORK_EVERY);
    }

    /**
     * Works out again what the conduit at {@code pos} touches, if there is one: for when a block beside it became,
     * or stopped being, part of a junction without changing itself (a rift gate forming or breaking).
     */
    public static void refresh(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RiftConduitBlock conduit)) return;
        BlockState now = state;
        for (Direction direction : DIRECTIONS) {
            now = now.setValue(property(direction), conduit.link(level, pos, direction, state.getValue(property(direction))));
        }
        if (now != state) level.setBlock(pos, now, Block.UPDATE_CLIENTS);
        ConduitNetworks.invalidate(level, pos);
        if (pulls(now)) level.scheduleTick(pos, conduit, WORK_EVERY);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        ConduitNetworks.invalidate(level, pos);
        // Its sieves and surge crystals come off with it
        ConduitFilters ends = ConduitFilters.get(level.getServer());
        for (Direction direction : DIRECTIONS) {
            ConduitFilters.Filter sieve = ends.remove(level.dimension(), pos, direction);
            if (sieve != null) Block.popResource(level, pos, FilterMessages.sieveItem(sieve));
            int crystals = ends.surge(level.dimension(), pos, direction);
            if (crystals > 0) {
                ends.setSurge(level.dimension(), pos, direction, 0);
                Block.popResource(level, pos, new ItemStack(ModRegistries.SURGE_CRYSTAL.get(), crystals));
            }
        }
        // Its neighbors were part of the same network, but their own entry is already gone with it
        for (Direction direction : DIRECTIONS) ConduitNetworks.invalidate(level, pos.relative(direction));
    }

    private static boolean pulls(BlockState state) {
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(property(direction)) == ConduitLink.EXTRACT) return true;
        }
        return false;
    }

    // ---- The rift tuner: push, pull or sealed ----

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof RiftFilterItem sieve) return useSieve(stack, sieve.advanced(), state, level, pos, player, hit);
        if (stack.is(ModRegistries.SURGE_CRYSTAL)) return useSurge(stack, state, level, pos, player, hit);
        if (!(stack.getItem() instanceof RiftTunerItem)) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Direction side = clickedSide(state, pos, hit);
        if (side == null) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.conduit.no_end"), true);
            }
            return InteractionResult.SUCCESS;
        }
        // Each use steps the end on: pushes -> pulls -> sealed -> pushes
        ConduitLink after = switch (state.getValue(property(side))) {
            case INSERT -> ConduitLink.EXTRACT;
            case EXTRACT -> ConduitLink.DISABLED;
            default -> ConduitLink.INSERT;
        };
        level.setBlock(pos, state.setValue(property(side), after), Block.UPDATE_CLIENTS);
        ConduitNetworks.invalidate(level, pos);
        if (after == ConduitLink.EXTRACT) level.scheduleTick(pos, this, WORK_EVERY);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.conduit." + after.getSerializedName()), true);
        }
        return InteractionResult.SUCCESS;
    }

    // ---- The rift sieve: what an end lets through ----

    /**
     * A sieve used on an end that pushes or pulls: it is set there (one is taken from the stack) and its screen
     * opens; on an end that already has one, only the screen opens.
     */
    private InteractionResult useSieve(ItemStack stack, boolean advanced, BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (kind == ConduitKind.ENERGY) {
            serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.filter.energy"), true);
            return InteractionResult.SUCCESS;
        }
        Direction side = clickedSide(state, pos, hit);
        ConduitLink link = side == null ? ConduitLink.NONE : state.getValue(property(side));
        if (link != ConduitLink.INSERT && link != ConduitLink.EXTRACT) {
            serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.filter.no_end"), true);
            return InteractionResult.SUCCESS;
        }
        ConduitFilters filters = ConduitFilters.get(serverPlayer.level().getServer());
        if (filters.at(level.dimension(), pos, side) == null) {
            filters.set(level.dimension(), pos, side, ConduitFilters.Filter.empty(advanced));
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_PLACE, SoundSource.BLOCKS, 1.0F, 1.3F);
        }
        FilterMessages.open(serverPlayer, pos, side, kind);
        return InteractionResult.SUCCESS;
    }

    // ---- Surge crystals: a pulling end moves more at a time ----

    /** What a pulling end with this many surge crystals moves each time it works. */
    private int batch(int crystals) {
        long batch = kind.perTransfer;
        for (int i = 0; i < Math.min(crystals, Config.maxSurges()); i++) batch *= Config.surgeFactor();
        return (int) Math.min(batch, Integer.MAX_VALUE / 2);
    }

    /** A surge crystal used on a pulling end is set on it (one is taken from the stack), up to the config's limit. */
    private InteractionResult useSurge(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        Direction side = clickedSide(state, pos, hit);
        if (side == null || state.getValue(property(side)) != ConduitLink.EXTRACT) {
            serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.surge.no_end"), true);
            return InteractionResult.SUCCESS;
        }
        ConduitFilters ends = ConduitFilters.get(serverPlayer.level().getServer());
        int crystals = ends.surge(level.dimension(), pos, side);
        if (crystals >= Config.maxSurges()) {
            serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.surge.full", Config.maxSurges()), true);
            return InteractionResult.SUCCESS;
        }
        ends.setSurge(level.dimension(), pos, side, crystals + 1);
        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.6F);
        serverPlayer.sendSystemMessage(Component.translatable("message.alchyrift.surge.set", batch(crystals + 1)), true);
        return InteractionResult.SUCCESS;
    }

    /**
     * The rift tuner used while sneaking on a conduit (RiftTunerItem): the surge crystals of the end aimed at come
     * off and drop. True if there were any.
     */
    public static boolean popSurges(ServerLevel level, BlockPos pos, BlockState state, BlockHitResult hit) {
        Direction side = clickedSide(state, pos, hit);
        if (side == null) return false;
        ConduitFilters ends = ConduitFilters.get(level.getServer());
        int crystals = ends.setSurge(level.dimension(), pos, side, 0);
        if (crystals <= 0) return false;
        Block.popResource(level, pos, new ItemStack(ModRegistries.SURGE_CRYSTAL.get(), crystals));
        return true;
    }

    // A bare hand on an end that has a sieve opens it
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (kind == ConduitKind.ENERGY) return InteractionResult.PASS;
        Direction side = clickedSide(state, pos, hit);
        if (side == null) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS; // the server knows whether there is a sieve
        if (!(player instanceof ServerPlayer serverPlayer)
                || ConduitFilters.get(serverPlayer.level().getServer()).at(level.dimension(), pos, side) == null) return InteractionResult.PASS;
        FilterMessages.open(serverPlayer, pos, side, kind);
        return InteractionResult.SUCCESS;
    }

    /**
     * The side whose end was clicked: the one the cursor is on (an arm, or that face of the knot: a sealed end has no
     * arm to aim at), or the conduit's only end.
     */
    private static Direction clickedSide(BlockState state, BlockPos pos, BlockHitResult hit) {
        Vec3 offset = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        Direction toward = Direction.getApproximateNearest(offset.x, offset.y, offset.z);
        if (state.getValue(property(toward)).isEnd()) return toward;
        List<Direction> ends = java.util.Arrays.stream(DIRECTIONS).filter(direction -> state.getValue(property(direction)).isEnd()).toList();
        return ends.size() == 1 ? ends.getFirst() : null;
    }

    // ---- Working ----

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!pulls(state)) return; // no pulling side any more: go back to sleep
        ConduitNetworks.Network network = ConduitNetworks.get(level, pos, this);
        List<ConduitNetworks.Endpoint> targets = network.inserts();
        if (!targets.isEmpty()) {
            // Sieves: one on the pulling end, one on each pushing end; what moves must pass both (energy has none)
            ConduitFilters ends = ConduitFilters.get(level.getServer());
            ConduitFilters filters = kind == ConduitKind.ENERGY || ends.hasNoSieve() ? null : ends;
            for (Direction direction : DIRECTIONS) {
                if (state.getValue(property(direction)) != ConduitLink.EXTRACT) continue;
                BlockPos source = pos.relative(direction);
                Direction sourceSide = direction.getOpposite();
                ConduitFilters.Filter pulling = filters == null ? null : filters.at(level.dimension(), pos, direction);
                // Surge crystals on the pulling end: each multiplies what it moves at a time
                int batch = batch(ends.surge(level.dimension(), pos, direction));
                int left = batch;
                int start = network.nextStart();
                for (int i = 0; i < targets.size() && left > 0; i++) {
                    ConduitNetworks.Endpoint target = targets.get((start + i) % targets.size());
                    ServerLevel targetLevel = target.dimension() == level.dimension() ? level : level.getServer().getLevel(target.dimension());
                    if (targetLevel == null || !targetLevel.isLoaded(target.pos())) continue;
                    if (targetLevel == level && target.pos().equals(source)) continue;
                    ConduitFilters.Filter pushing = filters == null ? null
                            : filters.at(target.dimension(), target.pos().relative(target.side()), target.side().getOpposite());
                    Predicate<ItemStack> passes = pulling == null && pushing == null ? null
                            : item -> (pulling == null || pulling.allows(item)) && (pushing == null || pushing.allows(item));
                    left -= kind.move(level, source, sourceSide, targetLevel, target.pos(), target.side(), left, passes);
                }
                if (left < batch) network.pulse(level, source);
            }
        }
        level.scheduleTick(pos, this, WORK_EVERY);
    }
}
