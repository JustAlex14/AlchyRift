package com.lealex.alchyrift.conduit;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.registry.ModRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * What goes between the server and the sieve screen: the server tells a player what the sieve on a conduit's end
 * lets through, the screen sends back each change. The screen never sends an item, only which of the player's own
 * inventory slots to read it from; everything is checked again on the server.
 */
@EventBusSubscriber(modid = AlchyRift.MODID)
public final class FilterMessages {
    private FilterMessages() {}

    /** Client: opens (or refreshes) the sieve screen. Set by the client entry point, null on a dedicated server. */
    public static @Nullable Consumer<Screen> clientOpener;

    /**
     * Server to client: the sieve on the {@code side} of the conduit at {@code pos}.
     *
     * @param fluid    a fluid conduit's: its entries are the fluids' buckets
     * @param deny     false: only these pass; true: everything but these
     * @param advanced a greater sieve
     * @param exact    a greater sieve: items must also carry the same data
     */
    public record Screen(BlockPos pos, Direction side, boolean fluid, List<ItemStack> items, boolean deny, boolean advanced,
                         boolean exact) implements CustomPacketPayload {
        public static final Type<Screen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "filter_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Screen> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeBlockPos(data.pos);
                    buf.writeEnum(data.side);
                    buf.writeBoolean(data.fluid);
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, data.items);
                    buf.writeBoolean(data.deny);
                    buf.writeBoolean(data.advanced);
                    buf.writeBoolean(data.exact);
                },
                buf -> new Screen(buf.readBlockPos(), buf.readEnum(Direction.class), buf.readBoolean(), ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf),
                        buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What a player does on the sieve screen. */
    public enum Kind {
        /** Name the item in slot {@code number} of the player's inventory. */
        ADD,
        /** Stop naming entry {@code number} of the sieve. */
        REMOVE,
        /** Switch between "only these" and "all but these". */
        TOGGLE,
        /** A greater sieve: switch between matching an item with its data and matching the item alone. */
        TOGGLE_EXACT,
        /** Take the sieve off the conduit; the player gets it back. */
        UNINSTALL
    }

    /** Client to server: one action on the sieve of the conduit at {@code pos}, side {@code side}. */
    public record Action(BlockPos pos, Direction side, Kind kind, int number) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "filter_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> STREAM_CODEC = StreamCodec.of(
                (buf, action) -> {
                    buf.writeBlockPos(action.pos);
                    buf.writeEnum(action.side);
                    buf.writeEnum(action.kind);
                    buf.writeVarInt(action.number);
                },
                buf -> new Action(buf.readBlockPos(), buf.readEnum(Direction.class), buf.readEnum(Kind.class), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Screen.TYPE, Screen.STREAM_CODEC, (data, context) -> {
            if (clientOpener != null) clientOpener.accept(data);
        });
        registrar.playToServer(Action.TYPE, Action.STREAM_CODEC, (action, context) -> {
            if (context.player() instanceof ServerPlayer player) handle(player, action);
        });
    }

    /** Shows a player the sieve on a conduit's end. */
    public static void open(ServerPlayer player, BlockPos pos, Direction side, ConduitKind kind) {
        ConduitFilters.Filter filter = ConduitFilters.get(player.level().getServer()).at(player.level().dimension(), pos, side);
        if (filter == null) return;
        PacketDistributor.sendToPlayer(player,
                new Screen(pos, side, kind == ConduitKind.FLUID, filter.items(), filter.deny(), filter.advanced(), filter.exact()));
    }

    /** The item a sieve gives back when it comes off. */
    public static ItemStack sieveItem(ConduitFilters.Filter filter) {
        return new ItemStack(filter.advanced() ? ModRegistries.GREATER_RIFT_FILTER.get() : ModRegistries.RIFT_FILTER.get());
    }

    private static void handle(ServerPlayer player, Action action) {
        ServerLevel level = player.level();
        BlockPos pos = action.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RiftConduitBlock conduit)) return;
        ConduitFilters filters = ConduitFilters.get(level.getServer());
        ConduitFilters.Filter filter = filters.at(level.dimension(), pos, action.side());
        if (filter == null) return;
        List<ItemStack> items = new ArrayList<>(filter.items());
        switch (action.kind()) {
            case ADD -> {
                if (action.number() < 0 || action.number() >= player.getInventory().getContainerSize() || items.size() >= ConduitFilters.SIZE) break;
                ItemStack held = player.getInventory().getItem(action.number());
                if (held.isEmpty()) break;
                // A plain sieve only remembers what the item is; a greater one keeps it whole
                ItemStack named = filter.advanced() ? held.copyWithCount(1) : new ItemStack(held.getItem());
                boolean known = items.stream().anyMatch(item -> ItemStack.isSameItemSameComponents(item, named));
                if (known) break;
                items.add(named);
                filters.set(level.dimension(), pos, action.side(), new ConduitFilters.Filter(List.copyOf(items), filter.deny(), filter.advanced(), filter.exact()));
            }
            case REMOVE -> {
                if (action.number() < 0 || action.number() >= items.size()) break;
                items.remove(action.number());
                filters.set(level.dimension(), pos, action.side(), new ConduitFilters.Filter(List.copyOf(items), filter.deny(), filter.advanced(), filter.exact()));
            }
            case TOGGLE -> filters.set(level.dimension(), pos, action.side(),
                    new ConduitFilters.Filter(filter.items(), !filter.deny(), filter.advanced(), filter.exact()));
            case TOGGLE_EXACT -> {
                if (filter.advanced()) {
                    filters.set(level.dimension(), pos, action.side(),
                            new ConduitFilters.Filter(filter.items(), filter.deny(), true, !filter.exact()));
                }
            }
            case UNINSTALL -> {
                ConduitFilters.Filter removed = filters.remove(level.dimension(), pos, action.side());
                if (removed != null) {
                    ItemStack sieve = sieveItem(removed);
                    if (!player.getInventory().add(sieve)) player.drop(sieve, false);
                }
                return; // the screen closes itself
            }
        }
        open(player, pos, action.side(), conduit.kind());
    }
}
