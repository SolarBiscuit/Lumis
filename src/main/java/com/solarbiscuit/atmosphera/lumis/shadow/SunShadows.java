package com.solarbiscuit.atmosphera.lumis.shadow;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lumis.Lumis;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import com.solarbiscuit.atmosphera.lumis.lightmap.DimensionProfiles;
import com.solarbiscuit.atmosphera.lumis.lightmap.LightmapGrader;
import com.solarbiscuit.atmosphera.lumis.sun.SunModel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;

/**
 * Lumis 0.2 sun shadows ({@code lumis:sun_shadows}): the heightfield, the horizon map built from it, the terrain
 * shader contract ({@code atmos_sunVisibility}) and the entity shadow. Render thread.
 */
public final class SunShadows {
    public static AtmosFeature feature;

    public enum Quality {
        /** 256-block window (shadows to about 110 blocks), rebuilt at most 10 times a second while the sun moves. */
        LOW(256, 100_000_000L),
        /** 512-block window (about 240 blocks), 20 rebuilds a second. */
        MEDIUM(512, 50_000_000L),
        /** 1024-block window (about 490 blocks), 30 rebuilds a second. */
        HIGH(1024, 33_000_000L);

        final int size;
        final long interval;

        Quality(int size, long interval) {
            this.size = size;
            this.interval = interval;
        }
    }

    private static boolean running;

    private SunShadows() {
    }

