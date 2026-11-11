package com.solarbiscuit.atmosphera.lumis.color;

import net.minecraft.util.Mth;

/**
 * Light colour on particles (0.9.0): smoke drifting past a soul lantern turns blue, sparks over redstone red. A
 * particle's colour is multiplied by the light colour at its block for the duration of its draw
 * ({@code SingleQuadParticleMixin}); particles that give light themselves (flames: block light 15 in their own light
 * value) are left alone, they are the light.
 * <p>
 * Particles cluster, so the tint is computed once per 2-block cell per frame (0.9.1; per block in 0.9.0, whose
 * 384-entry table overflowed in a dust cloud, so most particles computed their own): an open-addressing table keyed by
 * the cell, emptied when the frame changes. The light colour is smooth at that scale. Render thread only.
 */
public final class ParticleTint {
    private static final int SIZE = 2048;                // power of two
    private static final long[] KEYS = new long[SIZE];
    private static final float[] RGB = new float[SIZE * 3];
    private static final byte[] STATE = new byte[SIZE];  // 0 empty, 1 neutral, 2 tinted
    private static int used;
    private static int frame = -1;
    private static final float[] OUT = new float[3];
    /** Particles tinted and blocks computed in the last full frame (for the status line). */
    static int lastTinted;
    static int lastComputed;
    private static int tinted;
    private static int computed;

    private ParticleTint() {
    }

    /** Start of a frame (ColoredLight.update). */
    static void newFrame(int frameNumber) {
        if (frameNumber == frame) {
            return;
        }
        frame = frameNumber;
        if (used > 0) {
            java.util.Arrays.fill(STATE, (byte) 0);
            used = 0;
        }
        lastTinted = tinted;
        lastComputed = computed;
        tinted = 0;
        computed = 0;
    }

    /**
     * The colour multiplier for a particle at (x, y, z) into {@code out}; false when neutral (draw unchanged).
     * {@code ownLight}: the particle's packed light value (its block level 15 means it gives light itself).
     */
    public static boolean at(double x, double y, double z, int ownLight, float[] out) {
        if (!ColoredLight.cfgParticles || !ColoredLight.running() || (ownLight >> 4 & 0xF) >= 15) {
            return false;
        }
        int cx = Mth.floor(x) >> 1;
        int cy = Mth.floor(y) >> 1;
        int cz = Mth.floor(z) >> 1;
        long key = net.minecraft.core.BlockPos.asLong(cx, cy, cz);
        int slot = (int) (key ^ key >>> 29 ^ key >>> 47) * 0x9E3779B9 >>> 21;   // 11 bits
        double sx = cx * 2.0 + 1.0;
        double sy = cy * 2.0 + 1.0;
        double sz = cz * 2.0 + 1.0;
        for (int probe = 0; probe < 8; probe++) {
            int i = slot + probe & SIZE - 1;
            byte st = STATE[i];
            if (st == 0) {
                if (used >= SIZE * 3 / 4) {
                    break;   // full: compute without caching
                }
                boolean on = ColoredLight.tintAt(sx, sy, sz, OUT);
                computed++;
                KEYS[i] = key;
                STATE[i] = (byte) (on ? 2 : 1);
                used++;
                if (on) {
                    RGB[i * 3] = OUT[0];
                    RGB[i * 3 + 1] = OUT[1];
                    RGB[i * 3 + 2] = OUT[2];
                }
                return copy(on, i, out);
            }
            if (KEYS[i] == key) {
                return copy(st == 2, i, out);
            }
        }
        computed++;
        if (!ColoredLight.tintAt(sx, sy, sz, out)) {
            return false;
        }
        tinted++;
        return true;
    }

    private static boolean copy(boolean on, int i, float[] out) {
        if (!on) {
            return false;
        }
        out[0] = RGB[i * 3];
        out[1] = RGB[i * 3 + 1];
        out[2] = RGB[i * 3 + 2];
        tinted++;
        return true;
    }
}
