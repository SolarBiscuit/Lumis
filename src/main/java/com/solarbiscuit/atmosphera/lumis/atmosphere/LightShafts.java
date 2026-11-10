package com.solarbiscuit.atmosphera.lumis.atmosphere;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import com.solarbiscuit.atmosphera.lumis.shadow.HorizonMap;
import com.solarbiscuit.atmosphera.lumis.shadow.SunShadows;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;


/**
 * Lumis 0.3 light shafts ({@code lumis:light_shafts}), one post pass after weather:
 * <ol>
 *   <li>VOLUMETRIC (while sun shadows run): a march through the air at low resolution, lit where the 0.2 horizon map
 *       says the point sees the sun, through a thin height mist;</li>
 *   <li>RADIAL (shadows off, or chosen): screen-space rays, the open sky around the sun's disc seen on the way to
 *       it, so leaves and ridges in front of the sun cast streaks; in AUTO also layered over the march (sun rays);</li>
 *   <li>a full-resolution composite with a depth-aware upsample: {@code scene * transmittance + light * (1 - scene)}.</li>
 * </ol>
 * Under water (0.7.2) the march switches to water: uniform scattering, depth colour and moving caustics from the
 * surface (see {@code ShaftGlsl.MARCH}), plus rays from the Snell window in the surface (0.8.7); no block glow
 * there. Off in lava or powder snow, while blind, under Fabulous graphics, in caves (eye sky light; under water,
 * the sky light at the surface above) and where the dimension has no sun. Render thread.
 */
public final class LightShafts {
    public static AtmosFeature feature;

    public enum Mode {
        /** Volumetric while sun shadows run, radial otherwise. */
        AUTO,
        /** Always the cheap screen-space rays. */
        RADIAL
    }

    public enum Quality {
        /** Quarter width and height (1/16 of the pixels), 8 steps. */
        LOW(4, 8),
        /** Quarter width and height, 12 steps. */
        MEDIUM(4, 12),
        /** Half width and height, 16 steps. */
        HIGH(2, 16);

        final int divisor;
        final int steps;

        Quality(int divisor, int steps) {
            this.divisor = divisor;
            this.steps = steps;
        }
    }

    /** How far radial rays reach from the sun, as a fraction of the screen height. */
    private static final float RADIAL_REACH = 0.9f;
    /** Radius of the bright core around the sun that rays come from, as a fraction of the screen height. */
    private static final float RADIAL_CORE = 0.1f;
    /** Open sky away from the core: a faint glow so clear sky is not black next to rays. */
    private static final float RADIAL_FLOOR = 0.015f;
    /** Overall brightness of screen rays (the core average is small away from the sun). */
    private static final float RADIAL_EXPOSURE = 2.0f;
    /** Taps per blur pass, and the share of the way to the sun each pass covers (12 x 12 = 144 points in all). */
    private static final int RAY_TAPS = 12;
    /** 0.8.17: blur taps per pass for the underwater rays (the first pass covers 1/taps of the way). */
    private static final int WATER_RAY_TAPS = 8;
    private static final float[] RAY_STEPS = {1f / 12f, 1f};
    /** Screen rays over the volumetric march, relative to RADIAL mode. */
    private static final float LAYERED_RAYS = 0.8f;
    /** Low-resolution lines the march may use at most (LOW/MEDIUM, HIGH): 4K costs about what 1440p does. */
    private static final int CAP_LINES = 360;
    private static final int CAP_LINES_HIGH = 720;
    /** Block glow in the mist: gain on the colour volume's level squared (tuned in the night glow scene). */
    private static final float GLOW_GAIN = 120f;
    private static final Vector4f SUN = new Vector4f();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();

