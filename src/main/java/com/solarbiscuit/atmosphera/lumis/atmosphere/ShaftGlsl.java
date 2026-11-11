package com.solarbiscuit.atmosphera.lumis.atmosphere;

import com.solarbiscuit.atmosphera.lumis.shadow.ShadowGlsl;

/**
 * Fragment sources of the light-shaft passes: GLSL 4.60 (after {@code GlslPrograms.header460()}, with the 4.60 fullscreen
 * vertex stage). The features that run them require OpenGL 4.6.
 */
final class ShaftGlsl {
    private static final String COMMON = """
            const float LUMIS_PI4 = 12.566371;
            // Interleaved gradient noise (Jimenez 2014): a cheap per-pixel offset that the upsample smooths out.
            float lumis_ign(vec2 p) {
                return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
            }
            // Camera-relative position of a main-buffer pixel, and its window depth.
            vec3 lumis_rel(sampler2D depthTex, mat4 invViewProj, ivec2 px, ivec2 size, out float depth) {
                depth = texelFetch(depthTex, px, 0).r;
                vec2 uv = (vec2(px) + 0.5) / vec2(size);
                vec4 p = invViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return p.xyz / p.w;
            }
            """;

    /** Underwater caustics shared by the march and the Snell-window ray mask (0.8.6). */
    private static final String CAUSTIC = """
            uniform vec4 u_Waves[25]; // caustic terms: xy wave vector (radians per block), z amplitude,
                                      // w phase at the camera now (camera position and drift added on the CPU)
            // Caustics (0.8.6): the light the swell focuses, C = (sum of 5 drifting cosines)^2 / 2.5, mean 1, sparse
            // bright spots that become beams below. Expanded it is 25 cosines (u_Waves), so its mean along a straight
            // stretch of the surface has a closed form: each term times sinc(k . half-stretch). x is the stretch's
            // middle and h its half-length (camera-relative, on the surface); returns C's mean minus 1.
            // The march samples a step's caustics with this mean instead of at one point: sampling them at 6 points
            // per 40-block ray aliased, and the temporal history averaged that into a plain glow (0.8.2-0.8.5).
            float lumis_causticMean(vec2 x, vec2 h) {
                float ac = 0.0;
                for (int i = 0; i < 25; i++) {
                    vec4 w = u_Waves[i];
                    float a = dot(w.xy, h);
                    float sinc = abs(a) < 1e-3 ? 1.0 : sin(a) / a;
                    ac += w.z * sinc * cos(dot(w.xy, x) + w.w);
                }
                return ac;
            }
            """;

