package com.solarbiscuit.atmosphera.lumis.lightmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import com.solarbiscuit.atmosphera.lumis.sun.LightFeatures;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.effect.MobEffects;

/**
 * Grades vanilla's 16x16 lightmap in place, right before it is uploaded (see {@code LightTextureMixin}), so every
 * renderer that samples it sees the result: Xenon terrain, entities, particles, NeoVoxy and AMDium, and Distant
 * Horizons (which copies the pixels when the update returns).
 * <p>
 * Pixel (b, s) is block level b, sky level s. Its sky share is taken from the image itself, the pure sky row against
 * the pure block column: {@code w = lum(P(0,s)) / (lum(P(0,s)) + lum(P(b,0)))}. The colour becomes
 * {@code P * (w * skyTint + (1 - w) * blockTint)}; both tints have luminance 1, so brightness stays vanilla and every
 * effect vanilla already applied (gamma, night vision, darkness, lightning) is kept. Render thread.
 */
public final class LightmapGrader {
    private static final int[] PIXELS = new int[256];
    private static final float[] LUM_SKY = new float[16];
    private static final float[] LUM_BLOCK = new float[16];
    private static final float[] BLOCK = new float[3];
    private static final float[] SKY = new float[3];
    private static int lastKelvin = -1;
    private static final float[] KELVIN = new float[3];
    private static long signature = Long.MIN_VALUE;
    /** Last grading, and since when the grading has been expected (feature active, game running). */
    private static long gradedAt;
    private static long expectedSince;
    private static boolean warnedSkipped;
    /** Luminance of every graded pixel (s * 16 + b), for {@link #shadowedSky}. */
    private static final float[] GRADED = new float[256];
    private static boolean gradedValid;

    private LightmapGrader() {
    }

    /** Torch flicker: the random kick of one tick, scaled by the option. */
    public static float flicker(float vanilla) {
        return LightFeatures.lightmap != null && LightFeatures.lightmap.isActive()
                ? vanilla * LumisConfig.flicker.get().floatValue() : vanilla;
    }

