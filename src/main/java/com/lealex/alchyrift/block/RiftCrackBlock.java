package com.lealex.alchyrift.block;

import com.lealex.alchyrift.room.RiftTravel;
import com.lealex.alchyrift.room.RoomLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The surface of a rift. In a room's wall, a player who steps into it leaves the room. In a gate it is only the
 * look: the gate itself sends players in. Only players pass: to anything else (mobs, items, arrows) it is as solid as
 * the wall it opened in, so nothing wanders out into the void.
 */
public class RiftCrackBlock extends Block {
    public RiftCrackBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context instanceof EntityCollisionContext entity && entity.getEntity() instanceof Player ? Shapes.empty() : Shapes.block();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
        if (level.isClientSide() || level.dimension() != RoomLayout.DIMENSION) return;
        if (entity instanceof ServerPlayer player && !player.isOnPortalCooldown()) {
            RiftTravel.leave(player);
        }
    }
}
