package com.solarbiscuit.atmosphera.lumis.color;

import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Light colour of distant terrain (NeoVoxy LODs): a coarse toroidal RGBA8 3D texture of 16-block cells, 2048 blocks
 * wide and 512 high around the camera (2 MB), filled from the light-emitting voxels NeoVoxy reports per LOD section
 * (NeoVoxy API 3, {@link VoxyBridge.LodEmitters}). Lumis has no block data out there; NeoVoxy's storage does.
 * <p>
 * Each emitter splats its colour into the cells its light reaches, weighted by the light it leaves there
 * ({@code level − distance}, from the cell's nearest point); no occlusion (16-block cells are far coarser than walls).
 * The LOD shader samples it where the near volume has nothing, and tints the block-lit share of the LOD's own stored
 * light, so only lit voxels change. The window is whole bricks (16 cells, 256 blocks) centred on the brick boundary
 * nearest the camera. Bricks are computed on the {@code lumis-far} worker when they enter the window or when a report
 * changes their emitters. Render thread except the reports and the worker.
 */
final class FarHue {
    static final int CELL = 16;
    static final int NXZ = 128;
    static final int NY = 32;
    private static final int BRICK = 16;
    private static final int BXZ = NXZ / BRICK;
    private static final int BY = NY / BRICK;
    private static final int BRICK_BLOCKS = CELL * BRICK;
    private static final int BRICK_BYTES = BRICK * BRICK * BRICK * 4;
    private static final int REPORT_BUDGET = 256;
    private static final int UPLOAD_BUDGET = 8;

    private record Report(int lod, int sx, int sy, int sz, int[] emitters) {
    }

    private record Result(int slot, int generation, byte[] rgba) {
    }

    private static final ConcurrentLinkedQueue<Report> REPORTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    /** Brick buffers returned after upload (render thread offers, the worker takes); never full in practice. */
    private static final java.util.concurrent.ArrayBlockingQueue<byte[]> FREE_OUT = new java.util.concurrent.ArrayBlockingQueue<>(64);
    private static final int[] NO_EMITTERS = new int[0];
    /** Per LOD section: its emitters in world space, {x, y, z, level << 16 | chroma} each. */
    private static final Long2ObjectOpenHashMap<int[]> SECTIONS = new Long2ObjectOpenHashMap<>();
    /** Per brick: the sections whose emitters reach it. */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> BRICK_SECTIONS = new Long2ObjectOpenHashMap<>();
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();

    private static ExecutorService worker;
    private static int texture;
    private static long[] owner;
    private static int[] generation;
    private static boolean[] nonZero;
    private static int nextGeneration;
    private static int centreX = Integer.MIN_VALUE;
    private static int centreY;
    private static int centreZ;
    private static ByteBuffer upload;
    private static volatile boolean wanted;
    private static boolean registered;
    private static long reports;

    private FarHue() {
    }

    static int texture() {
        return texture;
    }

    static boolean allocated() {
        return texture != 0;
    }

    /** Registers with the lib's Voxy bridge once (mod construction or first update). */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        VoxyBridge.setLodEmitters(new VoxyBridge.LodEmitters() {
            @Override
            public int emitterWordFor(BlockState state) {
                int level = state.getLightEmission();
                if (level <= 0) {
                    return 0;
                }
                return Math.min(level, 15) << 16 | EmitterColors.ofAnyThread(state);
            }

            @Override
            public void sectionEmitters(int lod, int sx, int sy, int sz, long[] emitters, int count) {
                // The array is the section's stored emitter list (kept until the next report replaces it).
                int[] world = count == 0 ? NO_EMITTERS : new int[count * 4];
                int voxel = 1 << lod;
                int ox = (sx << 5) << lod;
                int oy = (sy << 5) << lod;
                int oz = (sz << 5) << lod;
                for (int i = 0; i < count; i++) {
                    long e = emitters[i];
                    int index = (int) (e & 0x7FFF);
                    int word = (int) (e >>> 15);
                    world[i * 4] = ox + (index & 31) * voxel + voxel / 2;
                    world[i * 4 + 1] = oy + (index >>> 10) * voxel + voxel / 2;
                    world[i * 4 + 2] = oz + ((index >>> 5) & 31) * voxel + voxel / 2;
                    world[i * 4 + 3] = word;
                }
                REPORTS.add(new Report(lod, sx, sy, sz, world));
            }

            @Override
            public boolean wanted() {
                return wanted;
            }
        });
    }

    /** Whether NeoVoxy will report emitters (NeoVoxy 1.43.7+ with its native extension API). */
    static boolean available() {
        return VoxyBridge.lodEmittersAvailable();
    }

    static void ensure() {
        if (texture != 0) {
            return;
        }
        int slots = BXZ * BXZ * BY;
        owner = new long[slots];
        java.util.Arrays.fill(owner, Long.MIN_VALUE);
        generation = new int[slots];
        nonZero = new boolean[slots];
        texture = GpuResources.texture("Lumis far light colour");
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
        ByteBuffer zeros = MemoryUtil.memCalloc(NXZ * NY * NXZ * 4);
        HueVolume.beginUnpack();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, NXZ, NY, NXZ, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, zeros);
        HueVolume.endUnpack();
        MemoryUtil.memFree(zeros);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) NXZ * NY * NXZ * 4);
        if (upload == null) {
            upload = MemoryUtil.memAlloc(BRICK_BYTES);
        }
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lumis-far");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        centreX = Integer.MIN_VALUE;
    }

    static void setWanted(boolean on) {
        wanted = on;
    }

    static void release() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        owner = null;
        RESULTS.clear();
        DIRTY.clear();
        centreX = Integer.MIN_VALUE;
    }

    /** World change: forget every report. */
    static void clear() {
        REPORTS.clear();
        SECTIONS.clear();
        BRICK_SECTIONS.clear();
        DIRTY.clear();
    }

    private static long sectionKey(int lod, int sx, int sy, int sz) {
        return (long) lod << 60 | ((long) sx & 0xFFFFF) << 40 | ((long) sy & 0xFFF) << 28 | ((long) sz & 0xFFFFFFF);
    }

    private static long brickKey(int bx, int by, int bz) {
        return ((long) bx & 0x3FFFFF) << 42 | ((long) by & 0xFFFFF) << 22 | ((long) bz & 0x3FFFFF);
    }

    /** Once per frame: takes reports, follows the camera, schedules bricks, uploads results. Render thread. */
    static void update(int camX, int camY, int camZ) {
        if (texture == 0) {
            return;
        }
        int taken = 0;
        Report r;
        while (taken < REPORT_BUDGET && (r = REPORTS.poll()) != null) {
            apply(r);
            taken++;
        }
        reports += taken;
        int cx = Math.floorDiv(camX + BRICK_BLOCKS / 2, BRICK_BLOCKS);
        int cy = Math.floorDiv(camY + BRICK_BLOCKS / 2, BRICK_BLOCKS);
        int cz = Math.floorDiv(camZ + BRICK_BLOCKS / 2, BRICK_BLOCKS);
        if (cx != centreX || cy != centreY || cz != centreZ) {
            centreX = cx;
            centreY = cy;
            centreZ = cz;
            for (int y = 0; y < BY; y++) {
                for (int z = 0; z < BXZ; z++) {
                    for (int x = 0; x < BXZ; x++) {
                        int bx = cx - BXZ / 2 + x;
                        int by = cy - BY / 2 + y;
                        int bz = cz - BXZ / 2 + z;
                        if (owner[slot(bx, by, bz)] != brickKey(bx, by, bz)) {
                            DIRTY.add(brickKey(bx, by, bz));
                        }
                    }
                }
            }
        }
        int scheduled = 0;
        while (!DIRTY.isEmpty() && scheduled < 16) {
            long key = DIRTY.removeFirstLong();
            int bx = (int) (key << 0 >> 42);
            int by = (int) (key << 22 >> 44);
            int bz = (int) (key << 42 >> 42);
            if (!inWindow(bx, by, bz)) {
                continue;
            }
            schedule(key, bx, by, bz);
            scheduled++;
        }
        int uploaded = 0;
        Result res;
        while (uploaded < UPLOAD_BUDGET && (res = RESULTS.poll()) != null) {
            if (owner == null || generation[res.slot()] != res.generation()) {
                recycle(res.rgba());
                continue;
            }
            boolean zero = res.rgba() == null;
            if (zero && !nonZero[res.slot()]) {
                continue;
            }
            if (uploaded == 0) {
                HueVolume.beginUnpack();
            }
            uploadSlot(res.slot(), res.rgba());
            nonZero[res.slot()] = !zero;
            recycle(res.rgba());
            uploaded++;
        }
        if (uploaded > 0) {
            HueVolume.endUnpack();
        }
    }

    /** A section's emitters replace its earlier ones; the bricks either touches are recomputed. */
    private static void apply(Report r) {
        long key = sectionKey(r.lod(), r.sx(), r.sy(), r.sz());
        int[] old = r.emitters().length == 0 ? SECTIONS.remove(key) : SECTIONS.put(key, r.emitters());
        if (old != null) {
            touch(key, old, false);
        }
        if (r.emitters().length != 0) {
            touch(key, r.emitters(), true);
        }
    }

    private static void touch(long sectionKey, int[] e, boolean add) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < e.length; i += 4) {
            minX = Math.min(minX, e[i]);
            maxX = Math.max(maxX, e[i]);
            minY = Math.min(minY, e[i + 1]);
            maxY = Math.max(maxY, e[i + 1]);
            minZ = Math.min(minZ, e[i + 2]);
            maxZ = Math.max(maxZ, e[i + 2]);
        }
        for (int by = Math.floorDiv(minY - 15, BRICK_BLOCKS); by <= Math.floorDiv(maxY + 15, BRICK_BLOCKS); by++) {
            for (int bz = Math.floorDiv(minZ - 15, BRICK_BLOCKS); bz <= Math.floorDiv(maxZ + 15, BRICK_BLOCKS); bz++) {
                for (int bx = Math.floorDiv(minX - 15, BRICK_BLOCKS); bx <= Math.floorDiv(maxX + 15, BRICK_BLOCKS); bx++) {
                    long brick = brickKey(bx, by, bz);
                    LongOpenHashSet set = BRICK_SECTIONS.get(brick);
                    if (add) {
                        if (set == null) {
                            set = new LongOpenHashSet(4);
                            BRICK_SECTIONS.put(brick, set);
                        }
                        set.add(sectionKey);
                    } else if (set != null) {
                        set.remove(sectionKey);
                        if (set.isEmpty()) {
                            BRICK_SECTIONS.remove(brick);
                        }
                    }
                    if (texture != 0) {
                        DIRTY.add(brick);
                    }
                }
            }
        }
    }

    private static boolean inWindow(int bx, int by, int bz) {
        return bx >= centreX - BXZ / 2 && bx < centreX + BXZ / 2 && bz >= centreZ - BXZ / 2 && bz < centreZ + BXZ / 2
                && by >= centreY - BY / 2 && by < centreY + BY / 2;
    }

    private static int slot(int bx, int by, int bz) {
        return ((Math.floorMod(by, BY)) * BXZ + Math.floorMod(bz, BXZ)) * BXZ + Math.floorMod(bx, BXZ);
    }

    private static void schedule(long key, int bx, int by, int bz) {
        int slot = slot(bx, by, bz);
        owner[slot] = key;
        int gen = ++nextGeneration;
        generation[slot] = gen;
        LongOpenHashSet set = BRICK_SECTIONS.get(key);
        if (set == null || set.isEmpty()) {
            RESULTS.add(new Result(slot, gen, null));
            return;
        }
        int[][] lists = new int[set.size()][];
        int n = 0;
        for (long s : set) {
            int[] e = SECTIONS.get(s);
            if (e != null) {
                lists[n++] = e;
            }
        }
        int[][] use = java.util.Arrays.copyOf(lists, n);
        int ox = bx * BRICK_BLOCKS;
        int oy = by * BRICK_BLOCKS;
        int oz = bz * BRICK_BLOCKS;
        worker.execute(() -> RESULTS.add(new Result(slot, gen, compute(use, ox, oy, oz))));
    }

    private static void recycle(byte[] rgba) {
        if (rgba != null) {
            FREE_OUT.offer(rgba);
        }
    }

    /** Scratch of the single {@code lumis-far} worker thread. */
    private static final float[] W = new float[BRICK * BRICK * BRICK];
    private static final float[] CO = new float[BRICK * BRICK * BRICK];
    private static final float[] CG = new float[BRICK * BRICK * BRICK];
    private static final float[] BEST = new float[BRICK * BRICK * BRICK];

    /** Worker: splats each emitter into the brick's cells its light reaches. */
    private static byte[] compute(int[][] lists, int ox, int oy, int oz) {
        int cells = BRICK * BRICK * BRICK;
        float[] w = W;
        float[] co = CO;
        float[] cg = CG;
        float[] best = BEST;
        java.util.Arrays.fill(w, 0f);
        java.util.Arrays.fill(co, 0f);
        java.util.Arrays.fill(cg, 0f);
        java.util.Arrays.fill(best, 0f);
        boolean any = false;
        for (int[] e : lists) {
            for (int i = 0; i < e.length; i += 4) {
                int ex = e[i] - ox;
                int ey = e[i + 1] - oy;
                int ez = e[i + 2] - oz;
                int word = e[i + 3];
                int level = word >>> 16 & 15;
                float c0 = (byte) (word >>> 8) / 63f;
                float c1 = (byte) word / 63f;
                for (int z = Math.max(0, Math.floorDiv(ez - level, CELL)); z <= Math.min(BRICK - 1, Math.floorDiv(ez + level, CELL)); z++) {
                    for (int y = Math.max(0, Math.floorDiv(ey - level, CELL)); y <= Math.min(BRICK - 1, Math.floorDiv(ey + level, CELL)); y++) {
                        for (int x = Math.max(0, Math.floorDiv(ex - level, CELL)); x <= Math.min(BRICK - 1, Math.floorDiv(ex + level, CELL)); x++) {
                            // Distance from the emitter to the nearest point of the cell.
                            float dx = Math.max(0, Math.max(x * CELL - ex, ex - (x * CELL + CELL)));
                            float dy = Math.max(0, Math.max(y * CELL - ey, ey - (y * CELL + CELL)));
                            float dz = Math.max(0, Math.max(z * CELL - ez, ez - (z * CELL + CELL)));
                            float pred = level - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (pred <= 0f) {
                                continue;
                            }
                            int t = (z * BRICK + y) * BRICK + x;
                            w[t] += pred;
                            co[t] += pred * c0;
                            cg[t] += pred * c1;
                            best[t] = Math.max(best[t], pred);
                            any = true;
                        }
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        byte[] out = FREE_OUT.poll();
        if (out == null) {
            out = new byte[BRICK_BYTES];
        } else {
            java.util.Arrays.fill(out, (byte) 0);
        }
        for (int t = 0; t < cells; t++) {
            if (w[t] <= 0f) {
                continue;
            }
            int p = t * 4;
            out[p] = (byte) Math.round(Math.max(0f, Math.min(1f, co[t] / w[t] * 0.25f + 0.5f)) * 255f);
            out[p + 1] = (byte) Math.round(Math.max(0f, Math.min(1f, cg[t] / w[t] * 0.25f + 0.5f)) * 255f);
            out[p + 2] = (byte) 255;
            out[p + 3] = (byte) Math.round(Math.min(1f, best[t] / 15f) * 255f);
        }
        return out;
    }

    private static void uploadSlot(int slot, byte[] rgba) {
        int x = slot % BXZ;
        int z = (slot / BXZ) % BXZ;
        int y = slot / (BXZ * BXZ);
        upload.clear();
        if (rgba == null) {
            MemoryUtil.memSet(upload, 0);
        } else {
            upload.put(rgba).flip();
        }
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, x * BRICK, y * BRICK, z * BRICK, BRICK, BRICK, BRICK, GL11C.GL_RGBA,
                GL11C.GL_UNSIGNED_BYTE, upload);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        GL13C.glActiveTexture(previousUnit);
    }

    static String describe() {
        return "far colour " + (texture == 0 ? "off" : (NXZ * CELL) + "x" + (NY * CELL) + " blocks, "
                + SECTIONS.size() + " LOD sections with emitters, " + reports + " reports");
    }
}
