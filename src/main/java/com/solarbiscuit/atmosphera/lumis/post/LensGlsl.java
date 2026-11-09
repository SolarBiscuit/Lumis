package com.solarbiscuit.atmosphera.lumis.post;

/**
 * Fragment sources of the lens passes (GLSL 4.60 after {@code GlslPrograms.header460()}, with the 4.60 fullscreen
 * vertex stage).
 */
final class LensGlsl {
    private LensGlsl() {
    }

    /**
     * Sun visibility (AFTER_WEATHER, into a 1x1 target): the share of open sky in a disc around the sun's screen
     * position, from the world depth (which is gone by the FINAL stage). 48 taps on a sunflower spiral; taps off the
     * screen count as covered, so the flare fades as the sun leaves the view.
     */
    static final String SUN_VIS = """
            uniform sampler2D u_Depth;
            uniform vec4 u_Sun;     // xy: sun position (0..1 screen), z: width / height, w: disc radius (fraction of the height)
            out vec4 o_Out;

            void main() {
                float open = 0.0;
                for (int i = 0; i < 48; i++) {
                    float r = sqrt((float(i) + 0.5) / 48.0) * u_Sun.w;
                    float a = float(i) * 2.3999632;
                    vec2 uv = u_Sun.xy + vec2(cos(a) / u_Sun.z, sin(a)) * r;
                    if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)))) {
                        open += step(1.0, textureLod(u_Depth, uv, 0.0).r);
                    }
                }
                o_Out = vec4(open / 48.0, 0.0, 0.0, 1.0);
            }
            """;

    /**
     * Bright pass (AFTER_WEATHER, half resolution): a soft-knee luminance threshold of the scene (four bilinear taps,
     * a 4x4 box of full-resolution pixels) on terrain, entities and particles only (sky pixels by the world depth: a
     * bright daytime sky would otherwise bloom all over), plus a bright core around the sun scaled by its visibility.
     */
    static final String BRIGHT = """
            uniform sampler2D u_Scene;
            uniform sampler2D u_Depth;
            uniform sampler2D u_SunVis;
            uniform vec4 u_Knee;    // x: threshold, y: knee, z: gain
            uniform vec4 u_Sun;     // xy: sun position (0..1 screen), z: width / height, w: core radius (fraction of the height)
            uniform vec4 u_SunCol;  // rgb: sun core colour with its strength (0 = none)
            out vec4 o_Out;

            void main() {
                vec2 size = vec2(textureSize(u_Scene, 0));
                vec2 uv = gl_FragCoord.xy * 2.0 / size;
                vec2 o = 1.0 / size;
                vec3 c = 0.25 * (texture(u_Scene, uv + vec2(-o.x, -o.y)).rgb + texture(u_Scene, uv + vec2(o.x, -o.y)).rgb
                        + texture(u_Scene, uv + vec2(-o.x, o.y)).rgb + texture(u_Scene, uv + vec2(o.x, o.y)).rgb);
                float world = 0.25 * dot(step(textureGather(u_Depth, uv, 0), vec4(0.99999)), vec4(1.0));
                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float s = clamp(l - u_Knee.x + u_Knee.y, 0.0, 2.0 * u_Knee.y);
                s = s * s / (4.0 * u_Knee.y + 1e-5);
                vec3 bright = c * (max(s, l - u_Knee.x) / max(l, 1e-4) * u_Knee.z * world);
                if (u_SunCol.a > 0.0) {
                    vec2 d = (uv - u_Sun.xy) * vec2(u_Sun.z, 1.0);
                    float vis = texelFetch(u_SunVis, ivec2(0), 0).r;
                    bright += u_SunCol.rgb * (vis * exp(-dot(d, d) / (u_Sun.w * u_Sun.w)));
                }
                o_Out = vec4(bright, 1.0);
            }
            """;

    /** Dual-filter downsample (Marius Bjorge, SIGGRAPH 2015): centre x4 and four diagonal taps, into half the size. */
    static final String DOWN = """
            uniform sampler2D u_Src;
            out vec4 o_Out;

            void main() {
                vec2 texel = 1.0 / vec2(textureSize(u_Src, 0));
                vec2 uv = gl_FragCoord.xy * 2.0 * texel;
                vec3 c = texture(u_Src, uv).rgb * 4.0;
                c += texture(u_Src, uv + vec2(-texel.x, -texel.y)).rgb;
                c += texture(u_Src, uv + vec2(texel.x, -texel.y)).rgb;
                c += texture(u_Src, uv + vec2(-texel.x, texel.y)).rgb;
                c += texture(u_Src, uv + vec2(texel.x, texel.y)).rgb;
                o_Out = vec4(c * 0.125, 1.0);
            }
            """;

    /**
     * Dual-filter upsample: eight taps of the smaller level, added (blend ONE, ONE) onto the next larger level, which
     * still holds its own downsample. The half-size level ends up with every level summed.
     */
    static final String UP = """
            uniform sampler2D u_Src;
            uniform vec4 u_Dst;     // xy: 1 / destination size
            out vec4 o_Out;

            void main() {
                vec2 uv = gl_FragCoord.xy * u_Dst.xy;
                vec2 h = 1.0 / vec2(textureSize(u_Src, 0));
                vec3 c = texture(u_Src, uv + vec2(-2.0 * h.x, 0.0)).rgb;
                c += texture(u_Src, uv + vec2(2.0 * h.x, 0.0)).rgb;
                c += texture(u_Src, uv + vec2(0.0, -2.0 * h.y)).rgb;
                c += texture(u_Src, uv + vec2(0.0, 2.0 * h.y)).rgb;
                c += texture(u_Src, uv + vec2(-h.x, -h.y)).rgb * 2.0;
                c += texture(u_Src, uv + vec2(h.x, -h.y)).rgb * 2.0;
                c += texture(u_Src, uv + vec2(-h.x, h.y)).rgb * 2.0;
                c += texture(u_Src, uv + vec2(h.x, h.y)).rgb * 2.0;
                o_Out = vec4(c / 12.0, 1.0);
            }
            """;