    /**
     * Volumetric march into the low-resolution target: rgb in-scattered light, a transmittance.
     * <p>
     * 0.6.0 "Air": energy-conserving steps ({@code T (1 - e^(-rho ds))}, so LOW and HIGH step counts give the same
     * brightness), a two-lobe phase that follows the weather, air coloured by height, and block light glowing in the
     * mist from the light colour volume (it follows the game's own light, so it stops at walls). The sky cookie
     * (0.6.0-0.8.12) was removed in 0.8.13: it never showed (p99 under 3/255).
     */
    static final String MARCH = """
            uniform sampler2D u_Depth;
            uniform sampler2D lumis_Horizon;
            uniform sampler3D u_Hue;
            uniform sampler3D u_HueDim; // 0.8.1: darkness share of dark light (reads 0 while unset)
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;   // x: divisor, y: steps, z: debug view (0 off), w: 1 = sunlight on
            uniform vec4 u_Light;   // rgb: light colour with every strength applied
            uniform vec4 u_Medium;  // x: density at the base height, y: height scale, z: base height (world y), w: march limit
            uniform vec4 u_Shape;   // x: beam contrast (how much fully lit air is dimmed), y: beam gain
            uniform vec4 u_Phase;   // x: forward lobe g, y: back lobe g, z: back lobe share
            uniform vec4 u_TintLow; // rgb: air colour at the base height, w: 1 / blocks to the high colour
            uniform vec4 u_TintHigh;// rgb: air colour high up
            uniform vec4 u_Glow;    // x: block glow gain (0 off), y: glow reach (blocks from the camera)
            uniform vec4 u_Temporal; // x: this frame's jitter offset (0 without temporal accumulation)
            uniform vec4 u_Water;   // x: 1 = camera under water, y: water surface above the camera (camera-relative y),
                                    // z: caustic strength
            uniform vec4 u_Absorb;  // rgb: water absorption per block without Torrente
            uniform vec4 u_Beam;    // x: haze (share of the mean light kept under water), y: beam gain, z: water gain
            out vec4 o_Out;
            """ + COMMON + CAUSTIC + ShadowGlsl.VIS_POINT + com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.GLSL + """
            float lumis_hg(float c, float g) {
                float g2 = g * g;
                return (1.0 - g2) / (LUMIS_PI4 * pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
            }

            // Under water (0.7.2) ------------------------------------------------------------------------------
            // Absorption per block: Torrente's water style when it is installed and running, else the default.
            vec3 lumis_waterAbsorb() {
                vec3 a = u_Absorb.rgb;
            #ifdef LUMIS_TORRENTE
                if (dot(torrente_Absorb.rgb, vec3(1.0)) > 0.0) a = torrente_Absorb.rgb;
            #endif
                return a;
            }
            // Water surface above q (camera-relative y): the column field's water top, or the surface found above the
            // camera where the field has no data.
            float lumis_waterSurface(vec3 q) {
                float top = atmos_waterTop(q);
                return top > -1e8 ? top - atmos_CameraPos.y : u_Water.y;
            }
            // The march under water: uniform scattering, sunlight that crossed the water above the point (along the
            // refracted sun, n = 1.33) and the way back to the eye loses colour per channel, and the surface's
            // caustics break it into moving beams. The scene is not dimmed (vanilla's water fog already is).
            vec4 lumis_underwater(vec3 dir, float len, float jitter, int steps, float phase) {
                // 0.8.5: 40% of the style's absorption. The styles' coefficients colour the water over the view
                // thickness, and vanilla's water fog already darkens what lies behind the beams; at full strength
                // only 1-10% of the light reached the eye 15 blocks down (Aurora), so no beam showed.
                vec3 absorb = lumis_waterAbsorb() * 0.4;
                vec3 L = lumis_Sun.xyz;
                float ly = sqrt(max(1.0 - (1.0 - L.y * L.y) / 1.7689, 0.0));
                float invLy = 1.0 / max(ly, 0.05);
                vec2 lxz = L.xz / 1.33;
                // The surface point a sample's sun ray crosses moves this much per block along the view (flat surface).
                vec2 drift = dir.xz - lxz * (dir.y * invLy);
                float sunUp = smoothstep(0.0, 0.08, L.y);
                vec3 acc = vec3(0.0);
                float transmit = 1.0;
                float invSteps = 1.0 / float(steps);
                for (int i = 0; i < steps; i++) {
                    float a = float(i) * invSteps;
                    float b = a + invSteps;
                    float s0 = len * a * a;
                    float ds = len * b * b - s0;
                    float s = s0 + ds * jitter;
                    vec3 p = dir * s;
                    float stepT = exp2(-1.442695 * u_Medium.x * ds);
                    float weight = transmit * (1.0 - stepT);
                    transmit *= stepT;
                    float vis = lumis_visPoint(p);
                    if (vis <= 0.0) continue;
                    float below = max(lumis_waterSurface(p) - p.y, 0.0);
                    vec3 path = exp(-absorb * (below * invLy + s));
                    // Caustics over the whole step (0.8.6), from its middle: haze (the mean, dimmed) plus the beams.
                    vec3 pm = dir * (s0 + 0.5 * ds);
                    float belowM = max(lumis_waterSurface(pm) - pm.y, 0.0);
                    vec2 surface = pm.xz + lxz * (belowM * invLy);
                    // Deep down the pattern blurs out (the waves' focus spreads), so it fades with depth (0.8.4: slower).
                    float k = u_Water.z * exp(-belowM * 0.025);
                    // Capped (0.8.8; 1.5x since 0.8.12, 6x the haze): seen end-on, one caustic spot lines up along the whole ray towards
                    // the sun and made a white disc there.
                    float c = clamp(u_Beam.x + u_Beam.y * k * lumis_causticMean(surface, drift * (0.5 * ds)), 0.0, 1.5);
                    acc += (weight * vis * c) * path;
                }
                // 0.8.8: the haze/beam split replaces air's beam contrast (in open water it only dimmed everything),
                // and the water gets its own gain: with a nearly even phase and absorption along the way the light
                // scattered under water was about 0.2% of the sunlight, so beams swung by under 1/255.
                acc *= u_Beam.z;
                return vec4(u_Light.rgb * acc * (phase * sunUp), 1.0);
            }

            // Block light in the air at q (camera-relative): the light colour volume's colour (luminance 1) times
            // its static level squared, faded towards the volume's edge like the terrain tint.
            vec3 lumis_glow(vec3 q) {
                vec2 half_ = 0.5 / lumis_HueMix.xy;
                float fade = (1.0 - smoothstep(half_.x - 26.0, half_.x - 12.0, max(abs(q.x), abs(q.z))))
                        * (1.0 - smoothstep(half_.y - 26.0, half_.y - 12.0, abs(q.y)));
                if (fade <= 0.0) return vec3(0.0);
                vec4 h = textureLod(u_Hue, (q + atmos_CameraPos.xyz) * lumis_HueMix.xyx, 0.0);
                if (h.b <= 0.02) return vec3(0.0);
                vec2 ch = (h.rg / h.b - 0.5) * 4.0 * lumis_HueMix.w;
                float tmp = 1.0 - ch.y;
                vec3 tint = max(vec3(tmp + ch.x, 1.0 + ch.y, tmp - ch.x), 0.0);
                tint /= max(dot(tint, vec3(0.2126, 0.7152, 0.0722)), 0.05);
                float level = h.a / h.b;
                // Dark light (0.8.1) does not glow: its share of the light dims the glow.
                tint *= 1.0 - clamp(textureLod(u_HueDim, (q + atmos_CameraPos.xyz) * lumis_HueMix.xyx, 0.0).r / h.b, 0.0, 1.0)
                        * lumis_HueFar.w;
                return tint * (level * level * fade * smoothstep(0.02, 0.4, h.b));
            }

            void main() {
                ivec2 size = textureSize(u_Depth, 0);
                ivec2 px = min(ivec2(gl_FragCoord.xy) * u_Info.x + u_Info.x / 2, size - 1);
                float depth;
                vec3 rel = lumis_rel(u_Depth, u_InvViewProj, px, size, depth);
                float dist = length(rel);
                vec3 dir = rel / max(dist, 1e-4);
                // Sky pixels march to the limit, so beams show against the sky.
                float len = depth >= 1.0 ? u_Medium.w : min(dist, u_Medium.w);
                if (u_Info.z == 1) {
                    // Debug: march length (red), window depth (green), sky (blue).
                    o_Out = vec4(len / u_Medium.w, depth, depth >= 1.0 ? 1.0 : 0.0, 1.0);
                    return;
                }
                if (u_Info.z == 2) {
                    // Debug: sun visibility 8 blocks along the ray (or at the surface when nearer).
                    o_Out = vec4(vec3(lumis_visPoint(dir * min(len, 8.0))), 1.0);
                    return;
                }
                // Two lobes: the forward glow around the sun plus a weak back lobe, so beams still show looking away
                // from it. Their g and mix follow the weather (set on the CPU). Once per pixel.
                float c = dot(dir, lumis_Sun.xyz);
                float lobes = mix(lumis_hg(c, u_Phase.x), lumis_hg(c, -u_Phase.y), u_Phase.z);
                float phase = mix(1.0 / LUMIS_PI4, lobes, 0.7);
                bool sunOn = u_Info.w != 0;
                bool glow = u_Glow.x > 0.0;
                // 0.7.1: with temporal accumulation the offset moves every frame (golden-ratio sequence), so the
                // history averages different sample positions: more effective steps than are marched per frame.
                float jitter = fract(lumis_ign(gl_FragCoord.xy) + u_Temporal.x);
                if (u_Water.x > 0.5) {
                    // 0.8.17: two thirds of air's steps (MEDIUM 4 per frame with temporal): the box-filtered caustics
                    // cover each whole step, so fewer, longer steps keep the beams (measured in one session at 1440p).
                    int wMax = max(2, (u_Info.y * 2 + 1) / 3);
                    int wSteps = clamp(int(ceil(float(wMax) * sqrt(len / u_Medium.w))), min(2, wMax), wMax);
                    // Under water (0.8.11) a broad lobe of its own (g 0.3), about 1.5x the even level towards the sun: air's forward
                    // lobe (g about 0.85), even at 5% of the phase, made a white disc at the refracted sun.
                    float waterPhase = mix(1.0 / LUMIS_PI4, lumis_hg(c, 0.3), 0.3);
                    o_Out = lumis_underwater(dir, len, jitter, wSteps, waterPhase);
                    return;
                }
                // Steps follow the ray length (0.6.7): the steps are energy-conserving, so the count no longer changes
                // brightness, and a ray that ends at a wall a few blocks away does not need the full count. sqrt keeps
                // mid-length rays near it; sky pixels (len = limit) keep all of them.
                int steps = clamp(int(ceil(float(u_Info.y) * sqrt(len / u_Medium.w))), min(2, u_Info.y), u_Info.y);
                float inscatter = 0.0;
                vec3 inscatterTint = vec3(0.0);
                vec3 glowLight = vec3(0.0);
                float unshadowed = 0.0;
                float transmit = 1.0;
                // Quadratic step spacing: short steps near the eye, where shafts are seen best.
                float invSteps = 1.0 / float(steps);
                float heightToLog2 = -1.442695 / u_Medium.y;
                float eyeHeight = atmos_CameraPos.y - u_Medium.z;
                for (int i = 0; i < steps; i++) {
                    float a = float(i) * invSteps;
                    float b = a + invSteps;
                    float s0 = len * a * a;
                    float ds = len * b * b - s0;
                    float sMid = s0 + 0.5 * ds;
                    vec3 p = dir * (s0 + ds * jitter);
                    // Density at the middle of the step, not at the jittered point: the transmittance (which dims the
                    // scene) then has no per-pixel noise. Only the sun visibility below is jittered.
                    float above = dir.y * sMid + eyeHeight;
                    float rho = u_Medium.x * exp2(max(above, 0.0) * heightToLog2);
                    // Energy-conserving step: the light a step scatters is the share of the remaining light it takes
                    // out, T (1 - e^(-rho ds)), exact for a constant density over the step. The Riemann sum used
                    // before (T rho ds) grows with the step length, so fewer steps or thicker mist over-brightened.
                    float stepT = exp2(-1.442695 * rho * ds);
                    float weight = transmit * (1.0 - stepT);
                    // High in thin air a step adds nothing visible: skip its fetches.
                    if (weight > 1e-5) {
                        if (sunOn) {
                            // Clouds shade the air too, so beams pour through cloud breaks; litShare uses the same
                            // vis, so the contrast term does not cancel it.
                            float vis = lumis_visPoint(p) * atmos_cloudShadow(p);
                            vec3 tint = mix(u_TintLow.rgb, u_TintHigh.rgb, clamp(above * u_TintLow.w, 0.0, 1.0));
                            inscatter += weight * vis;
                            inscatterTint += (weight * vis) * tint;
                        }
                        if (glow && sMid < u_Glow.y) {
                            glowLight += weight * lumis_glow(dir * sMid);
                        }
                    }
                    unshadowed += weight;
                    transmit *= stepT;
                    if (transmit < 0.01) {
                        break;
                    }
                }
                // Beam contrast: dim the light by the lit share of the ray. Air lit all the way (open sky, the uniform
                // glow towards the sun) loses the most; a beam crossing shade is mostly unlit air and keeps nearly all
                // of its light, so beams stand out from the haze instead of drowning in it.
                float litShare = inscatter / max(unshadowed, 1e-6);
                vec3 rays = inscatterTint * ((1.0 - u_Shape.x * litShare) * u_Shape.y);
                // Block light scatters evenly (no strong forward lobe from a lamp a few blocks away).
                o_Out = vec4(u_Light.rgb * rays * phase + glowLight * (u_Glow.x / LUMIS_PI4), transmit);
                if (u_Info.z == 3) {
                    // Debug: in-scattered light x10, ignoring colour and phase; plus the raw block glow.
                    o_Out = vec4(vec3(inscatter * 10.0) + glowLight, 1.0);
                }
            }
            """;

