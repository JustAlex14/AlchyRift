package com.lealex.alchyrift.relay;

import com.lealex.alchyrift.conduit.ConduitNetworks;
import com.lealex.alchyrift.registry.ModRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** A rift relay's memory: the other relays it is linked to. */
public class RiftRelayBlockEntity extends BlockEntity {
    /** The most relays one relay links to. A group can be larger: links chain from relay to relay. */
    public static final int MAX_LINKS = 8;

    /** Client: the relays loaded right now, for showing their links while a rift tuner is held. */
    private static final Set<RiftRelayBlockEntity> CLIENT_LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public static synchronized List<RiftRelayBlockEntity> clientLoaded() {
        return new ArrayList<>(CLIENT_LOADED);
    }

    private final Set<GlobalPos> partners = new LinkedHashSet<>();
    /** Server tick of the last transfer that went through (not saved): keeps a greater relay's rift open. */
    private long lastPulse = Long.MIN_VALUE / 2;

    public RiftRelayBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.RIFT_RELAY_BE.get(), pos, state);
    }

    public Set<GlobalPos> getPartners() {
        return partners;
    }

    public boolean isLinkedTo(GlobalPos other) {
        return partners.contains(other);
    }

    public int tier() {
        return getBlockState().getBlock() instanceof RiftRelayBlock relay ? relay.tier() : 1;
    }

    private @Nullable GlobalPos here() {
        return level == null ? null : GlobalPos.of(level.dimension(), worldPosition);
    }

    /**
     * The relays at the other end of this one's links that really work right now: loaded, pointing back here, of
     * the same tier, and for lesser relays near enough.
     */
    public List<RiftRelayBlockEntity> workingPartners() {
        List<RiftRelayBlockEntity> working = new ArrayList<>();
        if (partners.isEmpty() || !(level instanceof ServerLevel serverLevel)) return working;
        GlobalPos here = GlobalPos.of(serverLevel.dimension(), worldPosition);
        boolean dead = false;
        for (GlobalPos partner : partners) {
            RiftRelayBlockEntity other = relayAt(serverLevel, partner);
            if (other == null || !other.partners.contains(here)) {
                dead |= isDead(serverLevel, here, partner);
                continue;
            }
            if (other.getBlockState().getBlock() != getBlockState().getBlock()) continue;
            if (RiftRelayBlock.inReach(tier(), here, partner)) working.add(other);
        }
        // a link to nothing: dropped after this (not while the network is being walked)
        if (dead) serverLevel.getServer().execute(this::pruneDead);
        return working;
    }

    /**
     * A partner that is surely gone: its place is loaded and holds no relay linking back here (it was broken while
     * this relay was unloaded, by a command, or with an erased room: none of those let it unlink this end). A partner
     * whose chunk is not loaded is never judged gone.
     */
    private static boolean isDead(ServerLevel from, GlobalPos here, GlobalPos partner) {
        return com.lealex.alchyx.link.Links.goneEntity(from.getServer(), partner,
                entity -> entity instanceof RiftRelayBlockEntity other && other.partners.contains(here));
    }

    /** Server: drops every link to a partner that is surely gone (see isDead). */
    public void pruneDead() {
        if (isRemoved() || partners.isEmpty() || !(level instanceof ServerLevel serverLevel)) return;
        GlobalPos here = GlobalPos.of(serverLevel.dimension(), worldPosition);
        if (partners.removeIf(partner -> isDead(serverLevel, here, partner))) changed();
    }

    private static @Nullable RiftRelayBlockEntity relayAt(ServerLevel from, GlobalPos pos) {
        ServerLevel there = from.getServer().getLevel(pos.dimension());
        if (there == null || !there.isLoaded(pos.pos())) return null;
        return there.getBlockEntity(pos.pos()) instanceof RiftRelayBlockEntity relay ? relay : null;
    }

    /** Links this relay and another, both ways. */
    public void link(RiftRelayBlockEntity other) {
        GlobalPos here = here(), there = other.here();
        if (here == null || there == null) return;
        partners.add(there);
        other.partners.add(here);
        changed();
        other.changed();
    }

    /** Breaks the link between this relay and another, both ways. */
    public void unlink(RiftRelayBlockEntity other) {
        GlobalPos here = here(), there = other.here();
        if (here == null || there == null) return;
        partners.remove(there);
        other.partners.remove(here);
        changed();
        other.changed();
    }

    /** Breaks every link of this relay, at both ends where the other end can be reached. */
    public void unlinkAll() {
        if (level instanceof ServerLevel serverLevel) {
            GlobalPos here = GlobalPos.of(serverLevel.dimension(), worldPosition);
            for (GlobalPos partner : partners) {
                RiftRelayBlockEntity other = relayAt(serverLevel, partner);
                if (other != null && other.partners.remove(here)) other.changed();
            }
        }
        partners.clear();
        changed();
    }

    private void changed() {
        setChanged();
        if (level == null || level.isClientSide()) return;
        // The block as it stands now: while the relay is being broken it is already gone, and must not be put back
        BlockState state = level.getBlockState(worldPosition);
        boolean linked = !partners.isEmpty();
        if (state.getBlock() instanceof RiftRelayBlock && state.getValue(RiftRelayBlock.LINKED) != linked) {
            level.setBlock(worldPosition, state.setValue(RiftRelayBlock.LINKED, linked), Block.UPDATE_CLIENTS);
        }
        ConduitNetworks.invalidate(level, worldPosition);
        // The links go to the clients too, so a held rift tuner can show them
        BlockState now = level.getBlockState(worldPosition);
        if (now.getBlock() instanceof RiftRelayBlock) level.sendBlockUpdated(worldPosition, now, now, Block.UPDATE_CLIENTS);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            synchronized (RiftRelayBlockEntity.class) {
                CLIENT_LOADED.add(this);
            }
        } else if (level instanceof ServerLevel serverLevel && !partners.isEmpty()) {
            // links to relays broken while this one was away go now (after the load, when neighbours have loaded too)
            serverLevel.getServer().execute(this::pruneDead);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            synchronized (RiftRelayBlockEntity.class) {
                CLIENT_LOADED.remove(this);
            }
        }
    }

    public long getLastPulse() {
        return lastPulse;
    }

    public void markPulse(long tick) {
        lastPulse = tick;
    }

    // The relay is being broken: the others must not keep pointing at nothing
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide()) unlinkAll();
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!partners.isEmpty()) output.store("partners", GlobalPos.CODEC.listOf(), List.copyOf(partners));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        partners.clear();
        input.read("partners", GlobalPos.CODEC.listOf()).ifPresent(partners::addAll);
        input.read("partner", GlobalPos.CODEC).ifPresent(partners::add); // worlds from before relays took several links
    }
}