    /**
     * Final composite (FINAL stage, after the hand): reads its own pixel of the scene (after a texture barrier), then
     * <ol>
     *   <li>bloom, screen-blended so it never clips;</li>
     *   <li>the lens flare: ghosts along the line from the sun through the screen centre, a faint chromatic halo and a
     *       six-spoke starburst at the sun, all scaled by the sun's visibility;</li>
     *   <li>vibrance (as NVIDIA's digital vibrance): dull colours gain saturation more than vivid ones, luminance
     *       kept; negative values desaturate evenly;</li>
     *   <li>vignette, last.</li>
     * </ol>
     */
    static final String COMPOSITE = """
            uniform sampler2D u_Scene;
            uniform sampler2D u_Bloom;
            uniform sampler2D u_SunVis;
            uniform vec4 u_Mix;     // x: bloom strength (0 off), y: flare strength (0 off)
            uniform vec4 u_Sun;     // xy: sun position (0..1 screen), z: width / height, w: flare fade (edge, horizon, cover)
            uniform vec4 u_SunCol;  // rgb: sun colour (luminance 1)
            uniform vec4 u_Grade;   // x: vibrance (-1..1, 0 neutral), y: vignette strength (0 off)
            out vec4 o_Out;

            float lumis_disc(float d, float r) {
                // A soft disc with a slightly brighter rim, like an aperture ghost.
                float body = 1.0 - smoothstep(r * 0.8, r, d);
                return body * (0.55 + 0.45 * smoothstep(r * 0.4, r * 0.95, d));
            }

            vec3 lumis_flare(vec2 uv) {
                vec2 asp = vec2(u_Sun.z, 1.0);
                vec2 toCentre = vec2(0.5) - u_Sun.xy;
                vec3 acc = vec3(0.0);
                // Ghosts: position along sun -> mirror point (t), radius (height fraction), strength, colour.
                acc += vec3(1.00, 0.62, 0.30) * 0.050 * lumis_disc(length((uv - (u_Sun.xy + toCentre * 0.45)) * asp), 0.035);
                acc += vec3(0.35, 0.85, 0.75) * 0.035 * lumis_disc(length((uv - (u_Sun.xy + toCentre * 0.80)) * asp), 0.060);
                acc += vec3(0.65, 0.45, 1.00) * 0.030 * lumis_disc(length((uv - (u_Sun.xy + toCentre * 1.20)) * asp), 0.020);
                acc += vec3(0.85, 1.00, 0.40) * 0.025 * lumis_disc(length((uv - (u_Sun.xy + toCentre * 1.55)) * asp), 0.090);
                acc += vec3(1.00, 0.45, 0.55) * 0.040 * lumis_disc(length((uv - (u_Sun.xy + toCentre * 2.10)) * asp), 0.045);
                vec2 fromSun = (uv - u_Sun.xy) * asp;
                float ds = length(fromSun);
                // Halo: a thin ring around the sun, red outside and blue inside.
                vec3 ring = vec3(0.32, 0.31, 0.30);
                acc += 0.025 * vec3(1.0 - smoothstep(0.0, 0.012, abs(ds - ring.r)),
                                    1.0 - smoothstep(0.0, 0.012, abs(ds - ring.g)),
                                    1.0 - smoothstep(0.0, 0.012, abs(ds - ring.b)));
                // Starburst: six spokes (a six-blade aperture) and a small glow.
                float ang = atan(fromSun.y, fromSun.x);
                float spokes = pow(abs(cos(ang * 3.0 + 0.4)), 60.0) + 0.5 * pow(abs(cos(ang * 3.0 + 1.45)), 90.0);
                acc += vec3(spokes * exp(-ds * 9.0) * 0.35 + exp(-ds * 28.0) * 0.25);
                // 0.8.3: three times the 0.8.0 strength (at 50% the flare changed the picture by 2/255).
                return acc * u_SunCol.rgb * 3.0;
            }

            void main() {
                ivec2 px = ivec2(gl_FragCoord.xy);
                vec2 size = vec2(textureSize(u_Scene, 0));
                vec2 uv = (vec2(px) + 0.5) / size;
                vec3 c = texelFetch(u_Scene, px, 0).rgb;
                vec3 light = vec3(0.0);
                if (u_Mix.x > 0.0) {
                    light += texture(u_Bloom, uv).rgb * u_Mix.x;
                }
                if (u_Mix.y > 0.0 && u_Sun.w > 0.0) {
                    float vis = texelFetch(u_SunVis, ivec2(0), 0).r;
                    if (vis > 0.001) {
                        light += lumis_flare(uv) * (u_Mix.y * u_Sun.w * vis);
                    }
                }
                c = 1.0 - (1.0 - c) * (1.0 - clamp(light, 0.0, 1.0));
                if (u_Grade.x != 0.0) {
                    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                    float amount = u_Grade.x > 0.0 ? u_Grade.x * (1.0 - sat) : u_Grade.x;
                    c = clamp(mix(vec3(y), c, 1.0 + amount), 0.0, 1.0);
                }
                if (u_Grade.y > 0.0) {
                    vec2 d = (uv - 0.5) * vec2(u_Sun.z, 1.0);
                    float r = length(d) / length(vec2(u_Sun.z, 1.0) * 0.5);
                    float v = smoothstep(0.4, 1.05, r);
                    c *= 1.0 - u_Grade.y * 0.85 * v * v;
                }
                o_Out = vec4(c, 1.0);
            }
            """;
}
