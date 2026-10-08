package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.gate.GateMessages;
import com.lealex.alchyrift.room.RoomRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * The gate screen.
 * <ul>
 *   <li>A gate that leads nowhere yet: two lists side by side, the rooms the player may use (and a field to make a
 *       new one) and the player's other gates. One is picked, "Open the rift" sets the gate for good.</li>
 *   <li>A gate set to a room: the room's name and owner; its owner can rename it, and on the right either share it
 *       (a list of the players online: a click shares the room with one, another click stops) or set the room's
 *       rules (spawns, explosions). A button switches between the two;
 *       beside it, "Expand" grows the room by one size for its price (its tooltip says which size and what price).</li>
 *   <li>A gate set to another gate: where it leads.</li>
 *   <li>A greater gate that is set: its owner also gets "Change destination", which brings the lists back.</li>
 * </ul>
 * Built from widgets (labels, buttons, text fields). The lists scroll with the mouse wheel. Everything is sized from
 * the window: the lists show as many rows as fit (2 to 8) and the columns narrow on a small window, so the screen
 * never runs off it whatever the GUI scale.
 * <p>
 * Its look is the mod's (textures/gui/rift_gate.png, drawn by tools/gui_textures.py, which lists where each piece
 * is): everything lies on a slab of raw obsidian with a chipped edge and amethyst grown on two corners, a crack of
 * the rift's light runs under the gate's name, and the buttons are flakes of the same stone, lit violet under the
 * pointer and bright when they are the picked entry of a list.
 */
public class RiftGateScreen extends Screen {
    private static final int ROW = 22, LINE = 14, MAX_COLUMN = 150, GAP = 10, MIN_ROWS = 2, MAX_ROWS = 8;
    /** Under the title: room for the crack. */
    private static final int UNDER_TITLE = 8;
    /** Height of everything but the list rows: title, list titles, the row under the lists, the gate's name, Done. */
    private static final int FIXED_HEIGHT = LINE + UNDER_TITLE + LINE + (ROW + 2) + (ROW + 4) + ROW;

    // The slab everything lies on (positions in the sheet: tools/gui_textures.py)
    private static final Identifier SHEET = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/gui/rift_gate.png");
    private static final int SHEET_SIZE = 128, EDGE = 12;
    /** From the slab's outer edge to the widgets. */
    private static final int PAD = EDGE + 4;
    private static final int TITLE = 0xF0DEFF, HEADING = 0xC9A6FF, TEXT = 0xE6D8FF;

    private GateMessages.Screen data;
    private int roomScroll, gateScroll, sharedScroll;
    private int pickedRoom = -1;
    private Optional<GlobalPos> pickedGate = Optional.empty();
    private String typedRoom = "";
    private boolean rulesPage; // set to a room: the right column shows the room's rules instead of who it is shared with
    // Worked out in init from the window's size
    private int rows, column, left, right;
    private int slabX, slabY, slabWidth, slabHeight;

    private RiftGateScreen(GateMessages.Screen data) {
        super(Component.translatable("screen.alchyrift.gate"));
        this.data = data;
    }

