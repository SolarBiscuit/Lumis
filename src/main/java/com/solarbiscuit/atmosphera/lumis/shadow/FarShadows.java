package com.solarbiscuit.atmosphera.lumis.shadow;

import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sun shadows on distant terrain (Lumis 0.10.0, NeoVoxy API 4): a coarse height field of 8-block cells, 2048 blocks
 * across around the camera, filled from the column tops NeoVoxy reports for every LOD section it builds
 * ({@link VoxyBridge.LodColumns}), and a horizon map over it built like the near one ({@link HorizonMap}: max-plus
 * doubling, 7 passes, shadows up to 128 cells = 1024 blocks long).
 * <p>
 * NeoVoxy reports a section only when it builds its mesh, so the reported tops are kept in world-anchored tiles (32x32
 * cells, 256 blocks) within {@link #KEEP_BLOCKS} of the camera, and the window is refilled from them when it moves. A
 * cell keeps the top from the finest LOD level that reported it; for that level the highest section wins, and a
 * section reporting nothing clears the top it gave earlier (a block removed far away).
 * <p>
 * LOD fragments read it on texture unit 13 (bound before each LOD draw); Xenon terrain reads it through a requested
 * terrain unit beyond the near shadow window. Render thread except the reports (mesh build threads).
 */
public final class FarShadows {
    static final int CELL = 8;
    static final int N = 256;
    private static final int PASSES = 7;
    private static final int RECENTRE_CELLS = 16;
    private static final int KEEP_BLOCKS = 3072;
    private static final int TILE = 32;
    private static final short NONE = Short.MIN_VALUE;
    private static final byte NO_LOD = (byte) 127;
    private static final int REPORT_BUDGET = 512;
    private static final long UPLOAD_INTERVAL_NS = 200_000_000L;
    private static final long BUILD_INTERVAL_NS = 250_000_000L;
    /** LOD texture unit (NeoVoxy API 4 leaves 8-13 to extensions; 13 is outside vanilla's tracked 0-11). */
    static final int LOD_UNIT = 13;

    private record Report(int lod, int sx, int sy, int sz, short[] top) {
    }

    private static final class Tile {
        final short[] top = new short[TILE * TILE];
        final byte[] lod = new byte[TILE * TILE];
        final short[] sy = new short[TILE * TILE];

        Tile() {
            java.util.Arrays.fill(top, NONE);
            java.util.Arrays.fill(lod, NO_LOD);
        }
    }

    private static final ConcurrentLinkedQueue<Report> REPORTS = new ConcurrentLinkedQueue<>();
    private static final Long2ObjectOpenHashMap<Tile> TILES = new Long2ObjectOpenHashMap<>();
    private static volatile boolean wanted;
    private static boolean registered;
    private static boolean running;

    // Window (cells, world-anchored, toroidal: texel = cell & (N - 1))
    private static int centreCellX = Integer.MIN_VALUE;
    private static int centreCellZ;
    private static float[] window;
    private static FloatBuffer upload;
    private static boolean dirty;
    private static long uploadedAt;
    private static long uploads;
    // Status (logged three times after start, then on request): reports applied, cells changed, builds.
    private static long applied;
    private static long cellsSet;
    private static long builds;
    private static int statusLogs;
    private static long statusAt;

    // GPU
    private static int heightTex;
    private static int texA;
    private static int texB;
    private static int fboA;
    private static int fboB;
    private static int program = -1;

    /** 0.10.9: the lib's shader reload: free the program; the next use rebuilds it. */
    public static void reloadShaders() {
        if (program > 0) {
            com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteProgram(program);
        }
        program = -1;
    }
    private static int uSrc;
    private static int uWin;
    private static int uSun;
    private static int uStep;
    static boolean valid;
    private static long builtUploads = -1;
    private static int builtCentreX;
    private static int builtCentreZ;
    private static long builtAt;
    static float dirX;
    static float dirZ;
    static float tan;
    private static float builtElevation = Float.NaN;
    private static boolean builtSunSide;
    static double windowCentreX;
    static double windowCentreZ;

    private static final String SOURCE = """
            uniform sampler2D u_Src;
            uniform ivec4 u_Win;   // xy: window centre (texel), z: N, w: N / 2 - 2 (the usable half width)
            uniform vec4 u_Sun;    // xy: step direction towards the sun (cells), z: tan(elevation) per cell
            uniform int u_Step;    // 0: build S_0 from the heights, else 2^(k-1)
            out vec2 o_Out;

            ivec2 relOf(ivec2 texel) {
                return ((texel - u_Win.xy + u_Win.z / 2) & ivec2(u_Win.z - 1)) - u_Win.z / 2;
            }

            bool inside(ivec2 rel) {
                return all(greaterThanEqual(rel, ivec2(-u_Win.w))) && all(lessThan(rel, ivec2(u_Win.w)));
            }

            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                ivec2 rel = relOf(p);
                int step = max(u_Step, 1);
                ivec2 off = ivec2(round(u_Sun.xy * float(step)));
                bool valid = inside(rel + off);
                ivec2 q = (p + off) & ivec2(u_Win.z - 1);
                if (u_Step == 0) {
                    float h = valid ? texelFetch(u_Src, q, 0).r : -1e4;
                    o_Out = vec2(h > -1e3 ? h - 0.5 * u_Sun.z : -1e4, h > -1e3 ? 1.0 : 0.0);
                    return;
                }
                vec2 a = texelFetch(u_Src, p, 0).rg;
                if (!valid) {
                    o_Out = a;
                    return;
                }
                vec2 b = texelFetch(u_Src, q, 0).rg;
                float sb = b.r - float(step) * u_Sun.z;
                o_Out = sb > a.r ? vec2(sb, min(b.g + float(step), 255.0)) : a;
            }
            """;

    private FarShadows() {
    }

    /** The far horizon map (RG32F: shadow height in world y, blocker distance in cells), or 0. */
    public static int texture() {
        return valid && running ? texB : 0;
    }

    /** Registers with the lib's Voxy bridge (mod construction). */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        VoxyBridge.setLodColumns(new VoxyBridge.LodColumns() {
            @Override
            public void sectionColumns(int lod, int sx, int sy, int sz, short[] top, short[] water) {
                if (lod > 6) {
                    return;   // a section over 2048 blocks wide adds nothing the window could hold
                }
                REPORTS.add(new Report(lod, sx, sy, sz, top.clone()));
            }

            @Override
            public boolean wanted() {
                return wanted;
            }
        });
        VoxyBridge.addBeforeLodDraw(FarShadows::bindForLods);
    }

    /** NeoVoxy reports column tops (API 4). */
    static boolean available() {
        return VoxyBridge.lodColumnsAvailable();
    }

    private static void bindForLods() {
        if (Lumis.stopping) {
            return;
        }
        com.solarbiscuit.atmosphera.lib.gpu.AtmosGl.bindTextureUnit(LOD_UNIT, GL11C.GL_TEXTURE_2D, texture());
    }

    /** Once per frame from {@link SunShadows#update} (render thread). {@code on}: sun shadows run and LOD shadows wanted. */
    static void update(boolean on, double camX, double camZ, float sunX, float sunY, float sunZ) {
        if (!on || !available()) {
            if (running) {
                stop();
            }
            return;
        }
        if (!running) {
            running = true;
            wanted = true;
            allocate();
            // NeoVoxy reports columns while it builds LOD meshes: rebuild them once so every section reports.
            VoxyBridge.requestRendererRebuild("Lumis sun shadows: collect distant terrain heights");
        }
        int camCellX = Math.floorDiv((int) Math.floor(camX), CELL);
        int camCellZ = Math.floorDiv((int) Math.floor(camZ), CELL);
        if (centreCellX == Integer.MIN_VALUE || Math.abs(camCellX - centreCellX) >= RECENTRE_CELLS
                || Math.abs(camCellZ - centreCellZ) >= RECENTRE_CELLS) {
            recentre(camCellX - Math.floorMod(camCellX, RECENTRE_CELLS), camCellZ - Math.floorMod(camCellZ, RECENTRE_CELLS));
        }
        for (int i = 0; i < REPORT_BUDGET; i++) {
            Report r = REPORTS.poll();
            if (r == null) {
                break;
            }
            apply(r);
        }
        long now = System.nanoTime();
        if (dirty && now - uploadedAt > UPLOAD_INTERVAL_NS) {
            uploadWindow();
            uploadedAt = now;
        }
        build(sunX, sunY, sunZ, now);
        if (statusLogs < 3 && now - statusAt > 15_000_000_000L) {
            statusAt = now;
            statusLogs++;
            Lumis.LOGGER.info("Distant sun shadows: {}", describe());
        }
    }

    /** One line for the log and the status command. */
    static String describe() {
        if (!running) {
            return "off";
        }
        int filled = 0;
        if (window != null) {
            for (float v : window) {
                if (v > -1e3f) filled++;
            }
        }
        return String.format(java.util.Locale.ROOT, "%d section reports (%d queued), %d cell changes, %d of %d window cells with "
                        + "heights, %d tiles, %d uploads, %d builds, map %s, texture %d",
                applied, REPORTS.size(), cellsSet, filled, N * N, TILES.size(), uploads, builds,
                valid ? "valid" : "not built", texture());
    }

    private static void stop() {
        running = false;
        wanted = false;
        valid = false;
        statusLogs = 0;
        REPORTS.clear();
        TILES.clear();
        centreCellX = Integer.MIN_VALUE;
        if (heightTex != 0) {
            GpuResources.deleteFramebuffer(fboA);
            GpuResources.deleteFramebuffer(fboB);
            GpuResources.deleteTexture(texA);
            GpuResources.deleteTexture(texB);
            GpuResources.deleteTexture(heightTex);
            heightTex = texA = texB = fboA = fboB = 0;
        }
        if (upload != null) {
            MemoryUtil.memFree(upload);
            upload = null;
        }
        window = null;
    }

    /** World login or dimension change: forget every height. */
    static void clear() {
        if (running) {
            stop();
        }
    }

    // ---- reports -------------------------------------------------------------------------------------------------

    private static final short[] SCRATCH = new short[64 * 64];

    /** One section's tops into the tiles (and the window): aggregated to cells first (max), then the merge rule. */
    private static void apply(Report r) {
        applied++;
        int voxel = 1 << r.lod();
        int originX = (r.sx() << 5) << r.lod();
        int originZ = (r.sz() << 5) << r.lod();
        int baseY = (r.sy() << 5) << r.lod();
        // Cells per section side (a voxel smaller than a cell: several voxels per cell; larger: several cells each).
        int span = 32 * voxel;
        int cells = Math.max(1, span / CELL);
        int cellX0 = Math.floorDiv(originX, CELL);
        int cellZ0 = Math.floorDiv(originZ, CELL);
        if (cells > 64) {
            return;
        }
        java.util.Arrays.fill(SCRATCH, 0, cells * cells, NONE);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int t = r.top()[z << 5 | x];
                if (t < 0) {
                    continue;
                }
                short y = (short) Math.min(Short.MAX_VALUE, baseY + (t + 1) * voxel);
                int c0x = x * voxel / CELL;
                int c1x = ((x + 1) * voxel - 1) / CELL;
                int c0z = z * voxel / CELL;
                int c1z = ((z + 1) * voxel - 1) / CELL;
                for (int cz = c0z; cz <= c1z; cz++) {
                    for (int cx = c0x; cx <= c1x; cx++) {
                        int i = cz * cells + cx;
                        if (y > SCRATCH[i]) {
                            SCRATCH[i] = y;
                        }
                    }
                }
            }
        }
        byte lod = (byte) r.lod();
        short sy = (short) r.sy();
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                merge(cellX0 + cx, cellZ0 + cz, SCRATCH[cz * cells + cx], lod, sy);
            }
        }
    }

    private static void merge(int cellX, int cellZ, short v, byte lod, short sy) {
        int blockX = cellX * CELL;
        int blockZ = cellZ * CELL;
        if (centreCellX != Integer.MIN_VALUE && (Math.abs(blockX - centreCellX * CELL) > KEEP_BLOCKS
                || Math.abs(blockZ - centreCellZ * CELL) > KEEP_BLOCKS)) {
            return;
        }
        long key = tileKey(Math.floorDiv(cellX, TILE), Math.floorDiv(cellZ, TILE));
        Tile tile = TILES.get(key);
        if (tile == null) {
            if (v == NONE) {
                return;
            }
            tile = new Tile();
            TILES.put(key, tile);
        }
        int i = Math.floorMod(cellZ, TILE) * TILE + Math.floorMod(cellX, TILE);
        byte stLod = tile.lod[i];
        boolean change;
        if (v != NONE) {
            // A finer level wins; the same level keeps its highest section, or the section that gave the top.
            change = stLod == NO_LOD || lod < stLod || lod == stLod && (v >= tile.top[i] || tile.sy[i] == sy);
        } else {
            // The section that gave the top has nothing in this column any more.
            change = stLod == lod && tile.sy[i] == sy;
        }
        if (!change) {
            return;
        }
        cellsSet++;
        tile.top[i] = v;
        tile.lod[i] = v == NONE ? NO_LOD : lod;
        tile.sy[i] = sy;
        writeWindow(cellX, cellZ, v);
    }

    private static long tileKey(int tx, int tz) {
        return (long) tx << 32 | (tz & 0xFFFFFFFFL);
    }

    private static void writeWindow(int cellX, int cellZ, short v) {
        if (window == null || centreCellX == Integer.MIN_VALUE
                || Math.abs(cellX - centreCellX) >= N / 2 || Math.abs(cellZ - centreCellZ) >= N / 2) {
            return;
        }
        window[(cellZ & (N - 1)) * N + (cellX & (N - 1))] = v == NONE ? -1e4f : v;
        dirty = true;
    }

    private static void recentre(int cellX, int cellZ) {
        centreCellX = cellX;
        centreCellZ = cellZ;
        windowCentreX = cellX * (double) CELL;
        windowCentreZ = cellZ * (double) CELL;
        // Forget tiles far outside (they would only come back with a NeoVoxy rebuild).
        int keepTiles = KEEP_BLOCKS / (TILE * CELL) + 1;
        int tcx = Math.floorDiv(cellX, TILE);
        int tcz = Math.floorDiv(cellZ, TILE);
        TILES.long2ObjectEntrySet().removeIf(e -> {
            int tx = (int) (e.getLongKey() >> 32);
            int tz = (int) e.getLongKey();
            return Math.abs(tx - tcx) > keepTiles || Math.abs(tz - tcz) > keepTiles;
        });
        if (window == null) {
            return;
        }
        for (int dz = -N / 2; dz < N / 2; dz++) {
            int cz = cellZ + dz;
            for (int dx = -N / 2; dx < N / 2; dx++) {
                int cx = cellX + dx;
                Tile tile = TILES.get(tileKey(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE)));
                short v = tile == null ? NONE : tile.top[Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE)];
                window[(cz & (N - 1)) * N + (cx & (N - 1))] = v == NONE ? -1e4f : v;
            }
        }
        dirty = true;
    }

    // ---- GPU ------------------------------------------------------------------------------------------------------

    private static void allocate() {
        window = new float[N * N];
        java.util.Arrays.fill(window, -1e4f);
        upload = MemoryUtil.memAllocFloat(N * N);
        heightTex = texture2D("Lumis far heights", GL30C.GL_R32F, GL30C.GL_RED);
        texA = texture2D("Lumis far horizon A", GL30C.GL_RG32F, GL30C.GL_RG);
        texB = texture2D("Lumis far horizon B", GL30C.GL_RG32F, GL30C.GL_RG);
        fboA = framebuffer(texA);
        fboB = framebuffer(texB);
        if (program < 0) {
            program = GlslPrograms.compile("Lumis far horizon map", GlslPrograms.FULLSCREEN_VERTEX_460,
                    "#version 460 core" + (char) 10 + SOURCE);
            if (program == 0) {
                Lumis.LOGGER.error("Distant sun shadows are off: their shader did not compile (see above).");
            } else {
                uSrc = GL20C.glGetUniformLocation(program, "u_Src");
                uWin = GL20C.glGetUniformLocation(program, "u_Win");
                uSun = GL20C.glGetUniformLocation(program, "u_Sun");
                uStep = GL20C.glGetUniformLocation(program, "u_Step");
            }
        }
        dirty = true;
        builtUploads = -1;
        valid = false;
    }

    private static int texture2D(String owner, int internal, int format) {
        int tex = GpuResources.texture(owner);
        int previous = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internal, N, N, 0, format, GL11C.GL_FLOAT, (FloatBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previous);
        GpuResources.textureBytes(tex, (long) N * N * (internal == GL30C.GL_R32F ? 4 : 8));
        return tex;
    }

    private static int framebuffer(int tex) {
        int fbo = GpuResources.framebuffer("Lumis far horizon");
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, tex, 0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
        return fbo;
    }

    private static void uploadWindow() {
        upload.clear();
        upload.put(window).flip();
        int previous = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, heightTex);
        // Tight client-memory unpacking (0.10.1): vanilla leaves a row length set, and with it the driver read past
        // this buffer (an access violation in the driver on the first upload in 0.10.0).
        com.solarbiscuit.atmosphera.lib.gpu.Uploads.begin(4);
        try {
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, N, N, GL30C.GL_RED, GL11C.GL_FLOAT, upload);
        } finally {
            com.solarbiscuit.atmosphera.lib.gpu.Uploads.end();
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previous);
        dirty = false;
        uploads++;
    }

    /** Rebuilds the far horizon map when the heights, the window or the sun moved enough (rate-limited). */
    private static void build(float sunX, float sunY, float sunZ, long now) {
        if (program <= 0 || heightTex == 0 || uploads == 0) {
            return;
        }
        float horizontal = (float) Math.sqrt(sunX * sunX + sunZ * sunZ);
        if (horizontal < 1e-4f || sunY <= 0.02f) {
            valid = false;   // the sun straight up casts no far shadows; below the horizon none at all
            return;
        }
        float elevation = (float) Math.atan2(sunY, horizontal);
        boolean moved = Float.isNaN(builtElevation) || Math.abs(elevation - builtElevation) > 0.004f
                || builtSunSide != (sunY > 0f);
        boolean changed = builtUploads != uploads || builtCentreX != centreCellX || builtCentreZ != centreCellZ;
        if (valid && !(changed || moved) || now - builtAt < BUILD_INTERVAL_NS) {
            return;
        }
        builtAt = now;
        builds++;
        builtUploads = uploads;
        builtCentreX = centreCellX;
        builtCentreZ = centreCellZ;
        builtElevation = elevation;
        builtSunSide = sunY > 0f;
        dirX = sunX / horizontal;
        dirZ = sunZ / horizontal;
        tan = (float) Math.tan(elevation);

        com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot saved = com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot.save();
        try {
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glViewport(0, 0, N, N);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(uSrc, 15);
            GL20C.glUniform4i(uWin, centreCellX & (N - 1), centreCellZ & (N - 1), N, N / 2 - 2);
            GL20C.glUniform4f(uSun, dirX, dirZ, tan * CELL, 0f);
            GpuTimers.begin("lumis_far_shadow_build");
            GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
            int src = heightTex;
            int target = fboA;
            int written = texA;
            for (int k = 0; k <= PASSES; k++) {
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, src);
                GL20C.glUniform1i(uStep, k == 0 ? 0 : 1 << (k - 1));
                GlslPrograms.drawFullscreen();
                src = written;
                // Ping-pong; an even number of passes after S_0 ends in B.
                if (target == fboA) {
                    target = fboB;
                    written = texB;
                } else {
                    target = fboA;
                    written = texA;
                }
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GpuTimers.end("lumis_far_shadow_build");
            valid = src == texB;
        } finally {
            saved.restore();
        }
    }
}
