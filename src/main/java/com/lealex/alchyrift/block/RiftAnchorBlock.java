package com.lealex.alchyrift.block;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyx.block.FacingCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The rift gate's core, on the front right corner of the gate's platform. Faces the player who places it; the gate
 * is built toward the far side. Broken, it keeps its room: the item it drops opens the same room wherever it is
 * placed again.
 */
public class RiftAnchorBlock extends FacingCoreBlock {
    /**
     * What a gate is, counted from its anchor in the pattern's own coordinates: its pattern file, the rift in its
     * frame, where its stabilizer stands, the block a player steps back out onto, and whether its destination can
     * be chosen again.
     */
    public record Gate(Identifier pattern, BlockPos riftMin, BlockPos riftMax, BlockPos stabilizer, BlockPos wayOut, boolean changeable) {}

    /** The rift gate: a 3x3 platform, a rift 1 wide and 2 tall. Set once. */
    public static final Gate LESSER = new Gate(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "rift_gate"),
            new BlockPos(-1, 0, -2), new BlockPos(-1, 1, -2), new BlockPos(-2, 0, 0), new BlockPos(-1, 0, 1), false);
    /** The greater rift gate: a 5x5 platform, a rift 3 wide and 3 tall. Its destination can be changed at any time. */
    public static final Gate GREATER = new Gate(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "greater_rift_gate"),
            new BlockPos(-3, 0, -4), new BlockPos(-1, 2, -4), new BlockPos(-4, 0, 0), new BlockPos(-2, 0, 1), true);

    private final Gate gate;

    public RiftAnchorBlock(Gate gate, Properties properties) {
        super(properties);
        this.gate = gate;
    }

    public Gate gate() {
        return gate;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RiftAnchorBlockEntity(pos, state);
    }

    // Whoever places the anchor owns the gate
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (!level.isClientSide() && by instanceof Player player && level.getBlockEntity(pos) instanceof RiftAnchorBlockEntity anchor) {
            anchor.setOwner(player.getUUID(), player.getScoreboardName());
        }
    }

    // Creative players break blocks without drops: an anchor that holds a room drops anyway, or the room would be lost
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops()
                && level.getBlockEntity(pos) instanceof RiftAnchorBlockEntity anchor && anchor.isSet()) {
            ItemStack stack = new ItemStack(this);
            stack.applyComponents(anchor.collectComponents());
            ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
