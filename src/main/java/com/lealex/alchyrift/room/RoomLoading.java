package com.lealex.alchyrift.room;

import com.lealex.alchyrift.Config;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps rooms loaded with nobody inside: their chunks are force-loaded (saved with the dimension, like /forceload),
 * so machines keep working and the rift vision stays live. The config can cap how many rooms get this; the rooms
 * entered most recently are the ones kept.
 */
public final class RoomLoading {
    private RoomLoading() {}

    /** Brings the forced chunks in line with the rooms and the config. Call after a room is made, grown or entered. */
    public static void refresh(MinecraftServer server) {
        ServerLevel rift = server.getLevel(RoomLayout.DIMENSION);
        if (rift == null) return;
        int max = Config.maxLoadedRooms();
        List<RiftRooms.Room> rooms = new ArrayList<>(RiftRooms.get(server).all());
        rooms.sort(Comparator.comparingLong(RiftRooms.Room::lastUsed).reversed());
        for (int i = 0; i < rooms.size(); i++) {
            setForced(rift, rooms.get(i), max < 0 || i < max);
        }
    }

    private static void setForced(ServerLevel rift, RiftRooms.Room room, boolean forced) {
        BlockPos origin = RoomLayout.origin(room.id());
        // Always the full plot when releasing, so a room that shrank or changed keeps nothing behind
        BlockPos far = RoomLayout.farCorner(room.id(), forced ? room.tier() : RoomLayout.MAX_TIER);
        for (int chunkX = origin.getX() >> 4; chunkX <= far.getX() >> 4; chunkX++) {
            for (int chunkZ = origin.getZ() >> 4; chunkZ <= far.getZ() >> 4; chunkZ++) {
                rift.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }
}
