package com.lealex.alchyrift.block.entity;

import com.lealex.alchyrift.Config;
import com.lealex.alchyrift.block.RiftAnchorBlock;
import com.lealex.alchyrift.block.RiftPortBlock;
import com.lealex.alchyrift.conduit.ConduitNetworks;
import com.lealex.alchyrift.conduit.RiftConduitBlock;
import com.lealex.alchyrift.gate.GateControl;
import com.lealex.alchyrift.gate.GateMessages;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.room.RiftRooms;
import com.lealex.alchyrift.room.RiftTravel;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyx.block.entity.ChamberShellBlockEntity;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.multiblock.MultiblockPattern;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The rift gate: forming, breaking and the wave come from AlchyX.
 *
 * A formed gate is closed until its destination is set on its screen: a room, or another gate of the same owner.
 * Then the rift tears open in its frame with a blast, the gate's floor takes the look of the ground on the other
 * side, and a player who walks into the rift crosses. A rift gate is set once; a greater rift gate can be set again
 * at any time. The destination stays with the anchor when it is broken and placed again.
 */
public class RiftAnchorBlockEntity extends MultiblockCoreBlockEntity {
    /** How far into the rift a player must be, so brushing its edge does not count. */
    private static final double RIFT_MARGIN = 0.2;

    /** Leads to this room (-1: not to a room). */
    private int roomId = -1;
    /** Leads to this other gate (null: not to a gate). */
    private @Nullable GlobalPos exitGate;
    private @Nullable UUID owner;
    private String ownerName = "";
    private String gateName = "";
    /** Whether lightning jumps from the gate's frame into its rift (a look only; its owner's choice). */
    private boolean lightning = true;
    /** Game time its destination was last chosen, the instant its rift tore open (0: never). Clients draw the blast from it. */
    private long openedAt;
    /** The floor still has to take (or lose) the look of where the gate leads; checked once after loading too. */
    private boolean floorPending = true;
    /** Game time the floor started changing: it spreads from the rift, {@link #FLOOR_SPREAD} ticks per block. */
    private long floorFrom;
    private static final int FLOOR_SPREAD = 7;
    /**
     * Floor blocks the owner paved with a block of their own (rift tuner in hand, the block in the other): these keep
     * that look whatever the gate leads to. The block itself is kept here, and comes back when the paving is lifted
     * or the gate breaks.
     */
    private final Map<BlockPos, BlockState> paving = new HashMap<>();

