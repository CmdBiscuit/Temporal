package com.temporal.mixin;

import com.temporal.client.Upscaler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Builds and sizes the entity-outline and Fabulous transparency chains against the scaled target. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = {"initOutline", "initTransparency"}, at = @At("HEAD"))
    private void temporal$beginInit(CallbackInfo ci) {
        Upscaler.pushInit();
    }

    @Inject(method = {"initOutline", "initTransparency"}, at = @At("RETURN"))
    private void temporal$endInit(CallbackInfo ci) {
        Upscaler.popInit();
    }

    // The chains' full-size targets take the size passed here, so it must be the scaled size, not the window's.
    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int temporal$scaledChainWidth(int width) {
        return Upscaler.chainWidth(width);
    }

    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int temporal$scaledChainHeight(int height) {
        return Upscaler.chainHeight(height);
    }
}
