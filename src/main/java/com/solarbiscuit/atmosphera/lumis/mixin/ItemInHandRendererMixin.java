package com.solarbiscuit.atmosphera.lumis.mixin;

import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** First-person arms and held items (both hands) take the colour of the light at the camera. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource lumis$tint(MultiBufferSource buffers) {
        return ColoredLight.tintCamera(buffers);
    }
}
