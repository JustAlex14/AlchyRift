package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.item.RiftTunerItem;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.relay.RiftRelayBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * While the player holds a rift tuner, the links of the relays around show as lines of motes: violet between lesser
 * relays, teal between greater ones. A link that leaves the dimension shows as a swirl on the relay. The relay the
 * tuner is holding (first of a pair being linked) sparkles.
 */
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public final class RelayLinks {
    private RelayLinks() {}

    private static final double RANGE = 48.0;
    private static final int EVERY = 4;
    /** Blocks between two motes of a line, and the most motes one line gets. */
    private static final double SPACING = 0.5;
    private static final int MAX_MOTES = 48;
    private static final DustParticleOptions LESSER = new DustParticleOptions(0xB26BFF, 0.7F);
    private static final DustParticleOptions GREATER = new DustParticleOptions(0x5FE3D2, 0.7F);

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || level.getGameTime() % EVERY != 0) return;
        ItemStack tuner = heldTuner(player);
        if (tuner == null) return;

        GlobalPos held = tuner.get(ModRegistries.TUNED_TO.get());
        for (RiftRelayBlockEntity relay : RiftRelayBlockEntity.clientLoaded()) {
            if (relay.isRemoved() || relay.getLevel() != level) continue;
            BlockPos pos = relay.getBlockPos();
            Vec3 from = Vec3.atCenterOf(pos);
            if (from.distanceToSqr(player.position()) > RANGE * RANGE) continue;
            DustParticleOptions mote = relay.tier() >= 2 ? GREATER : LESSER;
            for (GlobalPos partner : relay.getPartners()) {
                if (!partner.dimension().equals(level.dimension())) {
                    // Linked into another dimension: nothing to draw a line to
                    level.addParticle(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 0.4, from.z, 0, 0.02, 0);
                    continue;
                }
                // Each link once, from its lower end, unless the other end isn't drawing (out of range or not loaded)
                boolean otherDraws = partner.pos().asLong() < pos.asLong()
                        && Vec3.atCenterOf(partner.pos()).distanceToSqr(player.position()) <= RANGE * RANGE
                        && level.getBlockEntity(partner.pos()) instanceof RiftRelayBlockEntity;
                if (!otherDraws) line(level, from, Vec3.atCenterOf(partner.pos()), mote);
            }
            if (held != null && held.dimension().equals(level.dimension()) && held.pos().equals(pos)) {
                level.addParticle(ParticleTypes.END_ROD, from.x, from.y + 0.5, from.z,
                        (level.getRandom().nextDouble() - 0.5) * 0.05, 0.04, (level.getRandom().nextDouble() - 0.5) * 0.05);
            }
        }
    }

    private static ItemStack heldTuner(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof RiftTunerItem) return stack;
        }
        return null;
    }

    private static void line(ClientLevel level, Vec3 from, Vec3 to, DustParticleOptions mote) {
        Vec3 along = to.subtract(from);
        double length = along.length();
        int motes = (int) Math.min(MAX_MOTES, Math.max(2, length / SPACING));
        // The motes drift along the line from tick to tick, so a link reads as a flow and not as a row of dots
        double shift = (level.getGameTime() / (double) EVERY % 4) / 4.0;
        for (int i = 0; i < motes; i++) {
            double t = (i + shift) / motes;
            level.addParticle(mote, from.x + along.x * t, from.y + along.y * t, from.z + along.z * t, 0, 0, 0);
        }
    }
}