    /**
     * Temporal resolve (0.7.1, lib 0.6.0 reprojection): this frame's low-resolution light blended with last frame's,
     * reprojected for camera motion (Minecraft has no motion vectors; moving entities are clamped). History is
     * clamped to the 3x3 neighbourhood of the current frame, light and transmittance alike (audit R12), so it never
     * holds a beam where there is none now; alpha 0.25. History is ignored after a cut (lib: not continuous).
     */
    static final String RESOLVE = """
            uniform sampler2D u_Cur;
            uniform sampler2D u_Hist;
            uniform sampler2D u_Depth;
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;   // x: divisor, y: 1 = history valid
            out vec4 o_Out;
            """ + COMMON + com.solarbiscuit.atmosphera.lib.gpu.PostPasses.REPROJECT_GLSL + """
            void main() {
                ivec2 px = ivec2(gl_FragCoord.xy);
                ivec2 lowSize = textureSize(u_Cur, 0);
                vec4 cur = texelFetch(u_Cur, px, 0);
                if (u_Info.y == 0 || atmos_CameraDelta.w < 0.5) {
                    o_Out = cur;
                    return;
                }
                vec4 lo = cur;
                vec4 hi = cur;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec4 s = texelFetch(u_Cur, clamp(px + ivec2(i, j), ivec2(0), lowSize - 1), 0);
                        lo = min(lo, s);
                        hi = max(hi, s);
                    }
                }
                ivec2 size = textureSize(u_Depth, 0);
                ivec2 fpx = min(px * u_Info.x + u_Info.x / 2, size - 1);
                float depth;
                vec3 rel = lumis_rel(u_Depth, u_InvViewProj, fpx, size, depth);
                float sky = depth >= 1.0 ? 1.0 : 0.0;
                vec2 uv = atmos_reproject(sky > 0.5 ? normalize(rel) : rel, sky);
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                    o_Out = cur;
                    return;
                }
                vec4 hist = clamp(textureLod(u_Hist, uv, 0.0), lo, hi);
                o_Out = mix(hist, cur, 0.25);
            }
            """;