    private static RenderTargets.Target low;
    private static int lowDivisor;
    /** Sun-ray ping-pong targets (R16F, linear), at the low target's size. */
    private static RenderTargets.Target rayA;
    private static RenderTargets.Target rayB;
    /** 0.8.15: low-resolution view depth for the composite's weights (R32F). */
    private static RenderTargets.Target lowDepth;
    private static Program depthDown;
    private static Program composite;
    private static Program march;
    private static Program rayMask;
    private static Program rayMaskWater;
    private static Program rayBlur;
    private static Program resolve;
    private static int[] resolveReproject;
    /** Temporal history of the low-resolution light (0.7.1). */
    private static RenderTargets.History history;
    private static int temporalFrame;
    private static boolean failed;
    private static boolean warnedFabulous;
    /** Smoothed sky light at the eye, 0..1: no haze in caves or houses. */
    private static float eyeSky = 1f;
    /** Under water (0.7.2): smoothed sky light at the surface above the camera, and that surface (world y). */
    private static float surfaceSky = 1f;
    private static double surfaceY;
    private static boolean wasUnderwater;
    /** Scattering per block under water (tuned in tools/lumis_underwater_scene.txt). */
    private static final float WATER_SCATTER = 0.045f;
    /** Absorption per block without Torrente (Torrente's fallback style). */
    private static final float[] WATER_ABSORB = {0.25f, 0.09f, 0.06f};
    private static final float WATER_CAUSTICS = 1.0f;
    /*
     * Caustic waves (0.8.6): 5 swells, directions spread over half a turn, wavelengths in blocks, drifting at about
     * WAVE_SPEED blocks a second (rounded to whole cycles per hour of the lib clock). Their squared sum is expanded
     * into 25 cosine terms for the shader (see ShaftGlsl.lumis_causticMean).
     */
    private static final int WAVES = 5;
    private static final double[] WAVE_ANGLE = {7, 43, 79, 118, 154};
    private static final double[] WAVE_LENGTH = {6.1, 4.9, 7.3, 5.5, 4.3};
    private static final double WAVE_SPEED = 0.6;
    private static final int WAVE_TERMS = WAVES + WAVES * (WAVES - 1);
    private static final double[] WAVE_KX = new double[WAVES];
    private static final double[] WAVE_KZ = new double[WAVES];
    private static final double[] WAVE_OMEGA = new double[WAVES];
    private static final double[] WAVE_PHASE = new double[WAVES];
    /** Haze: the share of the mean underwater light kept; beam gain on the caustics' moving part. */
    private static final float WATER_HAZE = 0.25f;
    private static final float WATER_BEAMS = 1.0f;
    /** Gain on all light scattered under water (0.8.8; see ShaftGlsl.lumis_underwater). */
    private static final float WATER_GAIN = 10.0f;
    /** Snell-window rays (0.8.7): core radius and floor of the mask, caustic weight, and light gain. */
    private static final float WATER_RAY_CORE = 0.22f;
    private static final float WATER_RAY_FLOOR = 0.06f;
    private static final float WATER_RAY_CAUSTIC = 0.35f;
    private static final float WATER_RAY_GAIN = 0.8f;

    static {
        double hour = 2.0 * Math.PI / 3600.0;
        for (int j = 0; j < WAVES; j++) {
            double k = 2.0 * Math.PI / WAVE_LENGTH[j];
            double a = Math.toRadians(WAVE_ANGLE[j]);
            WAVE_KX[j] = k * Math.cos(a);
            WAVE_KZ[j] = k * Math.sin(a);
            WAVE_OMEGA[j] = Math.max(1, Math.round(k * WAVE_SPEED / hour)) * hour;
        }
    }
    private static long lastNanos;

