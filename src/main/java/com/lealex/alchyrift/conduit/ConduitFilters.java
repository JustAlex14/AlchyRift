package com.lealex.alchyrift.conduit;

import com.lealex.alchyrift.AlchyRift;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jspecify.annotations.Nullable;

/**
 * What is set on conduit ends, saved with the overworld (data/alchyrift/conduit_filters): the sieves, which choose
 * what an end lets through, and the surge crystals, which make a pulling end move more at a time. A conduit keeps
 * everything else in its block state and has no block entity, so these are kept here, by conduit position and side.
 */
public final class ConduitFilters extends SavedData {
    /** The most entries one sieve holds. */
    public static final int SIZE = 9;

    /**
     * One sieve.
     *
     * @param items    what it names, one of each (for a fluid conduit: the fluids' buckets)
     * @param deny     false: only these pass; true: everything but these passes
     * @param advanced a greater sieve: it keeps the named items whole, with their data (enchantments, names, contents...)
     * @param exact    a greater sieve only: whether an item must also carry the same data to count as named
     */
    public record Filter(List<ItemStack> items, boolean deny, boolean advanced, boolean exact) {
        public static Filter empty(boolean advanced) {
            return new Filter(List.of(), false, advanced, true);
        }

        /** Whether this is one of the things it names. */
        public boolean names(ItemStack stack) {
            for (ItemStack item : items) {
                if (advanced && exact ? ItemStack.isSameItemSameComponents(item, stack) : item.is(stack.getItem())) return true;
            }
            return false;
        }

        /** Whether it lets this through. A sieve that names nothing yet lets everything through. */
        public boolean allows(ItemStack stack) {
            return items.isEmpty() || deny != names(stack);
        }
    }

    private record Key(GlobalPos conduit, Direction side) {}

    private record Sieve(GlobalPos conduit, Direction side, List<ItemStack> items, boolean deny, boolean advanced, boolean exact) {
        static final Codec<Sieve> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("conduit").forGetter(Sieve::conduit),
                Direction.CODEC.fieldOf("side").forGetter(Sieve::side),
                ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Sieve::items),
                Codec.BOOL.optionalFieldOf("deny", false).forGetter(Sieve::deny),
                Codec.BOOL.optionalFieldOf("advanced", false).forGetter(Sieve::advanced),
                Codec.BOOL.optionalFieldOf("exact", true).forGetter(Sieve::exact)
        ).apply(i, Sieve::new));
    }

    private record Surge(GlobalPos conduit, Direction side, int level) {
        static final Codec<Surge> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("conduit").forGetter(Surge::conduit),
                Direction.CODEC.fieldOf("side").forGetter(Surge::side),
                Codec.INT.fieldOf("level").forGetter(Surge::level)
        ).apply(i, Surge::new));
    }

    private static final Codec<ConduitFilters> CODEC = RecordCodecBuilder.create(i -> i.group(
            Sieve.CODEC.listOf().optionalFieldOf("sieves", List.of()).forGetter(all -> {
                List<Sieve> sieves = new ArrayList<>();
                all.filters.forEach((key, filter) -> sieves.add(
                        new Sieve(key.conduit(), key.side(), filter.items(), filter.deny(), filter.advanced(), filter.exact())));
                return sieves;
            }),
            Surge.CODEC.listOf().optionalFieldOf("surges", List.of()).forGetter(all -> {
                List<Surge> surges = new ArrayList<>();
                all.surges.forEach((key, level) -> surges.add(new Surge(key.conduit(), key.side(), level)));
                return surges;
            })
    ).apply(i, ConduitFilters::new));

    public static final SavedDataType<ConduitFilters> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AlchyRift.MODID, "conduit_filters"), ConduitFilters::new, CODEC);

    private final Map<Key, Filter> filters = new HashMap<>();
    private final Map<Key, Integer> surges = new HashMap<>();

    public ConduitFilters() {}

    private ConduitFilters(List<Sieve> sieves, List<Surge> surges) {
        for (Sieve sieve : sieves) {
            filters.put(new Key(sieve.conduit(), sieve.side()), new Filter(List.copyOf(sieve.items()), sieve.deny(), sieve.advanced(), sieve.exact()));
        }
        for (Surge surge : surges) {
            if (surge.level() > 0) this.surges.put(new Key(surge.conduit(), surge.side()), surge.level());
        }
    }

    public static ConduitFilters get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // ---- Sieves ----

    /** No sieve anywhere: conduits skip looking. */
    public boolean hasNoSieve() {
        return filters.isEmpty();
    }

    /** The sieve on the side of the conduit at {@code pos} that faces {@code side}, or null. */
    public @Nullable Filter at(ResourceKey<Level> dimension, BlockPos pos, Direction side) {
        return filters.isEmpty() ? null : filters.get(new Key(GlobalPos.of(dimension, pos), side));
    }

    public void set(ResourceKey<Level> dimension, BlockPos pos, Direction side, Filter filter) {
        Filter before = filters.put(new Key(GlobalPos.of(dimension, pos.immutable()), side), filter);
        setDirty();
        if (before == null || before.advanced() != filter.advanced()) shownChanged(); // a new sieve, not a change in what it names
    }

    /** Takes the sieve off; returns the one that was there, or null. */
    public @Nullable Filter remove(ResourceKey<Level> dimension, BlockPos pos, Direction side) {
        Filter removed = filters.remove(new Key(GlobalPos.of(dimension, pos), side));
        if (removed != null) {
            setDirty();
            shownChanged();
        }
        return removed;
    }

    // ---- Surge crystals ----

    /** How many surge crystals are set on that side of that conduit. */
    public int surge(ResourceKey<Level> dimension, BlockPos pos, Direction side) {
        return surges.isEmpty() ? 0 : surges.getOrDefault(new Key(GlobalPos.of(dimension, pos), side), 0);
    }

    /** Sets how many are there (0 takes them all off); returns how many there were. */
    public int setSurge(ResourceKey<Level> dimension, BlockPos pos, Direction side, int level) {
        Key key = new Key(GlobalPos.of(dimension, pos.immutable()), side);
        Integer before = level <= 0 ? surges.remove(key) : surges.put(key, level);
        setDirty();
        if ((before == null ? 0 : before) != Math.max(0, level)) shownChanged();
        return before == null ? 0 : before;
    }

    // ---- What clients draw ----

    /** Every end that carries something, for clients to draw (FittingSync). */
    public List<FittingSync.Fitting> fittings() {
        List<FittingSync.Fitting> all = new ArrayList<>();
        filters.forEach((key, filter) -> all.add(
                new FittingSync.Fitting(key.conduit(), key.side(), filter.advanced() ? 2 : 1, surges.getOrDefault(key, 0))));
        surges.forEach((key, level) -> {
            if (!filters.containsKey(key)) all.add(new FittingSync.Fitting(key.conduit(), key.side(), 0, level));
        });
        return all;
    }

    private void shownChanged() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) FittingSync.changed(server, this);
    }
}
