package com.solarbiscuit.atmosphera.lumis.sun;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;

/**
 * Lumis 0.1 features:
 * <ul>
 *   <li>{@code lumis:lightmap}: lightmap grading (CPU, see {@code LightmapGrader});</li>
 *   <li>{@code lumis:sun_shading}: the sun/shade model in the terrain shader;</li>
 *   <li>{@code lumis:lod_sun_shading}: the same on Voxy LODs (switched with it, plus its own option);</li>
 *   <li>{@code lumis:entity_light}: entity diffuse light from the sun (see {@link EntityLight}).</li>
 * </ul>
 * Plus three AtmosFrame slots written from {@link SunModel} once per frame.
 */
public final class LightFeatures {
    public static AtmosFeature lightmap;
    public static AtmosFeature sunShading;
    public static AtmosFeature lodShading;
    public static AtmosFeature entityLight;

    private LightFeatures() {
    }

    public static void register() {
        registerSlots();

        lightmap = AtmosFeature.builder(Lumis.MOD_ID, "lightmap")
                .provides(Capability.BLOCKLIGHT_TINT, Capability.SKYLIGHT_TINT)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(LumisConfig.lightmap)
                .register();
        sunShading = AtmosFeature.builder(Lumis.MOD_ID, "sun_shading")
                .provides(Capability.DIRECTIONAL_LIGHT)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.FACE_NORMAL)
                .switchedBy(LumisConfig.sunShading)
                .register();
        // Its own feature so it can be switched off alone: the Voxy bridge recompiles the LOD shaders when it changes.
        lodShading = AtmosFeature.builder(Lumis.MOD_ID, "lod_sun_shading")
                .provides(Capability.DIRECTIONAL_LIGHT)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER)
                .switchedBy(LumisConfig.sunShading)
                .enabledWhen(() -> LumisConfig.sunShading.enabled().get() && LumisConfig.lodShading.get())
                .register();
        entityLight = AtmosFeature.builder(Lumis.MOD_ID, "entity_light")
                .provides(Capability.DIRECTIONAL_LIGHT)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(LumisConfig.entityLight)
                .register();

        int lit = ShaderSnippets.PASS_SOLID | ShaderSnippets.PASS_CUTOUT;
        ShaderSnippets.register(sunShading, ShaderHook.VERT_DECL, ShaderSnippets.PASS_ALL, 20, SunGlsl.VERT_DECL);
        ShaderSnippets.register(sunShading, ShaderHook.VERT_POST_LIGHT, lit, 20, SunGlsl.VERT_POST_LIGHT);
        ShaderSnippets.register(sunShading, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, 20, SunGlsl.FRAG_DECL);
        ShaderSnippets.register(sunShading, ShaderHook.FRAG_PRE_FOG, lit, 20, SunGlsl.FRAG_PRE_FOG);
        ShaderSnippets.register(lodShading, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, 20, SunGlsl.LOD_DECL);
        ShaderSnippets.register(lodShading, ShaderHook.LOD_FRAGMENT, lit, 20, SunGlsl.LOD_FRAGMENT);
    }

    private static void registerSlots() {
        FrameUniforms.vec4("lumis_Sun", "xyz: direction towards the sun or moon, w: strength (0 = vanilla)", out -> {
            out[0] = SunModel.dirX;
            out[1] = SunModel.dirY;
            out[2] = SunModel.dirZ;
            out[3] = SunModel.strength * LumisConfig.sunStrength.get().floatValue();
        });
        FrameUniforms.vec4("lumis_Tint", "rgb: sunlit-face colour (luminance 1), w: sunlit boost", out -> {
            out[0] = SunModel.SUN[0];
            out[1] = SunModel.SUN[1];
            out[2] = SunModel.SUN[2];
            out[3] = LumisConfig.sunBoost.get().floatValue();
        });
        FrameUniforms.vec4("lumis_Ambient", "rgb: shade colour, w: how much darker the sky-lit share is in shade", out -> {
            float tint = LumisConfig.shadeTint.get().floatValue();
            out[0] = 1f + (SunModel.SHADE[0] - 1f) * tint;
            out[1] = 1f + (SunModel.SHADE[1] - 1f) * tint;
            out[2] = 1f + (SunModel.SHADE[2] - 1f) * tint;
            out[3] = 0.6f * LumisConfig.shadeDepth.get().floatValue();
        });
    }
}
