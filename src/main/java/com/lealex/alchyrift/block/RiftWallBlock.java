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
 * The wall of a pocket room. Every block of it is the way out: a player who walks into the wall, anywhere, leaves
 * the room. Only players pass. To anything else (mobs, items, arrows) it is solid, so nothing wanders out into the
 * void. How it looks when a player comes close is the client's business (AlchyX NearLook, set up in AlchyRiftClient).
 */
public class RiftWallBlock extends Block {
    public RiftWallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context instanceof EntityCollisionContext entity && entity.getEntity() instanceof Player ? Shapes.empty() : Shapes.block();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
        if (level.isClientSide() || level.dimension() != RoomLayout.DIMENSION) return;
        // No cooldown here: whoever is in the wall is on their way out (and a player who just left is in another level)
        if (entity instanceof ServerPlayer player && player.level() == level && !player.isSpectator()) RiftTravel.leave(player);
    }
}