    /**
     * Sun-ray mask (screen rays, pass 1 of 3), at the shafts' low resolution inside the box around the sun: the share
     * of open sky in this pixel's block of the full-resolution depth (every texel, 4 at a time with
     * {@code textureGather}, so thin gaps in leaves count), weighted by a bright core around the sun's disc plus a
     * faint floor. Leaves or a ridge covering part of the core leave dark texels here, which the blur passes turn
     * into streaks.
     */
    static final String RAY_MASK = """
            layout(binding = 12) uniform sampler2D u_Depth;
            uniform ivec4 u_Info;   // x: divisor
            uniform vec4 u_SunPos;  // xy: sun position (0..1 screen), z: width / height
            uniform vec4 u_Ray;     // x: core radius (fraction of the height), y: sky floor
            out vec4 o_Out;

            void main() {
                vec2 size = vec2(textureSize(u_Depth, 0));
                int div = u_Info.x;
                int gathers = max(div / 2, 1);
                vec2 base = floor(gl_FragCoord.xy) * float(div);
                float sky = 0.0;
                for (int j = 0; j < gathers; j++) {
                    for (int i = 0; i < gathers; i++) {
                        // The shared corner of a 2x2 texel group: one gather returns all four depths.
                        vec4 d = textureGather(u_Depth, (base + vec2(2 * i, 2 * j) + 1.0) / size, 0);
                        sky += dot(step(vec4(1.0), d), vec4(1.0));
                    }
                }
                sky /= float(gathers * gathers * 4);
                vec2 uv = gl_FragCoord.xy * float(div) / size;
                vec2 toSun = (u_SunPos.xy - uv) * vec2(u_SunPos.z, 1.0);
                float core = exp(-dot(toSun, toSun) / (u_Ray.x * u_Ray.x));
                // 0.10.7: sky behind a cloud mod's clouds (Stratos) is not open sky for the rays.
                o_Out = vec4(sky * (core + u_Ray.y) * atmos_cloudTransmittance(uv), 0.0, 0.0, 1.0);
            }
            """;

