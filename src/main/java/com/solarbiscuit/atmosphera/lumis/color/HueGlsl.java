package com.solarbiscuit.atmosphera.lumis.color;

/**
 * GLSL of coloured block light: Xenon terrain (all passes, GLSL 330 like the host shader) and NeoVoxy LODs.
 * <p>
 * Colour of a pixel's light = weighted mix of the static colour (the volume: chroma, coverage, static level) and the
 * dynamic lights ({@code lumis_Dyn0..3}: camera-relative position, luminance and chroma packed in w), each weighted
 * by the light level it gives there (linear, as on the CPU and in the colour volume). The tint keeps luminance 1 and applies to the block-lit share only.
 */
final class HueGlsl {
    private HueGlsl() {
    }

    static final String VERT_DECL = """
            #ifndef LUMIS_HUE_VERT
            #define LUMIS_HUE_VERT
            out float lumis_vBlockShare;
            #endif
            """;

    /** The block-lit share of this vertex's light (as the sun shading reads it), 0 without block light. */
    static final String VERT_POST_LIGHT = """
                {
                    vec3 lumisHL = _sample_lightmap(u_LightTex, atmos_LightUV).rgb;
                    vec3 lumisHLb = _sample_lightmap(u_LightTex, ivec2(atmos_LightUV.x, 0)).rgb;
                    lumis_vBlockShare = atmos_LightUV.x < 8 ? 0.0
                            : clamp(atmos_lum(lumisHLb) / max(atmos_lum(lumisHL), 0.0001), 0.0, 1.0);
                }
            """;

    /** Shared by the Xenon fragment stage and the LOD fragment stage. */
    private static final String COMMON = """
            // Adds one dynamic light (lumis_DynN) to the colour sums: acc = (Co*w, Cg*w, w); dark lights (chroma bytes
            // 128, 128: EmitterColors.DARK, 0.8.1) add no colour, only their weight to dark.
            void lumis_dyn(vec4 s, vec3 rel, inout vec3 acc, inout float cov, inout float dark) {
                if (s.w < 65536.0) {
                    return;
                }
                float lum = floor(s.w / 65536.0);
                float rest = s.w - lum * 65536.0;
                float co = floor(rest / 256.0);
                float cg = rest - co * 256.0;
                bool isDark = co == 128.0 && cg == 128.0;
                co = isDark ? 0.0 : (co >= 128.0 ? co - 256.0 : co) / 63.0;
                cg = isDark ? 0.0 : (cg >= 128.0 ? cg - 256.0 : cg) / 63.0;
                float pred = lum * (1.0 - length(rel - s.xyz) / 7.75);
                if (pred <= 0.0) {
                    return;
                }
                float w = pred;
                acc += vec3(co * w, cg * w, w);
                cov = max(cov, clamp(pred, 0.0, 1.0));
                if (isDark) {
                    dark += w;
                }
            }
            // Light colour at a camera-relative cell position: rgb tint (luminance 1, lower where dark light reaches)
            // and how much of it applies.
            vec4 lumis_lightTint(sampler3D volume, vec3 rel) {
                vec3 acc = vec3(0.0);
                float cov = 0.0;
                float dark = 0.0;
                vec2 half_ = 0.5 / lumis_HueMix.xy;
                float fade = (1.0 - smoothstep(half_.x - 26.0, half_.x - 12.0, max(abs(rel.x), abs(rel.z))))
                        * (1.0 - smoothstep(half_.y - 26.0, half_.y - 12.0, abs(rel.y)));
                if (fade > 0.0) {
                    vec3 lumisAt = (rel + atmos_CameraPos.xyz) * lumis_HueMix.xyx;
                    vec4 h = textureLod(volume, lumisAt, 0.0);
                    if (h.b > 0.02) {
                        float c = smoothstep(0.02, 0.4, h.b) * fade;
                        float w = c * 15.0 * h.a / h.b;
                        acc += vec3((h.rg / h.b - 0.5) * 4.0 * w, w);
                        cov = c;
                    #ifdef LUMIS_HUE_DIM
                        if (lumis_HueFar.w > 0.0) {
                            dark += w * clamp(textureLod(lumis_HueDim, lumisAt, 0.0).r / h.b, 0.0, 1.0);
                        }
                    #endif
                    }
                }
                lumis_dyn(lumis_Dyn0, rel, acc, cov, dark);
                lumis_dyn(lumis_Dyn1, rel, acc, cov, dark);
                lumis_dyn(lumis_Dyn2, rel, acc, cov, dark);
                lumis_dyn(lumis_Dyn3, rel, acc, cov, dark);
                if (acc.z <= 0.0) {
                    return vec4(1.0, 1.0, 1.0, 0.0);
                }
                vec2 ch = acc.xy / acc.z * lumis_HueMix.w;
                float tmp = 1.0 - ch.y;
                vec3 tint = max(vec3(tmp + ch.x, 1.0 + ch.y, tmp - ch.x), 0.0);
                tint /= max(dot(tint, vec3(0.2126, 0.7152, 0.0722)), 0.05);
                // Dark light (0.8.1): its share of the light here dims the tint (lumis_HueFar.w: strength).
                tint *= 1.0 - clamp(dark / acc.z, 0.0, 1.0) * lumis_HueFar.w;
                return vec4(tint, cov);
            }
            // Distant light colour from NeoVoxy's emitter reports: 16-block cells, 2048 x 512 blocks around the camera.
            // Shared by LODs and (0.9.0) Xenon terrain beyond the near volume.
            vec4 lumis_farTint(sampler3D far, vec3 rel) {
                if (lumis_HueFar.z <= 0.0) {
                    return vec4(1.0, 1.0, 1.0, 0.0);
                }
                vec2 half_ = 0.5 / lumis_HueFar.xy;
                float fade = (1.0 - smoothstep(half_.x - 320.0, half_.x - 160.0, max(abs(rel.x), abs(rel.z))))
                        * (1.0 - smoothstep(half_.y - 160.0, half_.y - 136.0, abs(rel.y)));
                if (fade <= 0.0) {
                    return vec4(1.0, 1.0, 1.0, 0.0);
                }
                vec4 h = textureLod(far, (rel + atmos_CameraPos.xyz) * lumis_HueFar.xyx, 0.0);
                if (h.b <= 0.02) {
                    return vec4(1.0, 1.0, 1.0, 0.0);
                }
                vec2 ch = (h.rg / h.b - 0.5) * 4.0 * lumis_HueMix.w;
                float tmp = 1.0 - ch.y;
                vec3 tint = max(vec3(tmp + ch.x, 1.0 + ch.y, tmp - ch.x), 0.0);
                tint /= max(dot(tint, vec3(0.2126, 0.7152, 0.0722)), 0.05);
                return vec4(tint, smoothstep(0.02, 0.4, h.b) * fade);
            }
            // Near colour, with the far colour blended in where the near volume's coverage runs out.
            vec4 lumis_lightTintFar(sampler3D near, sampler3D far, vec3 rel) {
                vec4 t = lumis_lightTint(near, rel);
                if (t.a < 0.999) {
                    vec4 f = lumis_farTint(far, rel);
                    t = vec4(mix(f.rgb, t.rgb, t.a), max(t.a, f.a));
                }
                return t;
            }
            """;

