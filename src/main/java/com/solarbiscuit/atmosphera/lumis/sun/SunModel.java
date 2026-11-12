package com.solarbiscuit.atmosphera.lumis.sun;

import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import com.solarbiscuit.atmosphera.lumis.lightmap.DimensionProfiles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * Where the light comes from this frame and what colour it has, computed once per rendered frame on the render thread
 * (before the world draws) and read by the frame slots, the lightmap grader and the entity light.
 * <p>
 * The light is the sun while it is above the horizon and the moon (weaker) below it. Its strength fades in between 2
 * and 8 degrees of elevation on both sides, so the direction never flips while the light is visible. Rain and thunder
 * dim it, and dimension profiles without a sun switch it off.
 */
public final class SunModel {
    private static final float SIN_2 = 0.0349f;
    private static final float SIN_8 = 0.1392f;

    // Light direction (towards the sun or moon, world space) and strength 0..1, options included.
    public static float dirX;
    public static float dirY = 1f;
    public static float dirZ;
    public static float strength;
    /** Height of the sun: 1 at noon, 0 at the horizon, -1 at midnight. */
    public static float sunHeight = 1f;
    // Sunlit-face colour, shade colour and sky-light colour (each luminance 1; the sky light up to 1.9 under a nebula, 0.11.1).
    public static final float[] SUN = {1f, 1f, 1f};
    public static final float[] SHADE = {1f, 1f, 1f};
    public static final float[] SKY = {1f, 1f, 1f};
    /** 0.10.11: lightning inside the clouds now (the lib's CloudCover.flash, Stratos), 0..1. */
    public static float flash;
    /** 0.10.11: the moon's tint during a lunar eclipse (the lib's SkySource.moonTint, Stratos's blood moon). */
    private static final float[] MOON_TINT = new float[3];
    /** 0.11.1: light a sky event adds to the night (the lib's SkySource.nightLight: Stratos's nebulae). */
    private static final float[] NIGHT_LIGHT = new float[3];

    private SunModel() {
    }

