package com.solarbiscuit.atmosphera.lumis.shadow;

import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import com.solarbiscuit.atmosphera.lumis.util.SectionReader;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * The top sun caster of every column in an N x N block window around the camera, on the CPU and in an R16 texture.
 * <p>
 * The window is whole chunks, {@code [cameraChunk - T/2, cameraChunk + T/2)} with T = N / 16, stored toroidally: block
 * x lives at texel {@code x & (N - 1)}. N divides 4096, the wrap period of {@code atmos_worldPos}, so shaders index it
 * straight from wrapped world positions. Each 16 x 16 tile slot remembers which chunk it holds; when the camera
 * crosses a chunk border, slots that fall out of the window are cleared and given the chunk that moved in.
 * <p>
 * Texel value: 0 = no caster, else {@code (y - minY) * 16 + h} where y is the caster block and h its height in
 * sixteenths ({@link CasterTable}). Columns are scanned on the render thread within a time budget, from the
 * WORLD_SURFACE heightmap down past non-casters; block changes update one column. Render thread only.
 */
public final class Heightfield {
    private static final long EMPTY = Long.MIN_VALUE;
    private static final byte MISSING = 0;
    private static final byte QUEUED = 1;
    private static final byte SCANNED = 2;
    /** Non-casters walked past before a column counts as empty (deep water, glass towers). */
    private static final int MAX_WALK = 64;
    private static final long SCAN_BUDGET_NS = 150_000L;
    private static final int MAX_TILE_UPLOADS = 128;

    static int n;
    static int tiles;
    static int minY;
    static short[] heights;
    private static long[] slotChunk;
    private static byte[] slotState;
    private static boolean[] slotDirty;
    private static final IntArrayList DIRTY = new IntArrayList();
    private static final IntArrayFIFOQueue QUEUE = new IntArrayFIFOQueue();
    private static boolean fullUpload;
    private static int texture;
    private static ShortBuffer staging;
    /** Window centre in chunks (the window is [centre - T/2, centre + T/2)). */
    static int centreChunkX;
    static int centreChunkZ;
    static boolean centred;
    /** Highest caster top seen since the last reset (world y), an upper bound for CPU marches. */
    static float maxTop = -1e9f;
    /** Tiles were uploaded since the horizon map last read the texture. */
    static boolean uploadedSinceRead;
    private static ClientLevel level;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private Heightfield() {
    }

    static boolean ready() {
        return heights != null && centred;
    }

    static int texture() {
        return texture;
    }

    /** Allocates for {@code size} (256, 512 or 1024), or clears everything when the level changed. */
    static void ensure(ClientLevel current, int size) {
        if (heights == null || n != size) {
            release();
            n = size;
            tiles = size / 16;
            heights = new short[size * size];
            slotChunk = new long[tiles * tiles];
            slotState = new byte[tiles * tiles];
            slotDirty = new boolean[tiles * tiles];
            Arrays.fill(slotChunk, EMPTY);
            staging = MemoryUtil.memAllocShort(256);
            createTexture();
            level = null;
        }
        if (current != level) {
            level = current;
            minY = current.getMinBuildHeight();
            verifyLeft = 1024;
            Arrays.fill(heights, (short) 0);
            Arrays.fill(slotChunk, EMPTY);
            Arrays.fill(slotState, MISSING);
            Arrays.fill(slotDirty, false);
            DIRTY.clear();
            QUEUE.clear();
            centred = false;
            maxTop = -1e9f;
            fullUpload = true;
            HorizonMap.valid = false;
        }
    }

