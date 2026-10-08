package com.lealex.alchyrift;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * Game settings, saved in config/alchyrift-common.toml. Read through the getters: they fall back to the defaults
 * while the file isn't loaded yet.
 */
public final class Config {
    private Config() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Pocket rooms.").push("rooms");
    }

    public static final ModConfigSpec.IntValue MAX_LOADED_ROOMS = BUILDER
            .comment("How many rooms stay loaded with nobody inside (machines keep working, the rift vision stays live).",
                    "The rooms entered most recently are the ones kept. -1: every room. 0: none.",
                    "A room is 1, 4, 9 or more chunks depending on its size.")
            .defineInRange("maxLoadedRooms", -1, -1, 100000);

    public static final ModConfigSpec.IntValue MAX_ROOM_SIZE = BUILDER
            .comment("The largest a room can be grown to from the gate screen, in chunks along one side.",
                    "Every room starts at 1 (1 x 1 chunk). 3 means up to 3 x 3 chunks. 1: rooms never grow.")
            .defineInRange("maxRoomSize", 3, 1, 6);

    public static final ModConfigSpec.ConfigValue<String> GROW_ITEM = BUILDER
            .comment("The item growing a room costs.")
            .define("growItem", "alchyrift:void_seed");

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> GROW_COSTS = BUILDER
            .comment("How many of that item each growth costs: the first number takes a room from 1 x 1 to 2 x 2 chunks,",
                    "the second from 2 x 2 to 3 x 3, and so on. Growths past the end of the list cost the last number.",
                    "0: that growth is free.")
            .defineListAllowEmpty("growCosts", List.of(1, 3, 6, 10, 15), () -> 1, value -> value instanceof Integer n && n >= 0 && n <= 6400);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> GROW_EXTRAS = BUILDER
            .comment("One more item a growth takes, besides the ones above: the first entry is for 1 x 1 to 2 x 2 chunks,",
                    "the second for 2 x 2 to 3 x 3, and so on. \"\" (or past the end of the list): nothing more.",
                    "By default reaching 3 x 3 chunks also takes a nether star.")
            .defineListAllowEmpty("growExtras", List.of("", "minecraft:nether_star"), () -> "", value -> value instanceof String);

    static {
        BUILDER.pop();
        BUILDER.comment("Rift vision: the room shown in small above a gate's stabilizer.").push("vision");
    }

    public static final ModConfigSpec.IntValue MAX_VIEW_DISTANCE = BUILDER
            .comment("The farthest from a gate (blocks) a player is sent its room. Clients choose their own distance up to this.")
            .defineInRange("maxViewDistance", 64, 8, 256);

    static {
        BUILDER.pop();
        BUILDER.comment("Rift conduits.").push("conduits");
    }

    public static final ModConfigSpec.IntValue MAX_SURGES = BUILDER
            .comment("How many surge crystals one pulling end of a conduit takes. 0: surge crystals do nothing.")
            .defineInRange("maxSurges", 3, 0, 6);

    public static final ModConfigSpec.IntValue SURGE_FACTOR = BUILDER
            .comment("How much each surge crystal multiplies what a pulling end moves at a time.",
                    "Without any, an end moves 16 items, 1000 mB or 4000 FE every half second.")
            .defineInRange("surgeFactor", 4, 2, 16);

    static {
        BUILDER.pop();
        BUILDER.comment("Rift gates.").push("gates");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FLOOR_BLOCKS = BUILDER
            .comment("When a gate's rift opens, the place it leads to seeps into the gate's floor: its blocks take the look",
                    "of that dimension's ground. One entry per dimension, written dimension=block. Add your own dimensions here.",
                    "A dimension that is not listed leaves the floor as it is. An empty list switches this off.")
            .defineListAllowEmpty("floorBlocks", List.of(
                    "minecraft:overworld=minecraft:grass_block",
                    "minecraft:the_nether=minecraft:netherrack",
                    "minecraft:the_end=minecraft:end_stone",
                    "alchyrift:rift_void=alchyrift:void_stone"), () -> "", value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue OPENING_PUSH = BUILDER
            .comment("Whether the blast of a rift tearing open throws back whoever stands close to it (it never hurts).")
            .define("openingPush", true);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int maxLoadedRooms() {
        return read(MAX_LOADED_ROOMS, -1);
    }

    /** The largest tier a room may be grown to (tier n is (n + 1) chunks a side). */
    public static int maxRoomTier() {
        return read(MAX_ROOM_SIZE, 3) - 1;
    }

    /** What growing a room costs; the void seed if the config names something that isn't an item. */
    public static Item growItem() {
        try {
            Identifier id = Identifier.tryParse(GROW_ITEM.get());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != Items.AIR) return item;
            }
        } catch (IllegalStateException notLoadedYet) {
            // the default below
        }
        return com.lealex.alchyrift.registry.ModRegistries.VOID_SEED.get();
    }

    /** What a gate's floor turns into when the gate leads into {@code dimension}, or null to leave it alone. */
    public static @Nullable BlockState floorBlock(ResourceKey<Level> dimension) {
        try {
            String wanted = dimension.identifier().toString();
            for (String entry : FLOOR_BLOCKS.get()) {
                int cut = entry.indexOf('=');
                if (cut < 0 || !entry.substring(0, cut).strip().equals(wanted)) continue;
                Identifier id = Identifier.tryParse(entry.substring(cut + 1).strip());
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
                BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
                return state.isAir() ? null : state;
            }
        } catch (IllegalStateException notLoadedYet) {
            // nothing
        }
        return null;
    }

    public static int maxSurges() {
        return read(MAX_SURGES, 3);
    }

    public static int surgeFactor() {
        return read(SURGE_FACTOR, 4);
    }

    public static boolean openingPush() {
        try {
            return OPENING_PUSH.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** The one more item growing a room out of {@code tier} takes, or null. */
    public static @Nullable Item growExtra(int tier) {
        try {
            List<? extends String> extras = GROW_EXTRAS.get();
            if (tier < 0 || tier >= extras.size()) return null;
            Identifier id = Identifier.tryParse(extras.get(tier).strip());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
            Item item = BuiltInRegistries.ITEM.getValue(id);
            return item == Items.AIR ? null : item;
        } catch (IllegalStateException notLoadedYet) {
            return tier == 1 ? Items.NETHER_STAR : null;
        }
    }

    /** How many grow items it takes to grow a room out of {@code tier}. */
    public static int growCost(int tier) {
        try {
            List<? extends Integer> costs = GROW_COSTS.get();
            if (costs.isEmpty()) return 0;
            return costs.get(Math.min(Math.max(tier, 0), costs.size() - 1));
        } catch (IllegalStateException notLoadedYet) {
            return tier + 1;
        }
    }

    public static int maxViewDistance() {
        return read(MAX_VIEW_DISTANCE, 64);
    }

    private static int read(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }
}
