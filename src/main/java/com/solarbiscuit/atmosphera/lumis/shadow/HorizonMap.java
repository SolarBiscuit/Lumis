package com.solarbiscuit.atmosphera.lumis.shadow;

import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.world.ColumnField;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The sun's shadow height for every column of the heightfield window: the lowest height that still sees the sun.
 * <p>
 * With the sun towards the horizontal unit direction d at elevation θ, a blocker column at distance t (centre to
 * centre) of top H shades everything below {@code H - (t - 0.5) tanθ} at the receiver's column centre (0.5: its near
 * edge). The texture holds, per column p, {@code S(p) = max over t in 1..128 of that}, built in 8 fullscreen passes by
 * max-plus doubling: {@code S_0(p) = term(p + d)}, {@code S_k(p) = max(S_(k-1)(p), S_(k-1)(p + 2^(k-1) d) - 2^(k-1) tanθ)}.
 * Columns outside the window count as empty. Shaders add {@code s tanθ} for the fragment's offset s along d from its
 * column centre, so shadow edges move continuously, not block by block.
 * <p>
 * RG32F: R = S (world y, -1e4 for none); G = own column top (raw heightfield value) * 256 + blocker distance, both
 * small integers, exact in a float. Rebuilt only when the sun moved enough, the window moved, or tiles changed.
 * Render thread only.
 */
public final class HorizonMap {
    private static final int PASSES = 7;

    private static final String SOURCE = """
            uniform sampler2D u_Src;
            uniform ivec4 u_Win;   // xy: window centre (texel), z: N, w: N / 2
            uniform vec4 u_Sun;    // xy: step direction towards the sun, z: tan(elevation), w: minY
            uniform int u_Step;    // 0: build S_0 from the heightfield, else 2^(k-1)
            out vec2 o_Out;

            ivec2 relOf(ivec2 texel) {
                return ((texel - u_Win.xy + u_Win.w) & ivec2(u_Win.z - 1)) - u_Win.w;
            }

            bool inside(ivec2 rel) {
                return all(greaterThanEqual(rel, ivec2(-u_Win.w))) && all(lessThan(rel, ivec2(u_Win.w)));
            }

            float rawAt(ivec2 texel) {
                return floor(texelFetch(u_Src, texel, 0).r * 65535.0 + 0.5);
            }

            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                ivec2 rel = relOf(p);
                int step = max(u_Step, 1);
                ivec2 off = ivec2(round(u_Sun.xy * float(step)));
                bool valid = inside(rel + off);
                ivec2 q = (p + off) & ivec2(u_Win.z - 1);
                if (u_Step == 0) {
                    float own = rawAt(p);
                    float raw = valid ? rawAt(q) : 0.0;
                    float s = raw > 0.5 ? u_Sun.w + raw / 16.0 - 0.5 * u_Sun.z : -1e4;
                    o_Out = vec2(s, own * 256.0 + (raw > 0.5 ? 1.0 : 0.0));
                    return;
                }
                vec2 a = texelFetch(u_Src, p, 0).rg;
                float own = floor(a.g / 256.0);
                float distA = a.g - own * 256.0;
                if (!valid) {
                    o_Out = a;
                    return;
                }
                vec2 b = texelFetch(u_Src, q, 0).rg;
                float sb = b.r - float(step) * u_Sun.z;
                float distB = b.g - floor(b.g / 256.0) * 256.0 + float(step);
                o_Out = sb > a.r ? vec2(sb, own * 256.0 + min(distB, 255.0)) : vec2(a.r, own * 256.0 + distA);
            }
            """;

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
    private static int texA;
    private static int texB;
    private static int fboA;
    private static int fboB;
    private static int size;

    /** Parameters of the last build; shaders and CPU marches must use these, not the current sun. */
    static boolean valid;
    /** Column-field uploads and generation the map was last built from (lib 0.5.0). */
    private static long seenUploads = -1;
    private static int fieldGeneration = -1;
    static int centreX;
    static int centreZ;
    static float dirX;
    static float dirZ;
    static float tan;
    /** tan(elevation) of the sun now (same formula as a build), for the shaders' between-build correction. */
    static float tanNow;
    /** The current horizontal sun direction still matches the last build (no sun/moon switch pending). */
    static boolean sameDirection;
    private static float elevation;
    private static long builtAt;

    private HorizonMap() {
    }

    /** The texture terrain samples (the last pass writes B), or 0. */
    public static int texture() {
        return valid ? texB : 0;
    }

    static void release() {
        if (texA != 0) {
            GpuResources.deleteFramebuffer(fboA);
            GpuResources.deleteFramebuffer(fboB);
            GpuResources.deleteTexture(texA);
            GpuResources.deleteTexture(texB);
            texA = texB = fboA = fboB = 0;
        }
        size = 0;
        valid = false;
    }