    /** Called before {@code DynamicTexture.upload()} in {@code LightTexture.updateLightTexture}. */
    public static void grade(NativeImage image) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || LightFeatures.lightmap == null || !LightFeatures.lightmap.isActive()) {
            return;
        }
        gradedAt = System.nanoTime();
        gradedValid = true;
        DimensionProfiles.Profile profile = DimensionProfiles.get(level);
        kelvin(profile.blockKelvin());
        float warmth = LumisConfig.blockWarmth.get().floatValue();
        for (int i = 0; i < 3; i++) {
            BLOCK[i] = 1f + (KELVIN[i] - 1f) * warmth;
        }
        float skyAmount = profile.skyTint() ? LumisConfig.skyTint.get().floatValue() : 0f;
        for (int i = 0; i < 3; i++) {
            SKY[i] = 1f + (SunModel.SKY[i] - 1f) * skyAmount;
        }
        boolean darkness = mc.player != null && mc.player.hasEffect(MobEffects.DARKNESS);
        float floor = darkness ? 0f : Math.min(0.3f, LumisConfig.caveMin.get().floatValue() + profile.caveMin()) * 255f;

        for (int s = 0; s < 16; s++) {
            for (int b = 0; b < 16; b++) {
                PIXELS[s * 16 + b] = image.getPixelRGBA(b, s);
            }
        }
        for (int i = 0; i < 16; i++) {
            LUM_SKY[i] = lum(PIXELS[i * 16]);
            LUM_BLOCK[i] = lum(PIXELS[i]);
        }
        for (int s = 0; s < 16; s++) {
            for (int b = 0; b < 16; b++) {
                float sum = LUM_SKY[s] + LUM_BLOCK[b];
                float w = sum > 1e-3f ? LUM_SKY[s] / sum : 0.5f;
                int p = PIXELS[s * 16 + b];
                // NativeImage pixels are ABGR: red in the low byte.
                float r = (p & 0xFF) * (w * SKY[0] + (1f - w) * BLOCK[0]);
                float g = ((p >> 8) & 0xFF) * (w * SKY[1] + (1f - w) * BLOCK[1]);
                float bl = ((p >> 16) & 0xFF) * (w * SKY[2] + (1f - w) * BLOCK[2]);
                int ri = clamp(Math.max(r, floor));
                int gi = clamp(Math.max(g, floor));
                int bi = clamp(Math.max(bl, floor));
                image.setPixelRGBA(b, s, 0xFF000000 | bi << 16 | gi << 8 | ri);
                GRADED[s * 16 + b] = 0.2126f * ri + 0.7152f * gi + 0.0722f * bi;
            }
        }
    }

    /**
     * The sky level that makes an entity as dark as terrain in a sun shadow: the brightness at (block, sky) with its
     * sky-lit share reduced by {@code loss}, found back on the graded lightmap. Without a graded lightmap (light colour
     * off, or replaced by another mod) a rough linear stand-in.
     */
    public static float shadowedSky(float block, float sky, float loss) {
        if (loss <= 0f || sky <= 0f) {
            return sky;
        }
        sky = Math.min(sky, 15f);
        if (!gradedValid || LightFeatures.lightmap == null || !LightFeatures.lightmap.isActive()) {
            return sky * (1f - 0.5f * loss);
        }
        int b = Math.max(0, Math.min(15, (int) block));
        float lumSky = row(0, sky);
        float lumBlock = GRADED[b];
        float share = lumSky + lumBlock > 1e-3f ? lumSky / (lumSky + lumBlock) : 1f;
        float target = row(b, sky) * (1f - loss * share);
        for (int s = (int) sky; s >= 0; s--) {
            float low = GRADED[s * 16 + b];
            if (low <= target) {
                float upper = Math.min(s + 1f, sky);
                float high = row(b, upper);
                return high - low > 1e-3f ? s + (upper - s) * Math.min(1f, (target - low) / (high - low)) : s;
            }
        }
        return 0f;
    }

    private static float row(int b, float sky) {
        int s = Math.min(15, (int) sky);
        float f = sky - s;
        float low = GRADED[s * 16 + b];
        return f > 0f && s < 15 ? low + (GRADED[(s + 1) * 16 + b] - low) * f : low;
    }

    /**
     * Forces a lightmap update when anything the grading depends on changed (options, feature state, dimension
     * profile). Vanilla, or BadOptimizations' cache, otherwise decide when the lightmap updates. Render thread,
     * once per frame.
     */
    public static void checkDirty(Minecraft mc) {
        if (LightFeatures.lightmap == null || mc.level == null) {
            return;
        }
        DimensionProfiles.Profile profile = DimensionProfiles.get(mc.level);
        long next = LightFeatures.lightmap.isActive() ? 1 : 0;
        next = next * 31 + Double.hashCode(LumisConfig.blockWarmth.get());
        next = next * 31 + Double.hashCode(LumisConfig.skyTint.get());
        next = next * 31 + Double.hashCode(LumisConfig.caveMin.get());
        next = next * 31 + profile.hashCode();
        if (next != signature) {
            signature = next;
            ((LumisLightTexture) mc.gameRenderer.lightTexture()).lumis$markDirty();
        }
        watchSkipped(mc);
    }

    /**
     * Vanilla updates the lightmap every tick, so with the feature active and the game running, three seconds without
     * a grading mean another mod replaces the update (Polytone's custom lightmaps cancel it at HEAD). Logged once.
     */
    private static void watchSkipped(Minecraft mc) {
        long now = System.nanoTime();
        if (!LightFeatures.lightmap.isActive() || mc.isPaused() || expectedSince == 0L) {
            expectedSince = now;
            return;
        }
        if (!warnedSkipped && now - Math.max(gradedAt, expectedSince) > 3_000_000_000L) {
            warnedSkipped = true;
            Lumis.LOGGER.warn("Light colour is on, but the lightmap has not been graded for 3 s: another mod replaces "
                    + "vanilla's lightmap update{}. Its lightmap is used as it is; terrain sun shading still works.",
                    AtmosCompat.isLoaded("polytone") ? " (Polytone is installed: a resource pack with a custom lightmap)" : "");
        }
    }

    private static float lum(int abgr) {
        return 0.2126f * (abgr & 0xFF) + 0.7152f * ((abgr >> 8) & 0xFF) + 0.0722f * ((abgr >> 16) & 0xFF);
    }

    private static int clamp(float v) {
        return v <= 0f ? 0 : v >= 255f ? 255 : (int) v;
    }

    /** Black-body colour (Tanner Helland's fit), scaled to luminance 1. */
    private static void kelvin(int kelvin) {
        if (kelvin == lastKelvin) {
            return;
        }
        lastKelvin = kelvin;
        double t = Math.max(1000, Math.min(40000, kelvin)) / 100.0;
        double r = t <= 66 ? 1.0 : 1.292936 * Math.pow(t - 60, -0.1332047);
        double g = t <= 66 ? 0.3900816 * Math.log(t) - 0.6318414 : 1.129891 * Math.pow(t - 60, -0.0755148);
        double b = t >= 66 ? 1.0 : t <= 19 ? 0.0 : 0.5432068 * Math.log(t - 10) - 1.1962541;
        KELVIN[0] = (float) Math.max(0, Math.min(1, r));
        KELVIN[1] = (float) Math.max(0, Math.min(1, g));
        KELVIN[2] = (float) Math.max(0, Math.min(1, b));
        SunModel.normalise(KELVIN);
    }
}
