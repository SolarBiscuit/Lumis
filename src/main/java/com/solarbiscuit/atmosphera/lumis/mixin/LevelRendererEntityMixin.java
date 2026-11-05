package com.solarbiscuit.atmosphera.lumis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers which world entity is being drawn, for {@link EntityRenderDispatcherMixin}. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEntityMixin {
    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void lumis$entity(Entity entity, double x, double y, double z, float partial, PoseStack pose,
                              MultiBufferSource buffers, CallbackInfo ci) {
        ColoredLight.putEntity(entity);
    }
}