    /**
     * Snell-window ray mask (0.8.7, under water; pass 1 of 3 like {@link #RAY_MASK}): looking up from under water the
     * sky shows through a window in the surface, within about 48.6 degrees of straight up (beyond it the surface
     * reflects). Each low-resolution texel counts the share of its full-resolution depth texels that reach the water
     * surface (the flat plane above the camera); kelp, rock or a mob in front of it stay dark and cut streaks. That
     * share is weighted by the surface caustics and a core around the refracted sun, where the beams converge.
     */
    static final String RAY_MASK_WATER = """
            layout(binding = 12) uniform sampler2D u_Depth;
            uniform ivec4 u_Info;   // x: divisor
            uniform vec4 u_SunPos;  // xy: refracted sun (0..1 screen), z: width / height
            uniform vec4 u_Ray;     // x: core radius (fraction of the height), y: floor, z: caustic weight
            uniform mat4 u_InvViewProj;
            uniform mat4 u_ViewProj;
            uniform vec4 u_Surface; // x: water surface above the camera (camera-relative y)
            out vec4 o_Out;
            """ + CAUSTIC + """
            float lumis_linear(float d) {
                return d >= 1.0 ? 1e5 : atmos_Proj.y / ((d * 2.0 - 1.0) + atmos_Proj.x);
            }

            void main() {
                vec2 size = vec2(textureSize(u_Depth, 0));
                int div = u_Info.x;
                vec2 uv = gl_FragCoord.xy * float(div) / size;
                vec4 far = u_InvViewProj * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w);
                float window = smoothstep(0.62, 0.70, dir.y);
                if (window <= 0.0 || u_Surface.x <= 0.0) {
                    o_Out = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                // Where this texel's ray meets the surface, and how far that is in view depth (clip w).
                vec3 q = dir * (u_Surface.x / dir.y);
                float plane = (u_ViewProj * vec4(q, 1.0)).w;
                float tolerance = 0.06 * plane + 0.4;
                int gathers = max(div / 2, 1);
                vec2 base = floor(gl_FragCoord.xy) * float(div);
                float open = 0.0;
                for (int j = 0; j < gathers; j++) {
                    for (int i = 0; i < gathers; i++) {
                        vec4 d = textureGather(u_Depth, (base + vec2(2 * i, 2 * j) + 1.0) / size, 0);
                        vec4 lin = vec4(lumis_linear(d.x), lumis_linear(d.y), lumis_linear(d.z), lumis_linear(d.w));
                        open += dot(step(vec4(plane - tolerance), lin), vec4(1.0));
                    }
                }
                open /= float(gathers * gathers * 4);
                // Only the bright caustic spots (0.8.8): the blur turns them into streaks; the whole pattern gave a wash.
                float caustic = max(lumis_causticMean(q.xz, vec2(0.0)) - 0.5, 0.0);
                vec2 toSun = (u_SunPos.xy - uv) * vec2(u_SunPos.z, 1.0);
                float core = exp(-dot(toSun, toSun) / (u_Ray.x * u_Ray.x));
                o_Out = vec4(open * window * (caustic * u_Ray.z) * (core + u_Ray.y), 0.0, 0.0, 1.0);
            }
            """;