    /**
     * Rebuilds when needed. {@code sunX/Y/Z}: unit direction towards the light. {@code minIntervalNs}: quality-dependent
     * rate cap for sun motion (window moves and the first build are immediate).
     */
    static void update(float sunX, float sunY, float sunZ, long minIntervalNs) {
        if (!ColumnField.ready()) {
            valid = false;
            return;
        }
        long now = System.nanoTime();
        float horizontal = (float) Math.sqrt(sunX * sunX + sunZ * sunZ);
        float elev = (float) Math.atan2(sunY, horizontal);
        float dx = horizontal > 1e-4f ? sunX / horizontal : 1f;
        float dz = horizontal > 1e-4f ? sunZ / horizontal : 0f;
        int cx = ColumnField.centreChunkX() << 4;
        int cz = ColumnField.centreChunkZ() << 4;
        // sunY / horizontal, not tan(atan2(...)): at noon the float angle rounds past pi/2 and tan() turns negative.
        float tanElevation = Math.max(1e-3f, Math.min(1e4f, sunY / Math.max(horizontal, 1e-4f)));
        tanNow = tanElevation;
        sameDirection = dx * dirX + dz * dirZ > 0.9999f;

        boolean rebuild = !valid || size != ColumnField.size() || cx != centreX || cz != centreZ
                || fieldGeneration != ColumnField.generation();
        if (!rebuild) {
            long since = now - builtAt;
            // Shadow ends move by h * dθ / sin²θ: keep that near 0.1 block for 24-block casters.
            double sin = Math.sin(Math.max(elev, 0.02f));
            double threshold = Math.max(1e-5, Math.min(0.004, 0.1 * sin * sin / 24.0));
            boolean sunMoved = Math.abs(elev - elevation) > threshold || dx * dirX + dz * dirZ < 0.9999f;
            rebuild = sunMoved && since >= minIntervalNs
                    || ColumnField.uploadCount() != seenUploads && since >= 250_000_000L;
        }
        if (!rebuild) {
            return;
        }
        if (!build(cx, cz, dx, dz, tanElevation)) {
            valid = false;
            return;
        }
        seenUploads = ColumnField.uploadCount();
        fieldGeneration = ColumnField.generation();
        sameDirection = true;
        elevation = elev;
        builtAt = now;
        valid = true;
    }

    private static boolean build(int cx, int cz, float dx, float dz, float tanElevation) {
        if (program < 0) {
            program = GlslPrograms.compile("Lumis horizon map", GlslPrograms.FULLSCREEN_VERTEX_460,
                    "#version 460 core\n" + SOURCE);
            if (program == 0) {
                Lumis.LOGGER.error("Sun shadows are off: the horizon-map shader did not compile (see above).");
                return false;
            }
            uSrc = GL20C.glGetUniformLocation(program, "u_Src");
            uWin = GL20C.glGetUniformLocation(program, "u_Win");
            uSun = GL20C.glGetUniformLocation(program, "u_Sun");
            uStep = GL20C.glGetUniformLocation(program, "u_Step");
        }
        if (program == 0) {
            return false;
        }
        int n = ColumnField.size();
        if (size != n) {
            release();
            texA = target("Lumis horizon A", n);
            texB = target("Lumis horizon B", n);
            fboA = framebuffer("Lumis horizon A", texA);
            fboB = framebuffer("Lumis horizon B", texB);
            size = n;
        }

        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] viewport = VIEWPORT;
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        boolean[] mask = MASK;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer m = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, m);
            for (int i = 0; i < 4; i++) {
                mask[i] = m.get(i) != 0;
            }
        }
        GL11C.glColorMask(true, true, true, true);
        disable(GL11C.GL_BLEND, blend);
        disable(GL11C.GL_DEPTH_TEST, depth);
        disable(GL11C.GL_CULL_FACE, cull);
        disable(GL11C.GL_SCISSOR_TEST, scissor);
        disable(GL11C.GL_STENCIL_TEST, stencil);

        GpuTimers.begin("lumis_shadow_build");
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(uSrc, 14);
        GL20C.glUniform4i(uWin, cx & (n - 1), cz & (n - 1), n, n / 2);
        GL20C.glUniform4f(uSun, dx, dz, tanElevation, ColumnField.minY());
        GL11C.glViewport(0, 0, n, n);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE14);

        // S_0 from the heightfield into A, then A->B, B->A, ... ending in B.
        pass(fboA, ColumnField.texture(), 0);
        for (int k = 1; k <= PASSES; k++) {
            boolean intoB = (k & 1) == 1;
            pass(intoB ? fboB : fboA, intoB ? texA : texB, 1 << (k - 1));
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GpuTimers.end("lumis_shadow_build");

        GL13C.glActiveTexture(previousUnit);
        GL20C.glUseProgram(previousProgram);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        enable(GL11C.GL_BLEND, blend);
        enable(GL11C.GL_DEPTH_TEST, depth);
        enable(GL11C.GL_CULL_FACE, cull);
        enable(GL11C.GL_SCISSOR_TEST, scissor);
        enable(GL11C.GL_STENCIL_TEST, stencil);
        GL11C.glColorMask(mask[0], mask[1], mask[2], mask[3]);

        centreX = cx;
        centreZ = cz;
        dirX = dx;
        dirZ = dz;
        tan = tanElevation;
        return true;
    }

    /** Saved GL state of a build (render thread). */
    private static final int[] VIEWPORT = new int[4];
    private static final boolean[] MASK = new boolean[4];

    private static void pass(int fbo, int source, int step) {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, source);
        GL20C.glUniform1i(uStep, step);
        GlslPrograms.drawFullscreen();
    }

    private static int target(String name, int n) {
        int texture = GpuResources.texture(name);
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG32F, n, n, 0, GL30C.GL_RG, GL11C.GL_FLOAT,
                (FloatBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) n * n * 8);
        return texture;
    }

    private static int framebuffer(String name, int texture) {
        int fbo = GpuResources.framebuffer(name);
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                texture, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            Lumis.LOGGER.error("Lumis horizon framebuffer incomplete: 0x{}", Integer.toHexString(status));
        }
        return fbo;
    }

    private static void disable(int cap, boolean was) {
        if (was) {
            GL11C.glDisable(cap);
        }
    }

    private static void enable(int cap, boolean was) {
        if (was) {
            GL11C.glEnable(cap);
        }
    }
}
