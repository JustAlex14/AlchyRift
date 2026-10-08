package com.lealex.alchyrift.vision;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.Config;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyx.miniature.BoxWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Rift vision, the data side: what a gate shows above its stabilizer. The watching, the sending and the drawing are
 * AlchyX's miniature kit; this only says which box of the world a gate stands for.
 * <ul>
 *   <li>A gate set to a room: the room, walls and ceiling left out, with the mobs and players inside.</li>
 *   <li>A gate set to another gate: that gate's surroundings.</li>
 *   <li>A gate that leads nowhere: nothing.</li>
 * </ul>
 */
public final class GateViews {
    private GateViews() {}

    /** The kind of key AlchyX is asked with: the position of a gate's anchor. */
    public static final Identifier KIND = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "gate");

    /** How far around the gate another gate leads to its surroundings are shown: to each side, below and above its anchor. */
    private static final int AREA_SIDE = 8, AREA_BELOW = 2, AREA_ABOVE = 11;

    public static void register() {
        BoxWatch.register(KIND, GateViews::resolve);
    }

    private static BoxWatch.@Nullable Source resolve(ServerPlayer player, GlobalPos key) {
        ServerLevel level = player.level();
        BlockPos gatePos = key.pos();
        if (!key.dimension().equals(level.dimension())) return null;
        double range = Config.maxViewDistance();
        if (player.distanceToSqr(gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5) > range * range) return null;
        if (!level.isLoaded(gatePos) || !(level.getBlockEntity(gatePos) instanceof RiftAnchorBlockEntity gate) || !gate.isFormed()) return null;

        if (gate.getRoomId() >= 0) {
            RiftRooms.Room room = RiftRooms.get(level.getServer()).get(gate.getRoomId());
            if (room == null) return null;
            return new BoxWatch.Source(RoomLayout.DIMENSION, RoomLayout.origin(room.id()), RoomLayout.farCorner(room.id(), room.tier()), true, true);
        }
        GlobalPos exit = gate.getExitGate();
        if (exit == null) return null;
        return new BoxWatch.Source(exit.dimension(), exit.pos().offset(-AREA_SIDE, -AREA_BELOW, -AREA_SIDE),
                exit.pos().offset(AREA_SIDE, AREA_ABOVE, AREA_SIDE), false, false);
    }
}
