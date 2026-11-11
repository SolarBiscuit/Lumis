package com.solarbiscuit.atmosphera.lumis.atmosphere;

import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import net.minecraft.client.Minecraft;

/**
 * Fog hand-over to NeoVoxy's LODs (Lumis 0.10.0, NeoVoxy API 4 fog hook): the light shafts' height mist is marched to
 * at most {@code light_shafts.distance} blocks; past that, on LOD terrain, NeoVoxy's own distance fog used to take over
 * with its own colour and curve, on top of the march's capped haze. Now the same mist continues analytically in
 * NeoVoxy's LOD composite: optical depth of {@code density * exp(-(y - sea level) / 32)} from the march limit to the
 * LOD point along the view ray (exact for a straight ray over the height falloff, the region below sea level counted
 * at sea-level density), coloured like the composite's haze (fog colour by eye sky light, plus a little sunlight), and
 * NeoVoxy's own LOD fog is scaled down by the share the mist already covers. Lumis's composite then adds the march's
 * near haze and beams on top, so near and far are one fog.
 * <p>
 * The parameters come from the last frame's march ({@link LightShafts}); without a fresh march (mist off, sun down,
 * under water, shafts off) the hook does nothing and NeoVoxy's fog stays as it was.
 */
final class LodFog {
    /** Statements inside NeoVoxy's {@code voxyExt_fog(inout vec3 colour, vec3 position, float dist, inout float builtIn)}. */
    private static final String GLSL = """
            // Lumis: the light shafts' mist past the march limit. voxyExt_fogData[0]: density, 1 / height scale,
            // sea level minus camera y, march limit; [1]: rgb haze colour, w: mist share (0 = off).
            if (voxyExt_fogData[1].w > 0.0) {
                vec4 lumisM = voxyExt_fogData[0];
                if (dist > lumisM.w) {
                    float lumisDy = position.y / max(dist, 1e-3);
                    float lumisA0 = max(-lumisM.z + lumisM.w * lumisDy, 0.0);
                    float lumisA1 = max(-lumisM.z + dist * lumisDy, 0.0);
                    float lumisE0 = exp(-lumisA0 * lumisM.y);
                    float lumisAvg = abs(lumisA1 - lumisA0) < 0.01 ? lumisE0
                            : (lumisE0 - exp(-lumisA1 * lumisM.y)) / ((lumisA1 - lumisA0) * lumisM.y);
                    float lumisCover = voxyExt_fogData[1].w * (1.0 - exp(-lumisM.x * (dist - lumisM.w) * lumisAvg));
                    colour = mix(colour, voxyExt_fogData[1].rgb, lumisCover);
                    builtIn *= 1.0 - lumisCover;
                }
            }
            """;

    private LodFog() {
    }

    /** Mod construction (NeoVoxy builds its composite shaders with the hook). */
    static void register() {
        VoxyBridge.setLodFog(new VoxyBridge.LodFog() {
            @Override
            public String glsl() {
                return GLSL;
            }

            @Override
            public void data(float[] out) {
                if (!LightShafts.tailFresh()) {
                    return;
                }
                double camY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
                out[0] = LightShafts.tailDensity;
                out[1] = 1f / 32f;
                out[2] = (float) (LightShafts.tailBase - camY);
                out[3] = LightShafts.tailLimit;
                out[4] = LightShafts.TAIL_COLOUR[0];
                out[5] = LightShafts.TAIL_COLOUR[1];
                out[6] = LightShafts.TAIL_COLOUR[2];
                out[7] = LightShafts.tailMist;
            }
        });
    }
}