    /**
     * Sun-ray blur (passes 2-3): 12 bilinear taps of the previous pass on the way to the sun, covering 1/12, then all
     * of the way. Nested, the two passes average 12 x 12 = 144 points between the pixel and the sun from 24 taps of a
     * small R16F texture: smooth rays without the grain of jittered samples. The last pass turns the average into
     * light (soft cap, falloff around the sun).
     */
    static final String RAY_BLUR = """
            layout(binding = 13) uniform sampler2D u_Src;
            uniform ivec4 u_Info;   // x: 1 = last pass (write light), y: taps, z: debug view (0 off)
            uniform vec4 u_SunPos;  // xy: sun position (0..1 screen), z: width / height, w: reach (fraction of the height)
            uniform vec4 u_Step;    // x: share of the way to the sun this pass covers, y: exposure
            uniform vec4 u_Light;   // rgb: light colour with every strength applied
            uniform vec4 u_Tint;    // rgb: water absorption per block without Torrente, w: blocks of water the rays
                                    // cross (0 above water)
            out vec4 o_Out;

            void main() {
                vec2 uv = gl_FragCoord.xy / vec2(textureSize(u_Src, 0));
                vec2 toSun = u_SunPos.xy - uv;
                int taps = u_Info.y;
                vec2 stepUv = toSun * (u_Step.x / float(taps));
                float acc = 0.0;
                for (int i = 0; i < taps; i++) {
                    acc += texture(u_Src, uv + stepUv * float(i)).r;
                }
                acc /= float(taps);
                if (u_Info.x == 0) {
                    o_Out = vec4(acc, 0.0, 0.0, 1.0);
                    return;
                }
                float fall = 1.0 - smoothstep(0.0, u_SunPos.w, length(toSun * vec2(u_SunPos.z, 1.0)));
                // Soft cap: linear for faint rays, no white blob where the whole core is open.
                float rays = (1.0 - exp(-acc * u_Step.y)) * fall;
                // Under water (0.8.13): coloured by the water they cross, in Torrente's style when it is installed
                // (the march's 40%, as the march colours its light).
                vec3 absorb = u_Tint.rgb;
            #ifdef LUMIS_TORRENTE
                if (dot(torrente_Absorb.rgb, vec3(1.0)) > 0.0) absorb = torrente_Absorb.rgb;
            #endif
                o_Out = vec4(u_Light.rgb * exp(-0.4 * absorb * u_Tint.w) * rays, 1.0);
                if (u_Info.z != 0) {
                    // Debug: ray strength (red), averaged mask (green), falloff around the sun (blue).
                    o_Out = vec4(rays, acc, fall, 1.0);
                }
            }
            """;