    static void release() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        heights = null;
        slotChunk = null;
        slotState = null;
        slotDirty = null;
        DIRTY.clear();
        QUEUE.clear();
        centred = false;
        level = null;
        n = 0;
    }

    /** Per frame: follow the camera, scan queued chunks within the budget, upload changed tiles. */
    static void update(int cameraChunkX, int cameraChunkZ) {
        if (!centred || cameraChunkX != centreChunkX || cameraChunkZ != centreChunkZ) {
            recentre(cameraChunkX, cameraChunkZ);
        }
        long start = System.nanoTime();
        while (!QUEUE.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            scanSlot(QUEUE.dequeueInt());
        }
        upload();
    }

    private static void recentre(int ccx, int ccz) {
        int changed = 0;
        int baseX = ccx - tiles / 2;
        int baseZ = ccz - tiles / 2;
        int mask = tiles - 1;
        for (int sz = 0; sz < tiles; sz++) {
            int cz = baseZ + ((sz - baseZ) & mask);
            for (int sx = 0; sx < tiles; sx++) {
                int cx = baseX + ((sx - baseX) & mask);
                int slot = sz * tiles + sx;
                long key = ChunkPos.asLong(cx, cz);
                if (slotChunk[slot] == key) {
                    continue;
                }
                slotChunk[slot] = key;
                slotState[slot] = MISSING;
                clearTile(slot);
                changed++;
                if (level.getChunkSource().getChunk(cx, cz, false) != null) {
                    enqueue(slot);
                }
            }
        }
        centreChunkX = ccx;
        centreChunkZ = ccz;
        centred = true;
        if (changed > tiles * tiles / 4) {
            fullUpload = true;
        }
    }

    /** A chunk arrived (or was resent) on the client. */
    static void onChunkLoad(LevelChunk chunk) {
        if (heights == null || chunk.getLevel() != level) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int slot = slotOf(pos.x, pos.z);
        if (slotChunk[slot] == pos.toLong()) {
            enqueue(slot);
        }
    }

    static void onChunkUnload(LevelChunk chunk) {
        if (heights == null || chunk.getLevel() != level) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int slot = slotOf(pos.x, pos.z);
        if (slotChunk[slot] == pos.toLong()) {
            slotState[slot] = MISSING;
            clearTile(slot);
        }
    }

    /** A block changed on the client (already set). Usually O(1): only the column's top caster matters. */
    static void onBlockChanged(BlockPos pos, BlockState state) {
        if (heights == null) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        int slot = slotOf(x >> 4, z >> 4);
        if (slotChunk[slot] != ChunkPos.asLong(x >> 4, z >> 4) || slotState[slot] != SCANNED) {
            return;
        }
        int index = index(x, z);
        int raw = heights[index] & 0xFFFF;
        int topY = raw == 0 ? Integer.MIN_VALUE : minY + (raw - 1) / 16;
        int y = pos.getY();
        int caster = CasterTable.of(state);
        int next = raw;
        if (caster != CasterTable.NONE) {
            if (y >= topY) {
                next = encode(y, caster);
            }
        } else if (y == topY) {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            next = chunk != null ? scanColumn(chunk, x, z, y - 1) : 0;
        }
        if (next != raw) {
            heights[index] = (short) next;
            noteTop(next);
            markDirty(slot);
        }
    }

    /** Raw texel at a world column, or 0 when the column is outside the window. */
    static int raw(int x, int z) {
        int half = tiles / 2;
        int rx = (x >> 4) - centreChunkX;
        int rz = (z >> 4) - centreChunkZ;
        if (rx < -half || rx >= half || rz < -half || rz >= half) {
            return 0;
        }
        return heights[index(x, z)] & 0xFFFF;
    }

    static float top(int raw) {
        return minY + raw / 16f;
    }

    private static int index(int x, int z) {
        return (z & (n - 1)) * n + (x & (n - 1));
    }

    private static int slotOf(int cx, int cz) {
        return (cz & (tiles - 1)) * tiles + (cx & (tiles - 1));
    }

    private static int encode(int y, int caster) {
        return Math.max(1, Math.min(0xFFFF, (y - minY) * 16 + caster));
    }

    private static void enqueue(int slot) {
        if (slotState[slot] != QUEUED) {
            slotState[slot] = QUEUED;
            QUEUE.enqueue(slot);
        }
    }

    private static void scanSlot(int slot) {
        if (slotState[slot] != QUEUED) {
            return;
        }
        long key = slotChunk[slot];
        LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        if (chunk == null) {
            slotState[slot] = MISSING;
            return;
        }
        int x0 = ChunkPos.getX(key) << 4;
        int z0 = ChunkPos.getZ(key) << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int raw = scanColumn(chunk, x0 + lx, z0 + lz, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
                heights[index(x0 + lx, z0 + lz)] = (short) raw;
                noteTop(raw);
            }
        }
        slotState[slot] = SCANNED;
        markDirty(slot);
    }

    private static final SectionReader READER = new SectionReader();
    /** Column scans still compared with the getBlockState path this session (reset when the window is set up). */
    private static int verifyLeft = 1024;

    /**
     * Top caster of a column from {@code fromY} down (at most {@link #MAX_WALK} non-casters), or 0. 0.5.11: reads the
     * sections' palettes directly ({@link SectionReader}) and skips all-air sections whole, instead of a
     * {@code getBlockState} per block; the first columns of a session are checked against the old path.
     */
    private static int scanColumn(LevelChunk chunk, int x, int z, int fromY) {
        if (!SectionReader.usable()) {
            return scanColumnStates(chunk, x, z, fromY);
        }
        int raw = scanColumnDirect(chunk, x, z, fromY);
        if (verifyLeft > 0) {
            verifyLeft--;
            int check = scanColumnStates(chunk, x, z, fromY);
            if (check != raw) {
                SectionReader.disable("heightfield column " + x + " " + z + " differed from getBlockState");
                raw = check;
            }
        }
        return raw;
    }

    private static int scanColumnDirect(LevelChunk chunk, int x, int z, int fromY) {
        int bottom = Math.max(minY, fromY - MAX_WALK);
        LevelChunkSection[] sections = chunk.getSections();
        int lx = x & 15;
        int lz = z & 15;
        int y = fromY;
        while (y >= bottom) {
            int stop = Math.max(bottom, y & ~15);
            int index = chunk.getSectionIndex(y);
            LevelChunkSection section = index >= 0 && index < sections.length ? sections[index] : null;
            if (section == null || section.hasOnlyAir()) {
                // Air (or outside the build height) all the way down this section: no caster.
                y = stop - 1;
                continue;
            }
            boolean direct = READER.bind(section);
            for (; y >= stop; y--) {
                BlockState state = direct ? READER.get(lx, y & 15, lz) : section.getBlockState(lx, y & 15, lz);
                int caster = CasterTable.of(state);
                if (caster != CasterTable.NONE) {
                    return encode(y, caster);
                }
            }
        }
        return 0;
    }

    /** The 0.5.10 path: fallback and self-check. */
    private static int scanColumnStates(LevelChunk chunk, int x, int z, int fromY) {
        int bottom = Math.max(minY, fromY - MAX_WALK);
        for (int y = fromY; y >= bottom; y--) {
            int caster = CasterTable.of(chunk.getBlockState(POS.set(x, y, z)));
            if (caster != CasterTable.NONE) {
                return encode(y, caster);
            }
        }
        return 0;
    }

    private static void noteTop(int raw) {
        if (raw != 0) {
            maxTop = Math.max(maxTop, top(raw));
        }
    }

    private static void clearTile(int slot) {
        int sx = slot % tiles;
        int sz = slot / tiles;
        for (int lz = 0; lz < 16; lz++) {
            int row = (sz * 16 + lz) * n + sx * 16;
            Arrays.fill(heights, row, row + 16, (short) 0);
        }
        markDirty(slot);
    }

    private static void markDirty(int slot) {
        if (!slotDirty[slot]) {
            slotDirty[slot] = true;
            DIRTY.add(slot);
        }
    }

    private static void createTexture() {
        texture = GpuResources.texture("Lumis heightfield");
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R16, n, n, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_SHORT,
                (ShortBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) n * n * 2);
        fullUpload = true;
    }

    private static void upload() {
        if (!fullUpload && DIRTY.isEmpty()) {
            return;
        }
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int previousPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int rowLength = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
        int alignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 2);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);

        if (fullUpload) {
            fullUpload = false;
            for (int i = 0; i < DIRTY.size(); i++) {
                slotDirty[DIRTY.getInt(i)] = false;
            }
            DIRTY.clear();
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, n, n, GL11C.GL_RED, GL11C.GL_UNSIGNED_SHORT, heights);
        } else {
            int count = Math.min(DIRTY.size(), MAX_TILE_UPLOADS);
            for (int i = 0; i < count; i++) {
                int slot = DIRTY.getInt(DIRTY.size() - 1 - i);
                slotDirty[slot] = false;
                int sx = slot % tiles;
                int sz = slot / tiles;
                staging.clear();
                for (int lz = 0; lz < 16; lz++) {
                    staging.put(heights, (sz * 16 + lz) * n + sx * 16, 16);
                }
                staging.flip();
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, sx * 16, sz * 16, 16, 16, GL11C.GL_RED,
                        GL11C.GL_UNSIGNED_SHORT, staging);
            }
            DIRTY.size(DIRTY.size() - count);
        }
        uploadedSinceRead = true;

        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, rowLength);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, skipRows);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, skipPixels);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
        GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousPbo);
    }
}