    public static void update(ClientLevel level, float partial) {
        DimensionProfiles.Profile profile = DimensionProfiles.get(level);
        // Lumis 0.7.0: the lib's sun source (audit BUG-08): Nuit's drawn sun when a skybox rotates it, else vanilla's
        // path (rises in the east, +x). Brightness and the sun/moon switch below still follow game time.
        com.solarbiscuit.atmosphera.lib.world.SunSource.update(level, partial);
        float sx = com.solarbiscuit.atmosphera.lib.world.SunSource.dirX();
        float sy = com.solarbiscuit.atmosphera.lib.world.SunSource.dirY();
        float sz = com.solarbiscuit.atmosphera.lib.world.SunSource.dirZ();
        sunHeight = sy;
        float rain = level.getRainLevel(partial);
        float thunder = level.getThunderLevel(partial);
        float weather = Mth.clamp(1f - 0.8f * rain - 0.15f * thunder, 0f, 1f);

        float light;
        if (sy >= 0f) {
            dirX = sx;
            dirY = sy;
            dirZ = sz;
            light = smoothstep(SIN_2, SIN_8, sy);
        } else {
            dirX = -sx;
            dirY = -sy;
            dirZ = -sz;
            light = smoothstep(SIN_2, SIN_8, -sy) * LumisConfig.moon.get().floatValue();
        }
        // 0.10.11: during a lunar eclipse the moonlight sinks with the moon's brightness and turns copper red.
        boolean eclipse = sy < 0f && com.solarbiscuit.atmosphera.lib.world.SkySource.moonTint(MOON_TINT);
        float eclipseLum = eclipse ? 0.2126f * MOON_TINT[0] + 0.7152f * MOON_TINT[1] + 0.0722f * MOON_TINT[2] : 1f;
        if (eclipse) {
            light *= Mth.clamp(eclipseLum, 0f, 1f);
        }
        strength = profile.sun() ? light * weather : 0f;

        // Golden hour near the horizon, night below it; morning when the sun is in the east.
        float golden = 1f - smoothstep(0.08f, 0.5f, Math.abs(sy));
        float night = smoothstep(0.05f, -0.15f, sy);
        boolean morning = sx > 0f;

        set(SUN, 1.0f, 0.94f, 0.85f); // 0.11.1: a little more orange (was 0.97, 0.92)
        mixInto(SUN, 1.0f, 0.84f, 0.66f, golden); // 0.11.1: was 0.88, 0.74
        mixInto(SUN, 0.80f, 0.88f, 1.10f, night);
        normalise(SUN);

        set(SHADE, 0.88f, 0.95f, 1.12f);
        mixInto(SHADE, 0.90f, 0.90f, 1.08f, golden);
        mixInto(SHADE, 0.92f, 0.95f, 1.05f, night);
        normalise(SHADE);

        set(SKY, 1f, 1f, 1f);
        if (morning) {
            mixInto(SKY, 1.0f, 0.92f, 0.82f, golden);
        } else {
            mixInto(SKY, 1.0f, 0.88f, 0.86f, golden);
        }
        mixInto(SKY, 0.78f, 0.86f, 1.12f, night);
        mixInto(SKY, 0.95f, 0.97f, 1.0f, rain * 0.7f);
        if (eclipse && eclipseLum > 1e-4f) {
            // The moon's own hue (luminance 1), mostly on the moonlit faces, a little in the sky light.
            float r = MOON_TINT[0] / eclipseLum;
            float g = MOON_TINT[1] / eclipseLum;
            float bl = MOON_TINT[2] / eclipseLum;
            float share = night * (1f - Mth.clamp(eclipseLum, 0f, 1f));
            mixInto(SUN, r, g, bl, 0.85f * share);
            mixInto(SKY, r, g, bl, 0.35f * share);
            normalise(SUN);
        }
        // 0.11.1: a bright nebula colours the night's sky light and lifts it a little, most on a moonless night (the
        // moon outshines it when full). The lift is the sky colour's luminance above 1 (the lightmap grader multiplies
        // the sky-lit share by it).
        float nightBoost = 1f;
        if (night > 0.01f && com.solarbiscuit.atmosphera.lib.world.SkySource.nightLight(NIGHT_LIGHT)) {
            float nl = 0.2126f * NIGHT_LIGHT[0] + 0.7152f * NIGHT_LIGHT[1] + 0.0722f * NIGHT_LIGHT[2];
            if (nl > 1e-4f) {
                // nl: about 1 for a typical nebula at its default brightness, up to 1.6 (largest) or 3.2 (brightness 200%).
                float share = night * Mth.clamp(nl, 0f, 2f) * (1f - 0.7f * level.getMoonBrightness());
                mixInto(SKY, NIGHT_LIGHT[0] / nl, NIGHT_LIGHT[1] / nl, NIGHT_LIGHT[2] / nl, Math.min(0.6f, 0.4f * share));
                nightBoost = 1f + 0.45f * share;
            }
        }
        // 0.10.11: a flash inside the clouds turns the sky light cool white for its few frames.
        flash = Mth.clamp(com.solarbiscuit.atmosphera.lib.world.CloudCover.flash(), 0f, 1f);
        if (flash > 0.01f) {
            mixInto(SKY, 0.86f, 0.92f, 1.15f, 0.6f * flash);
        }
        normalise(SKY);
        if (nightBoost > 1f) {
            SKY[0] *= nightBoost;
            SKY[1] *= nightBoost;
            SKY[2] *= nightBoost;
        }
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static void set(float[] c, float r, float g, float b) {
        c[0] = r;
        c[1] = g;
        c[2] = b;
    }

    private static void mixInto(float[] c, float r, float g, float b, float t) {
        c[0] += (r - c[0]) * t;
        c[1] += (g - c[1]) * t;
        c[2] += (b - c[2]) * t;
    }

    /** Scales a colour to luminance 1 (Rec. 709). */
    public static void normalise(float[] c) {
        float lum = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        if (lum > 1e-4f) {
            c[0] /= lum;
            c[1] /= lum;
            c[2] /= lum;
        }
    }
}
