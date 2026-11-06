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

    /**
     * Volumetric march into the low-resolution target: rgb in-scattered light, a transmittance.
     * <p>
     * 0.6.0 "Air": energy-conserving steps ({@code T (1 - e^(-rho ds))}, so LOW and HIGH step counts give the same
     * brightness), a two-lobe phase that follows the weather, air coloured by height, an optional invisible cloud
     * layer that breaks up sunlight over open land (sky cookie), and block light glowing in the mist from the light
     * colour volume (it follows the game's own light, so it stops at walls).
     */
    static final String MARCH = """
            uniform sampler2D u_Depth;
            uniform sampler2D lumis_Horizon;
            uniform sampler3D u_Hue;
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;   // x: divisor, y: steps, z: debug view (0 off), w: 1 = sunlight on
            uniform vec4 u_Light;   // rgb: light colour with every strength applied
            uniform vec4 u_Medium;  // x: density at the base height, y: height scale, z: base height (world y), w: march limit
            uniform vec4 u_Shape;   // x: beam contrast (how much fully lit air is dimmed), y: beam gain
            uniform vec4 u_Phase;   // x: forward lobe g, y: back lobe g, z: back lobe share
            uniform vec4 u_TintLow; // rgb: air colour at the base height, w: 1 / blocks to the high colour
            uniform vec4 u_TintHigh;// rgb: air colour high up
            uniform vec4 u_Cookie;  // x: strength (0 off), y: layer height minus camera y, z: 1 / cell size
            uniform vec4 u_CookieOff; // xy: wind drift of the layer, in blocks, mod 4096
            uniform vec4 u_Glow;    // x: block glow gain (0 off), y: glow reach (blocks from the camera)
            out vec4 o_Out;
            """ + COMMON + ShadowGlsl.VIS_POINT + """
            float lumis_hg(float c, float g) {
                float g2 = g * g;
                return (1.0 - g2) / (LUMIS_PI4 * pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
            }

            // Value noise on whole cells, repeating every 64 cells (64-block cells repeat every 4096 blocks, like the
            // camera position the frame block wraps).
            float lumis_cellHash(ivec2 c) {
                c &= ivec2(63);
                uint h = uint(c.x) * 0x8da6b343u ^ uint(c.y) * 0xd8163841u;
                h = (h ^ (h >> 15)) * 0x2c1b3c6du;
                return float(h >> 8) * (1.0 / 16777216.0);
            }
            float lumis_vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                ivec2 c = ivec2(i);
                return mix(mix(lumis_cellHash(c), lumis_cellHash(c + ivec2(1, 0)), f.x),
                           mix(lumis_cellHash(c + ivec2(0, 1)), lumis_cellHash(c + ivec2(1, 1)), f.x), f.y);
            }
            // Sky cookie: how much sunlight an invisible broken cloud layer lets through to point q (camera-relative),
            // looked up where the ray from q to the sun crosses the layer.
            float lumis_cookie(vec3 q) {
                vec3 L = lumis_Sun.xyz;
                if (L.y < 0.05) return 1.0;
                vec2 xz = q.xz + L.xz * ((u_Cookie.y - q.y) / L.y) + atmos_CameraPos.xz + u_CookieOff.xy;
                vec2 c = xz * u_Cookie.z;
                float n = 0.65 * lumis_vnoise(c) + 0.35 * lumis_vnoise(c * 2.0 + vec2(17.0, 31.0));
                return 1.0 - u_Cookie.x * smoothstep(0.42, 0.68, n);
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
                bool cookie = u_Cookie.x > 0.0;
                bool glow = u_Glow.x > 0.0;
                float jitter = lumis_ign(gl_FragCoord.xy);
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
                            float vis = lumis_visPoint(p);
                            // The cookie dims the light after beam contrast: contrast is measured on terrain shadows
                            // only (inscatter), else the gaps it cuts would lower the lit share and contrast would
                            // brighten the rest back, undoing the beams it makes.
                            float lit = vis;
                            if (cookie && vis > 0.0) {
                                lit *= lumis_cookie(p);
                            }
                            vec3 tint = mix(u_TintLow.rgb, u_TintHigh.rgb, clamp(above * u_TintLow.w, 0.0, 1.0));
                            inscatter += weight * vis;
                            inscatterTint += (weight * lit) * tint;
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
                o_Out = vec4(sky * (core + u_Ray.y), 0.0, 0.0, 1.0);
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
                o_Out = vec4(u_Light.rgb * rays, 1.0);
                if (u_Info.z != 0) {
                    // Debug: ray strength (red), averaged mask (green), falloff around the sun (blue).
                    o_Out = vec4(rays, acc, fall, 1.0);
                }
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
                float d = lumis_viewDepth(px);
                float tolerance = 0.05 * d + 0.5;
                ivec2 half_ = ivec2(div / 2);
                ivec2 top = size - 1;
                float w00 = (1.0 - f.x) * (1.0 - f.y) * (exp(-abs(lumis_viewDepth(min(q00 * div + half_, top)) - d) / tolerance) + 1e-3);
                float w10 = f.x * (1.0 - f.y) * (exp(-abs(lumis_viewDepth(min(q10 * div + half_, top)) - d) / tolerance) + 1e-3);
                float w01 = (1.0 - f.x) * f.y * (exp(-abs(lumis_viewDepth(min(q01 * div + half_, top)) - d) / tolerance) + 1e-3);
                float w11 = f.x * f.y * (exp(-abs(lumis_viewDepth(min(q11 * div + half_, top)) - d) / tolerance) + 1e-3);
                vec4 v = (s00 * w00 + s10 * w10 + s01 * w01 + s11 * w11) / max(w00 + w10 + w01 + w11, 1e-6);
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