    public static void register() {
        feature = AtmosFeature.builder(Lumis.MOD_ID, "sun_shadows")
                .provides(Capability.SUN_SHADOWS)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.FACE_NORMAL)
                .switchedBy(LumisConfig.shadows)
                .enabledWhen(() -> LumisConfig.shadows.enabled().get() && LumisConfig.sunShading.enabled().get())
                .requiresGl(4, 6)
                .register();
        TerrainSamplers.register(feature, "lumis_Horizon", HorizonMap::texture);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, 5, ShadowGlsl.FRAG_DECL);

        FrameUniforms.vec4("lumis_ShadowWin", "xy: camera minus shadow window centre (x, z), z: window size, w: minY", out -> {
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            out[0] = (float) (camera.x - HorizonMap.centreX);
            out[1] = (float) (camera.z - HorizonMap.centreZ);
            out[2] = Math.max(16, Heightfield.n);
            out[3] = Heightfield.minY;
        });
        FrameUniforms.vec4("lumis_ShadowSun", "xy: horizontal direction to the sun, z: tan(elevation), w: penumbra per block", out -> {
            out[0] = HorizonMap.dirX;
            out[1] = HorizonMap.dirZ;
            out[2] = HorizonMap.tan;
            out[3] = 0.04f * LumisConfig.shadowSoftness.get().floatValue();
        });
        FrameUniforms.vec4("lumis_ShadowMix", "x: shadow strength (0 = none), y: fade length at the window edge, z: tan(elevation) now", out -> {
            out[0] = running && HorizonMap.valid ? LumisConfig.shadowStrength.get().floatValue() : 0f;
            out[1] = 32f;
            out[2] = HorizonMap.sameDirection ? HorizonMap.tanNow : HorizonMap.tan;
        });

        MinecraftForge.EVENT_BUS.addListener(SunShadows::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(SunShadows::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(SunShadows::onLogin);
    }

    /** Sun shadows are running and the horizon map is built (light shafts can march it). */
    public static boolean ready() {
        return running && HorizonMap.valid;
    }

    /** Half the heightfield window in blocks. */
    public static int windowRadius() {
        return Heightfield.n / 2;
    }

    /** Once per frame, after {@link SunModel#update}. */
    public static void update(Minecraft mc) {
        ClientLevel level = mc.level;
        boolean want = level != null && feature.isActive() && level.dimensionType().hasSkyLight();
        if (want) {
            DimensionProfiles.Profile profile = DimensionProfiles.get(level);
            want = profile.sun() && profile.shadows();
        }
        if (!want) {
            if (running) {
                running = false;
                HorizonMap.release();
                Heightfield.release();
            }
            return;
        }
        running = true;
        Heightfield.ensure(level, LumisConfig.shadowQuality.get().size);
        Camera camera = mc.gameRenderer.getMainCamera();
        Heightfield.update(Mth.floor(camera.getPosition().x) >> 4, Mth.floor(camera.getPosition().z) >> 4);
        HorizonMap.update(SunModel.dirX, SunModel.dirY, SunModel.dirZ, LumisConfig.shadowQuality.get().interval);
    }

    /**
     * Entities: lowers the sky-light part of the packed light where the entity stands in a sun shadow, by the amount
     * terrain in shadow loses. Runs after CheapLighting, Belt Slot and RatOpt (priority 1500), so only the sky field
     * is touched.
     */
    public static int entityLight(Entity entity, float partial, int packed) {
        if (!running || !HorizonMap.valid || !LumisConfig.entityShadows.get() || SunModel.strength <= 0f) {
            return packed;
        }
        int skyUv = (packed >>> 16) & 0xFFFF;
        if (skyUv == 0) {
            return packed;
        }
        // entity.getPosition(partial) without the Vec3 (this runs per entity per frame).
        double x = Mth.lerp(partial, entity.xo, entity.getX());
        double y = Mth.lerp(partial, entity.yo, entity.getY());
        double z = Mth.lerp(partial, entity.zo, entity.getZ());
        float vis = visibility(x, y + entity.getBbHeight() * 0.6, z);
        if (vis >= 0.999f) {
            return packed;
        }
        float strength = SunModel.strength * LumisConfig.sunStrength.get().floatValue();
        float loss = 0.6f * LumisConfig.shadeDepth.get().floatValue() * (1f - vis) * strength;
        int blockUv = packed & 0xFFFF;
        // Entity shaders fetch whole light levels (texelFetch(UV2 / 16)), so round to one.
        int sky = Math.round(LightmapGrader.shadowedSky(blockUv / 16f, skyUv / 16f, loss));
        return blockUv | (sky * 16) << 16;
    }

    /** CPU version of {@code atmos_sunVisibility}, marching the heightfield (an entity's centre, not a surface). */
    static float visibility(double x, double y, double z) {
        int half = Heightfield.n / 2;
        double rx = x - HorizonMap.centreX;
        double rz = z - HorizonMap.centreZ;
        double edge = half - 16 - Math.max(Math.abs(rx), Math.abs(rz));
        if (edge <= 0) {
            return 1f;
        }
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int own = Heightfield.raw(ix, iz);
        float vis;
        if (own != 0 && y + 0.25 < Heightfield.top(own)) {
            vis = 0f;
        } else {
            // The march reads the heightfield directly, so it can use the sun as it is now.
            float tan = HorizonMap.sameDirection ? HorizonMap.tanNow : HorizonMap.tan;
            float dx = HorizonMap.dirX;
            float dz = HorizonMap.dirZ;
            double room = Heightfield.maxTop - y + 2.0;
            if (room <= 0) {
                return 1f;
            }
            int steps = (int) Math.min(128, Math.ceil(room / tan + 1));
            float best = -1e4f;
            int bestT = 0;
            for (int t = 1; t <= steps; t++) {
                int raw = Heightfield.raw(Mth.floor(ix + 0.5 + t * dx), Mth.floor(iz + 0.5 + t * dz));
                if (raw != 0) {
                    float s = Heightfield.top(raw) - (t - 0.5f) * tan;
                    if (s > best) {
                        best = s;
                        bestT = t;
                    }
                }
            }
            double along = (x - ix - 0.5) * dx + (z - iz - 0.5) * dz;
            double s = best + along * tan;
            float penumbra = 0.04f + bestT * 0.04f * LumisConfig.shadowSoftness.get().floatValue();
            vis = (float) Mth.clamp((y - s) / penumbra + 0.5, 0.0, 1.0);
        }
        float fade = (float) Math.min(1.0, edge / 32.0) * LumisConfig.shadowStrength.get().floatValue();
        return 1f - (1f - vis) * fade;
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (running && event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            Heightfield.onChunkLoad(chunk);
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (running && event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            Heightfield.onChunkUnload(chunk);
        }
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Block-state ids may differ per server: classify again.
        CasterTable.reset();
    }

    /** From the block-update mixin. */
    public static void onBlockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (running) {
            Heightfield.onBlockChanged(pos, state);
        }
    }
}
