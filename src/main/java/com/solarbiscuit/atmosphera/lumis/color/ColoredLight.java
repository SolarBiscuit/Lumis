package com.solarbiscuit.atmosphera.lumis.color;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;

/**
 * Lumis 0.4 coloured block light ({@code lumis:colored_light}). Light propagation stays vanilla's (or Solix's): only
 * the colour of block light changes, per block, from the emitters that light it. Pieces:
 * <ul>
 *   <li>{@link EmitterColors}: the colour of each light-emitting state (table, id rules, sprite);</li>
 *   <li>{@link EmitterIndex}: the emitters of the loaded sections;</li>
 *   <li>{@link HueVolume}: the colour of the light per block around the camera, a 3D texture computed on a worker;</li>
 *   <li>{@link HueGlsl}: one filtered fetch per terrain pixel, tinting its block-lit share.</li>
 * </ul>
 * Nothing is written into chunk meshes, so it adds no meshing work and needs no chunk rebuilds (CheapLighting's
 * re-meshing is unaffected), and colours update as soon as a section's light changes. Render thread.
 */
public final class ColoredLight {
    public static AtmosFeature feature;

    public enum Quality {
        /** 64 blocks wide and high around the camera (1 MB). */
        LOW(64, 64),
        /** 128 wide and high (8 MB). */
        MEDIUM(128, 128),
        /** 256 wide, 128 high (32 MB). */
        HIGH(256, 128);

        final int xz;
        final int y;

        Quality(int xz, int y) {
            this.xz = xz;
            this.y = y;
        }
    }

    private static final EmitterIndex.Listener CHANGED = HueVolume::markAround;
    private static boolean running;
    private static ClientLevel runningLevel;

