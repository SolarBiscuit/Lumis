package com.solarbiscuit.atmosphera.lumis.sun;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lumis.LumisConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Entity and block-entity diffuse light. Vanilla lights them from two fixed directions ({@code 0.2, 1, -0.7} and
 * {@code -0.2, 1, 0.7}); the main one is turned towards the sun (or moon), the fill stays vanilla so sides facing away
 * keep their light. Render thread, from {@code Lighting.setupLevel}.
 */
public final class EntityLight {
    private static final Vector3f VANILLA_0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3f VANILLA_1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    private static final Vector3f MAIN = new Vector3f();
    private static final Vector3f TOWARDS = new Vector3f();

    private EntityLight() {
    }

    public static void apply(Matrix4f matrix) {
        if (LightFeatures.entityLight == null || !LightFeatures.entityLight.isActive()) {
            return;
        }
        float t = SunModel.strength * LumisConfig.entityStrength.get().floatValue();
        if (t <= 0.001f) {
            return;
        }
        MAIN.set(VANILLA_0).lerp(TOWARDS.set(SunModel.dirX, SunModel.dirY, SunModel.dirZ), t);
        if (MAIN.lengthSquared() < 1e-6f) {
            return;
        }
        MAIN.normalize();
        RenderSystem.setupLevelDiffuseLighting(MAIN, VANILLA_1, matrix);
    }
}
