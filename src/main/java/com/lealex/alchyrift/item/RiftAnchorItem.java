package com.lealex.alchyrift.item;

import com.lealex.alchyrift.registry.ModRegistries;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * The rift anchor as an item. An anchor whose gate was set carries its destination: broken and placed again,
 * anywhere, it leads to the same room or gate. It glows and says so in its tooltip.
 */
public class RiftAnchorItem extends BlockItem {
    public RiftAnchorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModRegistries.BOUND_ROOM.get()) || stack.has(ModRegistries.BOUND_GATE.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        Integer room = stack.get(ModRegistries.BOUND_ROOM.get());
        GlobalPos gate = stack.get(ModRegistries.BOUND_GATE.get());
        if (room != null) {
            tooltip.accept(Component.translatable("tooltip.alchyrift.rift_anchor.bound", room).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else if (gate != null) {
            tooltip.accept(Component.translatable("tooltip.alchyrift.rift_anchor.bound_gate",
                    gate.pos().getX() + " " + gate.pos().getY() + " " + gate.pos().getZ()).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            tooltip.accept(Component.translatable("tooltip.alchyrift.rift_anchor.empty").withStyle(ChatFormatting.GRAY));
        }
    }
}
