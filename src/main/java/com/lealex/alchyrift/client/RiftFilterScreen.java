package com.lealex.alchyrift.client;

import com.lealex.alchyrift.conduit.ConduitFilters;
import com.lealex.alchyrift.conduit.FilterMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The sieve screen, for the sieve on one end of a conduit: the nine things it names on top (click one to stop naming
 * it), a button that switches between "only these" and "all but these", and under it the player's own inventory,
 * shown as it is in their hands: click an item to name it. Nothing is taken from the player, the sieve only
 * remembers what the item is (a greater sieve remembers it whole, with its data, and has a second switch for
 * whether that data must match). On a fluid conduit the fluids are named by their buckets.
 * <p>
 * It lies on the same slab of obsidian as the gate screen (RiftGateScreen draws both).
 */
public class RiftFilterScreen extends Screen {
    private static final int SLOT = 18, COLUMNS = 9, LINE = 14, PAD = 16;
    private static final int WIDTH = COLUMNS * SLOT;
    private static final int HEIGHT = LINE + SLOT + 4 + 20 + 6 + LINE + 4 * SLOT + 6 + 20;
    private static final int TITLE = 0xF0DEFF, HEADING = 0xC9A6FF;

    private FilterMessages.Screen data;
    private int ticks;

    private RiftFilterScreen(FilterMessages.Screen data) {
        super(Component.translatable(data.advanced() ? "screen.alchyrift.filter.greater" : "screen.alchyrift.filter"));
        this.data = data;
    }

    /** Opens the screen for a sieve, or refreshes it if it is already showing that one. */
    public static void show(FilterMessages.Screen data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RiftFilterScreen open && open.data.pos().equals(data.pos()) && open.data.side() == data.side()) {
            open.data = data;
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new RiftFilterScreen(data));
        }
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2, top = Math.max(PAD, (height - HEIGHT) / 2);
        label(title, left, top, TITLE);
        int y = top + LINE;
        // What the sieve names
        for (int i = 0; i < ConduitFilters.SIZE; i++) {
            int entry = i;
            boolean named = i < data.items().size();
            slot(left + i * SLOT, y, named ? data.items().get(i) : ItemStack.EMPTY, named ? () -> send(FilterMessages.Kind.REMOVE, entry) : null);
        }
        y += SLOT + 4;
        // A greater sieve has a second switch beside the first: whether an item's data must match too
        int modeWidth = data.advanced() ? (WIDTH - 4) / 2 : WIDTH;
        addRenderableWidget(Button.builder(Component.translatable(data.deny() ? "screen.alchyrift.filter.deny" : "screen.alchyrift.filter.allow"),
                button -> send(FilterMessages.Kind.TOGGLE, 0)).bounds(left, y, modeWidth, 20).build(RiftGateScreen.RiftButton::plain));
        if (data.advanced()) {
            addRenderableWidget(Button.builder(Component.translatable(data.exact() ? "screen.alchyrift.filter.exact" : "screen.alchyrift.filter.loose"),
                            button -> send(FilterMessages.Kind.TOGGLE_EXACT, 0)).bounds(left + WIDTH - modeWidth, y, modeWidth, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.alchyrift.filter.exact.tip"))).build(RiftGateScreen.RiftButton::plain));
        }
        y += 20 + 6;
        label(Component.translatable(data.fluid() ? "screen.alchyrift.filter.fluid" : "screen.alchyrift.filter.inventory"), left, y, HEADING);
        y += LINE;
        // The player's inventory, laid out as they know it: three rows, then the hotbar
        Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row < 3 ? 9 + row * 9 + column : column;
                ItemStack stack = inventory == null ? ItemStack.EMPTY : inventory.getItem(index);
                slot(left + column * SLOT, y + row * SLOT + (row == 3 ? 2 : 0), stack, stack.isEmpty() ? null : () -> send(FilterMessages.Kind.ADD, index));
            }
        }
        y += 4 * SLOT + 6;
        int half = (WIDTH - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.filter.remove"), button -> {
            send(FilterMessages.Kind.UNINSTALL, 0);
            onClose();
        }).bounds(left, y, half, 20).build(RiftGateScreen.RiftButton::plain));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + WIDTH - half, y, half, 20).build(RiftGateScreen.RiftButton::plain));
    }

    private void label(Component words, int x, int y, int color) {
        Component text = words.copy().withStyle(style -> style.withColor(color));
        int textWidth = Math.min(WIDTH, font.width(text));
        addRenderableWidget(new StringWidget(x + (WIDTH - textWidth) / 2, y, WIDTH, 12, text, font).setMaxWidth(WIDTH));
    }

    private void slot(int x, int y, ItemStack stack, Runnable onClick) {
        Button.Builder builder = Button.builder(Component.empty(), button -> {
            if (onClick != null) onClick.run();
        }).bounds(x, y, SLOT, SLOT);
        if (!stack.isEmpty()) builder.tooltip(Tooltip.create(stack.getHoverName()));
        ItemStack shown = stack.copyWithCount(1);
        addRenderableWidget(builder.build(made -> new ItemSlot(made, shown)));
    }

    /** One square that shows an item: a hollow in the slab, lit when the pointer is on something in it. */
    private static final class ItemSlot extends Button {
        private final ItemStack stack;

        private ItemSlot(Builder builder, ItemStack stack) {
            super(builder);
            this.stack = stack;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            int x = getX(), y = getY();
            boolean lit = !stack.isEmpty() && isHoveredOrFocused();
            graphics.fill(x, y, x + SLOT, y + SLOT, lit ? 0xFFB26BFF : 0xFF4A2E7A);
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, lit ? 0xFF2A1D48 : 0xFF0A0714);
            if (!stack.isEmpty()) graphics.item(stack, x + 1, y + 1);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int left = (width - WIDTH) / 2, top = Math.max(PAD, (height - HEIGHT) / 2);
        RiftGateScreen.slab(graphics, left - PAD, top - PAD, WIDTH + 2 * PAD, HEIGHT + 2 * PAD);
    }

    // The inventory may change while the screen is open (something picked up): it is read again twice a second
    @Override
    public void tick() {
        super.tick();
        if (++ticks % 10 == 0) rebuildWidgets();
    }

    private void send(FilterMessages.Kind kind, int number) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new ServerboundCustomPayloadPacket(new FilterMessages.Action(data.pos(), data.side(), kind, number)));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