    /** Opens the screen for a gate, or refreshes it if it is already showing that gate. */
    public static void show(GateMessages.Screen data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RiftGateScreen open && open.data.gate().equals(data.gate())) {
            open.data = data;
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new RiftGateScreen(data));
        }
    }

    @Override
    protected void init() {
        rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, (height - FIXED_HEIGHT - 2 * PAD) / ROW));
        column = Math.max(90, Math.min(MAX_COLUMN, (width - GAP - 2 * PAD) / 2));
        left = width / 2 - column - GAP / 2;
        right = width / 2 + GAP / 2;
        int total = FIXED_HEIGHT + rows * ROW;
        int top = Math.max(2, (height - total) / 2);
        slabX = left - PAD;
        slabY = top - PAD;
        slabWidth = column * 2 + GAP + 2 * PAD;
        slabHeight = total + 2 * PAD;

        label(Component.literal(data.gateName()), left, top, column * 2 + GAP, true, TITLE);
        int body = top + LINE + UNDER_TITLE;
        switch (data.state()) {
            case GateMessages.TO_ROOM -> initRoom(body);
            case GateMessages.TO_GATE -> label(data.exitName().isEmpty()
                    ? Component.translatable("screen.alchyrift.gate.exit_gone")
                    : Component.translatable("screen.alchyrift.gate.leads_to_gate", data.exitName()), left, body + LINE, column * 2 + GAP, true, TEXT);
            default -> initChoice(body);
        }

        int bottom = body + LINE + rows * ROW + (ROW + 2);
        if (data.gateMine()) {
            EditBox name = field(left, bottom, column, data.gateName(), Component.translatable("screen.alchyrift.gate.name"));
            addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.gate.rename_gate"),
                    button -> send(GateMessages.Kind.RENAME_GATE, 0, name.getValue(), Optional.empty())).bounds(right, bottom, column, 20).build(RiftButton::plain));
        }
        if (data.changeable() && data.gateMine()) {
            // A greater gate: its owner may send it somewhere else, and quiet the lightning off its frame
            int third = (column * 2 + GAP - 8) / 3;
            Button change = Button.builder(Component.translatable("screen.alchyrift.gate.change"),
                    button -> send(GateMessages.Kind.RESET, 0, "", Optional.empty())).bounds(left, bottom + ROW + 4, third, 20).build(RiftButton::plain);
            change.active = data.state() != GateMessages.UNSET;
            addRenderableWidget(change);
            addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.gate.lightning").append(": ")
                            .append(data.lightning() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF),
                    button -> send(GateMessages.Kind.TOGGLE_LIGHTNING, 0, "", Optional.empty())).bounds(left + third + 4, bottom + ROW + 4, third, 20).build(RiftButton::plain));
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(left + (third + 4) * 2, bottom + ROW + 4, third, 20).build(RiftButton::plain));
        } else if (data.gateMine()) {
            // A lesser gate: its owner may quiet the lightning too
            addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.gate.lightning").append(": ")
                            .append(data.lightning() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF),
                    button -> send(GateMessages.Kind.TOGGLE_LIGHTNING, 0, "", Optional.empty())).bounds(left, bottom + ROW + 4, column, 20).build(RiftButton::plain));
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(right, bottom + ROW + 4, column, 20).build(RiftButton::plain));
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(width / 2 - 50, bottom + ROW + 4, 100, 20).build(RiftButton::plain));
        }
    }

    // ---- Nothing chosen yet: rooms on the left, gates on the right ----

    private void initChoice(int top) {
        label(Component.translatable("screen.alchyrift.gate.rooms"), left, top, column, false, HEADING);
        label(Component.translatable("screen.alchyrift.gate.gates"), right, top, column, false, HEADING);
        List<GateMessages.RoomEntry> rooms = data.rooms();
        roomScroll = clamp(roomScroll, rooms.size(), rows);
        for (int row = 0; row < rows && roomScroll + row < rooms.size(); row++) {
            GateMessages.RoomEntry room = rooms.get(roomScroll + row);
            Component text = Component.literal(room.name() + (room.mine() || room.owner().isEmpty() ? "" : " (" + room.owner() + ")"));
            boolean picked = room.id() == pickedRoom;
            addRenderableWidget(Button.builder(text, button -> {
                pickedRoom = room.id();
                pickedGate = Optional.empty();
                rebuildWidgets();
            }).bounds(left, top + LINE + row * ROW, column, 20).build(builder -> new RiftButton(builder, picked)));
        }
        List<GateMessages.GateEntry> gates = data.gates();
        gateScroll = clamp(gateScroll, gates.size(), rows);
        for (int row = 0; row < rows && gateScroll + row < gates.size(); row++) {
            GateMessages.GateEntry gate = gates.get(gateScroll + row);
            boolean picked = pickedGate.isPresent() && pickedGate.get().equals(gate.pos());
            addRenderableWidget(Button.builder(Component.literal(gate.name()), button -> {
                pickedGate = Optional.of(gate.pos());
                pickedRoom = -1;
                rebuildWidgets();
            }).bounds(right, top + LINE + row * ROW, column, 20).build(builder -> new RiftButton(builder, picked)));
        }

        int below = top + LINE + rows * ROW;
        EditBox name = field(left, below, column - 54, typedRoom, Component.translatable("screen.alchyrift.gate.new_room"));
        name.setResponder(value -> typedRoom = value);
        addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.gate.create"), button -> {
            if (!name.getValue().isBlank()) {
                send(GateMessages.Kind.CREATE_ROOM, 0, name.getValue(), Optional.empty());
                typedRoom = "";
            }
        }).bounds(left + column - 50, below, 50, 20).build(RiftButton::plain));

        Button open = Button.builder(Component.translatable("screen.alchyrift.gate.open"), button -> {
            if (pickedRoom >= 0) send(GateMessages.Kind.CHOOSE_ROOM, pickedRoom, "", Optional.empty());
            else if (pickedGate.isPresent()) send(GateMessages.Kind.CHOOSE_GATE, 0, "", pickedGate);
        }).bounds(right, below, column - 54, 20).build(RiftButton::plain);
        open.active = pickedRoom >= 0 || pickedGate.isPresent();
        addRenderableWidget(open);

        // Delete the picked room (one's own only), after being asked once more
        GateMessages.RoomEntry doomed = rooms.stream().filter(room -> room.id() == pickedRoom && room.mine()).findFirst().orElse(null);
        Button delete = Button.builder(Component.translatable("screen.alchyrift.gate.delete"), button -> {
            if (doomed == null) return;
            minecraft.setScreen(new ConfirmScreen(yes -> {
                if (yes) {
                    send(GateMessages.Kind.DELETE_ROOM, doomed.id(), "", Optional.empty());
                    pickedRoom = -1;
                }
                minecraft.setScreen(this);
            }, Component.translatable("screen.alchyrift.gate.delete.title", doomed.name()),
                    Component.translatable("screen.alchyrift.gate.delete.warning"),
                    Component.translatable("screen.alchyrift.gate.delete.yes"), CommonComponents.GUI_CANCEL));
        }).bounds(right + column - 50, below, 50, 20).tooltip(Tooltip.create(Component.translatable("screen.alchyrift.gate.delete.tip")))
                .build(RiftButton::plain);
        delete.active = doomed != null;
        addRenderableWidget(delete);
    }

    // ---- Set to a room: its settings ----

    private void initRoom(int top) {
        if (data.room().isEmpty()) {
            label(Component.translatable("screen.alchyrift.gate.room_gone"), left, top + LINE, column * 2 + GAP, true, TEXT);
            return;
        }
        GateMessages.RoomEntry room = data.room().get();
        label(Component.translatable("screen.alchyrift.gate.leads_to_room", room.name()), left, top, column, false, HEADING);
        if (!room.mine()) {
            if (!room.owner().isEmpty()) label(Component.translatable("screen.alchyrift.gate.owner", room.owner()), left, top + LINE, column, false, TEXT);
            return;
        }
        EditBox name = field(left, top + LINE, column, room.name(), Component.translatable("screen.alchyrift.gate.room_name"));
        addRenderableWidget(Button.builder(Component.translatable("screen.alchyrift.gate.rename_room"),
                button -> send(GateMessages.Kind.RENAME_ROOM, 0, name.getValue(), Optional.empty())).bounds(left, top + LINE + ROW, column, 20).build(RiftButton::plain));
        // Two buttons share the third row: the right column's page, and growing the room
        int half = (column - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable(rulesPage ? "screen.alchyrift.gate.show_shared" : "screen.alchyrift.gate.show_rules"),
                button -> {
                    rulesPage = !rulesPage;
                    rebuildWidgets();
                }).bounds(left, top + LINE + 2 * ROW, half, 20).build(RiftButton::plain));
        int chunks = data.roomTier() + 1;
        Component growTip = data.growCost() < 0
                ? Component.translatable("screen.alchyrift.gate.expand.max", chunks, chunks)
                : data.growExtra().isEmpty()
                ? Component.translatable("screen.alchyrift.gate.expand.tip", chunks, chunks, chunks + 1, chunks + 1, data.growCost(), itemName(data.growItem()))
                : Component.translatable("screen.alchyrift.gate.expand.tip_extra", chunks, chunks, chunks + 1, chunks + 1, data.growCost(),
                        itemName(data.growItem()), itemName(data.growExtra()));
        Button grow = Button.builder(Component.translatable("screen.alchyrift.gate.expand"),
                        button -> send(GateMessages.Kind.GROW_ROOM, 0, "", Optional.empty()))
                .bounds(left + column - half, top + LINE + 2 * ROW, half, 20).tooltip(Tooltip.create(growTip)).build(RiftButton::plain);
        grow.active = data.growCost() >= 0;
        addRenderableWidget(grow);

        if (rulesPage) {
            // Right column: one switch per rule
            label(Component.translatable("screen.alchyrift.gate.rules"), right, top, column, false, HEADING);
            RoomRule[] all = RoomRule.values();
            for (int i = 0; i < all.length; i++) {
                RoomRule rule = all[i];
                Component text = Component.translatable(rule.translationKey()).append(": ")
                        .append(rule.in(data.rules()) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
                addRenderableWidget(Button.builder(text, button -> send(GateMessages.Kind.TOGGLE_RULE, rule.ordinal(), "", Optional.empty()))
                        .bounds(right, top + LINE + i * ROW, column, 20).build(RiftButton::plain));
            }
            return;
        }

        // Right column: the players online, and those the room is shared with already. A click shares it, another stops.
        label(Component.translatable("screen.alchyrift.gate.shared"), right, top, column, false, HEADING);
        List<String> guests = guests();
        int listRows = rows + 1;
        sharedScroll = clamp(sharedScroll, guests.size(), listRows);
        if (guests.isEmpty()) label(Component.translatable("screen.alchyrift.gate.nobody"), right, top + LINE + 4, column, false, TEXT);
        for (int row = 0; row < listRows && sharedScroll + row < guests.size(); row++) {
            String guest = guests.get(sharedScroll + row);
            boolean shared = data.shared().stream().anyMatch(guest::equalsIgnoreCase);
            addRenderableWidget(Button.builder(Component.literal(guest),
                            button -> send(shared ? GateMessages.Kind.UNSHARE : GateMessages.Kind.SHARE, 0, guest, Optional.empty()))
                    .bounds(right, top + LINE + row * ROW, column, 20)
                    .tooltip(Tooltip.create(Component.translatable(shared ? "screen.alchyrift.gate.unshare.tip" : "screen.alchyrift.gate.share.tip", guest)))
                    .build(builder -> new RiftButton(builder, shared)));
        }
    }

    /** Who the sharing list shows: those the room is shared with already, then the other players online. */
    private List<String> guests() {
        List<String> names = new ArrayList<>(data.shared());
        for (String name : data.online()) {
            if (names.stream().noneMatch(name::equalsIgnoreCase)) names.add(name);
        }
        return names;
    }

    // ---- Helpers ----

    /** The name of an item growing a room costs (the server's config names them). */
    private static Component itemName(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return Component.literal(name);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName();
    }

    private void label(Component words, int x, int y, int labelWidth, boolean centered, int color) {
        Component text = words.copy().withStyle(style -> style.withColor(color));
        // A string widget writes from its left edge: a centered one is moved to the middle of its span
        int textWidth = Math.min(labelWidth, font.width(text));
        addRenderableWidget(new StringWidget(centered ? x + (labelWidth - textWidth) / 2 : x, y, labelWidth, 12, text, font).setMaxWidth(labelWidth));
    }

    private EditBox field(int x, int y, int fieldWidth, String value, Component hint) {
        EditBox box = new EditBox(font, x, y, fieldWidth, 20, hint);
        box.setMaxLength(GateMessages.MAX_NAME);
        box.setValue(value);
        box.setHint(hint);
        box.setTextColor(0xFF000000 | TEXT);
        return addRenderableWidget(box);
    }

    // ---- The look ----

    /** A piece of the sheet repeated over a rectangle (the last row and column cut to fit). */
    static void tile(GuiGraphicsExtractor graphics, int x, int y, int tileWidth, int tileHeight, int u, int v, int pieceWidth, int pieceHeight) {
        for (int dy = 0; dy < tileHeight; dy += pieceHeight) {
            for (int dx = 0; dx < tileWidth; dx += pieceWidth) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, SHEET, x + dx, y + dy, u, v,
                        Math.min(pieceWidth, tileWidth - dx), Math.min(pieceHeight, tileHeight - dy), SHEET_SIZE, SHEET_SIZE);
            }
        }
    }

    static void piece(GuiGraphicsExtractor graphics, int x, int y, int u, int v, int pieceWidth, int pieceHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, SHEET, x, y, u, v, pieceWidth, pieceHeight, SHEET_SIZE, SHEET_SIZE);
    }

    /** The slab every screen of the mod lies on: its stone, its four chipped edges, its corners, amethyst on two of them. */
    static void slab(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        tile(graphics, x + EDGE, y + EDGE, w - 2 * EDGE, h - 2 * EDGE, 0, 0, 64, 64);
        tile(graphics, x + EDGE, y, w - 2 * EDGE, EDGE, 88, 0, 32, EDGE);
        tile(graphics, x + EDGE, y + h - EDGE, w - 2 * EDGE, EDGE, 88, 12, 32, EDGE);
        tile(graphics, x, y + EDGE, EDGE, h - 2 * EDGE, 64, 24, EDGE, 32);
        tile(graphics, x + w - EDGE, y + EDGE, EDGE, h - 2 * EDGE, 76, 24, EDGE, 32);
        piece(graphics, x, y, 64, 0, EDGE, EDGE);
        piece(graphics, x + w - EDGE, y, 76, 0, EDGE, EDGE);
        piece(graphics, x, y + h - EDGE, 64, 12, EDGE, EDGE);
        piece(graphics, x + w - EDGE, y + h - EDGE, 76, 12, EDGE, EDGE);
        piece(graphics, x - 4, y - 5, 64, 96, 24, 20);
        piece(graphics, x + w - 14, y + h - 10, 88, 96, 16, 12);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = slabX, w = slabWidth;
        slab(graphics, slabX, slabY, slabWidth, slabHeight);
        // The crack under the gate's name, breathing
        int glow = 170 + (int) (85 * (0.5F + 0.5F * Mth.sin(Util.getMillis() / 500.0F)));
        int crackY = slabY + PAD + LINE;
        for (int dx = 0; dx < w - 2 * PAD; dx += 64) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, SHEET, x + PAD + dx, crackY, 64, 88, Math.min(64, w - 2 * PAD - dx), 5,
                    SHEET_SIZE, SHEET_SIZE, glow << 24 | 0xFFFFFF);
        }
    }

    /** A button as a flake of the slab's stone: lit under the pointer, bright when it is a list's picked entry. */
    static final class RiftButton extends Button {
        private static final int END = 6, MIDDLE = 52;
        private final boolean picked;

        private RiftButton(Builder builder, boolean picked) {
            super(builder);
            this.picked = picked;
        }

        static Button plain(Builder builder) {
            return new RiftButton(builder, false);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            int u = active && picked ? 64 : 0;
            int v = !active ? 104 : picked ? 64 : isHoveredOrFocused() ? 84 : 64;
            int x = getX(), y = getY(), w = getWidth(), h = Math.min(20, getHeight());
            piece(graphics, x, y, u, v, END, h);
            for (int dx = END; dx < w - END; dx += MIDDLE) {
                piece(graphics, x + dx, y, u + END, v, Math.min(MIDDLE, w - END - dx), h);
            }
            piece(graphics, x + w - END, y, u + END + MIDDLE, v, END, h);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }

    /** Keeps a list's first shown entry in range: the last page stays full. */
    private static int clamp(int scroll, int size, int shown) {
        return Math.max(0, Math.min(scroll, size - shown));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (step != 0) {
            boolean leftSide = mouseX < width / 2.0;
            if (data.state() == GateMessages.UNSET) {
                if (leftSide) roomScroll = clamp(roomScroll + step, data.rooms().size(), rows);
                else gateScroll = clamp(gateScroll + step, data.gates().size(), rows);
                rebuildWidgets();
                return true;
            }
            if (data.state() == GateMessages.TO_ROOM && !leftSide && !rulesPage) {
                sharedScroll = clamp(sharedScroll + step, guests().size(), rows + 1);
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void send(GateMessages.Kind kind, int number, String text, Optional<GlobalPos> target) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new ServerboundCustomPayloadPacket(new GateMessages.Action(data.gate(), kind, number, text, target)));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
