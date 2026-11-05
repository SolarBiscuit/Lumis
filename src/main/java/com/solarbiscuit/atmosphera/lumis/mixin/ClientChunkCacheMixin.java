package com.solarbiscuit.atmosphera.lumis.mixin;

import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block light of a section changed in the client's own light engine (a torch placed or broken nearby). */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void lumis$lightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (layer == LightLayer.BLOCK) {
            ColoredLight.onLightChanged(pos.x(), pos.y(), pos.z(), false);
        }
    }
}
