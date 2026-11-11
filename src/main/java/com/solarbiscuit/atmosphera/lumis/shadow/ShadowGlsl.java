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

    /** {@code lumis_visFar(sampler, q)}: distant shadows (0.10.0); needs the lumis_FarShadow* slots. */
    public static final String VIS_FAR = """
            // Distant sun shadows (0.10.0): the far horizon map over NeoVoxy's LOD column tops, 8-block cells, 2048
            // blocks across (R: shadow height, world y; G: blocker distance in cells). 1 where it has nothing.
            // 0.10.3: the shadow height is interpolated between the four nearest cells (one cell is 8 blocks: a single
            // fetch drew 8-block steps and streaks), and faces turned nearly edge-on to the sun (n . sun under about
            // 0.3) take the far shadow only partly: they are half lit by the sun shading anyway, and over them the coarse
            // cells drew noise rather than shadows. n: the surface normal (vec3(0) for points in the air).
            float lumis_visFar(sampler2D far, vec3 q, vec3 n) {
                // w: strength; negative while the debug view is on (0.10.4).
                if (lumis_FarShadowSun.w == 0.0) return 1.0;
                vec2 farRel = q.xz + lumis_FarShadowWin.xy;
                float farEdge = 0.5 * lumis_FarShadowWin.z - 32.0 - max(abs(farRel.x), abs(farRel.y));
                if (farEdge <= 0.0) return 1.0;
                vec3 w = q + atmos_CameraPos.xyz;
                vec2 c = w.xz * lumis_FarShadowWin.w - 0.5;
                ivec2 b0 = ivec2(floor(c));
                vec2 f = c - vec2(b0);
                ivec2 m = ivec2(textureSize(far, 0) - 1);
                vec2 h00 = texelFetch(far, b0 & m, 0).rg;
                vec4 s = vec4(h00.r, texelFetch(far, (b0 + ivec2(1, 0)) & m, 0).r,
                        texelFetch(far, (b0 + ivec2(0, 1)) & m, 0).r, texelFetch(far, (b0 + ivec2(1, 1)) & m, 0).r);
                if (all(lessThan(s, vec4(-1e3)))) return 1.0;
                // An empty cell (no blocker towards the sun) counts as a shadow line well below the point.
                s = max(s, vec4(w.y - 32.0));
                float sh = mix(mix(s.x, s.y, f.x), mix(s.z, s.w, f.x), f.y);
                float vis = clamp((w.y - sh) / (1.0 + h00.g / lumis_FarShadowWin.w * 0.02) + 0.5, 0.0, 1.0);
                float facing = dot(n, n) > 0.0 ? smoothstep(0.05, 0.3, dot(n, lumis_Sun.xyz)) : 1.0;
                return mix(1.0, vis, clamp(farEdge / 128.0, 0.0, 1.0) * abs(lumis_FarShadowSun.w) * facing);
            }
            """;

    static final String FRAG_DECL = """
            #define ATMOS_SUN_SHADOW
            uniform sampler2D lumis_Horizon;
            uniform sampler2D lumis_FarShadow;
            """ + VIS_POINT + VIS_FAR + """
            // How much of the sun reaches a surface: 0 in the sun's shadow, 1 lit. rel: camera-relative position,
            // n: surface normal (camera-relative == world orientation). Beyond the near window (where lumis_visPoint
            // fades to 1) the distant shadows take over (0.10.0).
            float atmos_sunVisibility(vec3 rel, vec3 n) {
                if (lumis_ShadowMix.x <= 0.0) return 1.0;
                vec3 p = rel + n * 0.05;
                float v = lumis_visPoint(p);
                if (lumis_FarShadowSun.w > 0.0) {
                    vec2 win = p.xz + lumis_ShadowWin.xy;
                    float edge = 0.5 * lumis_ShadowWin.z - 16.0 - max(abs(win.x), abs(win.y));
                    float nearW = clamp(edge / lumis_ShadowMix.y, 0.0, 1.0) * lumis_ShadowMix.x;
                    if (nearW < 1.0) {
                        v += (lumis_visFar(lumis_FarShadow, p + vec3(0.0, 0.5, 0.0), n) - 1.0) * (1.0 - nearW);
                    }
                }
#ifdef ATMOS_CLOUD_SHADOW
                v *= atmos_cloudShadow(p);
#endif
                return v;
            }
            """;

    private ShadowGlsl() {
    }
}
