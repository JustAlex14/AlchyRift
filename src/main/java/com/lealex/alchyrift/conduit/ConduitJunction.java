package com.lealex.alchyrift.conduit;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * A block that joins conduit lines without being a conduit: rift relays, a gate's stabilizer (which stands for the
 * whole formed gate: a conduit may touch any block of it), a room's ports.
 * Every conduit that reaches a junction is joined to the conduits around it and around the junctions it is joined
 * to, wherever those are. A junction serves all three kinds of conduit at once, without mixing them.
 */
public interface ConduitJunction {
    /** The other junctions this one is joined to right now (loaded or not: the network checks). */
    List<GlobalPos> joined(ServerLevel level, BlockPos pos);

    /**
     * The blocks a conduit may reach this junction through: only itself, unless the junction stands for a whole
     * structure (a formed rift gate is entered from any of its blocks, see {@link ConduitNetworks#junctionAt}).
     */
    default Iterable<BlockPos> body(ServerLevel level, BlockPos pos) {
        return List.of(pos);
    }

    /** Something just went through from this junction to another: show it. Default: a few motes on both. */
    default void pulse(ServerLevel level, BlockPos pos, ServerLevel toLevel, BlockPos to) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
        toLevel.sendParticles(ParticleTypes.REVERSE_PORTAL, to.getX() + 0.5, to.getY() + 0.7, to.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
    }
}
