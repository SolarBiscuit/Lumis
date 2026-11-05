package com.solarbiscuit.atmosphera.lumis.mixin;

import com.solarbiscuit.atmosphera.lumis.shadow.SunShadows;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities in a sun shadow: after the dynamic-light mods (CheapLighting, Belt Slot, RatOpt) changed the light. */
@Mixin(value = EntityRenderer.class, priority = 1500)
public abstract class EntityRendererMixin {
    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void lumis$sunShadow(Entity entity, float partial, CallbackInfoReturnable<Integer> cir) {
        int packed = cir.getReturnValueI();
        int shadowed = SunShadows.entityLight(entity, partial, packed);
        if (shadowed != packed) {
            cir.setReturnValue(shadowed);
        }
    }
}
