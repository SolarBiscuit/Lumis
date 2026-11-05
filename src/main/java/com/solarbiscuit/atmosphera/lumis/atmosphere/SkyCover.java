package com.solarbiscuit.atmosphera.lumis.atmosphere;

import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * How much of the sun (by day) or moon (by night) the sky's own clouds cover, 0 clear .. 1 covered (0.6.6). Read from
 * Nuit Forged's API v2 ({@code NuitApi.getSunCover()} / {@code getMoonCover()}, Nuit Forged 1.0.1+), which works it out
 * from the skybox layer textures. Through reflection, so Lumis keeps no dependency on Nuit; 0 without Nuit, with an
 * older Nuit, or when the sky has no cover data. Smoothed over about 0.3 s. Render thread.
 */
final class SkyCover {
    private static final float SMOOTH_SECONDS = 0.3f;

    private static boolean probed;
    private static MethodHandle sun;
    private static MethodHandle moon;
    private static boolean failed;
    private static float value;
    private static int band = -1;
    private static long lastLog;

    private SkyCover() {
    }

    /** The smoothed cover for this frame. */
    static float get() {
        return value;
    }

    /** Once per frame: reads the cover and eases towards it. */
    static void update(float dt) {
        float target = read();
        value += (target - value) * Math.min(1f, dt / SMOOTH_SECONDS);
        // Diagnostic: one line when the cover crosses a quarter step (a sun going into or out of cloud), at most 1/s.
        int now = Math.round(value * 4f);
        long t = System.nanoTime();
        if (sun != null && now != band && t - lastLog > 1_000_000_000L) {
            band = now;
            lastLog = t;
            Lumis.LOGGER.info("Light shafts: {} cover from the skybox now {}", SunModel.sunHeight > 0f ? "sun" : "moon",
                    String.format(java.util.Locale.ROOT, "%.2f", value));
        }
    }

    private static float read() {
        if (!probed) {
            probed = true;
            probe();
        }
        if (sun == null || failed) {
            return 0f;
        }
        try {
            float cover = SunModel.sunHeight > 0f ? (float) sun.invokeExact() : (float) moon.invokeExact();
            return Math.max(0f, Math.min(1f, cover));
        } catch (Throwable t) {
            failed = true;
            Lumis.LOGGER.warn("Light shafts: Nuit's sun cover could not be read ({}); ignored from now on.", t.toString());
            return 0f;
        }
    }

    private static void probe() {
        if (!ModList.get().isLoaded("nuit")) {
            return;
        }
        try {
            Class<?> api = Class.forName("me.flashyreese.mods.nuit.api.NuitApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            int version = (int) lookup.findStatic(api, "getApiVersion", MethodType.methodType(int.class)).invokeExact();
            if (version < 2) {
                Lumis.LOGGER.info("Light shafts: Nuit API v{} has no sun cover (needs Nuit Forged 1.0.1+); rays stay as they are under skybox clouds.", version);
                return;
            }
            sun = lookup.findStatic(api, "getSunCover", MethodType.methodType(float.class));
            moon = lookup.findStatic(api, "getMoonCover", MethodType.methodType(float.class));
            Lumis.LOGGER.info("Light shafts: using Nuit's sun cover (API v{}): no clear-sky rays or halo from a sun inside skybox clouds.", version);
        } catch (Throwable t) {
            sun = null;
            moon = null;
            Lumis.LOGGER.warn("Light shafts: Nuit's API could not be read ({}); sun cover ignored.", t.toString());
        }
    }
}
