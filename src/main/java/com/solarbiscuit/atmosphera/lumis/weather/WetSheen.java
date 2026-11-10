package com.solarbiscuit.atmosphera.lumis.weather;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.world.ColumnField;
import com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * Lumis 0.7.3 wet sheen ({@code lumis:wet_sheen}): while it rains, surfaces the rain reaches (at or above the column
 * field's rain top, lib 0.5.0) turn slightly darker and pick up a Fresnel reflection of the sky, a weak sun glint when
 * the sun comes out on them, and rain rings on upward faces near the camera. Wetness builds up over about 20 s of
 * rain and dries over about a minute, so the sheen outlasts a shower. Terrain only (solid and cutout passes); LODs stay
 * dry. Where the field has no data (outside its window) nothing is wet.
 */
public final class WetSheen {
    public static AtmosFeature feature;
    /** The column field window the sheen asks for (it reads only near the camera). */
    private static final int COLUMN_SIZE = 256;
    private static final float WET_RATE = 1f / 20f;
    private static final float DRY_RATE = 1f / 60f;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static float wetness;
    /** Rain falling at the camera now (0 in snow, deserts or with no rain), 0..1. */
    private static float rainHere;
    private static long lastNanos;

    private WetSheen() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Lumis.MOD_ID, "wet_sheen")
                .provides(Capability.DIRECTIONAL_LIGHT)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.FACE_NORMAL)
                .switchedBy(LumisConfig.wetSheen)
                .register();
        ColumnFieldShader.requireInTerrain(feature);
        int lit = ShaderSnippets.PASS_SOLID | ShaderSnippets.PASS_CUTOUT;
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, 30, WetGlsl.FRAG_DECL);
        ShaderSnippets.register(feature, ShaderHook.FRAG_PRE_FOG, lit, 30, WetGlsl.FRAG_PRE_FOG);
        FrameUniforms.vec4("lumis_Wet", "x: wetness 0..1 (times the option), y: rain falling now (rings), z: sun glint strength",
                out -> {
                    float k = LumisConfig.wetStrength.get().floatValue();
                    out[0] = wetness * k;
                    out[1] = LumisConfig.wetRings.get() ? rainHere * k : 0f;
                    out[2] = 1f - 0.85f * rainHere;
                    out[3] = 0f;
                });
    }

    /** Once per frame (render tick start): wetness follows the rain at the camera; keeps the column field running. */
    public static void update(Minecraft mc) {
        ClientLevel level = mc.level;
        if (feature == null || !feature.isActive() || level == null) {
            wetness = 0f;
            rainHere = 0f;
            lastNanos = 0;
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        POS.set(Mth.floor(camera.x), Mth.floor(camera.y), Mth.floor(camera.z));
        float rain = level.getRainLevel(mc.getFrameTime());
        // Rain, not snow or dry biomes, where the camera is (the precipitation test is per biome and height).
        boolean rains = rain > 0f && level.getBiome(POS).value().getPrecipitationAt(POS) == Biome.Precipitation.RAIN;
        rainHere = rains ? rain : 0f;
        float target = rainHere;
        wetness += target > wetness ? Math.min(target - wetness, WET_RATE * dt) : Math.max(target - wetness, -DRY_RATE * dt);
        if (wetness > 0.001f) {
            ColumnField.use(WetSheen.class, level, COLUMN_SIZE);
        }
    }
}