    /**
     * Low-resolution view depth (0.8.15): the depth texel the march and the composite's weights use for each
     * low-resolution texel ({@code px * div + div / 2}), as linear view depth (sky 1e5), so the composite gathers four
     * of them in one fetch instead of four scattered full-resolution reads.
     */
    static final String DEPTH_DOWN = """
            uniform sampler2D u_Depth;
            uniform ivec4 u_Info;   // x: divisor
            out vec4 o_Out;

            void main() {
                ivec2 size = textureSize(u_Depth, 0);
                ivec2 px = min(ivec2(gl_FragCoord.xy) * u_Info.x + u_Info.x / 2, size - 1);
                float d = texelFetch(u_Depth, px, 0).r;
                o_Out = vec4(d >= 1.0 ? 1e5 : atmos_Proj.y / ((d * 2.0 - 1.0) + atmos_Proj.x), 0.0, 0.0, 1.0);
            }
            """;

    /**
     * Full-resolution composite with a depth-aware upsample. 0.6.4: the pass reads its own pixel of the scene (after a
     * texture barrier) and writes {@code base + min(light * sqrt(1 - base), cap * (1 - base))}, base = scene x
     * transmittance: dark and mid tones get nearly all of the light, bright pixels at most a share of their headroom,
     * so skybox clouds, snow and the sun's disc never clip. Pixels the shafts leave unchanged are discarded before any
     * depth read.
     * Depth weights use linear view depth from {@code atmos_Proj} (a divide per tap instead of a matrix).
     */
    static final String COMPOSITE = """
            uniform sampler2D u_Low;
            uniform sampler2D u_Depth;
            uniform sampler2D u_Scene;  // the colour this pass writes: each pixel reads only itself (texture barrier)
            uniform sampler2D u_LowDepth; // 0.8.15: view depth of the texels the march used (q * div + div / 2)
            uniform vec4 u_Tone;        // x: share of a pixel's headroom the light may fill at most
            uniform ivec4 u_Info;   // x: divisor
            uniform vec4 u_Mix;     // x: how much the transmittance darkens the scene (mist), y: 1 = debug (replace the scene),
                                    // z: ambient strength (mist lit by the sky: fog colour x eye sky light)
            out vec4 o_Out;

            float lumis_viewDepth(ivec2 px) {
                float d = texelFetch(u_Depth, px, 0).r;
                return d >= 1.0 ? 1e5 : atmos_Proj.y / ((d * 2.0 - 1.0) + atmos_Proj.x);
            }

            void main() {
                ivec2 size = textureSize(u_Depth, 0);
                ivec2 lowSize = textureSize(u_Low, 0);
                ivec2 px = ivec2(gl_FragCoord.xy);
                int div = u_Info.x;
                vec2 lowPos = (vec2(px) + 0.5) / float(div) - 0.5;
                ivec2 base = ivec2(floor(lowPos));
                vec2 f = lowPos - vec2(base);
                ivec2 q00 = clamp(base, ivec2(0), lowSize - 1);
                ivec2 q11 = clamp(base + ivec2(1), ivec2(0), lowSize - 1);
                ivec2 q10 = ivec2(q11.x, q00.y);
                ivec2 q01 = ivec2(q00.x, q11.y);
                vec4 s00 = texelFetch(u_Low, q00, 0);
                vec4 s10 = texelFetch(u_Low, q10, 0);
                vec4 s01 = texelFetch(u_Low, q01, 0);
                vec4 s11 = texelFetch(u_Low, q11, 0);
                // Nothing to add and nothing to dim here: leave the pixel alone (no depth reads, no blend).
                vec3 hi = max(max(s00.rgb, s10.rgb), max(s01.rgb, s11.rgb));
                float lowT = min(min(s00.a, s10.a), min(s01.a, s11.a));
                if (u_Mix.y < 0.5 && max(hi.r, max(hi.g, hi.b)) < 0.002 && (u_Mix.x <= 0.0 || lowT > 0.998)) {
                    discard;
                }
                // 0.8.15: the four texels' view depths in one gather of the low-resolution depth (stored once per
                // frame) instead of four scattered full-resolution reads. Where they agree the light is smooth here:
                // plain bilinear, without reading this pixel's depth. Near edges the depth-aware weights as before.
                // Gather order: x (i0, j1), y (i1, j1), z (i1, j0), w (i0, j0); the edge clamp matches the clamp above.
                vec4 wts = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
                vec4 ld = textureGather(u_LowDepth, (vec2(base) + 1.0) / vec2(lowSize), 0).wzxy;
                float nearD = min(min(ld.x, ld.y), min(ld.z, ld.w));
                float farD = max(max(ld.x, ld.y), max(ld.z, ld.w));
                if (farD - nearD >= 0.05 * nearD + 0.5) {
                    float d = lumis_viewDepth(px);
                    float tolerance = 0.05 * d + 0.5;
                    wts *= exp(-abs(ld - d) / tolerance) + 1e-3;
                }
                vec4 v = (s00 * wts.x + s10 * wts.y + s01 * wts.z + s11 * wts.w) / max(dot(wts, vec4(1.0)), 1e-6);
                float t = mix(1.0, v.a, u_Mix.x);
                // The mist scatters sky light too, not only sunlight: what it takes from the scene comes back as the
                // fog colour (Atmospherics' colour, dark at night), so thick low mist turns hazy, not grey.
                vec3 ambient = (1.0 - t) * atmos_FogColor.rgb * u_Mix.z;
                if (u_Mix.y > 0.5) {
                    o_Out = vec4(v.rgb, 1.0);
                    return;
                }
                // Soft add: light x sqrt(headroom), never more than a share of the headroom. A mid tone at 0.35 keeps
                // about 80% of the light; a cloud at 0.92 gets at most +0.05; white stays white (the sun's disc).
                vec3 dimmed = texelFetch(u_Scene, px, 0).rgb * t;
                vec3 head = max(1.0 - dimmed, 0.0);
                vec3 add = min((v.rgb + ambient) * sqrt(head), head * u_Tone.x);
                o_Out = vec4(dimmed + add, 1.0);
            }
            """;

    private ShaftGlsl() {
    }
}
