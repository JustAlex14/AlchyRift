package com.lealex.alchyrift.registry;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.ConduitFittingBlock;
import com.lealex.alchyrift.block.RiftAnchorBlock;
import com.lealex.alchyrift.block.RiftClumpBlock;
import com.lealex.alchyrift.block.RiftCrackBlock;
import com.lealex.alchyrift.block.RiftFrameBlock;
import com.lealex.alchyrift.block.RiftSkyBlock;
import com.lealex.alchyrift.block.RiftWallBlock;
import com.lealex.alchyrift.block.RiftPortBlock;
import com.lealex.alchyrift.block.RiftStabilizerBlock;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.conduit.ConduitKind;
import com.lealex.alchyrift.conduit.RiftConduitBlock;
import com.lealex.alchyrift.item.RiftAnchorItem;
import com.lealex.alchyrift.item.RiftFilterItem;
import com.lealex.alchyrift.item.RiftTunerItem;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import com.lealex.alchyrift.relay.RiftRelayBlock;
import com.lealex.alchyrift.relay.RiftRelayBlockEntity;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything AlchyRift registers: blocks, items, block entity types and the creative tab. */
public final class ModRegistries {
    private ModRegistries() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AlchyRift.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AlchyRift.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AlchyRift.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AlchyRift.MODID);

    /**
     * What everything is crafted from: an ender pearl the void got into, a splinter of obsidian broken off near a rift
     * (made around a void pearl), and an eye of ender set in them, for the anchors and the greater blocks.
     */
    public static final DeferredItem<Item> VOID_PEARL = ITEMS.registerSimpleItem("void_pearl");
    public static final DeferredItem<Item> RIFT_SHARD = ITEMS.registerSimpleItem("rift_shard");
    public static final DeferredItem<Item> RIFT_EYE = ITEMS.registerSimpleItem("rift_eye");
    /** What a room is grown with, from the gate screen (the config may name another item, and sets how many). */
    public static final DeferredItem<Item> VOID_SEED = ITEMS.registerSimpleItem("void_seed");

    /** The walls and ceiling of a pocket room. Nothing breaks, pushes or blows it up. */
    public static final DeferredBlock<RiftWallBlock> RIFT_WALL = BLOCKS.registerBlock("rift_wall",
            RiftWallBlock::new, p -> roomShell(p).isSuffocating((state, level, pos) -> false));

    /** The amethyst that grows where a conduit meets a relay (a look the relay's renderer draws: no item, never placed). */
    public static final DeferredBlock<RiftClumpBlock> RIFT_CLUMP = BLOCKS.registerBlock("rift_clump",
            RiftClumpBlock::new, p -> p.mapColor(MapColor.COLOR_PURPLE).noCollision().noOcclusion().noLootTable());

    /** A sieve's collar and surge crystals on a conduit's end (a look the client draws: no item, never placed). */
    public static final DeferredBlock<ConduitFittingBlock> CONDUIT_FITTING = BLOCKS.registerBlock("conduit_fitting",
            ConduitFittingBlock::new, p -> p.mapColor(MapColor.COLOR_PURPLE).noCollision().noOcclusion().noLootTable());

    /** What a gate's frame looks like once formed (a look for AlchyX's shells: no item, never placed as itself). */
    public static final DeferredBlock<RiftFrameBlock> RIFT_FRAME = BLOCKS.registerBlock("rift_frame",
            RiftFrameBlock::new, p -> p.mapColor(MapColor.COLOR_BLACK).strength(50.0f, 1200.0f).noOcclusion().noLootTable()
                    .sound(SoundType.AMETHYST).lightLevel(state -> 7));

    /** The top of a pocket room: unseen and as unbreakable as its walls, open onto the eye. */
    public static final DeferredBlock<RiftSkyBlock> RIFT_SKY = BLOCKS.registerBlock("rift_sky",
            RiftSkyBlock::new, p -> roomShell(p).noOcclusion()
                    .isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));

    /** The floor of a pocket room, as unbreakable as its walls. */
    public static final DeferredBlock<Block> VOID_STONE = BLOCKS.registerBlock("void_stone",
            Block::new, p -> roomShell(p).isValidSpawn((state, level, pos, type) -> true)); // when the room's rules allow it

    /** The rift gate's core: the expensive part of the gate, the one that holds the room. */
    public static final DeferredBlock<RiftAnchorBlock> RIFT_ANCHOR = BLOCKS.registerBlock("rift_anchor",
            p -> new RiftAnchorBlock(RiftAnchorBlock.LESSER, p),
            p -> p.mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.0f, 6.0f)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 7));

    /** The crack in a room's wall, the way out. Only ever placed by the room builder. */
    public static final DeferredBlock<RiftCrackBlock> RIFT_CRACK = BLOCKS.registerBlock("rift_crack",
            RiftCrackBlock::new,
            p -> roomShell(p).noOcclusion().lightLevel(state -> 11)
                    .isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));

    /** The rift stabilizer: stands in a gate, holds its rift open and shows the gate's room in small above it. */
    public static final DeferredBlock<RiftStabilizerBlock> RIFT_STABILIZER = BLOCKS.registerBlock("rift_stabilizer",
            RiftStabilizerBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE)
                    .strength(2.0f, 6.0f)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 9)
                    .noOcclusion());

    public static final DeferredItem<BlockItem> RIFT_STABILIZER_ITEM = ITEMS.registerSimpleBlockItem("rift_stabilizer", RIFT_STABILIZER);
    /** Rift conduits: one kind each for items, fluids and energy. */
    public static final DeferredBlock<RiftConduitBlock> ITEM_CONDUIT = conduit("item_conduit", ConduitKind.ITEM);
    public static final DeferredBlock<RiftConduitBlock> FLUID_CONDUIT = conduit("fluid_conduit", ConduitKind.FLUID);
    public static final DeferredBlock<RiftConduitBlock> ENERGY_CONDUIT = conduit("energy_conduit", ConduitKind.ENERGY);

    private static DeferredBlock<RiftConduitBlock> conduit(String name, ConduitKind kind) {
        return BLOCKS.registerBlock(name, p -> new RiftConduitBlock(kind, p),
                p -> p.mapColor(MapColor.COLOR_PURPLE).strength(1.0f, 6.0f).sound(SoundType.AMETHYST).noOcclusion());
    }

    public static final DeferredItem<BlockItem> ITEM_CONDUIT_ITEM = ITEMS.registerSimpleBlockItem("item_conduit", ITEM_CONDUIT);
    public static final DeferredItem<BlockItem> FLUID_CONDUIT_ITEM = ITEMS.registerSimpleBlockItem("fluid_conduit", FLUID_CONDUIT);
    public static final DeferredItem<BlockItem> ENERGY_CONDUIT_ITEM = ITEMS.registerSimpleBlockItem("energy_conduit", ENERGY_CONDUIT);

    /** The rift port: placed in a room, it joins the conduits there to those on the stabilizer of the room's gate. */
    public static final DeferredBlock<RiftPortBlock> RIFT_PORT = BLOCKS.registerBlock("rift_port",
            RiftPortBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(2.0f, 6.0f).sound(SoundType.AMETHYST).lightLevel(state -> 6).noOcclusion());

    public static final DeferredItem<BlockItem> RIFT_PORT_ITEM = ITEMS.registerSimpleBlockItem("rift_port", RIFT_PORT);

    /** Rift relays, the wireless ends of conduit lines: the lesser reaches 64 blocks, the greater anywhere. */
    public static final DeferredBlock<RiftRelayBlock> RIFT_RELAY = relay("rift_relay", 1);
    public static final DeferredBlock<RiftRelayBlock> GREATER_RIFT_RELAY = relay("greater_rift_relay", 2);

    private static DeferredBlock<RiftRelayBlock> relay(String name, int tier) {
        return BLOCKS.registerBlock(name, p -> new RiftRelayBlock(tier, p),
                p -> p.mapColor(MapColor.COLOR_PURPLE).strength(2.0f, 6.0f).sound(SoundType.AMETHYST)
                        .lightLevel(state -> state.getValue(RiftRelayBlock.OPEN) ? 12 : state.getValue(RiftRelayBlock.LINKED) ? 6 : 0)
                        .noOcclusion());
    }

    public static final DeferredItem<BlockItem> RIFT_RELAY_ITEM = ITEMS.registerSimpleBlockItem("rift_relay", RIFT_RELAY);
    public static final DeferredItem<BlockItem> GREATER_RIFT_RELAY_ITEM = ITEMS.registerSimpleBlockItem("greater_rift_relay", GREATER_RIFT_RELAY);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RiftRelayBlockEntity>> RIFT_RELAY_BE =
            BLOCK_ENTITY_TYPES.register("rift_relay",
                    () -> new BlockEntityType<>(RiftRelayBlockEntity::new, false, RIFT_RELAY.get(), GREATER_RIFT_RELAY.get()));

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, AlchyRift.MODID);

    /** Data stored on a rift anchor item: the room it opened, so the room follows the anchor wherever it is placed. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> BOUND_ROOM =
            DATA_COMPONENTS.registerComponentType("bound_room",
                    builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Data stored on a rift anchor item set to another gate: that gate, so the exit follows the anchor too. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> BOUND_GATE =
            DATA_COMPONENTS.registerComponentType("bound_gate",
                    builder -> builder.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));

    /** Data stored on a rift tuner between two clicks: the first relay of the pair being linked. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> TUNED_TO =
            DATA_COMPONENTS.registerComponentType("tuned_to",
                    builder -> builder.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));

    /** The rift sieve: set on a conduit's end, it chooses what that end lets through. */
    public static final DeferredItem<RiftFilterItem> RIFT_FILTER = ITEMS.registerItem("rift_filter", p -> new RiftFilterItem(false, p));
    /** The greater rift sieve: it also tells items apart by their data (enchantments, names, contents...). */
    public static final DeferredItem<RiftFilterItem> GREATER_RIFT_FILTER =
            ITEMS.registerItem("greater_rift_filter", p -> new RiftFilterItem(true, p));
    /** The surge crystal: set on a conduit's pulling end, it multiplies what that end moves at a time. */
    public static final DeferredItem<Item> SURGE_CRYSTAL = ITEMS.registerSimpleItem("surge_crystal");

    /** The rift tuner: switches a conduit's end between pushing and pulling, and links two relays. */
    public static final DeferredItem<RiftTunerItem> RIFT_TUNER = ITEMS.registerItem("rift_tuner", RiftTunerItem::new, p -> p.stacksTo(1));

    public static final DeferredItem<RiftAnchorItem> RIFT_ANCHOR_ITEM = ITEMS.registerItem("rift_anchor",
            p -> new RiftAnchorItem(RIFT_ANCHOR.get(), p), p -> p.useBlockDescriptionPrefix());

    /** The greater rift gate's core, the end-game gate: a wider rift, and a destination that can be changed at any time. */
    public static final DeferredBlock<RiftAnchorBlock> GREATER_RIFT_ANCHOR = BLOCKS.registerBlock("greater_rift_anchor",
            p -> new RiftAnchorBlock(RiftAnchorBlock.GREATER, p),
            p -> p.mapColor(MapColor.COLOR_PURPLE)
                    .strength(5.0f, 1200.0f)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 12));

    public static final DeferredItem<RiftAnchorItem> GREATER_RIFT_ANCHOR_ITEM = ITEMS.registerItem("greater_rift_anchor",
            p -> new RiftAnchorItem(GREATER_RIFT_ANCHOR.get(), p), p -> p.useBlockDescriptionPrefix());
    public static final DeferredItem<BlockItem> RIFT_WALL_ITEM = ITEMS.registerSimpleBlockItem("rift_wall", RIFT_WALL);
    public static final DeferredItem<BlockItem> VOID_STONE_ITEM = ITEMS.registerSimpleBlockItem("void_stone", VOID_STONE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RiftAnchorBlockEntity>> RIFT_ANCHOR_BE =
            BLOCK_ENTITY_TYPES.register("rift_anchor",
                    () -> new BlockEntityType<>(RiftAnchorBlockEntity::new, false, RIFT_ANCHOR.get(), GREATER_RIFT_ANCHOR.get()));

    private static BlockBehaviour.Properties roomShell(BlockBehaviour.Properties properties) {
        return properties.mapColor(MapColor.COLOR_BLACK)
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                .isValidSpawn((state, level, pos, type) -> false)
                .pushReaction(PushReaction.BLOCK);
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.alchyrift"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> RIFT_ANCHOR_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get())))
                    .build());

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
