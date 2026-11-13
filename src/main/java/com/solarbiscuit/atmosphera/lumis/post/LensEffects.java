package com.solarbiscuit.atmosphera.lumis.post;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import com.solarbiscuit.atmosphera.lumis.atmosphere.SkyCover;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Lumis 0.8.0 lens effects ({@code lumis:lens}): bloom, a lens flare from the sun, vibrance and a vignette.
 * <ol>
 *   <li>AFTER_WEATHER ({@code lumis_lens_prepare}, while the world's depth is still there): the sun's visibility
 *       (1x1), a bright pass at half resolution and a dual-filter blur chain down to 1/32 and back;</li>
 *   <li>FINAL (lib 0.6.4, after the hand): one composite of bloom, flare, vibrance and vignette into the picture.</li>
 * </ol>
 * Each part is off at 0 (vibrance at 50%); with all four off neither pass draws. A shaderpack with its own bloom and
 * grading turns the feature off (capability {@code POST_PROCESS}). Render thread.
 */
public final class LensEffects {
    public static AtmosFeature feature;

    /** Bloom chain: 1/2, 1/4, 1/8, 1/16, 1/32 of the screen. */
    private static final int LEVELS = 5;
    /* Soft-knee thresholds by day and by night: options bloom_threshold and bloom_night_threshold (0.8.4; the night
       one was 0.62, which only about 2,300 pixels of the night colour scene passed). */
    private static final float KNEE = 0.12f;
    /** Composite gain on the summed chain (tuned in tools/lumis_color_scene.txt at night, 0.8.3). */
    private static final float BLOOM_GAIN = 1.5f;
    /** Sun core in the bright pass: radius (fraction of the screen height) and gain. */
    private static final float SUN_CORE = 0.035f;
    private static final float SUN_CORE_GAIN = 0.6f;
    /** Disc around the sun whose open-sky share gives its visibility (fraction of the screen height). */
    private static final float SUN_DISC = 0.02f;

    private static final RenderTargets.Target[] CHAIN = new RenderTargets.Target[LEVELS];
    private static RenderTargets.Target sunVis;
    /**
     * 0.11.2: a copy of the scene colour the composite reads. It read the scene's own colour attachment while drawing
     * into it (a feedback loop made legal by a texture barrier), which crashed the AMD driver (atio6axx.dll access
     * violation) a few seconds into some worlds.
     */
    private static RenderTargets.Target source;
    private static int sunVisProgram;
    private static int brightProgram;
    private static int downProgram;
    private static int upProgram;
    private static int compositeProgram;
    private static int[] uSunVis;
    private static int[] uBright;
    private static int[] uDown;
    private static int[] uUp;
    private static int[] uComposite;
    private static boolean failed;
    /** Whether this frame's prepare pass filled the bloom chain and the sun visibility. */
    private static boolean prepared;
    private static long preparedFrame = -1;
    /** Sun on screen this frame (from the prepare pass): uv, and the flare fade. */
    private static float sunU;
    private static float sunV;
    private static float flareFade;
    private static final Vector4f SUN = new Vector4f();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();

    private LensEffects() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Lumis.MOD_ID, "lens")
                .provides(Capability.POST_PROCESS)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(LumisConfig.lens)
                .requiresGl(4, 6)
                .register();
        PostPasses.register(feature, PostPasses.Stage.AFTER_WEATHER, 70, "lumis_lens_prepare", LensEffects::prepare);
        PostPasses.register(feature, PostPasses.Stage.FINAL, 50, "lumis_lens", LensEffects::composite);
    }

    private static float bloom() {
        return LumisConfig.lensBloomOn.get() ? LumisConfig.lensBloom.get().floatValue() : 0f;
    }

    private static float flare() {
        return LumisConfig.lensFlareOn.get() ? LumisConfig.lensFlare.get().floatValue() : 0f;
    }

    /** -1..1, 0 neutral (the option's 50%). */
    private static float vibrance() {
        return LumisConfig.lensVibranceOn.get() ? (LumisConfig.lensVibrance.get().floatValue() - 0.5f) * 2f : 0f;
    }

    private static float vignette() {
        return LumisConfig.lensVignetteOn.get() ? LumisConfig.lensVignette.get().floatValue() : 0f;
    }

    /** No lens flare (or sun bloom core) under water, in lava, while blind, or with the sun below the horizon. */
    private static boolean sunUsable(Minecraft mc) {
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            return false;
        }
        if (mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return false;
        }
        return SunModel.sunHeight > 0f && SunModel.strength > 0.01f;
    }

    private static void prepare(PostPasses.Context ctx) {
        prepared = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed || Lumis.stopping || Minecraft.useShaderTransparency()) {
            return;
        }
        float bloom = bloom();
        float flare = flare();
        if (bloom <= 0f && flare <= 0f) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable() || brightProgram == 0 && !compile()) {
            return;
        }
        // The sun on screen: SunModel's direction is the sun by day (sunHeight > 0).
        boolean sun = sunUsable(mc);
        flareFade = 0f;
        if (sun) {
            SUN.set(SunModel.dirX, SunModel.dirY, SunModel.dirZ, 0f)
                    .mul(VIEW_PROJ.set(ctx.projection()).mul(ctx.poseStack().last().pose()));
            if (SUN.w > 1e-4f) {
                sunU = SUN.x / SUN.w * 0.5f + 0.5f;
                sunV = SUN.y / SUN.w * 0.5f + 0.5f;
                float edge = Math.max(Math.abs(sunU - 0.5f), Math.abs(sunV - 0.5f));
                float onScreen = 1f - smoothstep(0.45f, 0.7f, edge);
                float horizon = smoothstep(0f, 0.12f, SunModel.sunHeight);
                flareFade = onScreen * horizon * SunModel.strength * (1f - 0.9f * SkyCover.get());
            }
        }
        float aspect = scene.width() / (float) Math.max(1, scene.height());

        if (sunVis == null) {
            sunVis = RenderTargets.create("Lumis lens sun", 1 << 16, GL30C.GL_R16F, false);
            for (int i = 0; i < LEVELS; i++) {
                CHAIN[i] = RenderTargets.create("Lumis bloom 1/" + (2 << i), 2 << i, GL30C.GL_R11F_G11F_B10F, true);
            }
        }
        if (!sunVis.ensure(scene)) {
            return;
        }
        for (RenderTargets.Target level : CHAIN) {
            if (!level.ensure(scene)) {
                return;
            }
        }

        GlStateSnapshot saved = GlStateSnapshot.save();
        try {
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            // 1. Sun visibility, while the world depth is still there.
            sunVis.bindWrite();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.depthTexture());
            GL20C.glUseProgram(sunVisProgram);
            GL20C.glUniform1i(uSunVis[0], 12);
            // Cloud transmittance (lib CloudCover, 0.10.7) on unit 13; unit 13 gets the scene colour next.
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, com.solarbiscuit.atmosphera.lib.world.CloudCover.transmittanceTexture());
            GL20C.glUniform1i(uSunVis[2], 13);
            // Without a usable sun the disc is put off the screen, so every tap counts as covered (visibility 0).
            GL20C.glUniform4f(uSunVis[1], flareFade > 0f ? sunU : -10f, flareFade > 0f ? sunV : -10f, aspect, SUN_DISC);
            GlslPrograms.drawFullscreen();

            // 2. Bright pass into the half-size level.
            GpuTimers.begin("lumis_lens_bloom");
            CHAIN[0].bindWrite();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.colorTexture());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sunVis.texture());
            GL20C.glUseProgram(brightProgram);
            GL20C.glUniform1i(uBright[0], 13);
            GL20C.glUniform1i(uBright[1], 12);
            GL20C.glUniform1i(uBright[2], 14);
            float day = smoothstep(-0.05f, 0.25f, SunModel.sunHeight);
            GL20C.glUniform4f(uBright[3], lerp(LumisConfig.lensBloomNightThreshold.get().floatValue(),
                    LumisConfig.lensBloomThreshold.get().floatValue(), day), KNEE, 1f, 0f);
            GL20C.glUniform4f(uBright[4], sunU, sunV, aspect, SUN_CORE);
            float core = flareFade * SUN_CORE_GAIN;
            GL20C.glUniform4f(uBright[5], SunModel.SUN[0] * core, SunModel.SUN[1] * core, SunModel.SUN[2] * core,
                    core > 0f ? 1f : 0f);
            GlslPrograms.drawFullscreen();

            // 3. Down the chain, then back up, adding each smaller level onto the next larger one.
            GL20C.glUseProgram(downProgram);
            GL20C.glUniform1i(uDown[0], 13);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            for (int i = 1; i < LEVELS; i++) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, CHAIN[i - 1].texture());
                CHAIN[i].bindWrite();
                GlslPrograms.drawFullscreen();
            }
            GL20C.glUseProgram(upProgram);
            GL20C.glUniform1i(uUp[0], 13);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE);
            for (int i = LEVELS - 1; i > 0; i--) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, CHAIN[i].texture());
                CHAIN[i - 1].bindWrite();
                GL20C.glUniform4f(uUp[1], 1f / CHAIN[i - 1].width(), 1f / CHAIN[i - 1].height(), 0f, 0f);
                GlslPrograms.drawFullscreen();
            }
            GL11C.glDisable(GL11C.GL_BLEND);
            GpuTimers.end("lumis_lens_bloom");
            prepared = true;
            preparedFrame = com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame();
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
        }
    }

    private static void composite(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed || Lumis.stopping || Minecraft.useShaderTransparency()) {
            return;
        }
        boolean lensReady = prepared && preparedFrame == com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame();
        float bloom = lensReady ? bloom() : 0f;
        float flare = lensReady && flareFade > 0f ? flare() : 0f;
        float vibrance = vibrance();
        float vignette = vignette();
        if (bloom <= 0f && flare <= 0f && Math.abs(vibrance) < 0.005f && vignette <= 0f) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (scene.colorTexture() == 0 || compositeProgram == 0 && !compile()) {
            return;
        }
        if (source == null) {
            source = RenderTargets.create("Lumis lens source", 1, GL11C.GL_RGBA8, false);
        }
        GlStateSnapshot saved = GlStateSnapshot.save();
        try {
            if (!source.ensure(scene)) {
                return;
            }
            // 0.11.2: copy the scene colour first (a blit converts the format), then draw into the scene from the copy.
            source.bindWrite();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, scene.framebuffer());
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBlitFramebuffer(0, 0, scene.width(), scene.height(), 0, 0, source.width(), source.height(),
                    GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            // 0.11.2: samplers the shader does not read this frame get a 1x1 black texture, never texture 0 (no
            // texture). The AMD driver crash of 2026-11 was the frame block's explicit binding (lib 0.9.3), not this.
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lensReady ? sunVis.texture() : blank());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lensReady ? CHAIN[0].texture() : blank());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, source.texture());
            GL20C.glUseProgram(compositeProgram);
            GL20C.glUniform1i(uComposite[0], 14);
            GL20C.glUniform1i(uComposite[1], 13);
            GL20C.glUniform1i(uComposite[2], 12);
            // The chain sums LEVELS blurred copies; a small source spread over that blur is faint, so no division
            // (0.8.3: with it, 50% bloom changed the picture by 1/255).
            GL20C.glUniform4f(uComposite[3], bloom * BLOOM_GAIN, flare, 0f, 0f);
            float aspect = scene.width() / (float) Math.max(1, scene.height());
            GL20C.glUniform4f(uComposite[4], sunU, sunV, aspect, flareFade);
            GL20C.glUniform4f(uComposite[5], SunModel.SUN[0], SunModel.SUN[1], SunModel.SUN[2], 0f);
            GL20C.glUniform4f(uComposite[6], vibrance, vignette, LumisConfig.lensVignetteSize.get().floatValue(), 0f);
            int tint = LumisConfig.lensVignetteColor.rgb();
            GL20C.glUniform4f(uComposite[7], (tint >> 16 & 255) / 255f, (tint >> 8 & 255) / 255f, (tint & 255) / 255f, 0f);
            GlslPrograms.drawFullscreen();
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
        }
    }

    private static int blank;

    /** A 1x1 black texture for samplers with nothing to read (created once, on unit 15). */
    private static int blank() {
        if (blank == 0) {
            blank = com.solarbiscuit.atmosphera.lib.gpu.GpuResources.texture("Lumis lens blank");
            int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, blank);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                    new int[] {0xFF000000});
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            GL13C.glActiveTexture(previousUnit);
        }
        return blank;
    }

    private static boolean compile() {
        String header = GlslPrograms.header460();
        sunVisProgram = program("Lumis lens sun", header + com.solarbiscuit.atmosphera.lib.world.CloudCover.GLSL
                + LensGlsl.SUN_VIS);
        brightProgram = program("Lumis bloom bright pass", header + LensGlsl.BRIGHT);
        downProgram = program("Lumis bloom down", header + LensGlsl.DOWN);
        upProgram = program("Lumis bloom up", header + LensGlsl.UP);
        compositeProgram = program("Lumis lens composite", header + LensGlsl.COMPOSITE);
        if (sunVisProgram == 0 || brightProgram == 0 || downProgram == 0 || upProgram == 0 || compositeProgram == 0) {
            failed = true;
            Lumis.LOGGER.error("Lens effects are off: a shader did not compile (see above).");
            return false;
        }
        uSunVis = locate(sunVisProgram, "u_Depth", "u_Sun", "atmos_CloudT");
        uBright = locate(brightProgram, "u_Scene", "u_Depth", "u_SunVis", "u_Knee", "u_Sun", "u_SunCol");
        uDown = locate(downProgram, "u_Src");
        uUp = locate(upProgram, "u_Src", "u_Dst");
        uComposite = locate(compositeProgram, "u_Scene", "u_Bloom", "u_SunVis", "u_Mix", "u_Sun", "u_SunCol", "u_Grade",
                "u_Vignette");
        return true;
    }

    /** 0.10.9: the lib's shader reload: free the programs; the next frame rebuilds them. */
    public static void reloadShaders() {
        for (int p : new int[] {sunVisProgram, brightProgram, downProgram, upProgram, compositeProgram}) {
            if (p > 0) {
                com.solarbiscuit.atmosphera.lib.gpu.GpuResources.deleteProgram(p);
            }
        }
        sunVisProgram = brightProgram = downProgram = upProgram = compositeProgram = 0;
        failed = false;
    }

    private static int program(String name, String fragment) {
        return GlslPrograms.compile(name, GlslPrograms.FULLSCREEN_VERTEX_460, fragment);
    }

    private static int[] locate(int program, String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = GL20C.glGetUniformLocation(program, names[i]);
        }
        return out;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }
}
