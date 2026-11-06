package com.solarbiscuit.atmosphera.lumis.color;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The block-light colour around the camera: a toroidal RGBA8 3D texture, one texel per block, addressed by world
 * position with {@code REPEAT} wrapping (no origin uniform needed). Each texel holds the chroma of the light reaching
 * that block, premultiplied by a coverage weight: {@code rg = w × (chroma / 4 + 0.5)}, {@code b = w}, so linear
 * filtering blends only lit cells, and {@code a = w × level / 15} (the static block light). Unlit cells are 0. A CPU
 * copy of every uploaded section serves entity, block-entity and held-item lookups ({@link #sample}).
 * <p>
 * The window is whole chunk sections around the camera's section. A section is (re)computed on a {@code lumis-light}
 * worker when it enters the window, when its block light changes (light updates on the client) or when emitters near
 * it change; the result is uploaded on the render thread within a budget.
 * <p>
 * Per lit cell, the colour is a mix of the emitters whose light reaches it (at most 64 per section, those reaching
 * furthest in), each weighted by the level it arrives with. Reach is a flood fill through the game's own block light,
 * so walls stop colour exactly where they stop light (see {@link #compute}).
 * <p>
 * Allocation-free in steady state (0.5.8): jobs, their arrays, the block-light copies and the section buffers are
 * pooled, and jobs travel between the render thread and the workers through two fixed-size queues.
 */
final class HueVolume {
    private static final int MAX_EMITTERS = 64;
    private static final int UPLOAD_BUDGET = 32;
    private static final int SCHEDULE_BUDGET = 64;
    /** Jobs in existence at most (pooled); scheduling waits a frame when all are in flight. */
    private static final int MAX_JOBS = 256;
    private static final int SECTION_BYTES = 16 * 16 * 16 * 4;
    private static final int LAYER_BYTES = 2048;
    private static final int WORKERS = 2;

    /** Sized for the HIGH window (2048 sections) so it never shrinks and regrows while the volume refills. */
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet(4096);

    /** Render thread → workers, and back. Both hold at most {@link #MAX_JOBS}, so offers never fail. */
    private static final ArrayBlockingQueue<Job> WORK = new ArrayBlockingQueue<>(MAX_JOBS);
    private static final ArrayBlockingQueue<Job> DONE = new ArrayBlockingQueue<>(MAX_JOBS);
    /** Pools, render thread only. */
    private static final ArrayDeque<Job> FREE_JOBS = new ArrayDeque<>();
    private static final ArrayDeque<byte[]> FREE_LAYERS = new ArrayDeque<>();
    private static final ArrayDeque<Batch> FREE_BATCHES = new ArrayDeque<>();
    private static int jobsCreated;
    private static boolean workersStarted;

    private static int texture;
    private static int sizeXZ;
    private static int sizeY;
    private static int nxz;
    private static int ny;
    /** Section stored in each slot (Long.MIN_VALUE = none) and the job generation the slot waits for. */
    private static long[] owner;
    private static int[] generation;
    /** Job generations are unique for the whole session, so results of a released volume never match. */
    private static int nextGeneration;
    private static boolean[] nonZero;
    /** Slots holding colour (render thread): the mist glow only runs while some exist. */
    private static int nonZeroCount;
    /** CPU copy of each slot's texels as uploaded (null = all zero), for {@link #sample}. */
    private static byte[][] cpu;
    private static int centreX = Integer.MIN_VALUE;
    private static int centreY;
    private static int centreZ;
    private static ByteBuffer upload;

    /** One section's work, reused. Fields are written by the render thread before {@code WORK.offer}, and by the
     * worker before {@code DONE.offer}; the queues order those writes. */
    private static final class Job {
        int slot;
        int generation;
        final byte[][] layers = new byte[27][];
        final int[] emitters = new int[MAX_EMITTERS * 6];
        final int[] score = new int[MAX_EMITTERS];
        int count;
        /** Layer copies of the frame this job was scheduled in (null for jobs with nothing to compute). */
        Batch batch;
        /** Output texels; swapped with the slot's CPU copy when the result is taken, so buffers circulate. */
        byte[] out;
        boolean lit;
    }

    /** The block-light copies made in one frame, shared by that frame's jobs; recycled when all of them are done. */
    private static final class Batch {
        final ArrayList<byte[]> copies = new ArrayList<>();
        int pending;
        boolean open;
    }

    /** Copies of block-light layers made this frame (sections are shared by neighbouring jobs). */
    private static final Long2ObjectOpenHashMap<byte[]> FRAME_LAYERS = new Long2ObjectOpenHashMap<>();
    private static final byte[] NO_LIGHT = new byte[0];
    private static Batch frameBatch;

    private HueVolume() {
    }

    static int texture() {
        return texture;
    }

    static boolean allocated() {
        return texture != 0;
    }

    static int sizeXZ() {
        return sizeXZ;
    }

    static int sizeY() {
        return sizeY;
    }

    /** (Re)allocates for a window of {@code xz × y × xz} blocks (powers of two, multiples of 16). Render thread. */
    static void ensure(int xz, int y) {
        if (texture != 0 && xz == sizeXZ && y == sizeY) {
            return;
        }
        release();
        sizeXZ = xz;
        sizeY = y;
        nxz = xz / 16;
        ny = y / 16;
        int slots = nxz * nxz * ny;
        owner = new long[slots];
        Arrays.fill(owner, Long.MIN_VALUE);
        generation = new int[slots];
        nonZero = new boolean[slots];
        nonZeroCount = 0;
        cpu = new byte[slots][];
        texture = GpuResources.texture("Lumis light colour");
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
        // Texture x/y/z = world x/y/z, so the shader samples (x, y, z) / (sizeXZ, sizeY, sizeXZ).
        ByteBuffer zeros = MemoryUtil.memCalloc(xz * y * xz * 4);
        beginUnpack();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, xz, y, xz, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, zeros);
        endUnpack();
        MemoryUtil.memFree(zeros);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) xz * y * xz * 4);
        if (upload == null) {
            upload = MemoryUtil.memAlloc(SECTION_BYTES);
        }
        startWorkers();
        centreX = Integer.MIN_VALUE;
    }

    private static void startWorkers() {
        if (workersStarted) {
            return;
        }
        workersStarted = true;
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(HueVolume::workLoop, "lumis-light");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
    }

    private static volatile boolean workerFailureLogged;

    private static void workLoop() {
        while (true) {
            Job job;
            try {
                job = WORK.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                long t0 = System.nanoTime();
                compute(job);
                JOB_NS.addAndGet(System.nanoTime() - t0);
                JOBS.incrementAndGet();
            } catch (Throwable t) {
                job.lit = false;
                if (!workerFailureLogged) {
                    workerFailureLogged = true;
                    Lumis.LOGGER.error("Light colour: a section failed to compute; it stays uncoloured", t);
                }
            }
            DONE.offer(job);
        }
    }

    static int nonZeroCount() {
        return texture == 0 ? 0 : nonZeroCount;
    }

    static void release() {
        nonZeroCount = 0;
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        // Jobs still on the workers come back later and are recycled by the next drain (their generation is stale).
        drainDone(0);
        closeFrameBatch();
        DIRTY.clear();
        owner = null;
        cpu = null;
        centreX = Integer.MIN_VALUE;
    }

    /** Light or emitters changed in this section (and so possibly its colour). */
    static void markDirty(int sx, int sy, int sz) {
        if (texture != 0) {
            DIRTY.add(SectionPos.asLong(sx, sy, sz));
        }
    }

    /** Marks a section and its 26 neighbours (emitters reach up to 15 blocks). */
    static void markAround(int sx, int sy, int sz) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    markDirty(sx + dx, sy + dy, sz + dz);
                }
            }
        }
    }

    /**
     * Once per frame: follows the camera, schedules dirty sections, uploads finished ones. Render thread. The window is
     * centred on the section boundary nearest the camera, so the camera is never more than 8 blocks off-centre.
     */
    static void update(ClientLevel level, int camX, int camY, int camZ) {
        if (texture == 0) {
            return;
        }
        int camSectionX = Math.floorDiv(camX + 8, 16);
        int camSectionY = Math.floorDiv(camY + 8, 16);
        int camSectionZ = Math.floorDiv(camZ + 8, 16);
        if (camSectionX != centreX || camSectionY != centreY || camSectionZ != centreZ) {
            centreX = camSectionX;
            centreY = camSectionY;
            centreZ = camSectionZ;
            // Every window section whose slot holds another section (or nothing yet) needs computing.
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nxz; z++) {
                    for (int x = 0; x < nxz; x++) {
                        int sx = centreX - nxz / 2 + x;
                        int sy = centreY - ny / 2 + y;
                        int sz = centreZ - nxz / 2 + z;
                        long key = SectionPos.asLong(sx, sy, sz);
                        if (owner[slot(sx, sy, sz)] != key) {
                            DIRTY.add(key);
                        }
                    }
                }
            }
        }
        closeFrameBatch();
        int scheduled = 0;
        while (!DIRTY.isEmpty() && scheduled < SCHEDULE_BUDGET) {
            long key = DIRTY.firstLong();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            if (!inWindow(sx, sy, sz)) {
                DIRTY.removeFirstLong();
                continue;
            }
            Job job = takeJob();
            if (job == null) {
                // Every job is in flight: the section stays queued for the next frame.
                break;
            }
            DIRTY.removeFirstLong();
            schedule(level, job, key, sx, sy, sz);
            scheduled++;
        }
        drainDone(UPLOAD_BUDGET);
    }

    private static Job takeJob() {
        Job job = FREE_JOBS.poll();
        if (job == null && jobsCreated < MAX_JOBS) {
            jobsCreated++;
            job = new Job();
        }
        return job;
    }

    /**
     * Takes finished jobs: uploads current results (at most {@code budget}; 0 = recycle only, no uploads) and returns
     * every job, its layer copies and its spare buffer to the pools. Render thread.
     */
    private static void drainDone(int budget) {
        int uploaded = 0;
        Job job;
        while ((budget == 0 || uploaded < budget) && (job = DONE.poll()) != null) {
            Batch batch = job.batch;
            if (batch != null) {
                job.batch = null;
                if (--batch.pending == 0 && !batch.open) {
                    recycle(batch);
                }
            }
            Arrays.fill(job.layers, null);
            int slot = job.slot;
            if (budget > 0 && owner != null && generation[slot] == job.generation) {
                boolean zero = !job.lit;
                if (!zero || nonZero[slot]) {
                    if (uploaded == 0) {
                        beginUnpack();
                    }
                    uploadSlot(slot, zero ? null : job.out);
                    uploaded++;
                }
                if (nonZero[slot] == zero) {
                    nonZeroCount += zero ? -1 : 1;
                }
                nonZero[slot] = !zero;
                if (!zero) {
                    // The job's buffer becomes the slot's CPU copy; the old copy becomes the job's next buffer.
                    byte[] old = cpu[slot];
                    cpu[slot] = job.out;
                    job.out = old;
                } else {
                    cpu[slot] = null;
                }
            }
            FREE_JOBS.push(job);
        }
        if (uploaded > 0) {
            endUnpack();
        }
    }

    /** Ends this frame's layer sharing; the copies return to the pool once its jobs are done. */
    private static void closeFrameBatch() {
        FRAME_LAYERS.clear();
        Batch batch = frameBatch;
        frameBatch = null;
        if (batch != null) {
            batch.open = false;
            if (batch.pending == 0) {
                recycle(batch);
            }
        }
    }

    private static void recycle(Batch batch) {
        for (int i = 0; i < batch.copies.size(); i++) {
            FREE_LAYERS.push(batch.copies.get(i));
        }
        batch.copies.clear();
        FREE_BATCHES.push(batch);
    }


    /**
     * Tight client-memory unpacking for our uploads. Vanilla leaves row length and skips set by its texture uploads,
     * and with them the driver reads past our 16 KB buffer (an access violation inside the AMD driver in testing).
     */
    static void beginUnpack() {
        // Lib 0.3.23: one query per batch (the unpack buffer); the unpack values are left at the GL defaults.
        com.solarbiscuit.atmosphera.lib.gpu.Uploads.begin(4);
    }

    static void endUnpack() {
        com.solarbiscuit.atmosphera.lib.gpu.Uploads.end();
    }

    private static boolean inWindow(int sx, int sy, int sz) {
        return sx >= centreX - nxz / 2 && sx < centreX + nxz / 2
                && sz >= centreZ - nxz / 2 && sz < centreZ + nxz / 2
                && sy >= centreY - ny / 2 && sy < centreY + ny / 2;
    }

    private static int slot(int sx, int sy, int sz) {
        return ((sy & (ny - 1)) * nxz + (sz & (nxz - 1))) * nxz + (sx & (nxz - 1));
    }

    private static void schedule(ClientLevel level, Job job, long key, int sx, int sy, int sz) {
        int slot = slot(sx, sy, sz);
        owner[slot] = key;
        int gen = ++nextGeneration;
        generation[slot] = gen;
        job.slot = slot;
        job.generation = gen;
        job.lit = false;
        job.count = 0;
        // The frame's shared layer copies; the section's own copy comes first (one light-engine read per section and
        // frame: with Solix every read clones the layer).
        Batch batch = frameBatch;
        if (batch == null) {
            batch = FREE_BATCHES.poll();
            if (batch == null) {
                batch = new Batch();
            }
            batch.open = true;
            batch.pending = 0;
            frameBatch = batch;
        }
        if (lightCopy(level, batch, sx, sy, sz) == null) {
            // No block light: nothing to colour.
            DONE.offer(job);
            return;
        }
        // Candidate emitters from this section and its neighbours (light reaches at most 15 blocks).
        int[] emitters = job.emitters;
        int[] score = job.score;
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int[] list = EmitterIndex.get(sx + dx, sy + dy, sz + dz);
                    for (int packed : list) {
                        int local = packed & 0xFFF;
                        int lvl = (packed >>> 12) & 0xF;
                        int ex = dx * 16 + (local & 15);
                        int ey = dy * 16 + (local >>> 8);
                        int ez = dz * 16 + ((local >>> 4) & 15);
                        // Light left when it reaches the section's box.
                        int reach = lvl - (out(ex) + out(ey) + out(ez));
                        if (reach <= 0) {
                            continue;
                        }
                        int at;
                        if (count < MAX_EMITTERS) {
                            at = count++;
                        } else {
                            // Full: replace the weakest candidate if this one reaches further in.
                            at = 0;
                            for (int i = 1; i < MAX_EMITTERS; i++) {
                                if (score[i] < score[at]) {
                                    at = i;
                                }
                            }
                            if (score[at] >= reach) {
                                continue;
                            }
                        }
                        score[at] = reach;
                        int chroma = packed >>> 16;
                        emitters[at * 6] = ex;
                        emitters[at * 6 + 1] = ey;
                        emitters[at * 6 + 2] = ez;
                        emitters[at * 6 + 3] = lvl;
                        emitters[at * 6 + 4] = (byte) (chroma >>> 8);
                        emitters[at * 6 + 5] = (byte) chroma;
                    }
                }
            }
        }
        if (count == 0) {
            DONE.offer(job);
            return;
        }
        job.count = count;
        // Block light of the 27 sections (copies), shared by the jobs scheduled in this frame.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    job.layers[((dy + 1) * 3 + dz + 1) * 3 + dx + 1] = lightCopy(level, batch, sx + dx, sy + dy, sz + dz);
                }
            }
        }
        batch.pending++;
        job.batch = batch;
        if (job.out == null) {
            job.out = new byte[SECTION_BYTES];
        }
        WORK.offer(job);
    }

    private static byte[] lightCopy(ClientLevel level, Batch batch, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        byte[] copy = FRAME_LAYERS.get(key);
        if (copy == null) {
            DataLayer layer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(sx, sy, sz));
            if (layer == null || layer.isDefinitelyFilledWith(0)) {
                copy = NO_LIGHT;
            } else {
                copy = FREE_LAYERS.poll();
                if (copy == null) {
                    copy = new byte[LAYER_BYTES];
                }
                System.arraycopy(layer.getData(), 0, copy, 0, LAYER_BYTES);
                batch.copies.add(copy);
            }
            FRAME_LAYERS.put(key, copy);
        }
        return copy == NO_LIGHT ? null : copy;
    }

    /** Blocks outside the 0..15 box along one axis (0 inside). */
    private static int out(int v) {
        return v < 0 ? -v : v > 15 ? v - 15 : 0;
    }

    /**
     * Region of a job: the target section and its 26 neighbours (48 blocks per side) plus a one-block border of
     * zero light, so the flood fill reads neighbours without bounds checks: 50 per side, block (x, y, z) relative to
     * the target section's origin at index {@code ((y + 17) * R + z + 17) * R + x + 17}.
     */
    private static final int R = 50;
    private static final int REGION = R * R * R;
    private static final int[] STEP = {1, -1, R, -R, R * R, -R * R};
    /** Per region cell: blocks (Manhattan) to the target section's box, 0 inside it (capped at 127). */
    private static final byte[] DIST = new byte[REGION];
    /** Per region cell: its texel index in the target section ({@code z << 8 | y << 4 | x}), or -1 outside. */
    private static final short[] TARGET = new short[REGION];

    static {
        for (int y = 0; y < R; y++) {
            for (int z = 0; z < R; z++) {
                for (int x = 0; x < R; x++) {
                    int i = (y * R + z) * R + x;
                    int d = out(x - 17) + out(y - 17) + out(z - 17);
                    DIST[i] = (byte) Math.min(127, d);
                    TARGET[i] = d == 0 ? (short) (((z - 17) << 4 | (y - 17)) << 4 | (x - 17)) : -1;
                }
            }
        }
    }

    /** Per worker thread scratch. */
    private static final class Scratch {
        final byte[] actual = new byte[REGION];
        final byte[] level = new byte[REGION];
        final int[] stamp = new int[REGION];
        final int[] seen = new int[REGION];
        final int[] queue = new int[1 << 18];
        final int[] visited = new int[REGION];
        final float[] sumW = new float[4096];
        final float[] sumCo = new float[4096];
        final float[] sumCg = new float[4096];
        int counter;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * Worker thread: fills {@code job.out} with the section's texels and sets {@code job.lit}, false when no cell has
     * a colour.
     * <p>
     * Each candidate emitter's light is flood-filled through the <em>game's own block light</em> of the 27 sections:
     * from the emitter, every step loses one level and can never exceed the light the game measured in that block
     * ({@code min(level − 1, actual)}). Opaque blocks hold no block light, so light only spreads where the game's light
     * really goes: around corners and over walls, through leaves and glass, never through stone. Every emitter that
     * reaches a cell adds its colour weighted by the level it reaches it with, so where two lights overlap their
     * colours blend gradually instead of meeting at a hard line.
     * <p>
     * Pruning (0.5.8, exact): a cell is only expanded while its level exceeds its distance to the target section
     * ({@link #DIST}). Every step loses at least one level, so light from a cell with {@code level <= distance} can
     * never arrive there; the result is bit-identical to the unpruned fill, 4-7× faster in a standalone benchmark.
     */
    private static void compute(Job job) {
        Scratch s = SCRATCH.get();
        byte[][] layers = job.layers;
        // Expand the 27 block-light layers into one byte per block of the region (the border stays 0).
        byte[] actual = s.actual;
        for (int sy = 0; sy < 3; sy++) {
            for (int sz = 0; sz < 3; sz++) {
                for (int sx = 0; sx < 3; sx++) {
                    byte[] data = layers[(sy * 3 + sz) * 3 + sx];
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int row = ((sy * 16 + y + 1) * R + sz * 16 + z + 1) * R + sx * 16 + 1;
                            if (data == null) {
                                Arrays.fill(actual, row, row + 16, (byte) 0);
                                continue;
                            }
                            int base = y << 8 | z << 4;
                            for (int x = 0; x < 16; x++) {
                                int li = base | x;
                                actual[row + x] = (byte) ((data[li >> 1] >> ((li & 1) << 2)) & 15);
                            }
                        }
                    }
                }
            }
        }
        Arrays.fill(s.sumW, 0f);
        Arrays.fill(s.sumCo, 0f);
        Arrays.fill(s.sumCg, 0f);
        int[] e = job.emitters;
        byte[] level = s.level;
        int[] stamp = s.stamp;
        int[] seen = s.seen;
        int[] queue = s.queue;
        int[] visited = s.visited;
        int mask = queue.length - 1;
        for (int i = 0; i < job.count; i++) {
            int o = i * 6;
            int ex = e[o] + 17;
            int ey = e[o + 1] + 17;
            int ez = e[o + 2] + 17;
            if (ex < 1 || ey < 1 || ez < 1 || ex > R - 2 || ey > R - 2 || ez > R - 2) {
                continue;
            }
            int start = (ey * R + ez) * R + ex;
            if (e[o + 3] <= DIST[start]) {
                continue;
            }
            int cur = ++s.counter;
            int head = 0;
            int tail = 0;
            int nVisited = 0;
            level[start] = (byte) e[o + 3];
            stamp[start] = cur;
            seen[start] = cur;
            if (TARGET[start] >= 0) {
                visited[nVisited++] = start;
            }
            queue[tail++ & mask] = start;
            while (head != tail) {
                int c = queue[head++ & mask];
                int lv = level[c];
                if (lv <= 1) {
                    continue;
                }
                for (int d = 0; d < 6; d++) {
                    // The border holds 0, so a step never leaves the region.
                    int n = c + STEP[d];
                    int nl = Math.min(lv - 1, actual[n]);
                    if (nl <= DIST[n] || stamp[n] == cur && level[n] >= nl) {
                        continue;
                    }
                    stamp[n] = cur;
                    level[n] = (byte) nl;
                    if (seen[n] != cur) {
                        seen[n] = cur;
                        if (TARGET[n] >= 0) {
                            visited[nVisited++] = n;
                        }
                    }
                    if (tail - head < queue.length) {
                        queue[tail++ & mask] = n;
                    }
                }
            }
            float co = e[o + 4] / 63f;
            float cg = e[o + 5] / 63f;
            for (int v = 0; v < nVisited; v++) {
                int c = visited[v];
                float w = level[c];
                int t = TARGET[c];
                s.sumW[t] += w;
                s.sumCo[t] += w * co;
                s.sumCg[t] += w * cg;
            }
        }
        byte[] out = job.out;
        boolean lit = false;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int t = (z << 4 | y) << 4 | x;
                    float w = s.sumW[t];
                    int a = actual[((y + 17) * R + z + 17) * R + x + 17];
                    if (w <= 0f || a == 0) {
                        continue;
                    }
                    if (!lit) {
                        // Pooled buffer: clear it once, the loop only writes lit texels.
                        Arrays.fill(out, (byte) 0);
                        lit = true;
                    }
                    float co = s.sumCo[t] / w;
                    float cg = s.sumCg[t] / w;
                    int p = t * 4;
                    out[p] = (byte) Math.round(Math.max(0f, Math.min(1f, co * 0.25f + 0.5f)) * 255f);
                    out[p + 1] = (byte) Math.round(Math.max(0f, Math.min(1f, cg * 0.25f + 0.5f)) * 255f);
                    out[p + 2] = (byte) 255;
                    // Alpha: the static block light level (/15), so dynamic lights can be weighed against it.
                    out[p + 3] = (byte) (a * 17);
                }
            }
        }
        job.lit = lit;
    }

    private static void uploadSlot(int slot, byte[] rgba) {
        int x = slot % nxz;
        int z = (slot / nxz) % nxz;
        int y = slot / (nxz * nxz);
        upload.clear();
        if (rgba == null) {
            MemoryUtil.memSet(upload, 0);
        } else {
            upload.put(rgba).flip();
        }
        // Texel layout: x fastest, then y, then z (rows, then images). Direct state access where available (0.6.8).
        com.solarbiscuit.atmosphera.lib.gpu.AtmosGl.texSubImage3D(15, texture, x * 16, y * 16, z * 16, 16, 16, 16,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, upload);
    }

    /**
     * The light colour at a world position, trilinear like the shader: {@code out = (Co, Cg, coverage, static block
     * level 0..15)}. Returns false (and coverage 0) outside the window or where nothing is coloured. Render thread.
     */
    static boolean sample(double x, double y, double z, float[] out) {
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (cpu == null || centreX == Integer.MIN_VALUE) {
            return false;
        }
        double fx = x - 0.5;
        double fy = y - 0.5;
        double fz = z - 0.5;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0);
        float ty = (float) (fy - y0);
        float tz = (float) (fz - z0);
        float r = 0f;
        float g = 0f;
        float b = 0f;
        float a = 0f;
        for (int c = 0; c < 8; c++) {
            int cx = x0 + (c & 1);
            int cy = y0 + (c >> 1 & 1);
            int cz = z0 + (c >> 2 & 1);
            float w = ((c & 1) != 0 ? tx : 1f - tx) * ((c & 2) != 0 ? ty : 1f - ty) * ((c & 4) != 0 ? tz : 1f - tz);
            if (w <= 0f) {
                continue;
            }
            int sx = cx >> 4;
            int sy = cy >> 4;
            int sz = cz >> 4;
            if (!inWindow(sx, sy, sz)) {
                continue;
            }
            int slot = slot(sx, sy, sz);
            byte[] data = cpu[slot];
            if (data == null || owner[slot] != SectionPos.asLong(sx, sy, sz)) {
                continue;
            }
            int t = (((cz & 15) << 4 | (cy & 15)) << 4 | (cx & 15)) * 4;
            r += w * (data[t] & 0xFF);
            g += w * (data[t + 1] & 0xFF);
            b += w * (data[t + 2] & 0xFF);
            a += w * (data[t + 3] & 0xFF);
        }
        if (b < 0.02f * 255f) {
            return false;
        }
        out[0] = (r / b - 0.5f) * 4f;
        out[1] = (g / b - 0.5f) * 4f;
        out[2] = b / 255f;
        out[3] = a / b * 15f;
        return true;
    }

    /** Worker statistics: sections computed with emitters, and their total time. */
    static final AtomicLong JOBS = new AtomicLong();
    static final AtomicLong JOB_NS = new AtomicLong();

    static String describe() {
        long n = JOBS.get();
        String work = n == 0 ? "no sections computed yet" : n + " sections computed, " + String.format(java.util.Locale.ROOT,
                "%.2f", JOB_NS.get() / 1e6 / n) + " ms each";
        return texture == 0 ? "off" : sizeXZ + "x" + sizeY + "x" + sizeXZ + " (" + (sizeXZ * sizeY * sizeXZ * 4 >> 20)
                + " MB), " + EmitterIndex.size() + " sections with emitters, " + DIRTY.size() + " queued, " + work
                + ", " + jobsCreated + " pooled jobs";
    }

    static void logOnce(String what) {
        Lumis.LOGGER.info("Light colour: {}", what);
    }
}
