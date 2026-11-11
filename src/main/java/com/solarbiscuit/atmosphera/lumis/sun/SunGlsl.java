package com.solarbiscuit.atmosphera.lumis.sun;

/**
 * GLSL of the sun/shade model (see docs/LUMIS-DESIGN.md).
 * <p>
 * The light of a vertex stays vanilla's lightmap value (night vision, darkness, gamma and lightning included); Lumis
 * only scales its sky-lit share: a cool shade on faces turned away from the light, a warm boost on faces turned to
 * it. The share comes from the lightmap itself, {@code lum(L(b, 0)) / lum(L(b, s))}, so block-lit surfaces keep
 * their light. (Reading the lightmap at a lower sky level instead does nothing in daylight with a bright gamma: the
 * top sky levels all clamp to white.)
 */
final class SunGlsl {
    private SunGlsl() {
    }

    static final String VERT_DECL = """
            #ifndef LUMIS_VERT_DECL
            #define LUMIS_VERT_DECL
            out vec3 lumis_vShade;
            out vec3 lumis_vSun;
            #endif
            """;

    /** After v_Color (solid and cutout): the shade and sunlit multipliers of this vertex's light. */
    static final String VERT_POST_LIGHT = """
                {
                    vec3 lumisL = _sample_lightmap(u_LightTex, atmos_LightUV).rgb;
                    vec3 lumisLb = _sample_lightmap(u_LightTex, ivec2(atmos_LightUV.x, 0)).rgb;
                    float lumisBlock = clamp(atmos_lum(lumisLb) / max(atmos_lum(lumisL), 0.0001), 0.0, 1.0);
                    lumis_vShade = mix(lumis_Ambient.rgb * (1.0 - lumis_Ambient.w), vec3(1.0), lumisBlock);
                    lumis_vSun = mix(lumis_Tint.rgb * (1.0 + lumis_Tint.w), vec3(1.0), lumisBlock);
                }
            """;

    static final String FRAG_DECL = """
            #ifndef LUMIS_FRAG_DECL
            #define LUMIS_FRAG_DECL
            in vec3 lumis_vShade;
            in vec3 lumis_vSun;
            #endif
            """;

    /**
     * Before fog (solid and cutout). Faces towards the light get the sunlit multiplier, faces away the shade, faces
     * side-on half of each. Quads that are not axis-aligned (cross plants) have no meaningful face direction and get a
     * mostly-lit constant.
     */
    static final String FRAG_PRE_FOG = """
                {
                    vec3 lumisAxis = abs(atmos_FaceNormal);
                    float lumisT = max(max(lumisAxis.x, lumisAxis.y), lumisAxis.z) > 0.98
                            ? smoothstep(-0.35, 0.35, dot(atmos_FaceNormal, lumis_Sun.xyz)) : 0.6;
            #ifdef ATMOS_SUN_SHADOW
                    if (lumisT > 0.001) {
                        lumisT *= atmos_sunVisibility(v_AtmosRelPos, atmos_FaceNormal);
                    }
            #endif
                    diffuseColor.rgb *= mix(vec3(1.0), mix(lumis_vShade, lumis_vSun, lumisT), lumis_Sun.w);
                }
            """;

    static final String LOD_DECL = """
            #ifndef LUMIS_LOD_DECL
            #define LUMIS_LOD_DECL
            #ifdef VOXY_EXT_STAGE_FRAGMENT
            // Vanilla's brightness curve for a light level (without the ambient floor).
            float lumis_br(float level) {
                float lumisF = clamp(level / 15.0, 0.0, 1.0);
                return lumisF / (4.0 - 3.0 * lumisF);
            }
            // Distant sun shadows (0.10.0): bound by Lumis on unit 13 before each LOD draw (NeoVoxy API 4 leaves 8-13
            // to extensions).
            layout(binding = 13) uniform sampler2D lumis_FarShadowLod;
            """ + com.solarbiscuit.atmosphera.lumis.shadow.ShadowGlsl.VIS_FAR + """
            #endif
            #endif
            """;

    /**
     * Voxy LODs (opaque passes): the same model on the LOD face normal. With NeoVoxy's API 2 lightmap the block share
     * is exact, as on Xenon; older builds (and the text-patch backend) get it from vanilla's brightness curve (sky and
     * block light added, as the lightmap does before gamma).
     */
    static final String LOD_FRAGMENT = """
                {
                #ifdef VOXY_EXT_LIGHTMAP
                    const vec3 lumisLum = vec3(0.2126, 0.7152, 0.0722);
                    float lumisBlock = clamp(dot(voxyExt_lightmap(vec2(f.lightLevel.x, 0.0)).rgb, lumisLum)
                            / max(dot(voxyExt_lightmap(f.lightLevel).rgb, lumisLum), 0.0001), 0.0, 1.0);
                #else
                    float lumisSkyBright = atmos_SunDir.w * 0.95 + 0.05;
                    float lumisB = min(lumis_br(f.block * 15.0) * 1.5, 1.0);
                    float lumisS = lumis_br(f.sky * 15.0) * lumisSkyBright;
                    float lumisBlock = clamp(lumisB / max(lumisB + lumisS, 0.0001), 0.0, 1.0);
                #endif
                    vec3 lumisShade = mix(lumis_Ambient.rgb * (1.0 - lumis_Ambient.w), vec3(1.0), lumisBlock);
                    vec3 lumisSun = mix(lumis_Tint.rgb * (1.0 + lumis_Tint.w), vec3(1.0), lumisBlock);
                    float lumisT = smoothstep(-0.35, 0.35, dot(f.geoNormal, lumis_Sun.xyz));
                    // 0.10.0: in the shadow of distant terrain (lifted a block: LOD voxels are coarse).
                    lumisT *= lumis_visFar(lumis_FarShadowLod, f.relPos + f.geoNormal * 0.5 + vec3(0.0, 1.0, 0.0), f.geoNormal);
                    if (lumis_FarShadowSun.w < 0.0) {
                        // 0.10.4 debug view (shadows.lod_shadow_debug): the raw distant visibility, without the facing
                        // weight or any shading: white lit, red in the shadow of distant terrain.
                        float lumisV = lumis_visFar(lumis_FarShadowLod, f.relPos + f.geoNormal * 0.5 + vec3(0.0, 1.0, 0.0), vec3(0.0));
                        f.colour.rgb = mix(vec3(0.8, 0.08, 0.05), vec3(0.95), lumisV);
                    }
                    f.colour.rgb *= mix(vec3(1.0), mix(lumisShade, lumisSun, lumisT), lumis_Sun.w);
                }
            """;
}
