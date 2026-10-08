package com.lealex.alchyrift.conduit;

import net.minecraft.util.StringRepresentable;

/**
 * What one side of a conduit touches. Kept to five values on purpose: a conduit has six sides, so every value added
 * multiplies its block states (5 values = 15,625 states per conduit block, 6 would be 46,656).
 */
public enum ConduitLink implements StringRepresentable {
    /** Nothing it can use. */
    NONE("none"),
    /** Another conduit of its kind, or a junction (a relay, a port, any block of a formed gate). */
    CONDUIT("conduit"),
    /** A block it pushes into. */
    INSERT("insert"),
    /** A block it pulls from. */
    EXTRACT("extract"),
    /**
     * A block it could use but was told to leave alone (the rift tuner): nothing goes either way and nothing is drawn,
     * exactly as on a side with nothing to connect to. The tuner opens it again when aimed at that side of the knot.
     */
    DISABLED("disabled");

    private final String name;

    ConduitLink(String name) {
        this.name = name;
    }

    /** The line goes on through this side: to another conduit, or to a junction. */
    public boolean joins() {
        return this == CONDUIT;
    }

    /** Touching a block (and not a conduit): an end the rift tuner can set to push, pull or sealed. */
    public boolean isEnd() {
        return this == INSERT || this == EXTRACT || this == DISABLED;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
