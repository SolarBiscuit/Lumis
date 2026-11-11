package com.solarbiscuit.atmosphera.lumis.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.solarbiscuit.atmosphera.lumis.color.ParticleTint;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light colour on particles (0.9.0, {@link ParticleTint}): the particle's colour is multiplied by the light colour at
 * its block while it draws its quad, then put back, so whatever writes the vertices (vanilla or Particle Core) reads
 * the tinted colour and nothing about the particle's own state changes.
 */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin extends Particle {
    @Unique
    private static final float[] LUMIS_TINT = new float[3];
    @Unique
    private static float lumis$r;
    @Unique
    private static float lumis$g;
    @Unique
    private static float lumis$b;
    @Unique
    private static boolean lumis$active;

    protected SingleQuadParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void lumis$tintStart(VertexConsumer buffer, Camera camera, float partial, CallbackInfo ci) {
        lumis$active = false;
        if (ParticleTint.at(this.x, this.y, this.z, this.getLightColor(partial), LUMIS_TINT)) {
            lumis$r = this.rCol;
            lumis$g = this.gCol;
            lumis$b = this.bCol;
            this.rCol *= LUMIS_TINT[0];
            this.gCol *= LUMIS_TINT[1];
            this.bCol *= LUMIS_TINT[2];
            lumis$active = true;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void lumis$tintEnd(VertexConsumer buffer, Camera camera, float partial, CallbackInfo ci) {
        if (lumis$active) {
            this.rCol = lumis$r;
            this.gCol = lumis$g;
            this.bCol = lumis$b;
            lumis$active = false;
        }
    }
}