    private ColoredLight() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Lumis.MOD_ID, "colored_light")
                .provides(Capability.COLORED_LIGHT)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.FACE_NORMAL)
                .switchedBy(LumisConfig.coloredLight)
                .register();
        TerrainSamplers.register(feature, "lumis_Hue", GL12C.GL_TEXTURE_3D, HueVolume::texture);
        // All passes: solid, cutout and translucent (water, glass, ice).
        int all = ShaderSnippets.PASS_ALL;
        ShaderSnippets.register(feature, ShaderHook.VERT_DECL, all, 25, HueGlsl.VERT_DECL);
        ShaderSnippets.register(feature, ShaderHook.VERT_POST_LIGHT, all, 25, HueGlsl.VERT_POST_LIGHT);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, all, 25, HueGlsl.FRAG_DECL);
        ShaderSnippets.register(feature, ShaderHook.FRAG_PRE_FOG, all, 25, HueGlsl.FRAG_PRE_FOG);
        ShaderSnippets.register(feature, ShaderHook.LOD_DECL, all, 25, HueGlsl.LOD_DECL);
        ShaderSnippets.register(feature, ShaderHook.LOD_FRAGMENT, all, 25, HueGlsl.LOD_FRAGMENT);
        FrameUniforms.vec4("lumis_HueMix", "x: 1 / volume width, y: 1 / volume height, z: strength (0 = off), w: saturation", out -> {
            boolean on = running && HueVolume.allocated();
            out[0] = 1f / Math.max(16, HueVolume.sizeXZ());
            out[1] = 1f / Math.max(16, HueVolume.sizeY());
            out[2] = on ? LumisConfig.colorStrength.get().floatValue() : 0f;
            out[3] = LumisConfig.colorSaturation.get().floatValue();
        });
        for (int i = 0; i < DynamicHue.MAX; i++) {
            int n = i;
            FrameUniforms.vec4("lumis_Dyn" + i, "dynamic light " + i + ": xyz camera-relative position, w: luminance * 65536 + Co byte * 256 + Cg byte (0 = none)", out -> {
                boolean on = running && n < DynamicHue.count;
                Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                out[0] = on ? (float) (DynamicHue.X[n] - cam.x) : 0f;
                out[1] = on ? (float) (DynamicHue.Y[n] - cam.y) : 0f;
                out[2] = on ? (float) (DynamicHue.Z[n] - cam.z) : 0f;
                out[3] = on ? DynamicHue.packW(n) : 0f;
            });
        }

        FrameUniforms.vec4("lumis_HueFar", "x: 1 / far volume width (blocks), y: 1 / its height, z: 1 while it runs", out -> {
            out[0] = 1f / (FarHue.CELL * FarHue.NXZ);
            out[1] = 1f / (FarHue.CELL * FarHue.NY);
            out[2] = running && FarHue.allocated() ? 1f : 0f;
            out[3] = 0f;
        });
        FarHue.register();
        com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.addBeforeLodDraw(ColoredLight::bindForLods);

        MinecraftForge.EVENT_BUS.addListener(ColoredLight::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(ColoredLight::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(ColoredLight::onLogin);
    }

    /** True while the light colour volume runs and holds colour somewhere (the shaft march's block glow). */
    public static boolean glowAvailable() {
        return running && HueVolume.allocated() && HueVolume.nonZeroCount() > 0;
    }

    /** The light colour volume (3D texture), for the shaft march. */
    public static int glowTexture() {
        return HueVolume.texture();
    }

    /** How far from the camera the volume reaches with full colour, in blocks. */
    public static float glowReach() {
        return Math.max(0f, HueVolume.sizeXZ() * 0.5f - 12f);
    }

    /** Once per frame (render tick start). */
    public static void update(Minecraft mc) {
        ClientLevel level = mc.level;
        boolean want = level != null && feature.isActive();
        if (!want || level != runningLevel) {
            if (running) {
                running = false;
                runningLevel = null;
                HueVolume.release();
                EmitterIndex.clear();
                FarHue.setWanted(false);
                FarHue.release();
                FarHue.clear();
            }
            if (!want) {
                return;
            }
        }
        Quality quality = LumisConfig.colorQuality.get();
        HueVolume.ensure(quality.xz, quality.y);
        if (!running) {
            running = true;
            runningLevel = level;
            // Chunks loaded before the feature started never sent a load event to us: queue them now. Centred on the
            // player, not the camera: on the first frame in a world the camera has not been placed yet (it is still at
            // the origin, or where the last world left it), and a loading screen can hold that first frame back for
            // seconds while every nearby chunk arrives. Centred on the camera, a rejoined world's lights stayed
            // uncoloured until a block change rescanned their section (fixed in 0.5.10). The radius covers the largest
            // server view distance (32) plus the client cache's margin; absent chunks are a cheap lookup.
            double px;
            double pz;
            if (mc.player != null) {
                px = mc.player.getX();
                pz = mc.player.getZ();
            } else {
                Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
                px = camPos.x;
                pz = camPos.z;
            }
            int cx = SectionPos.blockToSectionCoord(Mth.floor(px));
            int cz = SectionPos.blockToSectionCoord(Mth.floor(pz));
            int r = Math.max(mc.options.getEffectiveRenderDistance(), 32) + 3;
            int queued = 0;
            for (int z = cz - r; z <= cz + r; z++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                    if (chunk != null) {
                        EmitterIndex.onChunkLoad(chunk);
                        queued++;
                    }
                }
            }
            HueVolume.logOnce("on, " + HueVolume.describe() + ", " + queued + " loaded chunks queued for emitter scans");
            if (FarHue.available()) {
                // NeoVoxy reports emitters while it builds LOD meshes: rebuild them once so every section reports.
                FarHue.setWanted(true);
                FarHue.ensure();
                com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.requestRendererRebuild("Lumis coloured light: collect distant light emitters");
            } else {
                HueVolume.logOnce("distant LODs uncoloured (needs NeoVoxy 1.43.7+ with its LOD extension API)");
            }
        }
        if (FarHue.allocated()) {
            FarHue.update(Mth.floor(mc.gameRenderer.getMainCamera().getPosition().x),
                    Mth.floor(mc.gameRenderer.getMainCamera().getPosition().y),
                    Mth.floor(mc.gameRenderer.getMainCamera().getPosition().z));
        }
        EmitterIndex.drain(level, CHANGED);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        HueVolume.update(level, Mth.floor(cam.x), Mth.floor(cam.y), Mth.floor(cam.z));
        DynamicHue.update(mc, level, cam);
        if (LumisConfig.colorDump.get() && !dumped) {
            dumped = true;
            EmitterColors.dump();
        }
        bindForLods();
    }

    /**
     * NeoVoxy's LOD shaders read the near volume from unit 15 and the far one from unit 14 (3D targets; Xenon's
     * programs use unit 14's 2D target for the horizon map). Every frame and right before each LOD draw.
     */
    private static void bindForLods() {
        if (!running || Lumis.stopping) {
            return;
        }
        int previous = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, HueVolume.texture());
        GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, FarHue.texture());
        GL13C.glActiveTexture(previous);
    }

    private static boolean dumped;
    private static final BlockPos.MutableBlockPos TINT_POS = new BlockPos.MutableBlockPos();
    private static final float[] SAMPLE = new float[4];
    private static final float[] TINT = new float[3];

    /**
     * Entities, block entities and held items: the buffers to render with, tinted by the light colour at a world
     * position (the block-lit share of its light, as on terrain), or the given buffers when the tint is neutral.
     */
    public static MultiBufferSource tint(MultiBufferSource buffers, double x, double y, double z) {
        if (!running || runningLevel == null || buffers instanceof TintedBuffers) {
            return buffers;
        }
        float strength = LumisConfig.colorStrength.get().floatValue();
        if (strength <= 0f) {
            return buffers;
        }
        float acc0 = 0f;
        float acc1 = 0f;
        float accW = 0f;
        float cover = 0f;
        float staticLevel = 0f;
        if (HueVolume.sample(x, y, z, SAMPLE)) {
            float c = smooth(0.02f, 0.4f, SAMPLE[2]);
            float w = c * SAMPLE[3];
            acc0 += SAMPLE[0] * w;
            acc1 += SAMPLE[1] * w;
            accW += w;
            cover = c;
            staticLevel = SAMPLE[3];
        }
        float dynamicLevel = 0f;
        for (int i = 0; i < DynamicHue.count; i++) {
            double dx = x - DynamicHue.X[i];
            double dy = y - DynamicHue.Y[i];
            double dz = z - DynamicHue.Z[i];
            float pred = DynamicHue.LUMINANCE[i] * (1f - (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / DynamicHue.RADIUS);
            if (pred <= 0f) {
                continue;
            }
            float w = pred;
            acc0 += EmitterColors.co(DynamicHue.CHROMA[i]) * w;
            acc1 += EmitterColors.cg(DynamicHue.CHROMA[i]) * w;
            accW += w;
            cover = Math.max(cover, Math.min(1f, pred));
            dynamicLevel = Math.max(dynamicLevel, pred);
        }
        if (accW <= 0f) {
            return buffers;
        }
        // Block-lit share of the light here: vanilla's brightness curve for the block and sky levels.
        BlockPos pos = TINT_POS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        float block = Math.max(Math.max(staticLevel, dynamicLevel), runningLevel.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
        float sky = runningLevel.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        float bb = brightness(block);
        float sb = brightness(sky) * runningLevel.getSkyDarken(1f);
        float share = bb / Math.max(bb + sb, 1e-4f);
        float k = share * strength * cover;
        if (k < 0.02f) {
            return buffers;
        }
        float saturation = LumisConfig.colorSaturation.get().floatValue();
        float co = acc0 / accW * saturation;
        float cg = acc1 / accW * saturation;
        float tmp = 1f - cg;
        TINT[0] = Math.max(0f, tmp + co);
        TINT[1] = Math.max(0f, 1f + cg);
        TINT[2] = Math.max(0f, tmp - co);
        float l = Math.max(0.05f, 0.2126f * TINT[0] + 0.7152f * TINT[1] + 0.0722f * TINT[2]);
        float r = 1f + (TINT[0] / l - 1f) * k;
        float g = 1f + (TINT[1] / l - 1f) * k;
        float b = 1f + (TINT[2] / l - 1f) * k;
        if (Math.abs(r - 1f) + Math.abs(g - 1f) + Math.abs(b - 1f) < 0.03f) {
            return buffers;
        }
        return TintedBuffers.INSTANCE.reset(buffers, r, g, b);
    }

    private static net.minecraft.world.entity.Entity pendingEntity;

    /** The world's entity loop is about to draw this entity (render thread). */
    public static void putEntity(net.minecraft.world.entity.Entity entity) {
        pendingEntity = running ? entity : null;
    }

    /** The entity announced by {@link #putEntity}, once. */
    public static net.minecraft.world.entity.Entity takeEntity() {
        net.minecraft.world.entity.Entity e = pendingEntity;
        pendingEntity = null;
        return e;
    }

    /** Entities: at the middle of the bounding box. */
    public static MultiBufferSource tintEntity(MultiBufferSource buffers, net.minecraft.world.entity.Entity entity) {
        if (!running) {
            return buffers;
        }
        return tint(buffers, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
    }

    /** First-person hands and held items: at the camera. */
    public static MultiBufferSource tintCamera(MultiBufferSource buffers) {
        if (!running) {
            return buffers;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return tint(buffers, cam.x, cam.y - 0.3, cam.z);
    }

    private static float brightness(float level) {
        float f = Math.max(0f, Math.min(1f, level / 15f));
        return f / (4f - 3f * f);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** From the block-update mixin (render thread). */
    public static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (running && runningLevel != null) {
            EmitterIndex.onBlockChanged(pos, oldState, newState);
        }
    }

    /** Block light of a section changed (client light engine), or a light packet arrived for it. */
    public static void onLightChanged(int sx, int sy, int sz, boolean withNeighbours) {
        if (!running) {
            return;
        }
        if (withNeighbours) {
            HueVolume.markAround(sx, sy, sz);
        } else {
            HueVolume.markDirty(sx, sy, sz);
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (running && event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            EmitterIndex.onChunkLoad(chunk);
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (running && event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            EmitterIndex.onChunkUnload(chunk, CHANGED);
        }
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Block-state ids may differ per server.
        EmitterColors.reset();
        EmitterIndex.clear();
        running = false;
        runningLevel = null;
        HueVolume.release();
        FarHue.setWanted(false);
        FarHue.release();
        FarHue.clear();
    }
}
