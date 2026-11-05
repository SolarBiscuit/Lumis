package com.solarbiscuit.atmosphera.lumis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.solarbiscuit.atmosphera.lumis.color.ColoredLight;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entities (chests, signs, banners, modded machines) take the colour of the light at their block. The block
 * entity is remembered at the head of {@code render} and the buffers swapped at the head of {@code setupAndRender}
 * (two methods, so the order is certain; head injections cannot be removed by other mods' redirects).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Unique
    private static BlockEntity lumis$blockEntity;

    @Inject(method = "render", at = @At("HEAD"))
    private <E extends BlockEntity> void lumis$capture(E blockEntity, float partial, PoseStack pose,
                                                      MultiBufferSource buffers, CallbackInfo ci) {
        lumis$blockEntity = blockEntity;
    }

    @ModifyVariable(method = "setupAndRender", at = @At("HEAD"), argsOnly = true)
    private static MultiBufferSource lumis$tint(MultiBufferSource buffers) {
        BlockEntity be = lumis$blockEntity;
        lumis$blockEntity = null;
        if (be == null) {
            return buffers;
        }
        BlockPos pos = be.getBlockPos();
        return ColoredLight.tint(buffers, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
