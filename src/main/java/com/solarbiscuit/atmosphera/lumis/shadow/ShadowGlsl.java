package com.solarbiscuit.atmosphera.lumis.shadow;

/**
 * GLSL side of the sun shadows.
 * <ul>
 *   <li>{@link #VIS_POINT}: {@code lumis_visPoint(q)}, the sun visibility of any camera-relative point (a surface
 *       sample already moved off its surface, or a point in the air). Needs the AtmosFrame block (the Lumis shadow
 *       slots, {@code atmos_CameraPos}) and {@code uniform sampler2D lumis_Horizon}. Shared by the terrain programs
 *       and the light-shaft march.</li>
 *   <li>{@link #FRAG_DECL}: the terrain contract, declared in every pass (order 5, before other mods' snippets), so any
 *       feature can test {@code #ifdef ATMOS_SUN_SHADOW} and call {@code atmos_sunVisibility}: Lumis's sun shading,
 *       Torrente's water glints and caustics.</li>
 * </ul>
 */
public final class ShadowGlsl {
    public static final String VIS_POINT = """
            // Sun visibility of a camera-relative point: 0 in the sun's shadow, 1 lit. One texel fetch.
            float lumis_visPoint(vec3 q) {
                vec2 win = q.xz + lumis_ShadowWin.xy;
                float edge = 0.5 * lumis_ShadowWin.z - 16.0 - max(abs(win.x), abs(win.y));
                if (edge <= 0.0) return 1.0;
                vec3 w = q + atmos_CameraPos.xyz;
                ivec2 texel = ivec2(floor(w.xz)) & ivec2(int(lumis_ShadowWin.z) - 1);
                vec2 h = texelFetch(lumis_Horizon, texel, 0).rg;
                float own = floor(h.y / 256.0);
                float dist = h.y - own * 256.0;
                // Shadow height at this point: the column-centre value moved along the sun direction, then corrected
                // from the build's sun angle to the current one for the dominant blocker (dist columns away), so
                // shadow ends glide between builds instead of stepping.
                float along = dot(fract(w.xz) - 0.5, lumis_ShadowSun.xy);
                float s = h.x + along * lumis_ShadowSun.z
                        - max(dist - 0.5 - along, 0.0) * (lumis_ShadowMix.z - lumis_ShadowSun.z);
                float vis = clamp((w.y - s) / (0.04 + dist * lumis_ShadowSun.w) + 0.5, 0.0, 1.0);
                // A caster above in the same column: an overhang, a roof or a canopy.
                if (own > 0.5 && w.y + 0.25 < lumis_ShadowWin.w + own / 16.0) vis = 0.0;
                return mix(1.0, vis, clamp(edge / lumis_ShadowMix.y, 0.0, 1.0) * lumis_ShadowMix.x);
            }
            """;

    static final String FRAG_DECL = """
            #define ATMOS_SUN_SHADOW
            uniform sampler2D lumis_Horizon;
            """ + VIS_POINT + """
            // How much of the sun reaches a surface: 0 in the sun's shadow, 1 lit. rel: camera-relative position,
            // n: surface normal (camera-relative == world orientation).
            float atmos_sunVisibility(vec3 rel, vec3 n) {
                if (lumis_ShadowMix.x <= 0.0) return 1.0;
                return lumis_visPoint(rel + n * 0.05);
            }
            """;

    private ShadowGlsl() {
    }
}
