package com.lealex.alchyrift.room;

import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyx.block.FacingCoreBlock;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Stepping through a rift, both ways. */
public final class RiftTravel {
    private RiftTravel() {}

    /** Ticks before a player who just crossed can cross again. */
    private static final int COOLDOWN = 40;

    /** A number above every room's last-use stamp (tick counts restart with the server, so they can't be used). */
    private static long nextStamp(RiftRooms rooms) {
        long latest = 0;
        for (RiftRooms.Room room : rooms.all()) latest = Math.max(latest, room.lastUsed());
        return latest + 1;
    }

    /** Through a set gate: into its room, or out of the gate it leads to. */
    public static void enter(ServerPlayer player, RiftAnchorBlockEntity gate) {
        MinecraftServer server = player.level().getServer();
        if (gate.getExitGate() != null) {
            RiftAnchorBlockEntity exit = formedGate(server, gate.getExitGate());
            if (exit == null || !(exit.getLevel() instanceof ServerLevel exitLevel)) {
                player.setPortalCooldown(COOLDOWN);
                player.sendSystemMessage(Component.translatable("message.alchyrift.gate.exit_gone"), true);
                return;
            }
            cross(player, exitLevel, Vec3.atBottomCenterOf(exit.wayOut()), exit.getBlockState().getValue(FacingCoreBlock.FACING).toYRot());
            return;
        }

        ServerLevel rift = server.getLevel(RoomLayout.DIMENSION);
        RiftRooms rooms = RiftRooms.get(server);
        RiftRooms.Room room = rooms.get(gate.getRoomId());
        if (rift == null || room == null) {
            player.setPortalCooldown(COOLDOWN);
            player.sendSystemMessage(Component.translatable("message.alchyrift.gate.room_gone"), true);
            return;
        }
        RoomBuilder.build(rift, room);
        GlobalPos gatePos = GlobalPos.of(player.level().dimension(), gate.getBlockPos());
        room = rooms.enter(room, gatePos, nextStamp(rooms));
        RoomLoading.refresh(server);
        rooms.setReturnPoint(player.getUUID(), new RiftRooms.ReturnPoint(
                GlobalPos.of(player.level().dimension(), player.blockPosition()), player.getYRot(), Optional.of(gatePos)));

        cross(player, rift, RoomLayout.entry(room.id()), RoomLayout.ENTRY_YAW);
    }

    /**
     * Out of the room a player stands in: in front of the gate they came through when it still stands, else where
     * they stood before crossing, else the world spawn.
     */
    public static void leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        RiftRooms.ReturnPoint point = RiftRooms.get(server).getReturnPoint(player.getUUID());
        if (point != null && point.gate().isPresent()) {
            RiftAnchorBlockEntity gate = formedGate(server, point.gate().get());
            if (gate != null && gate.getLevel() instanceof ServerLevel gateLevel) {
                Direction front = gate.getBlockState().getValue(FacingCoreBlock.FACING);
                cross(player, gateLevel, Vec3.atBottomCenterOf(gate.wayOut()), front.toYRot());
                return;
            }
        }
        ServerLevel level = point == null ? null : server.getLevel(point.pos().dimension());
        if (level != null) {
            cross(player, level, Vec3.atBottomCenterOf(point.pos().pos()), point.yaw());
            return;
        }
        player.setPortalCooldown(COOLDOWN);
        player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
    }

    /** The formed gate at a position (its chunk is loaded to look), or null. */
    private static @Nullable RiftAnchorBlockEntity formedGate(MinecraftServer server, GlobalPos pos) {
        ServerLevel level = server.getLevel(pos.dimension());
        if (level == null) return null;
        return level.getBlockEntity(pos.pos()) instanceof RiftAnchorBlockEntity gate && gate.isFormed() ? gate : null;
    }

    private static void cross(ServerPlayer player, ServerLevel level, Vec3 pos, float yaw) {
        player.setPortalCooldown(COOLDOWN);
        player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, 0f, true);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, 0.6f);
    }
}
