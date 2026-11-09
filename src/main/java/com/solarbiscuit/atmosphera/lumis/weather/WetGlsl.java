package com.solarbiscuit.atmosphera.lumis.weather;

/**
 * GLSL of the wet sheen (Xenon terrain, solid and cutout passes, after sun shading and light colour).
 * <p>
 * The rain rings are the same stateless rings as Torrente 0.2.0's (one ring per half-block cell in two offset layers,
 * 2400-3599 rings per hour per cell: whole cycles, so the hourly wrap of {@code atmos_Time.y} never jumps), copied so
 * Lumis does not depend on Torrente.
 */
final class WetGlsl {
    private WetGlsl() {
    }

    static final String FRAG_DECL = """
            #ifndef LUMIS_WET_DECL
            #define LUMIS_WET_DECL
            vec2 lumis_wetHash(vec2 lumisCell) {
                uvec2 lumisQ = uvec2(ivec2(lumisCell)) * uvec2(1597334673u, 3812015801u);
                uint lumisN = (lumisQ.x ^ lumisQ.y) * 1597334673u;
                return vec2(uvec2(lumisN, lumisN * 0x2c1b3c6du) >> 8u) / 16777216.0;
            }
            // Slope (d/dx, d/dz) of the rain rings at a world position.
            vec2 lumis_rainRings(vec2 lumisXZ) {
                vec2 lumisSum = vec2(0.0);
                for (int lumisL = 0; lumisL < 2; lumisL++) {
                    vec2 lumisQ = lumisXZ * 2.0 + vec2(0.5 * float(lumisL));
                    vec2 lumisCell = floor(lumisQ);
                    vec2 lumisF = fract(lumisQ);
                    vec2 lumisH = lumis_wetHash(lumisCell + vec2(float(lumisL) * 71.0, 0.0));
                    vec2 lumisCtr = 0.3 + 0.4 * lumis_wetHash(lumisCell + vec2(13.0, 29.0 + float(lumisL)));
                    float lumisN = 2400.0 + floor(lumisH.x * 1200.0);
                    float lumisPh = fract(atmos_Time.y * (lumisN * (1.0 / 3600.0)) + lumisH.y);
                    vec2 lumisR = lumisF - lumisCtr;
                    float lumisD = length(lumisR);
                    float lumisX = (lumisD - lumisPh * 0.3) * 14.0;
                    float lumisBump = -2.0 * lumisX * exp(-lumisX * lumisX);
                    float lumisFade = (1.0 - lumisPh) * (1.0 - lumisPh);
                    lumisSum += lumisR / max(lumisD, 1e-3) * (lumisBump * lumisFade * 0.35);
                }
                return lumisSum;
            }
            #endif
            """;

    /**
     * Wet where rain reaches: up faces at or above their column's rain top, side faces above the rain top of the
     * column in front of them. Darker albedo, then a Fresnel sky reflection (Schlick, F0 0.02) and a weak sun glint on
     * the rippled normal; both are added light, scaled by the pixel's sky-lit brightness so caves and night stay dark.
     */
    static final String FRAG_PRE_FOG = """
                {
            #ifdef ATMOS_COLUMN_FIELD
                    vec3 lumisN = atmos_FaceNormal;
                    if (lumis_Wet.x > 0.003 && lumisN.y > -0.5) {
                        vec3 lumisRel = v_AtmosRelPos;
                        float lumisDist = length(lumisRel);
                        // Up faces: a point just above them in their own column; walls: the column they face.
                        float lumisTop = atmos_rainTop(lumisRel + lumisN * 0.5);
                        float lumisY = lumisRel.y + atmos_CameraPos.y;
                        float lumisWet = lumisTop > -1e8 ? smoothstep(lumisTop - 0.25, lumisTop + 0.05, lumisY) : 0.0;
                        lumisWet *= (lumisN.y > 0.5 ? 1.0 : 0.6) * lumis_Wet.x * (1.0 - smoothstep(96.0, 128.0, lumisDist));
                        if (lumisWet > 0.003) {
                            vec3 lumisV = lumisRel / max(lumisDist, 1e-4);
                            vec3 lumisNr = lumisN;
                            if (lumisN.y > 0.5 && lumis_Wet.y > 0.01 && lumisDist < 24.0) {
                                float lumisRf = lumis_Wet.y * (1.0 - smoothstep(12.0, 24.0, lumisDist));
                                vec2 lumisS = lumis_rainRings(lumisRel.xz + atmos_CameraPos.xz) * lumisRf;
                                lumisNr = normalize(vec3(-lumisS.x, 1.0, -lumisS.y));
                            }
                            float lumisCos = clamp(dot(-lumisV, lumisNr), 0.0, 1.0);
                            float lumisF = 0.02 + 0.98 * pow(1.0 - lumisCos, 5.0);
                            // Brightness of the pixel's light before the darkening: the reflection is lit like it.
                            float lumisLit = clamp(atmos_lum(diffuseColor.rgb) * 2.5, 0.0, 1.0);
                            diffuseColor.rgb *= 1.0 - 0.18 * lumisWet;
                            vec3 lumisR = reflect(lumisV, lumisNr);
                            vec3 lumisAdd = atmos_SkyColor.rgb * (lumisF * 0.8 * smoothstep(-0.2, 0.3, lumisR.y));
                            float lumisSun = pow(max(dot(lumisR, lumis_Sun.xyz), 0.0), 96.0) * lumis_Sun.w * lumis_Wet.z;
            #ifdef ATMOS_SUN_SHADOW
                            if (lumisSun > 0.001) {
                                lumisSun *= atmos_sunVisibility(lumisRel, lumisN);
                            }
            #endif
                            lumisAdd += lumis_Tint.rgb * (lumisSun * lumisF * 6.0);
                            diffuseColor.rgb += lumisAdd * (lumisWet * lumisLit);
                        }
                    }
            #endif
                }
            """;
}