    private record Paved(BlockPos pos, BlockState state) {
        static final Codec<List<Paved>> CODEC = RecordCodecBuilder.<Paved>create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Paved::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Paved::state)
        ).apply(i, Paved::new)).listOf();
    }

    public RiftAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.RIFT_ANCHOR_BE.get(), pos, state);
    }

    /** The shape of this gate, lesser or greater, from its anchor block. */
    public RiftAnchorBlock.Gate gate() {
        return getBlockState().getBlock() instanceof RiftAnchorBlock anchor ? anchor.gate() : RiftAnchorBlock.LESSER;
    }

    @Override
    public Identifier patternId() {
        return gate().pattern();
    }

    @Override
    protected String machineName() {
        return "Rift gate";
    }

    // ---- Whose it is, what it is called, where it leads ----

    /** The room this gate leads to, or -1. */
    public int getRoomId() {
        return roomId;
    }

    /** The gate this gate leads to, or null. */
    public @Nullable GlobalPos getExitGate() {
        return exitGate;
    }

    /** Whether the destination has been chosen. It is chosen once. */
    public boolean isSet() {
        return roomId >= 0 || exitGate != null;
    }

    public @Nullable UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
        setChanged();
        syncRegistry();
    }

    /** Its own name, or "Gate x y z" for one that never got any. */
    public String getGateName() {
        return gateName.isBlank() ? "Gate " + worldPosition.getX() + " " + worldPosition.getY() + " " + worldPosition.getZ() : gateName;
    }

    public void setGateName(String name) {
        this.gateName = name;
        setChanged();
        syncRegistry();
    }

    public boolean hasLightning() {
        return lightning;
    }

    public void setLightning(boolean lightning) {
        this.lightning = lightning;
        setChanged();
        syncToClients(); // the client draws it
    }

    public void setDestinationRoom(int roomId) {
        this.roomId = roomId;
        this.exitGate = null;
        destinationChanged();
        tearOpen();
    }

    public void setExitGate(GlobalPos exitGate) {
        this.exitGate = exitGate;
        this.roomId = -1;
        destinationChanged();
        tearOpen();
    }

    /** Client: the game time the rift last tore open (0: never), for the blast. */
    public long openedAt() {
        return openedAt;
    }

    /**
     * The rift tears open, violently: a blast of sound and debris at the frame, and whoever stands close is thrown
     * back (never hurt). Clients draw the rest from {@link #openedAt}: the flash, the shockwaves, the rift slamming
     * wide and settling (client/Rifts).
     */
    private void tearOpen() {
        if (!(level instanceof ServerLevel serverLevel) || !isFormed()) return;
        openedAt = serverLevel.getGameTime();
        setChanged();
        syncToClients();
        Vec3 middle = riftMiddle();
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.4F, 0.55F);
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 2.0F, 0.5F);
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.2F, 0.6F);
        serverLevel.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFF0DEFF), middle.x, middle.y, middle.z, 1, 0, 0, 0, 0);
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, middle.x, middle.y, middle.z, 4, 0.6, 0.8, 0.6, 0);
        serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL, middle.x, middle.y, middle.z, 140, 0.4, 0.8, 0.4, 0.9);
        serverLevel.sendParticles(ParticleTypes.END_ROD, middle.x, middle.y, middle.z, 50, 0.3, 0.7, 0.3, 0.35);
        serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OBSIDIAN.defaultBlockState()),
                middle.x, middle.y, middle.z, 60, 0.6, 0.9, 0.6, 0.3);
        serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                middle.x, middle.y, middle.z, 40, 0.6, 0.9, 0.6, 0.3);
        if (Config.openingPush()) {
            for (LivingEntity near : serverLevel.getEntitiesOfClass(LivingEntity.class, new AABB(middle, middle).inflate(5.0))) {
                Vec3 away = near.position().add(0, near.getBbHeight() / 2, 0).subtract(middle);
                double distance = Math.max(0.6, away.length());
                if (distance > 5.0) continue;
                Vec3 push = away.normalize().scale(1.1 * (1 - distance / 5.5));
                near.setDeltaMovement(near.getDeltaMovement().add(push.x, 0.25 + push.y * 0.3, push.z));
                near.hurtMarked = true; // sent to the player it moves
            }
        }
    }

    private Vec3 riftMiddle() {
        Rotation rotation = rotation();
        BlockPos a = worldPosition.offset(gate().riftMin().rotate(rotation)), b = worldPosition.offset(gate().riftMax().rotate(rotation));
        return new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, (a.getY() + b.getY()) / 2.0 + 0.5, (a.getZ() + b.getZ()) / 2.0 + 0.5);
    }

    // ---- The floor takes the look of where the gate leads ----

    /** The dimension this gate leads into, or null. */
    private @Nullable ResourceKey<Level> destinationDimension() {
        if (roomId >= 0) return RoomLayout.DIMENSION;
        return exitGate == null ? null : exitGate.dimension();
    }

    /** Whether this is one of the gate's floor blocks (the layer the anchor stands on), while the gate is formed. */
    private boolean isFloor(BlockPos pos) {
        return isFormed() && level != null && pos.getY() == worldPosition.getY() - 1 && body().contains(pos)
                && level.getBlockEntity(pos) instanceof ChamberShellBlockEntity;
    }

    /**
     * The rift tuner used on a block of the gate, with {@code held} in the player's other hand. On a floor block: a
     * plain full block in that hand paves it (one is taken; what paved it before comes back); anything else in that
     * hand lifts the paving, and the floor takes the look of where the gate leads again. Returns false when the block
     * is not this gate's floor, so the tuner may do something else with it.
     */
    public boolean pave(ServerPlayer player, BlockPos pos, ItemStack held) {
        if (!isFloor(pos) || !(level instanceof ServerLevel serverLevel)
                || !(serverLevel.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell)) return false;
        if (owner != null && !owner.equals(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("message.alchyrift.gate.floor_not_yours"), true);
            return true;
        }
        BlockState before = paving.get(pos);
        if (held.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(serverLevel, pos) || state.getDestroySpeed(serverLevel, pos) < 0) {
                player.sendSystemMessage(Component.translatable("message.alchyrift.gate.floor_refused"), true);
                return true;
            }
            if (state == before) return true;
            if (before != null) giveBack(player, before);
            paving.put(pos.immutable(), state);
            shell.setDisguise(state);
            held.consume(1, player);
            serverLevel.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
            player.sendSystemMessage(Component.translatable("message.alchyrift.gate.floor_paved"), true);
        } else if (before != null) {
            paving.remove(pos);
            giveBack(player, before);
            floorChanges(); // it takes the look of where the gate leads again
            player.sendSystemMessage(Component.translatable("message.alchyrift.gate.floor_lifted"), true);
        } else {
            player.sendSystemMessage(Component.translatable("message.alchyrift.gate.floor_hint"), true);
        }
        setChanged();
        return true;
    }

    private static void giveBack(ServerPlayer player, BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        if (!stack.isEmpty() && !player.getAbilities().instabuild && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** The gate is gone: the blocks its floor was paved with drop where they lay. */
    private void dropPaving() {
        if (level instanceof ServerLevel) {
            paving.forEach((pos, state) -> Block.popResource(level, pos, new ItemStack(state.getBlock())));
        }
        if (!paving.isEmpty()) setChanged();
        paving.clear();
    }

    private void floorChanges() {
        floorPending = true;
        floorFrom = level == null ? 0 : level.getGameTime();
    }

    /**
     * While pending, every tick: each block of the gate's floor (the layer the anchor stands on) turns, in its turn
     * outward from the rift, into the ground of the dimension the gate leads to (Config.floorBlock: grass for the
     * overworld, netherrack, void stone for a room...), or back into the gate's own floor when it leads nowhere or
     * to a dimension the config doesn't list. Only the look of AlchyX's shells changes: the gate stays formed.
     */
    private void tickFloor(ServerLevel serverLevel) {
        MultiblockPattern pattern = pattern();
        if (pattern == null) {
            floorPending = false;
            return;
        }
        ResourceKey<Level> destination = isSet() ? destinationDimension() : null;
        BlockState ground = destination == null ? null : Config.floorBlock(destination);
        Vec3 middle = riftMiddle();
        long elapsed = serverLevel.getGameTime() - floorFrom;
        boolean left = false;
        for (MultiblockPattern.Conversion conversion : pattern.conversions(worldPosition, rotation())) {
            BlockPos pos = conversion.pos();
            if (pos.getY() != worldPosition.getY() - 1 || conversion.result().real()) continue;
            if (!(serverLevel.getBlockEntity(pos) instanceof ChamberShellBlockEntity shell)) continue;
            BlockState paved = paving.get(pos);
            if (paved != null) { // the owner's own block: it stays, whatever the gate leads to
                if (shell.getDisguise() != paved) shell.setDisguise(paved);
                continue;
            }
            BlockState own = conversion.disguise(Blocks.AIR.defaultBlockState());
            BlockState wanted = ground != null ? ground : own;
            if (wanted.isAir() || shell.getDisguise() == wanted) continue;
            double distance = Math.sqrt(Mth.square(pos.getX() + 0.5 - middle.x) + Mth.square(pos.getZ() + 0.5 - middle.z));
            if (elapsed < (long) (distance * FLOOR_SPREAD)) {
                left = true;
                continue;
            }
            shell.setDisguise(wanted);
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wanted), pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5,
                    10, 0.3, 0.05, 0.3, 0.1);
            serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 6, 0.3, 0.05, 0.3, 0.02);
            serverLevel.playSound(null, pos, wanted.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.6F, 0.7F);
        }
        floorPending = left;
    }

    /** A greater gate forgets where it leads: its rift closes until a destination is chosen again. */
    public void clearDestination() {
        if (!gate().changeable()) return;
        refreshLinks(); // while it still knows its room
        this.roomId = -1;
        this.exitGate = null;
        destinationChanged();
    }

    private void destinationChanged() {
        setChanged();
        syncRegistry();
        refreshLinks();
        updateRift();
        floorChanges();
    }

    /** The world's list of gates knows this one while it stands formed and owned. */
    private void syncRegistry() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        RiftRooms rooms = RiftRooms.get(serverLevel.getServer());
        GlobalPos here = GlobalPos.of(serverLevel.dimension(), worldPosition);
        if (isFormed() && owner != null) {
            rooms.putGate(new RiftRooms.Gate(here, owner, ownerName, getGateName(), roomId));
        } else {
            rooms.removeGate(here);
        }
    }

    // ---- The screen ----

    @Override
    public void openGui(Player player) {
        if (isFormed() && player instanceof ServerPlayer serverPlayer) {
            GateControl.open(serverPlayer, this);
        } else {
            super.openGui(player); // not formed: what is missing
        }
    }

    // ---- Forming, breaking ----

    @Override
    protected void onStructureChanged(boolean nowFormed) {
        syncRegistry();
        refreshLinks();
        updateRift();
        refreshConduits();
        if (!nowFormed) dropPaving();
        if (nowFormed) {
            // A gate formed around an anchor that already leads somewhere: its rift tears open as the last block turns
            floorChanges();
            if (isSet()) tearOpen();
        }
    }

    /** Every block of the gate (frame, floor, stabilizer, this anchor): what a conduit may touch to reach it. */
    public Set<BlockPos> body() {
        MultiblockPattern pattern = pattern();
        return pattern == null ? Set.of(worldPosition) : pattern.occupiedPositions(worldPosition, rotation());
    }

    /**
     * The conduits standing against the gate look again at what they touch: a gate's blocks only count as a junction
     * while it is formed, and forming or breaking changes none of the blocks they are looking at.
     */
    private void refreshConduits() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        for (BlockPos part : body()) {
            for (Direction direction : Direction.values()) RiftConduitBlock.refresh(serverLevel, part.relative(direction));
        }
    }

    // The anchor itself is being broken: its rift closes and lines through the gate stop at once
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            RiftRooms.get(serverLevel.getServer()).removeGate(GlobalPos.of(serverLevel.dimension(), worldPosition));
            closeRift(serverLevel);
        }
        refreshLinks();
        if (level instanceof ServerLevel serverLevel) {
            // Once the anchor is gone, the conduits around what was the gate let go of it
            List<BlockPos> around = new ArrayList<>();
            for (BlockPos part : body()) {
                for (Direction direction : Direction.values()) around.add(part.relative(direction));
            }
            serverLevel.getServer().execute(() -> around.forEach(beside -> RiftConduitBlock.refresh(serverLevel, beside)));
        }
        dropPaving();
        super.preRemoveSideEffects(pos, state);
    }

    /** The conduit lines through this gate (stabilizer outside, ports inside) are worked out again. */
    private void refreshLinks() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ConduitNetworks.invalidate(serverLevel, stabilizerPos());
        RiftRooms.Room room = roomId < 0 ? null : RiftRooms.get(serverLevel.getServer()).get(roomId);
        if (room != null) RiftPortBlock.refreshLinks(serverLevel.getServer(), room);
    }

    // ---- The rift in the frame ----

    /** Opens the rift when the gate is formed and set, closes it otherwise. */
    private void updateRift() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isFormed() || !isSet()) {
            closeRift(serverLevel);
            return;
        }
        BlockState crack = ModRegistries.RIFT_CRACK.get().defaultBlockState();
        for (BlockPos pos : riftCells()) {
            BlockState there = serverLevel.getBlockState(pos);
            if (!there.is(crack.getBlock()) && (there.isAir() || there.canBeReplaced())) {
                serverLevel.setBlock(pos, crack, Block.UPDATE_ALL);
            }
        }
    }

    private void closeRift(ServerLevel serverLevel) {
        boolean wasOpen = false;
        for (BlockPos pos : riftCells()) {
            if (serverLevel.getBlockState(pos).is(ModRegistries.RIFT_CRACK)) {
                serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                wasOpen = true;
            }
        }
        if (wasOpen) collapse(serverLevel);
    }

    /**
     * A rift that was open closes (the gate leads elsewhere now, or it was broken): it falls in on itself. The sound
     * and what is sucked in come from here; clients nearby are told, and draw the rest (client/Rifts: the tear
     * shrinking, shockwaves running inward, a last spark).
     */
    private void collapse(ServerLevel serverLevel) {
        Rotation rotation = rotation();
        BlockPos a = worldPosition.offset(gate().riftMin().rotate(rotation)), b = worldPosition.offset(gate().riftMax().rotate(rotation));
        Vec3 middle = riftMiddle();
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.4F, 0.45F);
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 1.6F, 0.5F);
        serverLevel.playSound(null, middle.x, middle.y, middle.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0F, 1.6F);
        // Portal motes are born away from their point and fall into it
        serverLevel.sendParticles(ParticleTypes.PORTAL, middle.x, middle.y - 0.5, middle.z, 160, 0.3, 0.5, 0.3, 1.6);
        serverLevel.sendParticles(ParticleTypes.SMOKE, middle.x, middle.y, middle.z, 30, 0.4, 0.8, 0.4, 0.01);
        GateMessages.Closed closed = new GateMessages.Closed(a, b, rotation.rotate(Direction.SOUTH).getAxis() == Direction.Axis.X, gate().changeable());
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(middle) <= 128 * 128) PacketDistributor.sendToPlayer(player, closed);
        }
    }

    private Iterable<BlockPos> riftCells() {
        Rotation rotation = rotation();
        return BlockPos.betweenClosed(worldPosition.offset(gate().riftMin().rotate(rotation)), worldPosition.offset(gate().riftMax().rotate(rotation)));
    }

    @Override
    protected void tickFormed() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (floorPending) tickFloor(serverLevel);
        if (serverLevel.getGameTime() % 20 == 0) {
            if (roomId >= 0 && RiftRooms.get(serverLevel.getServer()).get(roomId) == null) {
                // Its room was deleted: the gate leads nowhere again (even one that is set once), and its rift closes
                roomId = -1;
                destinationChanged();
            }
            updateRift(); // a rift block removed some other way comes back
            syncRegistry(); // and gates formed before the list of gates existed get into it
        }
        if (!isSet()) return;
        Rotation rotation = rotation();
        AABB inside = AABB.encapsulatingFullBlocks(
                worldPosition.offset(gate().riftMin().rotate(rotation)), worldPosition.offset(gate().riftMax().rotate(rotation)))
                .deflate(RIFT_MARGIN, 0, RIFT_MARGIN);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, inside)) {
            if (!player.isOnPortalCooldown() && !player.isSpectator()) RiftTravel.enter(player, this);
        }
    }

    /** The block of the rift stabilizer (both sides). */
    public BlockPos stabilizerPos() {
        return worldPosition.offset(gate().stabilizer().rotate(rotation()));
    }

    /** The block a player steps back out onto, in front of the platform. */
    public BlockPos wayOut() {
        return worldPosition.offset(gate().wayOut().rotate(rotation()));
    }

    // ---- The destination travels with the anchor: block -> item -> block ----

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        roomId = components.getOrDefault(ModRegistries.BOUND_ROOM.get(), -1);
        exitGate = components.get(ModRegistries.BOUND_GATE.get());
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (roomId >= 0) components.set(ModRegistries.BOUND_ROOM.get(), roomId);
        if (exitGate != null) components.set(ModRegistries.BOUND_GATE.get(), exitGate);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("room");
        output.discard("exit_gate");
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("room", roomId);
        if (exitGate != null) output.store("exit_gate", GlobalPos.CODEC, exitGate);
        if (owner != null) output.store("owner", UUIDUtil.CODEC, owner);
        output.putString("owner_name", ownerName);
        output.putString("gate_name", gateName);
        output.putBoolean("lightning", lightning);
        output.putLong("opened_at", openedAt);
        if (!paving.isEmpty()) {
            List<Paved> paved = new ArrayList<>();
            paving.forEach((pos, state) -> paved.add(new Paved(pos, state)));
            output.store("paving", Paved.CODEC, paved);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        roomId = input.getIntOr("room", -1);
        exitGate = input.read("exit_gate", GlobalPos.CODEC).orElse(null);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        gateName = input.getStringOr("gate_name", "");
        lightning = input.getBooleanOr("lightning", true);
        openedAt = input.getLongOr("opened_at", 0L);
        paving.clear();
        input.read("paving", Paved.CODEC).ifPresent(paved -> paved.forEach(one -> paving.put(one.pos(), one.state())));
    }
}
