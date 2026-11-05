package com.solarbiscuit.atmosphera.lumis.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.solarbiscuit.atmosphera.lumis.lightmap.LightmapGrader;
import com.solarbiscuit.atmosphera.lumis.lightmap.LumisLightTexture;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grades the lightmap pixels right before they are uploaded. Grading at the upload call (not at RETURN) matters:
 * Distant Horizons copies the pixels at RETURN, and NeoVoxy/AMDium sample the uploaded texture. A mod that replaces
 * the lightmap at HEAD (Polytone) skips this; the require = 0 keeps the game running if another mod rewrites the
 * method.
 * <p>
 * Flicker is scaled where vanilla makes it, in {@code tick()}: only the random kick of this tick is scaled, so the
 * field always holds a consistent value and nothing has to be restored (a mod that cancels the lightmap update at
 * HEAD, or BadOptimizations skipping a tick, cannot leave it scaled twice).
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin implements LumisLightTexture {
    @Shadow @Final private NativeImage lightPixels;
    @Shadow private boolean updateLightTexture;
    @Shadow private float blockLightRedFlicker;

    @Unique private float lumis$flickerBefore;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void lumis$beforeTick(CallbackInfo ci) {
        this.lumis$flickerBefore = this.blockLightRedFlicker;
    }

    /** Vanilla: {@code f = (f + noise) * 0.9}. Keeps the decay, scales the noise term. */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void lumis$scaleFlicker(CallbackInfo ci) {
        float decayed = this.lumis$flickerBefore * 0.9f;
        this.blockLightRedFlicker = decayed + LightmapGrader.flicker(this.blockLightRedFlicker - decayed);
    }

    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"), require = 0)
    private void lumis$grade(float partialTick, CallbackInfo ci) {
        LightmapGrader.grade(this.lightPixels);
    }

    @Override
    public void lumis$markDirty() {
        this.updateLightTexture = true;
    }
}
