package com.lealex.alchyrift.item;

import com.lealex.alchyrift.conduit.RiftConduitBlock;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.relay.RiftRelayBlock;
import com.lealex.alchyrift.relay.RiftRelayBlockEntity;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyx.block.ChamberShellBlock;
import com.lealex.alchyx.item.MachineTool;
import com.lealex.alchyx.multiblock.CoreTracker;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The rift tuner.
 * <ul>
 *   <li>On the end of a conduit: switches that end between pushing into the block it touches and pulling from it
 *       (handled by the conduit).</li>
 *   <li>On a rift relay, then on a second one: links the two (a relay takes several links, so relays form groups);
 *       on two already linked: removes that link. On a relay while sneaking: removes all its links.</li>
 *   <li>On a floor block of a rift gate, with a plain block in the other hand: paves the floor with it (the gate's
 *       owner; the paving keeps its look whatever the gate leads to). With anything else in the other hand: lifts
 *       it.</li>
 *   <li>Sneak + use in the air: forgets the first relay.</li>
 * </ul>
 * A machine tool for AlchyX, so machines let it act on them and don't open their screen.
 */
public class RiftTunerItem extends Item implements MachineTool {
    public RiftTunerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        // Sneaking on a conduit's end: its surge crystals come off (the conduit handles a plain use itself)
        if (level.getBlockState(clicked).getBlock() instanceof RiftConduitBlock && context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            if (level instanceof ServerLevel conduitLevel) {
                BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), clicked, context.isInside());
                tell(context.getPlayer(), Component.translatable(RiftConduitBlock.popSurges(conduitLevel, clicked, level.getBlockState(clicked), hit)
                        ? "message.alchyrift.surge.removed" : "message.alchyrift.surge.none"));
            }
            return InteractionResult.SUCCESS;
        }
        // On a machine's block: if it is the floor of a rift gate, its owner paves it with the block in their other
        // hand (or lifts the paving with anything else there). The tuner never falls through to that other hand.
        if (level.getBlockState(clicked).getBlock() instanceof ChamberShellBlock) {
            if (level instanceof ServerLevel gateLevel && context.getPlayer() instanceof ServerPlayer paver
                    && CoreTracker.formedCoreAt(gateLevel, clicked) instanceof RiftAnchorBlockEntity anchor) {
                InteractionHand other = context.getHand() == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                anchor.pave(paver, clicked, paver.getItemInHand(other));
            }
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(clicked) instanceof RiftRelayBlockEntity relay)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        GlobalPos here = GlobalPos.of(level.dimension(), clicked);

        if (player != null && player.isShiftKeyDown()) {
            relay.unlinkAll();
            stack.remove(ModRegistries.TUNED_TO.get());
            tell(player, Component.translatable("message.alchyrift.relay.unlinked"));
            return InteractionResult.SUCCESS;
        }

        GlobalPos first = stack.get(ModRegistries.TUNED_TO.get());
        if (first == null || first.equals(here)) {
            stack.set(ModRegistries.TUNED_TO.get(), here);
            tell(player, Component.translatable("message.alchyrift.relay.first"));
            level.playSound(null, clicked, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f);
            return InteractionResult.SUCCESS;
        }

        ServerLevel firstLevel = serverLevel.getServer().getLevel(first.dimension());
        if (firstLevel == null || !firstLevel.isLoaded(first.pos())
                || !(firstLevel.getBlockEntity(first.pos()) instanceof RiftRelayBlockEntity other)) {
            stack.remove(ModRegistries.TUNED_TO.get());
            tell(player, Component.translatable("message.alchyrift.relay.gone"));
            return InteractionResult.FAIL;
        }
        if (other.getBlockState().getBlock() != relay.getBlockState().getBlock()) {
            tell(player, Component.translatable("message.alchyrift.relay.different"));
            return InteractionResult.FAIL;
        }
        if (!RiftRelayBlock.inReach(relay.tier(), first, here)) {
            tell(player, Component.translatable("message.alchyrift.relay.too_far", RiftRelayBlock.LESSER_REACH));
            return InteractionResult.FAIL;
        }
        // The same two again: that one link is let go
        if (relay.isLinkedTo(first)) {
            relay.unlink(other);
            stack.remove(ModRegistries.TUNED_TO.get());
            tell(player, Component.translatable("message.alchyrift.relay.link_removed"));
            return InteractionResult.SUCCESS;
        }
        if (relay.getPartners().size() >= RiftRelayBlockEntity.MAX_LINKS || other.getPartners().size() >= RiftRelayBlockEntity.MAX_LINKS) {
            tell(player, Component.translatable("message.alchyrift.relay.full", RiftRelayBlockEntity.MAX_LINKS));
            return InteractionResult.FAIL;
        }
        relay.link(other);
        stack.remove(ModRegistries.TUNED_TO.get());
        tell(player, Component.translatable("message.alchyrift.relay.linked"));
        level.playSound(null, clicked, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.4f);
        return InteractionResult.SUCCESS;
    }

    // Sneak + use in the air: forget the first relay
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.has(ModRegistries.TUNED_TO.get())) {
            if (!level.isClientSide()) {
                stack.remove(ModRegistries.TUNED_TO.get());
                tell(player, Component.translatable("message.alchyrift.relay.cleared"));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // Glows while it holds a first relay
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModRegistries.TUNED_TO.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        GlobalPos first = stack.get(ModRegistries.TUNED_TO.get());
        tooltip.accept(first == null
                ? Component.translatable("tooltip.alchyrift.rift_tuner.empty").withStyle(ChatFormatting.GRAY)
                : Component.translatable("tooltip.alchyrift.rift_tuner.tuned",
                        first.pos().getX() + " " + first.pos().getY() + " " + first.pos().getZ()).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    private static void tell(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(message, true);
    }
}
