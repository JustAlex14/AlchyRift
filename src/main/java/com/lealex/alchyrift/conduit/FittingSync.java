package com.lealex.alchyrift.conduit;

import com.lealex.alchyrift.AlchyRift;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * Tells clients which conduit ends carry a sieve or surge crystals, so they can draw them (client/ConduitFittings):
 * the whole list, to a player who joins and to everyone whenever it changes. It only changes when a player sets or
 * takes one off, and an entry is a position and three small numbers.
 */
@EventBusSubscriber(modid = AlchyRift.MODID)
public final class FittingSync {
    private FittingSync() {}

    /** Client: takes the new list. Set by the client entry point, null on a dedicated server. */
    public static @Nullable Consumer<Fittings> clientReceiver;

    /**
     * What one conduit end carries.
     *
     * @param sieve 0 none, 1 a rift sieve, 2 a greater one
     * @param surge how many surge crystals
     */
    public record Fitting(GlobalPos conduit, Direction side, int sieve, int surge) {}

    /** Server to client: every fitted conduit end of the world. */
    public record Fittings(List<Fitting> all) implements CustomPacketPayload {
        public static final Type<Fittings> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "conduit_fittings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Fittings> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeVarInt(data.all.size());
                    for (Fitting fitting : data.all) {
                        GlobalPos.STREAM_CODEC.encode(buf, fitting.conduit);
                        buf.writeEnum(fitting.side);
                        buf.writeByte(fitting.sieve);
                        buf.writeByte(fitting.surge);
                    }
                },
                buf -> {
                    List<Fitting> all = new ArrayList<>();
                    for (int i = buf.readVarInt(); i > 0; i--) {
                        all.add(new Fitting(GlobalPos.STREAM_CODEC.decode(buf), buf.readEnum(Direction.class), buf.readByte(), buf.readByte()));
                    }
                    return new Fittings(all);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Fittings.TYPE, Fittings.STREAM_CODEC, (data, context) -> {
            if (clientReceiver != null) clientReceiver.accept(data);
        });
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new Fittings(ConduitFilters.get(player.level().getServer()).fittings()));
        }
    }

    /** Something was set on, or taken off, a conduit end: everyone gets the new list. */
    static void changed(MinecraftServer server, ConduitFilters filters) {
        PacketDistributor.sendToAllPlayers(new Fittings(filters.fittings()));
    }
}
