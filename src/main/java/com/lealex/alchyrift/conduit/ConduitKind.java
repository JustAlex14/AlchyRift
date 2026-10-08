package com.lealex.alchyrift.conduit;

import net.minecraft.core.BlockPos;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/** What a conduit carries. Each kind has its own conduit block, and conduits of different kinds never join. */
public enum ConduitKind {
    /** Up to 16 items per transfer. */
    ITEM(16),
    /** Up to 1,000 mB per transfer. */
    FLUID(1000),
    /** Up to 4,000 FE per transfer. */
    ENERGY(4000);

    /** The order other sides are offered what the touched side can't use: top first, bottom last. */
    private static final Direction[] OTHER_SIDES = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};

    /** The most one extracting connection moves each time it works. */
    public final int perTransfer;

    ConduitKind(int perTransfer) {
        this.perTransfer = perTransfer;
    }

    /** Whether the block at {@code pos} takes or gives this kind through its {@code side}. */
    public boolean connects(Level level, BlockPos pos, Direction side) {
        return switch (this) {
            case ITEM -> level.getCapability(Capabilities.Item.BLOCK, pos, side) != null;
            case FLUID -> level.getCapability(Capabilities.Fluid.BLOCK, pos, side) != null;
            case ENERGY -> level.getCapability(Capabilities.Energy.BLOCK, pos, side) != null;
        };
    }

    /** What a sieve knows a resource by: the item itself with its data, or a fluid's bucket. */
    private static ItemStack named(Resource resource) {
        if (resource instanceof ItemResource item) return item.toStack(1);
        if (resource instanceof FluidResource fluid) return new ItemStack(fluid.getFluid().getBucket());
        return ItemStack.EMPTY;
    }

    /**
     * Moves up to {@code amount} from one block's side to another's (maybe in another dimension). Returns what moved.
     * {@code passes}: what the sieves on the two ends let through (null: everything); energy has no sieve.
     */
    public int move(Level level, BlockPos from, Direction fromSide, Level toLevel, BlockPos to, Direction toSide, int amount,
                    @Nullable Predicate<ItemStack> passes) {
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = switch (this) {
                case ITEM -> moveSided(Capabilities.Item.BLOCK, level, from, fromSide, toLevel, to, toSide, amount, passes, transaction);
                case FLUID -> moveSided(Capabilities.Fluid.BLOCK, level, from, fromSide, toLevel, to, toSide, amount, passes, transaction);
                case ENERGY -> {
                    EnergyHandler source = level.getCapability(Capabilities.Energy.BLOCK, from, fromSide);
                    EnergyHandler target = toLevel.getCapability(Capabilities.Energy.BLOCK, to, toSide);
                    yield EnergyHandlerUtil.move(source, target, amount, transaction);
                }
            };
            if (moved > 0) transaction.commit();
            return moved;
        }
    }

    /**
     * Items and fluids, for blocks whose sides differ (a furnace: ore from the top, fuel from the sides, results from
     * the bottom). A conduit doesn't ask its user to reach the right face:
     * <ul>
     *   <li>pulling takes what the block gives from its bottom, the side results come out of, when it has one;</li>
     *   <li>pushing goes to the touched side first; what that side has no use for at all (meat on a furnace's fuel
     *       side) is offered to the other sides, top first. What the touched side does take never goes elsewhere,
     *       so fuel can't end up in the ore slot.</li>
     * </ul>
     */
    private static <T extends Resource> int moveSided(BlockCapability<ResourceHandler<T>, @Nullable Direction> capability, Level level,
                                                      BlockPos from, Direction fromSide, Level toLevel, BlockPos to, Direction toSide,
                                                      int amount, @Nullable Predicate<ItemStack> passes, TransactionContext transaction) {
        ResourceHandler<T> source = level.getCapability(capability, from, Direction.DOWN);
        if (source == null) source = level.getCapability(capability, from, fromSide);
        ResourceHandler<T> target = toLevel.getCapability(capability, to, toSide);
        if (source == null) return 0;
        int moved = ResourceHandlerUtil.move(source, target, resource -> passes == null || passes.test(named(resource)), amount, transaction);
        for (Direction side : OTHER_SIDES) {
            if (moved >= amount) break;
            if (side == toSide) continue;
            ResourceHandler<T> other = toLevel.getCapability(capability, to, side);
            if (other == null || other == target) continue;
            moved += ResourceHandlerUtil.move(source, other,
                    resource -> (passes == null || passes.test(named(resource)))
                            && (target == null || !ResourceHandlerUtil.isValid(target, resource)), amount - moved, transaction);
        }
        return moved;
    }
}
