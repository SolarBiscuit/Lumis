package com.solarbiscuit.atmosphera.lumis.color;

import com.solarbiscuit.atmosphera.lumis.util.SectionReader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Light-emitting blocks of the loaded chunks, per chunk section: an immutable {@code int[]} per section with one entry
 * per emitter, {@code localIndex (12 bits) | level << 12 (4 bits) | chroma << 16 (16 bits, see EmitterColors)}.
 * Sections without emitters have no entry. Render thread only: chunk scans (within a time budget), block changes and
 * the colour volume's scheduling all run there, so a plain fastutil map serves (no {@code Long} boxing per access).
 * Arrays are never changed after they are stored.
 * <p>
 * 0.5.11: sections are read straight from their palette ({@link SectionReader}), each distinct block state classified
 * once per scan, instead of 4096 {@code getBlockState} calls; the first scans of a session are checked against the
 * {@code getBlockState} path. Block changes queue their section once and it is rescanned in the next drain, so a
 * {@code /fill} of thousands of lights rescans each section once, not once per light.
 */
final class EmitterIndex {
    private static final long SCAN_BUDGET_NS = 100_000L;
    private static final int[] NONE = new int[0];

    private static final Long2ObjectOpenHashMap<int[]> SECTIONS = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<LevelChunk> QUEUE = new ArrayDeque<>();
    /** Emitters of the section being scanned (render thread), copied to an exact-size array when stored. */
    private static final int[] FOUND = new int[16 * 16 * 16];
    /** Sections whose emitters changed by a block change, rescanned once in the next drain. */
    private static final LongLinkedOpenHashSet RESCAN = new LongLinkedOpenHashSet(256);

    private static final SectionReader READER = new SectionReader();
    /** Palette id -> emitter word ({@code level << 12 | chroma << 16}, 0 = no light) for the scan stamped {@link #stamp}. */
    private static int[] idWord = new int[256];
    private static int[] idStamp = new int[256];
    private static int stamp;
    private static final Collect COLLECT = new Collect();
    /** Palette scans still compared with the getBlockState path this session. */
    private static int verifyLeft = 64;
    private static final int[] CHECK = new int[16 * 16 * 16];

    /** Called for every section whose emitters changed (the colour volume recomputes around it). */
    interface Listener {
        void emittersChanged(int sx, int sy, int sz);
    }

    private EmitterIndex() {
    }

    static void clear() {
        SECTIONS.clear();
        QUEUE.clear();
        RESCAN.clear();
        verifyLeft = 64;
    }

    static int size() {
        return SECTIONS.size();
    }

    static int[] get(int sx, int sy, int sz) {
        int[] e = SECTIONS.get(SectionPos.asLong(sx, sy, sz));
        return e == null ? NONE : e;
    }

    static void onChunkLoad(LevelChunk chunk) {
        QUEUE.add(chunk);
    }

    static void onChunkUnload(LevelChunk chunk, Listener listener) {
        // A queued chunk that was unloaded is skipped when it comes up in drain (no O(n) removal here).
        ChunkPos pos = chunk.getPos();
        int minSection = chunk.getMinSection();
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            if (SECTIONS.remove(SectionPos.asLong(pos.x, minSection + i, pos.z)) != null) {
                listener.emittersChanged(pos.x, minSection + i, pos.z);
            }
        }
    }

    /** Rescans sections changed by block changes, then scans queued chunks, within the time budget. Render thread. */
    static void drain(ClientLevel level, Listener listener) {
        long start = System.nanoTime();
        while (!RESCAN.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            long key = RESCAN.removeFirstLong();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
            if (chunk == null) {
                continue;
            }
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= chunk.getSectionsCount()) {
                continue;
            }
            scan(chunk.getSection(index), sx, sy, sz);
            listener.emittersChanged(sx, sy, sz);
        }
        while (!QUEUE.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            LevelChunk chunk = QUEUE.poll();
            ChunkPos pos = chunk.getPos();
            if (chunk.getLevel().getChunkSource().getChunk(pos.x, pos.z, false) != chunk) {
                // Unloaded (or replaced) since it was queued.
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            int minSection = chunk.getMinSection();
            for (int i = 0; i < sections.length; i++) {
                if (scan(sections[i], pos.x, minSection + i, pos.z)) {
                    listener.emittersChanged(pos.x, minSection + i, pos.z);
                }
            }
        }
    }

    /**
     * A block changed: when an emitter came or went (or changed level or colour), its section is queued for a rescan
     * in the next drain (once, however many of its blocks change in between).
     */
    static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState.getLightEmission() == 0 && newState.getLightEmission() == 0) {
            return;
        }
        RESCAN.add(SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ())));
    }

    /** Rebuilds one section's entry; true when it has emitters. */
    private static boolean scan(LevelChunkSection section, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.getLightEmission() > 0)) {
            return SECTIONS.remove(key) != null;
        }
        int n;
        if (READER.bind(section)) {
            n = scanPalette();
            if (verifyLeft > 0) {
                verifyLeft--;
                int m = scanStates(section, CHECK);
                if (m != n || !Arrays.equals(FOUND, 0, n, CHECK, 0, m)) {
                    SectionReader.disable("emitter scan differed from getBlockState in section " + sx + " " + sy + " " + sz);
                    System.arraycopy(CHECK, 0, FOUND, 0, m);
                    n = m;
                }
            }
        } else {
            n = scanStates(section, FOUND);
        }
        if (n == 0) {
            return SECTIONS.remove(key) != null;
        }
        SECTIONS.put(key, Arrays.copyOf(FOUND, n));
        return true;
    }

    /** The bound section's emitters into {@link #FOUND} from its palette ids; returns the count. */
    private static int scanPalette() {
        int size = READER.paletteSize();
        if (size > idStamp.length) {
            int grown = Math.max(size, idStamp.length * 2);
            idStamp = new int[grown];
            idWord = new int[grown];
            stamp = 0;
        }
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(idStamp, 0);
            stamp = 1;
        }
        COLLECT.index = 0;
        COLLECT.n = 0;
        READER.forEachId(COLLECT);
        return COLLECT.n;
    }

    private static int wordOf(int id) {
        if (id < idStamp.length && idStamp[id] == stamp) {
            return idWord[id];
        }
        BlockState state = READER.valueFor(id);
        int level = state.getLightEmission();
        int word = level <= 0 ? 0 : Math.min(level, 15) << 12 | EmitterColors.of(state) << 16;
        if (id < idStamp.length) {
            idStamp[id] = stamp;
            idWord[id] = word;
        }
        return word;
    }

    /** Storage-order consumer: palette id per block, index = {@code y << 8 | z << 4 | x}. */
    private static final class Collect implements IntConsumer {
        int index;
        int n;

        @Override
        public void accept(int id) {
            int word = wordOf(id);
            if (word != 0) {
                FOUND[n++] = index | word;
            }
            index++;
        }
    }

    /** The 0.5.10 path (one getBlockState per block): fallback and self-check; returns the count. */
    private static int scanStates(LevelChunkSection section, int[] out) {
        int n = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    int level = state.getLightEmission();
                    if (level <= 0) {
                        continue;
                    }
                    out[n++] = (y << 8 | z << 4 | x) | Math.min(level, 15) << 12 | EmitterColors.of(state) << 16;
                }
            }
        }
        return n;
    }
}