    static final String FRAG_DECL = """
            #ifndef LUMIS_HUE_FRAG
            #define LUMIS_HUE_FRAG
            in float lumis_vBlockShare;
            uniform sampler3D lumis_Hue;
            #define LUMIS_HUE_DIM
            uniform sampler3D lumis_HueDim;
            uniform sampler3D lumis_HueFarTex; // 0.9.0: the far volume (NeoVoxy's emitters) beyond the near one
            """ + COMMON + """
            #endif
            """;

    /** Tints the block-lit share of the pixel by the light colour of the cell in front of its face. */
    static final String FRAG_PRE_FOG = """
                {
                    if (lumis_vBlockShare > 0.01 && lumis_HueMix.z > 0.0) {
                        vec4 lumisT = lumis_lightTintFar(lumis_Hue, lumis_HueFarTex, v_AtmosRelPos + atmos_FaceNormal * 0.5);
                        diffuseColor.rgb *= mix(vec3(1.0), lumisT.rgb, lumis_vBlockShare * lumis_HueMix.z * lumisT.a);
                    }
                }
            """;

    /** NeoVoxy LODs: the same volume, bound by Lumis to unit 15 (NeoVoxy uses 0-7). */
    static final String LOD_DECL = """
            #ifndef LUMIS_HUE_LOD
            #define LUMIS_HUE_LOD
            #ifdef VOXY_EXT_STAGE_FRAGMENT
            layout(binding = 15) uniform sampler3D lumis_HueLod;
            layout(binding = 14) uniform sampler3D lumis_HueFarLod;
            """ + COMMON + """
            #endif
            #endif
            """;

    static final String LOD_FRAGMENT = """
                {
                    if (f.block > 0.0 && lumis_HueMix.z > 0.0) {
                        vec3 lumisRel = f.relPos + f.geoNormal * 0.5;
                        // Beyond the near volume: the far one (NeoVoxy's emitters), blended in by what is missing.
                        vec4 lumisT = lumis_lightTintFar(lumis_HueLod, lumis_HueFarLod, lumisRel);
                        if (lumisT.a > 0.0) {
                        #ifdef VOXY_EXT_LIGHTMAP
                            const vec3 lumisHLum = vec3(0.2126, 0.7152, 0.0722);
                            float lumisShare = clamp(dot(voxyExt_lightmap(vec2(f.lightLevel.x, 0.0)).rgb, lumisHLum)
                                    / max(dot(voxyExt_lightmap(f.lightLevel).rgb, lumisHLum), 0.0001), 0.0, 1.0);
                        #else
                            float lumisShare = clamp(f.block / max(f.block + f.sky * (atmos_SunDir.w * 0.95 + 0.05), 0.0001), 0.0, 1.0);
                        #endif
                            f.colour.rgb *= mix(vec3(1.0), lumisT.rgb, lumisShare * lumis_HueMix.z * lumisT.a);
                        }
                    }
                }
            """;
}
