package com.lealex.alchyrift.room;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import com.lealex.alchyrift.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places a room's shell in the rift dimension: a void stone floor, rift walls (the way out), and an unseen sky
 * block over it all, so the room is open onto the eye but nothing leaves through the top.
 */
public final class RoomBuilder {
    private RoomBuilder() {}

    /** Builds the shell of a room at its tier. Nothing inside is touched. */
    public static void build(ServerLevel level, RiftRooms.Room room) {
        placeShell(level, room.id(), room.tier());
    }

    /**
     * Grows a room from one tier to a larger one: the new shell first, then the old east and south walls and the
     * old ceiling, now inside the room, are removed. Whatever the player built stays.
     */
    public static void grow(ServerLevel level, int id, int oldTier, int newTier) {
        placeShell(level, id, newTier);

        BlockPos origin = RoomLayout.origin(id);
        BlockPos oldFar = RoomLayout.farCorner(id, oldTier);
        BlockPos newFar = RoomLayout.farCorner(id, newTier);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() + 1; x <= oldFar.getX(); x++) {
            for (int y = origin.getY() + 1; y <= oldFar.getY(); y++) {
                for (int z = origin.getZ() + 1; z <= oldFar.getZ(); z++) {
                    boolean oldShell = x == oldFar.getX() || y == oldFar.getY() || z == oldFar.getZ();
                    boolean newShell = x == newFar.getX() || y == newFar.getY() || z == newFar.getZ();
                    if (!oldShell || newShell) continue;
                    pos.set(x, y, z);
                    BlockState old = level.getBlockState(pos);
                    if (old.is(ModRegistries.RIFT_WALL) || old.is(ModRegistries.RIFT_SKY)) {
                        level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * Empties a room that is being deleted: every block of it, shell included, without drops (chests don't spill),
     * and whatever lies or lives in it except players (they are sent out first). Its chunks are let go.
     */
    public static void erase(ServerLevel level, int id, int tier) {
        BlockPos origin = RoomLayout.origin(id);
        BlockPos far = RoomLayout.farCorner(id, tier);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX(); x <= far.getX(); x++) {
            for (int z = origin.getZ(); z <= far.getZ(); z++) {
                for (int y = origin.getY(); y <= far.getY(); y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
                                | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
            }
        }
        for (Entity entity : level.getEntitiesOfClass(Entity.class, AABB.encapsulatingFullBlocks(origin, far).inflate(2))) {
            if (!(entity instanceof Player)) entity.discard();
        }
        BlockPos plotFar = RoomLayout.farCorner(id, RoomLayout.MAX_TIER);
        for (int chunkX = origin.getX() >> 4; chunkX <= plotFar.getX() >> 4; chunkX++) {
            for (int chunkZ = origin.getZ() >> 4; chunkZ <= plotFar.getZ() >> 4; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, false);
            }
        }
    }

    private static void placeShell(ServerLevel level, int id, int tier) {
        BlockPos origin = RoomLayout.origin(id);
        BlockPos far = RoomLayout.farCorner(id, tier);
        BlockState floor = ModRegistries.VOID_STONE.get().defaultBlockState();
        BlockState wall = ModRegistries.RIFT_WALL.get().defaultBlockState();
        BlockState sky = ModRegistries.RIFT_SKY.get().defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX(); x <= far.getX(); x++) {
            for (int z = origin.getZ(); z <= far.getZ(); z++) {
                boolean side = x == origin.getX() || x == far.getX() || z == origin.getZ() || z == far.getZ();
                for (int y = origin.getY(); y <= far.getY(); y++) {
                    BlockState state;
                    if (y == origin.getY()) state = side ? wall : floor;
                    else if (side) state = wall;
                    else if (y == far.getY()) state = sky;
                    else continue;
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).is(state.getBlock())) {
                        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        removeOldCrack(level, origin);
    }

    /**
     * Rooms made before walls opened by themselves had a fixed crack in the west wall (the loop above has just
     * walled it up) and a second wall behind it, outside the room: that one is taken away.
     */
    private static void removeOldCrack(ServerLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= RoomLayout.CRACK_SIZE; y++) {
            for (int z = RoomLayout.CRACK_Z; z < RoomLayout.CRACK_Z + RoomLayout.CRACK_SIZE; z++) {
                pos.set(origin.getX() - 1, origin.getY() + y, origin.getZ() + z);
                if (level.getBlockState(pos).is(ModRegistries.RIFT_WALL)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }
}
