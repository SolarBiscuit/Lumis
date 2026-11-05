package com.solarbiscuit.atmosphera.lumis.mixin;

import com.mojang.blaze3d.platform.Lighting;
import com.solarbiscuit.atmosphera.lumis.sun.EntityLight;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World entity/block-entity diffuse light from the sun's side. GUI and Nether lighting are untouched. */
@Mixin(Lighting.class)
public abstract class LightingMixin {
    @Inject(method = "setupLevel", at = @At("TAIL"), require = 0)
    private static void lumis$sunLight(Matrix4f matrix, CallbackInfo ci) {
        EntityLight.apply(matrix);
    }
}
