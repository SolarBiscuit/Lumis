package com.solarbiscuit.atmosphera.lumis.mixin;

import com.solarbiscuit.atmosphera.lumis.shadow.SunShadows;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block changes on the client (packets and the player's own), for the sun-shadow heightfield and the light-colour
 * emitter index; and sections whose light arrived from the server (light packets), for the light-colour volume.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void lumis$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        SunShadows.onBlockChanged(pos, newState);
        com.solarbiscuit.atmosphera.lumis.color.ColoredLight.onBlockChanged(pos, oldState, newState);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void lumis$lightArrived(int x, int y, int z, CallbackInfo ci) {
        com.solarbiscuit.atmosphera.lumis.color.ColoredLight.onLightChanged(x, y, z, true);
    }
}
