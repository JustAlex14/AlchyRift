package com.lealex.alchyrift.room;

import com.lealex.alchyrift.AlchyRift;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where rooms sit in the rift dimension. Every room owns a plot (up to 6x6 chunks of it are ever used); plots are
 * 32 chunks apart on a square spiral around the origin. A room starts in the plot's first chunk and grows toward +x
 * and +z, so its floor and its west and north walls never move. How large a room may actually get in a world is the
 * config's choice (Config.maxRoomTier).
 */
public final class RoomLayout {
    private RoomLayout() {}

    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(AlchyRift.MODID, "rift_void"));

    /** The largest size the code can build: tier n is (n + 1) x (n + 1) chunks. */
    public static final int MAX_TIER = 5;
    /** Blocks between two plot origins. */
    public static final int PLOT_SPACING = 32 * 16;
    /** Y of the floor blocks. */
    public static final int FLOOR_Y = 64;

    /** Inner height per tier (the dimension is 128 high and the floor is at 64). */
    private static final int[] INNER_HEIGHT = {14, 24, 32, 40, 48, 56};

    /** Chunks along one side of a room. */
    public static int chunks(int tier) {
        return tier + 1;
    }

    /** Outer width (walls included): 16 blocks per chunk. */
    public static int outerSize(int tier) {
        return chunks(tier) * 16;
    }

    public static int innerHeight(int tier) {
        return INNER_HEIGHT[tier];
    }

    /** The lowest corner of the room's shell: the floor block under the north-west wall corner. */
    public static BlockPos origin(int id) {
        int[] cell = spiral(id);
        return new BlockPos(cell[0] * PLOT_SPACING, FLOOR_Y, cell[1] * PLOT_SPACING);
    }

    /** The highest corner of the shell (ceiling, south-east). */
    public static BlockPos farCorner(int id, int tier) {
        int size = outerSize(tier);
        return origin(id).offset(size - 1, innerHeight(tier) + 1, size - 1);
    }

    /**
     * Where rooms used to have a fixed crack: 3 wide, 3 tall, in the west wall from this offset along z. Walls now
     * are the way out everywhere (RiftWallBlock); this is still where players arrive.
     */
    public static final int CRACK_Z = 7;
    public static final int CRACK_SIZE = 3;

    /** Where a player arrives: on the floor by the west wall, their back to it. */
    public static Vec3 entry(int id) {
        BlockPos origin = origin(id);
        return new Vec3(origin.getX() + 2.5, FLOOR_Y + 1, origin.getZ() + CRACK_Z + 1.5);
    }

    /** Key of the plot a room id sits on. */
    public static long plotKey(int id) {
        int[] cell = spiral(id);
        return plotKey(cell[0], cell[1]);
    }

    /** Key of the plot around a position of the rift dimension. */
    public static long plotKey(BlockPos pos) {
        // A room starts at its plot's origin (the margin dates from the wall that stood behind the old crack)
        return plotKey(Math.floorDiv(pos.getX() + 16, PLOT_SPACING), Math.floorDiv(pos.getZ() + 16, PLOT_SPACING));
    }

    private static long plotKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /** Looking east, into the room. */
    public static final float ENTRY_YAW = -90f;

    /** Cell n of a square spiral: 0 = (0,0), then (1,0), (1,1), (0,1), (-1,1)... */
    static int[] spiral(int n) {
        int x = 0, z = 0, dx = 1, dz = 0, run = 1, done = 0, turns = 0;
        for (int i = 0; i < n; i++) {
            x += dx;
            z += dz;
            if (++done == run) {
                done = 0;
                int oldDx = dx;
                dx = -dz;
                dz = oldDx;
                if (++turns % 2 == 0) run++;
            }
        }
        return new int[] {x, z};
    }
}
