package com.lealex.alchyrift;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side settings (visuals only), saved in config/alchyrift-client.toml. Read through the getters: they fall
 * back to the defaults while the file isn't loaded yet.
 */
public final class ClientConfig {
    private ClientConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Rift vision: the room shown in small above a gate's stabilizer.").push("vision");
    }

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Show rooms above stabilizers at all.")
            .define("enabled", true);
    public static final ModConfigSpec.IntValue VIEW_DISTANCE = BUILDER
            .comment("How far from a gate (blocks) its room is asked for and drawn. The server may allow less.")
            .defineInRange("viewDistance", 48, 8, 256);
    public static final ModConfigSpec.IntValue MAX_FACES = BUILDER
            .comment("Most block faces drawn per room. A room with more loses its lowest blocks first.",
                    "Only faces that can be seen count: an empty 3x3 chunk room is about 2,100.")
            .defineInRange("maxFaces", 60000, 1000, 1000000);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    public static int viewDistance() {
        return read(VIEW_DISTANCE, 48);
    }

    public static int maxFaces() {
        return read(MAX_FACES, 60000);
    }

    private static int read(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }
}