    private LightShafts() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Lumis.MOD_ID, "light_shafts")
                .provides(Capability.VOLUMETRIC_LIGHT, Capability.GODRAYS)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(LumisConfig.lightShafts)
                .requiresGl(4, 6)
                .register();
        PostPasses.register(feature, PostPasses.Stage.AFTER_WEATHER, 50, "lumis_light_shafts", LightShafts::render);
    }

    private static final BlockPos.MutableBlockPos EYE = new BlockPos.MutableBlockPos();

    /** Once per frame (render tick start): eye sky light, and memory released while the feature is off. */
    public static void update(Minecraft mc) {
        if (feature == null || !feature.isActive()) {
            release();
            return;
        }
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        BlockPos eye = EYE.set(Mth.floor(camera.x), Mth.floor(camera.y), Mth.floor(camera.z));
        float target = level.getBrightness(LightLayer.SKY, eye) / 15f;
        eyeSky += (target - eyeSky) * Math.min(1f, dt * 1.5f);
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER) {
            // Under water the eye's sky light falls by one per block of water, which the march's absorption already
            // covers: gate by the sky light just above the water column instead (0 in a flooded cave).
            int top = eye.getY();
            int limit = Math.min(level.getMaxBuildHeight(), top + 64);
            while (top < limit && level.getFluidState(EYE.setY(top)).is(net.minecraft.tags.FluidTags.WATER)) {
                top++;
            }
            surfaceY = top - 0.11;
            float above = level.getBrightness(LightLayer.SKY, EYE.setY(top)) / 15f;
            surfaceSky += (above - surfaceSky) * Math.min(1f, dt * 1.5f);
        } else {
            surfaceSky = eyeSky;
        }
        SkyCover.update(dt);
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || failed || com.solarbiscuit.atmosphera.lumis.Lumis.stopping) {
            return;
        }
        if (Minecraft.useShaderTransparency()) {
            if (!warnedFabulous) {
                warnedFabulous = true;
                Lumis.LOGGER.info("Light shafts are off under Fabulous graphics (not supported yet).");
            }
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        FogType fluid = camera.getFluidInCamera();
        boolean underwater = fluid == FogType.WATER;
        if (fluid != FogType.NONE && !underwater || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        boolean marchPossible = LumisConfig.shaftMode.get() == Mode.AUTO && SunShadows.ready();
        float waterOption = LumisConfig.shaftUnderwaterOn.get() ? LumisConfig.shaftUnderwater.get().floatValue() : 0f;
        if (underwater && (!marchPossible || waterOption <= 0f)) {
            return;
        }
        float light = underwater
                ? SunModel.strength * LumisConfig.shaftStrength.get().floatValue() * waterOption * surfaceSky * surfaceSky
                : SunModel.strength * LumisConfig.shaftStrength.get().floatValue() * eyeSky * eyeSky;
        boolean sunOn = light > 0.003f;
        // Block glow (0.6.0) needs the march (AUTO with sun shadows) and coloured light holding colour somewhere.
        float glowOption = LumisConfig.shaftBlockGlow.get().floatValue();
        boolean glowOn = !underwater && glowOption > 0f && marchPossible && ColoredLight.glowAvailable();
        if (!sunOn && !glowOn) {
            return;
        }
        // The framebuffer the world is being drawn into now (not always the main target: render-scale mods).
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable()) {
            return;
        }
        if (march == null && !compile()) {
            return;
        }

        boolean volumetric = marchPossible;
        Quality quality = LumisConfig.shaftQuality.get();
        boolean temporal = volumetric && LumisConfig.shaftTemporal.get() && LumisConfig.shaftDebug.get() == 0;
        int divisor = volumetric ? quality.divisor : 4;
        // Resolution cap (0.6.0): above 1440p the divisor grows so the low-resolution target keeps about the same
        // number of lines, so 4K costs what 1440p does.
        int capLines = quality == Quality.HIGH ? CAP_LINES_HIGH : CAP_LINES;
        divisor = Math.max(divisor, (scene.height() + capLines - 1) / capLines);
        int debug = LumisConfig.shaftDebug.get();

        // Screen rays only reach so far from the sun: they run inside a box around it (and only when the sun is in
        // front of the camera). RADIAL mode needs them; AUTO adds them over the march when sun rays are on.
        // Under water (0.8.7): rays from the Snell window, converging on the refracted sun.
        boolean waterRays = underwater && sunOn && LumisConfig.shaftUnderwaterRays.get() && debug == 0;
        boolean rays = waterRays || !underwater && sunOn && (!volumetric || LumisConfig.shaftSunRays.get() && debug == 0);
        int boxX = 0;
        int boxY = 0;
        int boxW = 0;
        int boxH = 0;
        float sunU = 0f;
        float sunV = 0f;
        if (rays) {
            float sx = SunModel.dirX;
            float sy = SunModel.dirY;
            float sz = SunModel.dirZ;
            if (underwater) {
                // Seen from under water the sun sits at its refracted direction (n = 1.33), nearer straight up.
                sy = (float) Math.sqrt(Math.max(0f, 1f - (1f - sy * sy) / 1.7689f));
                sx /= 1.33f;
                sz /= 1.33f;
            }
            SUN.set(sx, sy, sz, 0f).mul(VIEW_PROJ.set(ctx.projection()).mul(ctx.poseStack().last().pose()));
            if (SUN.w > 1e-4f) {
                sunU = SUN.x / SUN.w * 0.5f + 0.5f;
                sunV = SUN.y / SUN.w * 0.5f + 0.5f;
                int reach = Math.round(RADIAL_REACH * scene.height()) + 2 * divisor;
                int cx = Math.round(sunU * scene.width());
                int cy = Math.round(sunV * scene.height());
                boxX = Math.max(0, cx - reach);
                boxY = Math.max(0, cy - reach);
                boxW = Math.min(scene.width(), cx + reach) - boxX;
                boxH = Math.min(scene.height(), cy + reach) - boxY;
            }
            rays = boxW > 0 && boxH > 0;
            if (!rays && !volumetric) {
                return;
            }
        }

        if (low == null || lowDivisor != divisor) {
            release();
            low = RenderTargets.create("Lumis light shafts", divisor, GL30C.GL_RGBA16F, false);
            rayA = RenderTargets.create("Lumis sun rays A", divisor, GL30C.GL_R16F, true);
            rayB = RenderTargets.create("Lumis sun rays B", divisor, GL30C.GL_R16F, true);
            lowDepth = RenderTargets.create("Lumis light shaft depth", divisor, GL30C.GL_R32F, false);
            lowDivisor = divisor;
        }
        if (!low.ensure(scene)) {
            return;
        }
        if (temporal) {
            if (history == null) {
                history = RenderTargets.history("Lumis light shafts", divisor, GL30C.GL_RGBA16F);
            }
            if (!history.ensure(scene)) {
                temporal = false;
            } else if (!ctx.continuous() || underwater != wasUnderwater) {
                // Also across the water surface: air and water light must not blend.
                history.invalidate();
            }
        } else if (history != null) {
            history.release();
            history = null;
        }
        if (rays && !(rayA.ensure(scene) && rayB.ensure(scene))) {
            rays = false;
            if (!volumetric) {
                return;
            }
        }

        wasUnderwater = underwater;
        float golden = 1f - smoothstep(0.08f, 0.5f, Math.abs(SunModel.sunHeight));
        float rain = level.getRainLevel(ctx.partialTick());
        // 0.6.6: a sun (or moon) inside the skybox's own clouds (Nuit 1.0.1+ reports how much) gives weaker light in
        // the mist and no clear-sky rays or tight halo; see SkyCover.
        float cover = SkyCover.get();
        float sunLight = sunOn ? light * (1f - 0.5f * cover) : 0f;
        float r = SunModel.SUN[0] * sunLight;
        float g = SunModel.SUN[1] * sunLight;
        float b = SunModel.SUN[2] * sunLight;

        FrameUniforms.update(AtmosClient.frame());
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Low-resolution light.
            low.bindWrite();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.depthTexture());
            if (volumetric) {
                GpuTimers.begin("lumis_shafts_march");
                GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, HorizonMap.texture());
                Program p = march;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u()[0], 12);
                GL20C.glUniform1i(p.u()[1], 13);
                GL20C.glUniformMatrix4fv(p.u()[2], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                // Temporal (0.7.1): half the steps per frame, the history makes up the rest.
                int marchSteps = temporal ? Math.max(4, quality.steps / 2) : quality.steps;
                GL20C.glUniform4i(p.u()[3], divisor, marchSteps, debug, sunOn ? 1 : 0);
                temporalFrame = (temporalFrame + 1) & 1023;
                GL20C.glUniform4f(p.u()[12], temporal ? (temporalFrame * 0.618034f) % 1f : 0f, 0f, 0f, 0f);
                GL20C.glUniform4f(p.u()[4], r, g, b, 0f);
                float mist = LumisConfig.shaftMist.get().floatValue();
                float density = underwater ? WATER_SCATTER
                        : 0.0035f * (0.5f + mist) * (1f + 2f * golden) * (1f + 2f * rain);
                float limit = Math.min(LumisConfig.shaftDistance.get(), SunShadows.windowRadius() - 16);
                if (underwater) {
                    // As far as one sees under water (vanilla's water fog grows with time spent under water).
                    limit = Math.min(limit, Math.max(16f, Math.min(64f,
                            com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd())));
                }
                GL20C.glUniform4f(p.u()[5], density, 32f, level.getSeaLevel(), Math.max(16f, limit));
                float contrast = LumisConfig.shaftContrast.get().floatValue();
                // Gain: beams through shade come out somewhat brighter than before, the open glow dimmer.
                GL20C.glUniform4f(p.u()[6], contrast, 1f + 0.5f * contrast, 0f, 0f);
                // Phase (0.6.0): the forward lobe tightens a little at sunrise and sunset and flattens in rain; a weak
                // back lobe (stronger in rain) keeps beams visible looking away from the sun.
                float forward = Math.max(0f, Math.min(0.95f,
                        LumisConfig.shaftAnisotropy.get().floatValue() + 0.1f * golden - 0.3f * rain)) * (1f - 0.7f * cover);
                GL20C.glUniform4f(p.u()[7], forward, 0.3f, 0.12f + 0.18f * rain, 0f);
                // Air colour by height: warmer low (more at golden hour), cooler high; luminance kept at 1.
                float tintAmount = LumisConfig.shaftHeightTint.get().floatValue();
                setTint(p.u()[8], lerp(0.97f, 0.90f, golden), lerp(0.92f, 0.78f, golden), tintAmount, 1f / 96f);
                setTint(p.u()[9], 0.98f, 1.07f, tintAmount, 0f, 0.93f);
                // Block glow: mostly a night effect (sunlight swamps it by day).
                float glowGain = glowOn ? glowOption * GLOW_GAIN * (1f - 0.8f * Math.min(1f, SunModel.strength)) : 0f;
                GL20C.glUniform4f(p.u()[10], glowGain, ColoredLight.glowReach(), 0f, 0f);
                GL20C.glUniform1i(p.u()[11], 14);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, glowOn ? ColoredLight.glowTexture() : 0);
                hueBound = true;
                // Under water (0.7.2): the surface above the camera, caustics, absorption, and the column field on
                // unit 15 (2D; Lumis keeps its LOD colour volume on that unit's 3D target, which this leaves alone).
                GL20C.glUniform4f(p.u()[13], underwater ? 1f : 0f, (float) (surfaceY - ctx.camera().y), WATER_CAUSTICS, 0f);
                GL20C.glUniform4f(p.u()[14], WATER_ABSORB[0], WATER_ABSORB[1], WATER_ABSORB[2], 0f);
                if (underwater) {
                    GL20C.glUniform4fv(p.u()[17], waveTerms(ctx.camera(), level, ctx.partialTick(), stack));
                    GL20C.glUniform4f(p.u()[18], WATER_HAZE, WATER_BEAMS, WATER_GAIN, 0f);
                }
                GL20C.glUniform1i(p.u()[15], 15);
                // 0.8.1: darkness of dark light on unit 11 (3D target; Minecraft binds only 2D textures there).
                GL20C.glUniform1i(p.u()[16], 11);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE11);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, glowOn ? ColoredLight.glowDimTexture() : 0);
                dimBound = true;
                GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, underwater && com.solarbiscuit.atmosphera.lib.world.ColumnField.ready()
                        ? com.solarbiscuit.atmosphera.lib.world.ColumnField.texture() : 0);
                columnBound = true;
                GlslPrograms.drawFullscreen();
                GpuTimers.end("lumis_shafts_march");
            }
            if (rays) {
                GpuTimers.begin("lumis_shafts_radial");
                // One low-resolution texel more than the composite box, so its bilinear taps never read stale texels.
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
                GL11C.glScissor(boxX / divisor - 1, boxY / divisor - 1, boxW / divisor + 3, boxH / divisor + 3);
                GL11C.glDisable(GL11C.GL_BLEND);
                float aspect = scene.width() / (float) Math.max(1, scene.height());
                // Mask: open sky x sun core, into A (the scene depth is still on unit 12). Under water: the open
                // Snell window x caustics x core around the refracted sun.
                rayA.bindWrite();
                Program p = underwater ? rayMaskWater : rayMask;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform4i(p.u()[0], divisor, 0, 0, 0);
                GL20C.glUniform4f(p.u()[1], sunU, sunV, aspect, RADIAL_REACH);
                if (underwater) {
                    GL20C.glUniform4f(p.u()[2], WATER_RAY_CORE, WATER_RAY_FLOOR, WATER_RAY_CAUSTIC, 0f);
                    GL20C.glUniformMatrix4fv(p.u()[3], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                    GL20C.glUniformMatrix4fv(p.u()[4], false, VIEW_PROJ.get(stack.mallocFloat(16)));
                    GL20C.glUniform4f(p.u()[5], (float) (surfaceY - ctx.camera().y), 0f, 0f, 0f);
                    GL20C.glUniform4fv(p.u()[6], waveTerms(ctx.camera(), level, ctx.partialTick(), stack));
                } else {
                    GL20C.glUniform4f(p.u()[2], RADIAL_CORE, RADIAL_FLOOR, 0f, 0f);
                }
                GlslPrograms.drawFullscreen();
                // Blur towards the sun, finest step first: A -> B -> light.
                p = rayBlur;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform4f(p.u()[1], sunU, sunV, aspect, RADIAL_REACH);
                // Rays: the march light already carries (1 - 0.5 cover); together the rays drop by about 0.85 x cover.
                float k = 0.6f * (1f + golden) * (1f - 0.7f * rain) * (volumetric ? LAYERED_RAYS : 1f)
                        * (1f - 0.85f * cover) / Math.max(0.05f, 1f - 0.5f * cover);
                float path = 0f;
                if (underwater) {
                    // The water above the camera and a few blocks of view colour the rays; the shader applies the
                    // absorption (Torrente's style when installed, 0.8.13).
                    float ly = (float) Math.sqrt(Math.max(0f, 1f - (1f - SunModel.dirY * SunModel.dirY) / 1.7689f));
                    path = (float) Math.max(0.0, surfaceY - ctx.camera().y) / Math.max(0.05f, ly) + 6f;
                    k = WATER_RAY_GAIN;
                }
                GL20C.glUniform4f(p.u()[3], r * k, g * k, b * k, 0f);
                GL20C.glUniform4f(p.u()[4], WATER_ABSORB[0], WATER_ABSORB[1], WATER_ABSORB[2], path);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
                for (int pass = 0; pass < RAY_STEPS.length; pass++) {
                    boolean last = pass == RAY_STEPS.length - 1;
                    RenderTargets.Target src = pass == 1 ? rayB : rayA;
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, src.texture());
                    if (last) {
                        low.bindWrite();
                        if (volumetric) {
                            // Over the march: add the light, keep its transmittance.
                            GL11C.glEnable(GL11C.GL_BLEND);
                            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE);
                        }
                    } else {
                        (pass == 0 ? rayB : rayA).bindWrite();
                    }
                    int taps = underwater ? WATER_RAY_TAPS : RAY_TAPS;
                    GL20C.glUniform4i(p.u()[0], last ? 1 : 0, taps, debug, 0);
                    GL20C.glUniform4f(p.u()[2], last ? RAY_STEPS[pass] : 1f / taps, RADIAL_EXPOSURE, 0f, 0f);
                    GlslPrograms.drawFullscreen();
                }
                GL11C.glDisable(GL11C.GL_BLEND);
                GpuTimers.end("lumis_shafts_radial");
            }

            // 1b. Temporal resolve (0.7.1): low + reprojected history -> history.current(), read by the composite.
            RenderTargets.Target lightOut = low;
            if (temporal) {
                GpuTimers.begin("lumis_shafts_resolve");
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDisable(GL11C.GL_BLEND);
                RenderTargets.Target target = history.current();
                target.bindWrite();
                GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, low.texture());
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, history.previous().texture());
                sceneBound = true;
                Program p = resolve;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u()[0], 13);
                GL20C.glUniform1i(p.u()[1], 14);
                GL20C.glUniform1i(p.u()[2], 12);
                GL20C.glUniformMatrix4fv(p.u()[3], false, ctx.invViewProjection().get(stack.mallocFloat(16)));
                GL20C.glUniform4i(p.u()[4], divisor, history.valid() ? 1 : 0, 0, 0);
                ctx.bindReproject(resolveReproject, stack);
                GlslPrograms.drawFullscreen();
                lightOut = target;
                history.swap();
                GpuTimers.end("lumis_shafts_resolve");
            }

            // 1c. Low-resolution view depth for the composite's weights (0.8.15).
            if (!lowDepth.ensure(scene)) {
                return;
            }
            {
                GpuTimers.begin("lumis_shafts_depthdown");
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDisable(GL11C.GL_BLEND);
                lowDepth.bindWrite();
                Program p = depthDown;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u()[0], 12);
                GL20C.glUniform4i(p.u()[1], divisor, 0, 0, 0);
                GlslPrograms.drawFullscreen();
                // Unit 15's 2D target (the column field under water is done by now); unbound in the finally.
                GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lowDepth.texture());
                columnBound = true;
                GpuTimers.end("lumis_shafts_depthdown");
            }

            // 2. Composite into the scene colour (its depth stays sampleable: this framebuffer has no depth).
            GpuTimers.begin("lumis_shafts_composite");
            if (RenderTargets.bindColorOnly(scene)) {
                if (volumetric) {
                    GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                } else {
                    // RADIAL: only the box around the sun (the depth pass above turned the scissor test off).
                    GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
                    GL11C.glScissor(boxX, boxY, boxW, boxH);
                }
                GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightOut.texture());
                Program p = composite;
                GL20C.glUseProgram(p.id());
                GL20C.glUniform1i(p.u()[0], 13);
                GL20C.glUniform1i(p.u()[1], 12);
                GL20C.glUniform4i(p.u()[2], divisor, 0, 0, 0);
                // Under water the scene is not dimmed or hazed (vanilla's water fog does that): light is only added.
                GL20C.glUniform4f(p.u()[3], volumetric && sunOn && !underwater ? LumisConfig.shaftMist.get().floatValue() : 0f,
                        debug > 0 ? 1f : 0f, underwater ? 0f : eyeSky * eyeSky, 0f);
                // The scene colour this pass writes, read back per pixel (0.6.4): unit 14, after a texture barrier so
                // the reads see everything drawn so far. Each pixel reads only its own texel, which GL 4.5 allows.
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.colorTexture());
                sceneBound = true;
                GL20C.glUniform1i(p.u()[4], 14);
                GL20C.glUniform4f(p.u()[5], TONE_HEADROOM, 0f, 0f, 0f);
                GL20C.glUniform1i(p.u()[6], 15);
                GL45C.glTextureBarrier();
                // The shader writes the final colour (no blending).
                GL11C.glDisable(GL11C.GL_BLEND);
                GlslPrograms.drawFullscreen();
            }
            GpuTimers.end("lumis_shafts_composite");
        } finally {
            if (sceneBound) {
                sceneBound = false;
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            if (dimBound) {
                dimBound = false;
                GL13C.glActiveTexture(GL13C.GL_TEXTURE11);
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
            }
            if (columnBound) {
                columnBound = false;
                GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            if (hueBound) {
                hueBound = false;
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                // Put back what the LOD binding keeps there (0.6.8: known, not queried).
                GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, ColoredLight.lodUnit14Texture());
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
        }
    }

    private static boolean compile() {
        String header = GlslPrograms.header460();
        // Torrente's water style slots are in the frame block when Torrente is installed (0.7.2).
        String water = FrameUniforms.glslBlock().contains("torrente_Absorb") ? "#define LUMIS_TORRENTE\n" : "";
        march = Program.of("Lumis shaft march", header + water + ShaftGlsl.MARCH,
                "u_Depth", "lumis_Horizon", "u_InvViewProj", "u_Info", "u_Light", "u_Medium", "u_Shape",
                "u_Phase", "u_TintLow", "u_TintHigh", "u_Glow", "u_Hue", "u_Temporal",
                "u_Water", "u_Absorb", "atmos_ColumnField", "u_HueDim", "u_Waves", "u_Beam");
        rayMask = Program.of("Lumis sun-ray mask", header + ShaftGlsl.RAY_MASK, "u_Info", "u_SunPos", "u_Ray");
        rayMaskWater = Program.of("Lumis Snell-window ray mask", header + ShaftGlsl.RAY_MASK_WATER, "u_Info", "u_SunPos",
                "u_Ray", "u_InvViewProj", "u_ViewProj", "u_Surface", "u_Waves");
        rayBlur = Program.of("Lumis sun-ray blur", header + water + ShaftGlsl.RAY_BLUR, "u_Info", "u_SunPos", "u_Step",
                "u_Light", "u_Tint");
        composite = Program.of("Lumis shaft composite", header + ShaftGlsl.COMPOSITE, "u_Low", "u_Depth", "u_Info", "u_Mix",
                "u_Scene", "u_Tone", "u_LowDepth");
        depthDown = Program.of("Lumis shaft depth", header + ShaftGlsl.DEPTH_DOWN, "u_Depth", "u_Info");
        resolve = Program.of("Lumis shaft resolve", header + ShaftGlsl.RESOLVE, "u_Cur", "u_Hist", "u_Depth",
                "u_InvViewProj", "u_Info");
        if (resolve != null) {
            resolveReproject = com.solarbiscuit.atmosphera.lib.gpu.PostPasses.locateReproject(resolve.id());
        }
        if (march == null || rayMask == null || rayMaskWater == null || rayBlur == null || composite == null || depthDown == null || resolve == null) {
            failed = true;
            Lumis.LOGGER.error("Light shafts are off: a shader did not compile (see above).");
            return false;
        }
        return true;
    }

    private static void release() {
        if (history != null) {
            history.release();
            history = null;
        }
        if (low != null) {
            low.release();
            low = null;
            rayA.release();
            rayA = null;
            rayB.release();
            rayB = null;
            lowDepth.release();
            lowDepth = null;
        }
    }

    private static boolean hueBound;
    private static boolean columnBound;
    private static boolean dimBound;
    private static boolean sceneBound;
    /** Share of a pixel's headroom (1 - colour) the shafts may fill at most (0.6.4 soft add). */
    private static final float TONE_HEADROOM = 0.6f;

    /**
     * The 25 caustic terms for this frame (stack memory): (kx, kz, amplitude, phase at the camera). Phases are summed in
     * double precision from the world camera position and the lib's world clock, so the shader works camera-relative
     * and the pattern stays put as the camera moves; the drift completes whole cycles per hour, so the clock's hourly
     * wrap does not jump.
     */
    private static java.nio.FloatBuffer waveTerms(Vec3 camera, ClientLevel level, float partialTick, MemoryStack stack) {
        double held = com.solarbiscuit.atmosphera.lib.wind.AtmosClock.overrideSeconds();
        double t = Double.isNaN(held)
                ? com.solarbiscuit.atmosphera.lib.wind.AtmosClock.worldSeconds(level, partialTick) % 3600.0 : held;
        for (int j = 0; j < WAVES; j++) {
            WAVE_PHASE[j] = WAVE_KX[j] * camera.x + WAVE_KZ[j] * camera.z - WAVE_OMEGA[j] * t + 1.7 * j;
        }
        java.nio.FloatBuffer out = stack.mallocFloat(WAVE_TERMS * 4);
        float self = 1f / WAVES;
        float pair = 2f / WAVES;
        for (int j = 0; j < WAVES; j++) {
            putTerm(out, 2 * WAVE_KX[j], 2 * WAVE_KZ[j], self, 2 * WAVE_PHASE[j]);
        }
        for (int i = 0; i < WAVES; i++) {
            for (int j = i + 1; j < WAVES; j++) {
                putTerm(out, WAVE_KX[i] + WAVE_KX[j], WAVE_KZ[i] + WAVE_KZ[j], pair, WAVE_PHASE[i] + WAVE_PHASE[j]);
                putTerm(out, WAVE_KX[i] - WAVE_KX[j], WAVE_KZ[i] - WAVE_KZ[j], pair, WAVE_PHASE[i] - WAVE_PHASE[j]);
            }
        }
        return out.flip();
    }

    private static void putTerm(java.nio.FloatBuffer out, double kx, double kz, float amplitude, double phase) {
        double tau = 2.0 * Math.PI;
        out.put((float) kx).put((float) kz).put(amplitude).put((float) (phase - Math.floor(phase / tau) * tau));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** A tint (r = 1 unless given) mixed with white by {@code amount}, scaled to luminance 1, with w. */
    private static void setTint(int location, float green, float blue, float amount, float w) {
        setTint(location, green, blue, amount, w, 1f);
    }

    private static void setTint(int location, float green, float blue, float amount, float w, float red) {
        float tr = lerp(1f, red, amount);
        float tg = lerp(1f, green, amount);
        float tb = lerp(1f, blue, amount);
        float lum = Math.max(0.05f, 0.2126f * tr + 0.7152f * tg + 0.0722f * tb);
        GL20C.glUniform4f(location, tr / lum, tg / lum, tb / lum, w);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** A compiled program and its uniform locations, in the order they were named. */
    private record Program(int id, int[] u) {
        static Program of(String name, String fragment, String... uniforms) {
            int id = GlslPrograms.compile(name, GlslPrograms.FULLSCREEN_VERTEX_460, fragment);
            if (id == 0) {
                return null;
            }
            int[] locations = new int[uniforms.length];
            for (int i = 0; i < uniforms.length; i++) {
                locations[i] = GL20C.glGetUniformLocation(id, uniforms[i]);
            }
            return new Program(id, locations);
        }
    }
}
