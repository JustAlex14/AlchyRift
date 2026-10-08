package com.lealex.alchyrift.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * The rift sieve: set on an end of an item or fluid conduit (the conduit handles the click), it chooses what that end
 * lets through. It stays on the conduit until taken off from its screen, or until the conduit is broken. The greater
 * sieve also tells items apart by their data (enchantments, names, what a shulker box holds...).
 */
public class RiftFilterItem extends Item {
    private final boolean advanced;

    public RiftFilterItem(boolean advanced, Properties properties) {
        super(properties);
        this.advanced = advanced;
    }

    /** Whether this is the greater sieve. */
    public boolean advanced() {
        return advanced;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.alchyrift.rift_filter").withStyle(ChatFormatting.GRAY));
        if (advanced) tooltip.accept(Component.translatable("tooltip.alchyrift.greater_rift_filter").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
