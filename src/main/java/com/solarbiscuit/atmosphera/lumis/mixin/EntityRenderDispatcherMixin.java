package com.solarbiscuit.atmosphera.lumis.mixin;

import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Entities (with their armour, held items and dropped items) take the colour of the light they stand in. The entity
 * comes from {@link LevelRendererEntityMixin} (the world's entity loop), so GUI and other callers stay untouched.
 * Injected at HEAD: another mod in RatPack redirects the inner {@code EntityRenderer.render} call.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource lumis$tint(MultiBufferSource buffers) {
        Entity entity = ColoredLight.takeEntity();
        return entity == null ? buffers : ColoredLight.tintEntity(buffers, entity);
    }
}
