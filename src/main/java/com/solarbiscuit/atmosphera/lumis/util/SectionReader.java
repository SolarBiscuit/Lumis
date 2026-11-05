package com.solarbiscuit.atmosphera.lumis.util;

import com.solarbiscuit.atmosphera.lumis.Lumis;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.function.IntConsumer;

/**
 * Reads a chunk section's block states straight from its palette and bit storage (0.5.11).
 * <p>
 * {@code LevelChunkSection.getBlockState} ends in {@code PalettedContainer.get(int)}, and in RatPack Collections of
 * Optimizations 5.4 wraps that method with a cancellable {@code @Inject}, which allocates a callback object on every
 * call: Lumis's emitter and heightfield scans made millions of them. This reader takes the container's palette and
 * storage once per section and then reads ids directly ({@link BitStorage#get} / {@link BitStorage#getAll} and
 * {@link Palette#valueFor}), the same data {@code get(int)} reads, with nothing in between.
 * <p>
 * The private fields are found once through {@link ObfuscationReflectionHelper} (SRG names, so it works in the
 * production jar). When they are missing, or a self-check in the caller finds a different answer than vanilla's path,
 * {@link #disable} turns the reader off for the session and callers use {@code getBlockState}. Render thread.
 */
public final class SectionReader {
    private static final MethodHandle DATA;
    private static final MethodHandle STORAGE;
    private static final MethodHandle PALETTE;
    private static boolean usable;

    static {
        MethodHandle data = null;
        MethodHandle storage = null;
        MethodHandle palette = null;
        try {
            Field dataField = ObfuscationReflectionHelper.findField(PalettedContainer.class, "f_188032_");
            Class<?> dataClass = dataField.getType();
            Field storageField = ObfuscationReflectionHelper.findField(dataClass, "f_188101_");
            Field paletteField = ObfuscationReflectionHelper.findField(dataClass, "f_188102_");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            data = lookup.unreflectGetter(dataField).asType(MethodType.methodType(Object.class, PalettedContainer.class));
            storage = lookup.unreflectGetter(storageField).asType(MethodType.methodType(BitStorage.class, Object.class));
            palette = lookup.unreflectGetter(paletteField).asType(MethodType.methodType(Palette.class, Object.class));
            usable = true;
        } catch (Throwable t) {
            Lumis.LOGGER.warn("Lumis: direct section reads unavailable ({}); scans use getBlockState.", t.toString());
        }
        DATA = data;
        STORAGE = storage;
        PALETTE = palette;
    }

    private BitStorage storage;
    private Palette<BlockState> palette;
    private LevelChunkSection bound;

    /** True while direct reads may be used. */
    public static boolean usable() {
        return usable;
    }

    /** Turns direct reads off for the session (a caller's self-check failed), with one WARN. */
    public static void disable(String why) {
        if (usable) {
            usable = false;
            Lumis.LOGGER.warn("Lumis: direct section reads turned off: {}. Scans use getBlockState.", why);
        }
    }

    /**
     * Takes the section's current palette and storage. Returns false when direct reads cannot be used for it (the
     * caller then reads through {@code getBlockState}). Rebind after the section may have changed (a later frame).
     */
    @SuppressWarnings("unchecked")
    public boolean bind(LevelChunkSection section) {
        bound = null;
        if (!usable || section == null) {
            return false;
        }
        try {
            Object data = (Object) DATA.invokeExact((PalettedContainer<?>) section.getStates());
            BitStorage s = (BitStorage) STORAGE.invokeExact(data);
            Palette<?> p = (Palette<?>) PALETTE.invokeExact(data);
            if (s.getSize() != 4096) {
                return false;
            }
            storage = s;
            palette = (Palette<BlockState>) p;
            bound = section;
            return true;
        } catch (Throwable t) {
            disable(t.toString());
            return false;
        }
    }

    /** The bound section. */
    public LevelChunkSection section() {
        return bound;
    }

    /** Block state at section-local x, y, z of the bound section. */
    public BlockState get(int x, int y, int z) {
        return palette.valueFor(storage.get((y << 4 | z) << 4 | x));
    }

    /** Palette entries of the bound section (ids passed to {@link #forEachId} are below this). */
    public int paletteSize() {
        return palette.getSize();
    }

    /** The state of a palette id of the bound section. */
    public BlockState valueFor(int id) {
        return palette.valueFor(id);
    }

    /**
     * Every block's palette id in index order ({@code y << 8 | z << 4 | x}), streamed from the storage words (no
     * per-index division). The consumer counts the index itself.
     */
    public void forEachId(IntConsumer consumer) {
        storage.getAll(consumer);
    }
}
