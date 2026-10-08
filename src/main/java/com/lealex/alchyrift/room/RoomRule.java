package com.lealex.alchyrift.room;

import com.lealex.alchyrift.AlchyRift;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * What a room's owner allows inside it, switched from the gate screen. Everything is off in a new room: a pocket
 * of the void is quiet until its owner says otherwise.
 */
public enum RoomRule {
    /** Monsters appear by themselves in the dark. */
    HOSTILE_SPAWNS("hostile_spawns"),
    /** Animals and other harmless creatures appear by themselves. */
    CREATURE_SPAWNS("creature_spawns"),
    /** Explosions break blocks (they always hurt). */
    EXPLOSIONS("explosions");

    private final String id;

    RoomRule(String id) {
        this.id = id;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public boolean in(int rules) {
        return (rules & bit()) != 0;
    }

    public String translationKey() {
        return "screen.alchyrift.rule." + id;
    }

    /** Whether the room around a position of the rift dimension allows this (outside every room: never). */
    public boolean allowedAt(ServerLevel level, BlockPos pos) {
        RiftRooms.Room room = RiftRooms.get(level.getServer()).at(pos);
        return room != null && in(room.rules());
    }

    /** Applies the rules to what happens in the rift dimension. */
    @EventBusSubscriber(modid = AlchyRift.MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onSpawnCheck(MobSpawnEvent.SpawnPlacementCheck event) {
            // Only what appears by itself: spawn eggs, spawners, breeding and summons are the player's doing
            EntitySpawnReason reason = event.getSpawnType();
            if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) return;
            ServerLevel level = event.getLevel().getLevel();
            if (level.dimension() != RoomLayout.DIMENSION) return;
            RoomRule rule = event.getEntityType().getCategory() == MobCategory.MONSTER ? HOSTILE_SPAWNS : CREATURE_SPAWNS;
            if (!rule.allowedAt(level, event.getPos())) event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }

        @SubscribeEvent
        public static void onExplosion(ExplosionEvent.Detonate event) {
            if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != RoomLayout.DIMENSION) return;
            if (!EXPLOSIONS.allowedAt(level, BlockPos.containing(event.getExplosion().center()))) event.getAffectedBlocks().clear();
        }
    }
}
